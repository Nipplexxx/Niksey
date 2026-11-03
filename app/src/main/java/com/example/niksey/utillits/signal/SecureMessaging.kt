package com.example.niksey.utillits.signal

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.niksey.R
import com.example.niksey.utillits.ChatEncryptionManager
import com.example.niksey.utillits.EncryptionUtils
import com.example.niksey.utillits.signal.SignalManager.SendOutcome
import java.util.concurrent.atomic.AtomicBoolean

/** Почему сообщение не удалось зашифровать (для текста ошибки пользователю). */
enum class EncryptError {
    /** Ключ собеседника изменился и не подтверждён */
    IDENTITY_CHANGED,
    /** У собеседника нет ключей шифрования */
    NO_KEYS,
    /** Не удалось установить сессию или групповой ключ */
    FAILED,
    /** Шифрование не завершилось за отведённое время (нет связи) */
    TIMEOUT
}

/**
 * Единая точка шифрования сообщений и вложений.
 *
 * • Личные чаты — Signal (PQXDH + Double Ratchet).
 * • Группы — Signal Sender Keys (см. SignalManager).
 * • Старая схема (AES с ключом беседы) — только если у собеседника нет ключей Signal вообще
 *   (старая версия приложения) и только для личных чатов. Временная ошибка никогда не даёт отката.
 * • Расшифрованный текст кэшируется по id сообщения: сообщение Signal расшифровывается один раз,
 *   а отправитель не может расшифровать собственное сообщение.
 *
 * Результаты приходят на поток, где они готовы (шифрование — сигнальный поток, расшифровка — главный).
 */
object SecureMessaging {

    private const val TAG = "SecureMessaging"
    private const val GROUP_PREFIX = "gr:"

    /** Сколько ждём шифрования (сессия, участники группы, запись ключей) до ошибки. */
    private const val SEND_TIMEOUT_MS = 20_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var plainCache: SecureKvStore
    private val cacheLock = Any()

    /** Расшифровки, которые идут прямо сейчас: messageId → колбэки ожидающих. Доступ — под lock на inFlight. */
    private val inFlight = HashMap<String, MutableList<(String?) -> Unit>>()

    /** Вызывается, когда фоновая расшифровка закончилась (например, имя файла) — экран нужно перерисовать. */
    @Volatile
    var onDecryptedUpdate: (() -> Unit)? = null

    fun init(context: Context) {
        SignalManager.init(context)
        plainCache = SecureKvStore(context, "plaintext_cache")
    }

    /** Полная очистка при выходе: ключи Signal, сессии и кэш расшифрованных сообщений. */
    fun wipe() {
        synchronized(cacheLock) { plainCache.clear() }
        SignalManager.resetLocal()
    }

    /** Текст ошибки для пользователя по причине сбоя шифрования. */
    fun errorMessageRes(error: EncryptError?): Int = when (error) {
        EncryptError.IDENTITY_CHANGED -> R.string.send_error_identity
        EncryptError.NO_KEYS -> R.string.send_error_no_keys
        EncryptError.TIMEOUT -> R.string.send_error_timeout
        EncryptError.FAILED, null -> R.string.send_error_failed
    }

    /** Идентификатор группы, если беседа групповая. */
    fun groupIdOf(conversationId: String): String? =
        if (conversationId.startsWith(GROUP_PREFIX)) conversationId.removePrefix(GROUP_PREFIX) else null

    // ==================== шифрование ====================

    /**
     * Шифрует [plain] для беседы. [peer] — собеседник в личном чате; для группы не используется.
     * [messageId] — id сообщения: для него сохраняется открытый текст, чтобы отправитель мог его показать.
     */
    fun encryptText(
        messageId: String,
        peer: String?,
        conversationId: String,
        plain: String,
        onResult: (wire: String?, error: EncryptError?) -> Unit
    ) {
        // Если шифрование зависло (нет ответа от Firebase, сеть), отправка не должна ждать вечно
        val done = AtomicBoolean(false)
        lateinit var timeout: Runnable
        val finish: (String?, EncryptError?) -> Unit = { wire, error ->
            if (done.compareAndSet(false, true)) {
                mainHandler.removeCallbacks(timeout)
                onResult(wire, error)
            }
        }
        timeout = Runnable {
            Log.w(TAG, "Шифрование не завершилось за ${SEND_TIMEOUT_MS} мс")
            finish(null, EncryptError.TIMEOUT)
        }
        mainHandler.postDelayed(timeout, SEND_TIMEOUT_MS)

        encryptInternal(messageId, peer, conversationId, plain, finish)
    }

