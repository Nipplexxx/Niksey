package com.example.niksey.utillits.signal

import android.content.Context
import android.util.Log
import com.example.niksey.utillits.CURRENT_UID
import com.example.niksey.utillits.NODE_GROUPS
import com.example.niksey.utillits.NODE_MEMBERS
import com.example.niksey.utillits.REF_DATABASE_ROOT
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.UntrustedIdentityException
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.GroupCipher
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Протокол Signal (libsignal-android 0.86.5): личные чаты и группы.
 *
 * Личные сообщения:
 *  • PQXDH (X3DH + ML-KEM-1024) — установка сессии; Double Ratchet — каждое сообщение с новым ключом.
 *
 * Группы (Sender Keys):
 *  • У каждого участника свой sender key группы (цепочка ключей, см. GroupSessionBuilder).
 *  • Чтобы участник мог читать сообщения, sender key отправляется каждому участнику — зашифрованно
 *    его личной Signal-сессией (SKDM). Так ключ группы не виден серверу.
 *  • Сообщения группы шифруются один раз (GroupCipher) и читаются всеми, кто получил SKDM отправителя.
 *
 * Ключевые наборы: `keyBundles/{uid}`. Одноразовые prekeys забираются транзакцией и пополняются
 * после каждого использования и при запуске приложения.
 *
 * Все обращения к хранилищу — на одном потоке: состояние сессий не потокобезопасно.
 */
object SignalManager {

    private const val TAG = "SignalManager"
    private const val DEVICE_ID = 1
    private const val SIGNED_PREKEY_ID = 1
    private const val KYBER_PREKEY_ID = 1
    private const val ONE_TIME_TARGET = 40
    private const val ONE_TIME_REFILL_BELOW = 20

    /** Формат зашифрованного текста: s1:<p|m|g>:<base64>. p — PreKey, m — сообщение сессии, g — сообщение группы. */
    const val WIRE_PREFIX = "s1"
    const val NODE_KEY_BUNDLES = "keyBundles"

    /** groups/{gid}/skdm/{получатель}/{отправитель} — sender key, зашифрованный сессией получателя. */
    const val NODE_SENDER_KEYS = "skdm"

    /** Результат отправки. Главное правило: откат на старую схему только при [NoBundle]. */
    sealed class SendOutcome {
        class Sent(val wire: String) : SendOutcome()

        /** У собеседника нет ключей Signal (старая версия приложения). */
        object NoBundle : SendOutcome()

        /** Ключ собеседника изменился — отправка заблокирована, пока пользователь не подтвердит новый ключ. */
        object Untrusted : SendOutcome()

        /** Временная ошибка. Откатываться на старую схему нельзя. */
        object Failed : SendOutcome()
    }

