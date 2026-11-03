package com.example.niksey.utillits.signal

import android.content.Context
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.groups.state.SenderKeyStore
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyStore
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Хранилище протокола Signal поверх [SecureKvStore]: все ключи и состояния сессий лежат зашифрованными.
 *
 * Каждая запись обновляется сразу после изменения (libsignal сам вызывает storeSession / removePreKey),
 * поэтому состояние Double Ratchet переживает перезапуск приложения — это основа forward secrecy.
 */
class SignalStore(context: Context) : SignalProtocolStore {

    private val identityKv = SecureKvStore(context, "signal_identity")
    private val remoteIdentities = SecureKvStore(context, "signal_remote_identities")
    private val sessions = SecureKvStore(context, "signal_sessions")
    private val preKeys = SecureKvStore(context, "signal_prekeys")
    private val signedPreKeys = SecureKvStore(context, "signal_signed_prekeys")
    private val kyberPreKeys = SecureKvStore(context, "signal_kyber_prekeys")
    private val senderKeys = SecureKvStore(context, "signal_sender_keys")
    private val flags = SecureKvStore(context, "signal_flags")

    /** Вызывается после удаления одноразового prekey (он был использован) — пора пополнить запас. */
    var onPreKeyRemoved: (() -> Unit)? = null

    /** Удаляет всё состояние Signal (при выходе из аккаунта). */
    fun wipe() {
        listOf(identityKv, remoteIdentities, sessions, preKeys, signedPreKeys, kyberPreKeys, senderKeys, flags)
            .forEach { it.clear() }
    }

    // ==================== идентичность ====================

    fun hasIdentity(): Boolean = identityKv.get(KEY_PAIR) != null

    fun saveIdentity(pair: IdentityKeyPair, registrationId: Int) {
        identityKv.put(KEY_PAIR, pair.serialize())
        identityKv.put(KEY_REG_ID, ByteBuffer.allocate(4).putInt(registrationId).array())
    }

    override fun getIdentityKeyPair(): IdentityKeyPair =
        IdentityKeyPair(identityKv.get(KEY_PAIR) ?: throw IllegalStateException("Ключи Signal не созданы"))

    override fun getLocalRegistrationId(): Int =
        ByteBuffer.wrap(identityKv.get(KEY_REG_ID) ?: throw IllegalStateException("Нет registrationId")).int