    private fun encryptInternal(
        messageId: String,
        peer: String?,
        conversationId: String,
        plain: String,
        onResult: (String?, EncryptError?) -> Unit
    ) {
        val bytes = plain.toByteArray(Charsets.UTF_8)

        groupIdOf(conversationId)?.let { groupId ->
            SignalManager.encryptForGroup(groupId, bytes) { outcome ->
                finishEncrypt(outcome, messageId, plain, conversationId, allowLegacy = false, onResult)
            }
            return
        }

        if (peer == null) {
            encryptLegacy(conversationId, plain, onResult)
            return
        }
        SignalManager.encryptForPeer(peer, bytes) { outcome ->
            finishEncrypt(outcome, messageId, plain, conversationId, allowLegacy = true, onResult)
        }
    }

    private fun finishEncrypt(
        outcome: SendOutcome,
        messageId: String,
        plain: String,
        conversationId: String,
        allowLegacy: Boolean,
        onResult: (String?, EncryptError?) -> Unit
    ) {
        when (outcome) {
            is SendOutcome.Sent -> {
                cacheText(messageId, plain)
                onResult(outcome.wire, null)
            }
            SendOutcome.NoBundle -> {
                // Собеседник на старой версии приложения: единственный случай отката на прежнюю схему
                if (allowLegacy) encryptLegacy(conversationId, plain, onResult) else onResult(null, EncryptError.NO_KEYS)
            }
            SendOutcome.Untrusted -> onResult(null, EncryptError.IDENTITY_CHANGED)
            SendOutcome.Failed -> onResult(null, EncryptError.FAILED)
        }
    }

    private fun encryptLegacy(conversationId: String, plain: String, onResult: (String?, EncryptError?) -> Unit) {
        ChatEncryptionManager.getKeyAsync(conversationId) { key ->
            if (key == null) {
                onResult(null, EncryptError.NO_KEYS)
            } else {
                onResult(EncryptionUtils.encryptMessage(plain, key, conversationId), null)
            }
        }
    }

    // ==================== расшифровка ====================

    /**
     * Расшифровывает сохранённый текст сообщения. Сначала смотрит в кэш.
     * Возвращает открытый текст, либо null — если ключа нет или сообщение не проверяется.
     * Старые незашифрованные сообщения возвращаются как есть.
     */
    fun decryptText(
        messageId: String,
        sender: String,
        conversationId: String,
        stored: String,
        onResult: (String?) -> Unit
    ) {
        cachedText(messageId)?.let { onResult(it); return }

        if (SignalManager.isSignalText(stored)) {
            synchronized(inFlight) {
                val waiting = inFlight[messageId]
                if (waiting != null) {
                    waiting.add(onResult)
                    return
                }
                inFlight[messageId] = mutableListOf(onResult)
            }
            decryptSignal(messageId, sender, groupIdOf(conversationId), stored, retryAllowed = true)
            return
        }

        if (!stored.startsWith(EncryptionUtils.FORMAT_V2_PREFIX)) {
            // Старое незашифрованное сообщение
            mainHandler.post { onResult(stored) }
            return
        }

        ChatEncryptionManager.getKeyAsync(conversationId) { key ->
            val text = key?.let { ChatEncryptionManager.decrypt(conversationId, stored) }
            mainHandler.post { onResult(text) }
        }
    }

    /**
     * Расшифровка Signal. Если это групповое сообщение и ключа отправителя ещё нет, сначала
     * забираем sender keys из группы и пробуем снова (один раз).
     */
    private fun decryptSignal(
        messageId: String,
        sender: String,
        groupId: String?,
        stored: String,
        retryAllowed: Boolean
    ) {
        SignalManager.decryptFromPeer(sender, stored) { bytes ->
            if (bytes == null && groupId != null && retryAllowed) {
                SignalManager.syncGroupKeys(groupId) {
                    decryptSignal(messageId, sender, groupId, stored, retryAllowed = false)
                }
                return@decryptFromPeer
            }
            val text = bytes?.toString(Charsets.UTF_8)
            if (text != null) cacheText(messageId, text)
            val waiters = synchronized(inFlight) { inFlight.remove(messageId).orEmpty() }
            mainHandler.post { waiters.forEach { it(text) } }
        }
    }

    /** Фоновая расшифровка без результата: когда он готов, экран перерисуется через [onDecryptedUpdate]. */
    fun prefetch(messageId: String, sender: String, conversationId: String, stored: String) {
        decryptText(messageId, sender, conversationId, stored) { onDecryptedUpdate?.invoke() }
    }

    // ==================== кэш открытого текста ====================

    /** Открытый текст из кэша (синхронно), либо null. */
    fun cachedText(messageId: String): String? = synchronized(cacheLock) {
        if (!::plainCache.isInitialized) return null
        plainCache.get(messageId)?.toString(Charsets.UTF_8)
    }

    private fun cacheText(messageId: String, text: String) {
        synchronized(cacheLock) {
            if (::plainCache.isInitialized) plainCache.put(messageId, text.toByteArray(Charsets.UTF_8))
        }
    }
}