    private sealed class SessionState {
        object Ready : SessionState()
        object NoBundle : SessionState()
        object Untrusted : SessionState()
        object Failed : SessionState()
    }

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "signal") }
    private lateinit var store: SignalStore

    /** Сессии, которые устанавливаются прямо сейчас: остальные сообщения того же собеседника ждут. */
    private val pendingSessions = HashMap<String, MutableList<(SessionState) -> Unit>>()

    fun init(context: Context) {
        store = SignalStore(context)
        // Использован одноразовый prekey — пополняем запас и публикуем новые
        store.onPreKeyRemoved = { executor.execute { replenishOneTimeKeys() } }
    }

    fun isSignalText(text: String): Boolean = text.startsWith("$WIRE_PREFIX:")

    private fun signedIn(): Boolean =
        FirebaseAuth.getInstance().currentUser != null && CURRENT_UID.isNotBlank() && CURRENT_UID != "null"

    // ==================== жизненный цикл ====================

    /** Вызывается после входа. */
    fun onUserReady() {
        executor.execute {
            try {
                ensureLocalKeys()
                if (!store.isBundlePublished()) publishBundle() else replenishOneTimeKeys()
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось подготовить ключи Signal", e)
            }
        }
    }

    /** Вызывается при открытии приложения: досоздаёт и публикует недостающие одноразовые ключи. */
    fun onAppForeground() {
        if (!signedIn()) return
        executor.execute {
            try {
                if (!store.hasIdentity()) return@execute
                if (!store.isBundlePublished()) publishBundle() else replenishOneTimeKeys()
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось обновить ключи Signal", e)
            }
        }
    }

    /** Удаляет все ключи и сессии Signal на устройстве (при выходе из аккаунта). */
    fun resetLocal() {
        executor.execute {
            store.wipe()
            pendingSessions.clear()
        }
    }

    // ==================== личные сообщения ====================

    /** Шифрует для собеседника. Сессия создаётся при первом сообщении (PQXDH). */
    fun encryptForPeer(peer: String, plaintext: ByteArray, onResult: (SendOutcome) -> Unit) {
        executor.execute {
            val address = addressOf(peer)
            if (store.containsSession(address)) {
                onResult(encryptNow(address, plaintext))
                return@execute
            }

            // Сессия уже создаётся: ждём её, а не создаём вторую
            pendingSessions[peer]?.let { queue ->
                queue.add { state -> executor.execute { onResult(finish(state, address, plaintext)) } }
                return@execute
            }
            pendingSessions[peer] = mutableListOf()

            establishSession(peer, address) { state ->
                executor.execute {
                    val waiters = pendingSessions.remove(peer).orEmpty()
                    onResult(finish(state, address, plaintext))
                    waiters.forEach { it(state) }
                }
            }
        }
    }

    private fun finish(state: SessionState, address: SignalProtocolAddress, plaintext: ByteArray): SendOutcome =
        when (state) {
            SessionState.Ready -> encryptNow(address, plaintext)
            SessionState.NoBundle -> SendOutcome.NoBundle
            SessionState.Untrusted -> SendOutcome.Untrusted
            SessionState.Failed -> SendOutcome.Failed
        }

    /** Расшифровка (личная или групповая). Вызывается на сигнальном потоке, результат — туда же. */
    fun decryptFromPeer(sender: String, wire: String, onResult: (ByteArray?) -> Unit) {
        executor.execute {
            val plain = try {
                decryptNow(sender, wire)
            } catch (e: UntrustedIdentityException) {
                store.setIdentityChanged(sender, true)
                null
            } catch (e: Exception) {
                Log.w(TAG, "Сообщение Signal не расшифровано: ${e.javaClass.simpleName}")
                null
            }
            onResult(plain)
        }
    }

    private fun encryptNow(address: SignalProtocolAddress, plaintext: ByteArray): SendOutcome = try {
        val message = SessionCipher(store, address).encrypt(plaintext)
        val tag = if (message.type == CiphertextMessage.PREKEY_TYPE) "p" else "m"
        SendOutcome.Sent("$WIRE_PREFIX:$tag:${encode(message.serialize())}")
    } catch (e: UntrustedIdentityException) {
        store.setIdentityChanged(address.name, true)
        SendOutcome.Untrusted
    } catch (e: Exception) {
        Log.w(TAG, "Шифрование Signal не удалось: ${e.javaClass.simpleName}")
        SendOutcome.Failed
    }

    private fun decryptNow(sender: String, wire: String): ByteArray {
        val parts = wire.split(':', limit = 3)
        require(parts.size == 3 && parts[0] == WIRE_PREFIX) { "Не формат Signal" }
        val bytes = decode(parts[2])
        val address = addressOf(sender)
        return when (parts[1]) {
            "p" -> SessionCipher(store, address).decrypt(PreKeySignalMessage(bytes))
            "m" -> SessionCipher(store, address).decrypt(SignalMessage(bytes))
            "g" -> GroupCipher(store, address).decrypt(bytes)
            else -> throw IllegalArgumentException("Неизвестный тип сообщения")
        }
    }

    // ==================== группы (Sender Keys) ====================

    /** Идентификатор распределения sender key для группы (один на группу). */
    private fun distributionIdOf(groupId: String): UUID =
        UUID.nameUUIDFromBytes("niksey-group:$groupId".toByteArray(Charsets.UTF_8))

    private fun myAddress(): SignalProtocolAddress = addressOf(CURRENT_UID)

    /**
     * Шифрует сообщение группы своим sender key. Перед этим отправляет SKDM тем участникам,
     * которым его ещё не отправляли (в том числе тем, кто вошёл в группу позже).
     */
    fun encryptForGroup(groupId: String, plaintext: ByteArray, onResult: (SendOutcome) -> Unit) {
        fetchMembers(groupId) { members ->
            if (members == null) {
                onResult(SendOutcome.Failed)
                return@fetchMembers
            }
            val others = members.filter { it != CURRENT_UID }

            executor.execute {
                val skdmBytes = try {
                    GroupSessionBuilder(store).create(myAddress(), distributionIdOf(groupId)).serialize()
                } catch (e: Exception) {
                    onResult(SendOutcome.Failed)
                    return@execute
                }

                distributeSenderKey(groupId, others, skdmBytes) {
                    executor.execute {
                        onResult(try {
                            val message = GroupCipher(store, myAddress()).encrypt(distributionIdOf(groupId), plaintext)
                            SendOutcome.Sent("$WIRE_PREFIX:g:${encode(message.serialize())}")
                        } catch (e: Exception) {
                            Log.w(TAG, "Групповое шифрование не удалось: ${e.javaClass.simpleName}")
                            SendOutcome.Failed
                        })
                    }
                }
            }
        }
    }

    /** Создаёт свой sender key группы и раздаёт его участникам (без отправки сообщения). */
    fun shareSenderKey(groupId: String, done: (Boolean) -> Unit) {
        fetchMembers(groupId) { members ->
            if (members == null) {
                done(false)
                return@fetchMembers
            }
            val others = members.filter { it != CURRENT_UID }
            executor.execute {
                val skdmBytes = try {
                    GroupSessionBuilder(store).create(myAddress(), distributionIdOf(groupId)).serialize()
                } catch (e: Exception) {
                    done(false)
                    return@execute
                }
                distributeSenderKey(groupId, others, skdmBytes) { done(true) }
            }
        }
    }

    /** Отправляет SKDM каждому участнику по очереди. Ошибка одного участника не останавливает остальных. */
    private fun distributeSenderKey(
        groupId: String,
        members: List<String>,
        skdmBytes: ByteArray,
        done: () -> Unit
    ) {
        val targets = members.filter { !store.isSenderKeySent(groupId, it) }

        fun next(index: Int) {
            if (index >= targets.size) {
                done()
                return
            }
            val target = targets[index]
            encryptForPeer(target, skdmBytes) { outcome ->
                if (outcome is SendOutcome.Sent) {
                    REF_DATABASE_ROOT.child("$NODE_GROUPS/$groupId/$NODE_SENDER_KEYS/$target/$CURRENT_UID")
                        .setValue(outcome.wire)
                        .addOnCompleteListener { task ->
                            if (task.isSuccessful) executor.execute { store.setSenderKeySent(groupId, target) }
                            next(index + 1)
                        }
                } else {
                    next(index + 1)
                }
            }
        }
        next(0)
    }

    /**
     * Получает sender keys других участников, адресованные мне, и принимает их.
     * Вызывать при открытии группы и при неудачной расшифровке группового сообщения.
     */
    fun syncGroupKeys(groupId: String, done: () -> Unit) {
        if (!signedIn()) {
            done()
            return
        }
        REF_DATABASE_ROOT.child("$NODE_GROUPS/$groupId/$NODE_SENDER_KEYS/$CURRENT_UID").get()
            .addOnSuccessListener { snapshot ->
                val entries = snapshot.children.mapNotNull { child ->
                    val sender = child.key ?: return@mapNotNull null
                    val wire = child.getValue(String::class.java) ?: return@mapNotNull null
                    sender to wire
                }
                if (entries.isEmpty()) {
                    done()
                    return@addOnSuccessListener
                }

                val remaining = AtomicInteger(entries.size)
                entries.forEach { (sender, wire) ->
                    decryptFromPeer(sender, wire) { bytes ->
                        executor.execute {
                            // Сначала обрабатываем, потом удаляем: удаление до обработки
                            // (или падение расшифровки) теряло бы sender key навсегда
                            val skdm = bytes
                            if (skdm != null) {
                                try {
                                    GroupSessionBuilder(store).process(
                                        addressOf(sender),
                                        SenderKeyDistributionMessage(skdm)
                                    )
                                } catch (e: Exception) {
                                    Log.w(TAG, "SKDM не принят: ${e.javaClass.simpleName}")
                                }
                            }
                            // Убрано только после обработки; расшифрованное значение
                            // продублировано в кэш открытым текстом (SecureMessaging),
                            // поэтому повторная расшифровка не потребуется
                            REF_DATABASE_ROOT.child("$NODE_GROUPS/$groupId/$NODE_SENDER_KEYS/$CURRENT_UID/$sender")
                                .removeValue()
                            if (remaining.decrementAndGet() == 0) done()
                        }
                    }
                }
            }
            .addOnFailureListener { done() }
    }

    fun fetchMembers(groupId: String, done: (List<String>?) -> Unit) {
        REF_DATABASE_ROOT.child("$NODE_GROUPS/$groupId/$NODE_MEMBERS").get()
            .addOnSuccessListener { snapshot -> done(snapshot.children.mapNotNull { it.key }) }
            .addOnFailureListener { done(null) }
    }

    // ==================== изменение ключа собеседника ====================

    /** Из переданных id возвращает тех, чей ключ безопасности изменился и ещё не подтверждён. */
    fun changedIdentities(peers: List<String>, done: (List<String>) -> Unit) {
        executor.execute { done(peers.filter { store.isIdentityChanged(it) }) }
    }

    /**
     * Пользователь подтвердил новый ключ собеседника: сохраняем его, сбрасываем старую сессию.
     * Следующее сообщение создаст новую сессию с новым ключом.
     */
    fun acceptNewIdentity(peer: String, done: (Boolean) -> Unit) {
        REF_DATABASE_ROOT.child("$NODE_KEY_BUNDLES/$peer").get()
            .addOnSuccessListener { snapshot ->
                executor.execute {
                    val identity = parseIdentity(snapshot)
                    if (identity == null) {
                        done(false)
                        return@execute
                    }
                    val address = addressOf(peer)
                    store.replaceIdentity(address, identity)
                    store.deleteSession(address)
                    done(true)
                }
            }
            .addOnFailureListener { done(false) }
    }

    // ==================== локальные ключи ====================

    /** Вызывается на сигнальном потоке. */
    private fun ensureLocalKeys() {
        if (!store.hasIdentity()) {
            store.saveIdentity(IdentityKeyPair.generate(), KeyHelper.generateRegistrationId(false))
        }
        val identity = store.identityKeyPair
        val now = System.currentTimeMillis()

        if (store.loadSignedPreKeys().isEmpty()) {
            val pair = ECKeyPair.generate()
            val signature = identity.privateKey.calculateSignature(pair.publicKey.serialize())
            store.storeSignedPreKey(SIGNED_PREKEY_ID, SignedPreKeyRecord(SIGNED_PREKEY_ID, now, pair, signature))
        }

        if (store.loadKyberPreKeys().isEmpty()) {
            val kem = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            val signature = identity.privateKey.calculateSignature(kem.publicKey.serialize())
            store.storeKyberPreKey(KYBER_PREKEY_ID, KyberPreKeyRecord(KYBER_PREKEY_ID, now, kem, signature))
        }

        if (store.preKeyIds().isEmpty()) {
            createOneTimeKeys(ONE_TIME_TARGET)
        }
    }

    /** Создаёт новые одноразовые ключи и возвращает их публичные части. */
    private fun createOneTimeKeys(count: Int): List<Pair<Int, ECPublicKey>> {
        var next = (store.preKeyIds().maxOrNull() ?: 0) + 1
        return List(count) {
            val pair = ECKeyPair.generate()
            val id = next++
            store.storePreKey(id, PreKeyRecord(id, pair))
            id to pair.publicKey
        }
    }

    /**
     * Пополняет одноразовые ключи, когда их становится мало. Публикуются только НОВЫЕ ключи
     * (updateChildren): уже выданные собеседникам ключи повторно не появляются на сервере.
     */
    private fun replenishOneTimeKeys() {
        if (!signedIn() || !store.isBundlePublished()) return
        val existing = store.preKeyIds()
        if (existing.size >= ONE_TIME_REFILL_BELOW) return

        val added = createOneTimeKeys(ONE_TIME_TARGET - existing.size)
        val updates = added.associate { (id, publicKey) -> id.toString() to encode(publicKey.serialize()) }
        REF_DATABASE_ROOT.child("$NODE_KEY_BUNDLES/$CURRENT_UID/oneTime").updateChildren(updates)
    }

    /** Полная публикация набора ключей. Вызывается, пока набор ещё не опубликован. */
    private fun publishBundle() {
        val identity = store.identityKeyPair
        val signed = store.loadSignedPreKeys().first { it.id == SIGNED_PREKEY_ID }
        val kyber = store.loadKyberPreKeys().first { it.id == KYBER_PREKEY_ID }
        val oneTime = store.preKeyIds().associate { id ->
            id.toString() to encode(store.loadPreKey(id).keyPair.publicKey.serialize())
        }

        val bundle = mapOf(
            "registrationId" to store.localRegistrationId,
            "identityKey" to encode(identity.publicKey.serialize()),
            "signedPreKey" to mapOf(
                "id" to SIGNED_PREKEY_ID,
                "pub" to encode(signed.keyPair.publicKey.serialize()),
                "sig" to encode(signed.signature)
            ),
            "kyberPreKey" to mapOf(
                "id" to KYBER_PREKEY_ID,
                "pub" to encode(kyber.keyPair.publicKey.serialize()),
                "sig" to encode(kyber.signature)
            ),
            "oneTime" to oneTime
        )

        REF_DATABASE_ROOT.child("$NODE_KEY_BUNDLES/$CURRENT_UID").setValue(bundle)
            .addOnSuccessListener { executor.execute { store.setBundlePublished() } }
    }

    // ==================== удалённые ключи ====================

    private class RemoteBundle(
        val registrationId: Int,
        val identityKey: ByteArray,
        val signedId: Int,
        val signedPub: ByteArray,
        val signedSig: ByteArray,
        val kyberId: Int,
        val kyberPub: ByteArray,
        val kyberSig: ByteArray,
        val oneTimeId: Int,
        val oneTimePub: ByteArray
    ) {
        fun toPreKeyBundle(): PreKeyBundle =
            if (oneTimeId >= 0) {
                PreKeyBundle(
                    registrationId, DEVICE_ID,
                    oneTimeId, ECPublicKey(oneTimePub),
                    signedId, ECPublicKey(signedPub), signedSig,
                    IdentityKey(identityKey),
                    kyberId, KEMPublicKey(kyberPub), kyberSig
                )
            } else {
                // Одноразовый ключ исчерпан: сессия устанавливается без него (PQXDH это позволяет)
                PreKeyBundle(
                    registrationId, DEVICE_ID,
                    -1, null,
                    signedId, ECPublicKey(signedPub), signedSig,
                    IdentityKey(identityKey),
                    kyberId, KEMPublicKey(kyberPub), kyberSig
                )
            }
    }

    /** Загружает набор ключей собеседника и забирает один одноразовый ключ (транзакцией). */
    private fun claimOneTimePreKey(peer: String, onResult: (Pair<Int, ByteArray>?) -> Unit) {
        REF_DATABASE_ROOT.child("$NODE_KEY_BUNDLES/$peer/oneTime").runTransaction(object : Transaction.Handler {
            private var claimed: Pair<Int, ByteArray>? = null

            override fun doTransaction(data: MutableData): Transaction.Result {
                claimed = null
                val first = data.children.firstOrNull { it.value != null }
                    ?: return Transaction.success(data)
                claimed = first.key!!.toInt() to decode(first.value as String)
                first.value = null
                return Transaction.success(data)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, currentData: DataSnapshot?) {
                onResult(if (error == null && committed) claimed else null)
            }
        })
    }

    /**
     * Создаёт сессию с собеседником. Сначала сверяем ключ безопасности: если он изменился —
     * сессию не создаём и помечаем собеседника (пользователь должен подтвердить новый ключ).
     */
    private fun establishSession(peer: String, address: SignalProtocolAddress, done: (SessionState) -> Unit) {
        REF_DATABASE_ROOT.child("$NODE_KEY_BUNDLES/$peer").get()
            .addOnSuccessListener { snapshot ->
                if (!snapshot.exists()) {
                    done(SessionState.NoBundle)
                    return@addOnSuccessListener
                }
                executor.execute {
                    val remoteIdentity = parseIdentity(snapshot)
                    if (remoteIdentity == null) {
                        done(SessionState.Failed)
                        return@execute
                    }
                    val known = store.getIdentity(address)
                    if (known != null && known != remoteIdentity) {
                        store.setIdentityChanged(peer, true)
                        done(SessionState.Untrusted)
                        return@execute
                    }

                    claimOneTimePreKey(peer) { oneTime ->
                        executor.execute {
                            // Одноразового ключа может не быть (закончились): PQXDH допускает
                            // установку сессии без него — пробуем, а не сдаёмся сразу
                            val remote = parseBundle(snapshot, oneTime)
                            if (remote == null) {
                                done(SessionState.Failed)
                                return@execute
                            }
                            try {
                                SessionBuilder(store, address).process(remote.toPreKeyBundle())
                                done(SessionState.Ready)
                            } catch (e: UntrustedIdentityException) {
                                store.setIdentityChanged(peer, true)
                                done(SessionState.Untrusted)
                            } catch (e: Exception) {
                                Log.w(TAG, "Сессия не создана: ${e.javaClass.simpleName}")
                                done(SessionState.Failed)
                            }
                        }
                    }
                }
            }
            .addOnFailureListener { done(SessionState.Failed) }
    }

    private fun parseIdentity(snapshot: DataSnapshot): IdentityKey? = try {
        snapshot.child("identityKey").getValue(String::class.java)?.let { IdentityKey(decode(it)) }
    } catch (e: Exception) {
        null
    }

    /** Набор ключей из снапшота; [oneTime] может отсутствовать (запас исчерпан) — это допустимо. */
    private fun parseBundle(snapshot: DataSnapshot, oneTime: Pair<Int, ByteArray>?): RemoteBundle? = try {
        val signed = snapshot.child("signedPreKey")
        val kyber = snapshot.child("kyberPreKey")
        RemoteBundle(
            registrationId = snapshot.child("registrationId").getValue(Long::class.java)!!.toInt(),
            identityKey = decode(snapshot.child("identityKey").getValue(String::class.java)!!),
            signedId = signed.child("id").getValue(Long::class.java)!!.toInt(),
            signedPub = decode(signed.child("pub").getValue(String::class.java)!!),
            signedSig = decode(signed.child("sig").getValue(String::class.java)!!),
            kyberId = kyber.child("id").getValue(Long::class.java)!!.toInt(),
            kyberPub = decode(kyber.child("pub").getValue(String::class.java)!!),
            kyberSig = decode(kyber.child("sig").getValue(String::class.java)!!),
            oneTimeId = oneTime?.first ?: -1,
            oneTimePub = oneTime?.second ?: ByteArray(0)
        )
    } catch (e: Exception) {
        Log.w(TAG, "Набор ключей собеседника повреждён")
        null
    }

    // ==================== утилиты ====================

    private fun addressOf(name: String) = SignalProtocolAddress(name, DEVICE_ID)
    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)
}