    /** Trust-on-first-use: первый ключ собеседника запоминаем, смена ключа — повод для предупреждения. */
    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val existing = getIdentity(address)
        remoteIdentities.put(addressKey(address), identityKey.serialize())
        return if (existing != null && existing != identityKey) {
            IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        } else {
            IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        }
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction
    ): Boolean {
        val known = getIdentity(address) ?: return true
        return known == identityKey
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
        remoteIdentities.get(addressKey(address))?.let { IdentityKey(it) }

    // ==================== одноразовые prekeys (EC) ====================

    override fun loadPreKey(preKeyId: Int): PreKeyRecord =
        preKeys.get(preKeyId.toString())?.let { PreKeyRecord(it) }
            ?: throw InvalidKeyIdException("Нет prekey $preKeyId")

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        preKeys.put(preKeyId.toString(), record.serialize())
    }

    override fun containsPreKey(preKeyId: Int): Boolean = preKeys.get(preKeyId.toString()) != null

    override fun removePreKey(preKeyId: Int) {
        preKeys.remove(preKeyId.toString())
        onPreKeyRemoved?.invoke()
    }

    // ==================== флаги ====================

    fun isIdentityChanged(peer: String): Boolean = flags.get("identity_changed:$peer") != null

    fun setIdentityChanged(peer: String, changed: Boolean) {
        if (changed) flags.put("identity_changed:$peer", byteArrayOf(1)) else flags.remove("identity_changed:$peer")
    }

    /** Подтверждённый пользователем новый ключ собеседника: заменяем сохранённый, сбрасываем предупреждение. */
    fun replaceIdentity(address: SignalProtocolAddress, identityKey: IdentityKey) {
        remoteIdentities.put(addressKey(address), identityKey.serialize())
        setIdentityChanged(address.name, false)
    }

    fun isBundlePublished(): Boolean = flags.get("bundle_published") != null

    fun setBundlePublished() = flags.put("bundle_published", byteArrayOf(1))

    /** Отправляли ли участнику наш sender key этой группы. */
    fun isSenderKeySent(groupId: String, memberId: String): Boolean =
        flags.get("skdm_sent:$groupId:$memberId") != null

    fun setSenderKeySent(groupId: String, memberId: String) =
        flags.put("skdm_sent:$groupId:$memberId", byteArrayOf(1))

    /** Сколько одноразовых ключей осталось. */
    fun preKeyIds(): List<Int> = preKeys.entries().keys.map { it.toInt() }

    // ==================== signed prekeys ====================

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord =
        signedPreKeys.get(signedPreKeyId.toString())?.let { SignedPreKeyRecord(it) }
            ?: throw InvalidKeyIdException("Нет signed prekey $signedPreKeyId")

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> =
        signedPreKeys.entries().values.map { SignedPreKeyRecord(it) }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        signedPreKeys.put(signedPreKeyId.toString(), record.serialize())
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean =
        signedPreKeys.get(signedPreKeyId.toString()) != null

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        signedPreKeys.remove(signedPreKeyId.toString())
    }

    // ==================== kyber (post-quantum) prekeys ====================

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord =
        kyberPreKeys.get(kyberPreKeyId.toString())?.let { KyberPreKeyRecord(it) }
            ?: throw InvalidKeyIdException("Нет kyber prekey $kyberPreKeyId")

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> =
        kyberPreKeys.entries().values.map { KyberPreKeyRecord(it) }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        kyberPreKeys.put(kyberPreKeyId.toString(), record.serialize())
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean =
        kyberPreKeys.get(kyberPreKeyId.toString()) != null

    /**
     * Последний резервный kyber-ключ можно использовать повторно (так устроен PQXDH),
     * поэтому ничего не удаляем. Ключ одноразовых kyber-ключей мы не публикуем.
     */
    @Throws(ReusedBaseKeyException::class)
    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) {
        // намеренно пусто: см. комментарий выше
    }

    // ==================== сессии Double Ratchet ====================

    override fun loadSession(address: SignalProtocolAddress): SessionRecord =
        sessions.get(addressKey(address))?.let { SessionRecord(it) } ?: SessionRecord()

    @Throws(NoSessionException::class)
    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        addresses.map { address ->
            sessions.get(addressKey(address))?.let { SessionRecord(it) }
                ?: throw NoSessionException("Нет сессии для $address")
        }

    override fun getSubDeviceSessions(name: String): List<Int> =
        sessions.entries().keys
            .filter { it.startsWith("$name:") }
            .map { it.substringAfterLast(':').toInt() }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        sessions.put(addressKey(address), record.serialize())
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean =
        sessions.get(addressKey(address)) != null

    override fun deleteSession(address: SignalProtocolAddress) {
        sessions.remove(addressKey(address))
    }

    override fun deleteAllSessions(name: String) {
        sessions.entries().keys.filter { it.startsWith("$name:") }.forEach { sessions.remove(it) }
    }

    // ==================== sender keys (группы) ====================

    override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) {
        senderKeys.put(senderKeyKey(sender, distributionId), record.serialize())
    }

    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? =
        senderKeys.get(senderKeyKey(sender, distributionId))?.let { SenderKeyRecord(it) }

    // ==================== ключи ====================

    private fun addressKey(address: SignalProtocolAddress) = "${address.name}:${address.deviceId}"

    private fun senderKeyKey(address: SignalProtocolAddress, distributionId: UUID) =
        "${addressKey(address)}|$distributionId"

    companion object {
        private const val KEY_PAIR = "identity_pair"
        private const val KEY_REG_ID = "registration_id"
    }
}
