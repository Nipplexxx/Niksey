package com.example.niksey.utillits.media

import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.example.niksey.ui.fragments.message_recycler_view.views.MessageView
import com.example.niksey.utillits.ChatEncryptionManager
import com.example.niksey.utillits.EncryptionUtils
import com.example.niksey.utillits.signal.SecureMessaging
import com.example.niksey.utillits.signal.SignalManager
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import javax.crypto.spec.SecretKeySpec

/** Вложение сообщения: где лежит шифротекст, ключ файла и имя (если было). */
class MediaAttachment(
    val url: String,
    /** Ключ файла; null — старое незашифрованное вложение. */
    val fileKey: ByteArray?,
    val name: String,
    /** Контекст, который защищает файл от подмены (беседа + id сообщения). */
    val aad: ByteArray
)

/**
 * Превращает вложение сообщения в локальный расшифрованный файл.
 *
 * Порядок: кэш → загрузка шифротекста → расшифровка по частям в кэш.
 * Одинаковые запросы не скачиваются дважды. Колбэки приходят на главный поток.
 */
object MediaRepository {

    /** Метаданные ещё нельзя расшифровать: ключ беседы не загружен. Экран перерисуется, когда ключ появится. */
    class KeyPendingException : IOException("Ключ беседы ещё загружается")

    private val executor = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val inFlight = HashMap<String, MutableList<(Result<File>) -> Unit>>()

    /** Метаданные медиа-сообщения. Новый формат — зашифрованный JSON (k = ключ файла, n = имя). */
    fun encodeMeta(fileKey: ByteArray, name: String): String =
        JSONObject()
            .put("v", 1)
            .put("k", Base64.encodeToString(fileKey, Base64.NO_WRAP))
            .put("n", name)
            .toString()

    /** Контекст файла: привязан к беседе и id сообщения. Тот же строится при загрузке. */
    fun fileAad(conversationId: String, messageId: String): ByteArray =
        "$conversationId|$messageId".toByteArray(Charsets.UTF_8)

    /** Метаданные вложения: ключ файла и имя. Расшифровываются протоколом Signal (или старой схемой). */
    fun loadAttachment(view: MessageView, conversationId: String, onResult: (Result<MediaAttachment>) -> Unit) {
        val aad = fileAad(conversationId, view.id)

        // Старые незашифрованные сообщения: в text лежит имя файла, URL открыт
        if (!isEncryptedStorage(view.text)) {
            onResult(Result.success(MediaAttachment(view.fileUrl, null, view.text, aad)))
            return
        }

        SecureMessaging.decryptText(view.id, view.from, conversationId, view.text) { json ->
            if (json == null) {
                onResult(Result.failure(KeyPendingException()))
                return@decryptText
            }
            onResult(runCatching {
                val meta = JSONObject(json)
                MediaAttachment(
                    url = view.fileUrl,
                    fileKey = Base64.decode(meta.getString("k"), Base64.NO_WRAP),
                    name = meta.optString("n"),
                    aad = aad
                )
            })
        }
    }

    /** Имя файла из уже расшифрованных метаданных (синхронно). null — если ещё не расшифровано. */
    fun cachedName(view: MessageView): String? {
        if (!isEncryptedStorage(view.text)) return view.text.ifEmpty { null }
        val json = SecureMessaging.cachedText(view.id) ?: return null
        return runCatching { JSONObject(json).optString("n") }.getOrNull()?.ifEmpty { null }
    }

    private fun isEncryptedStorage(text: String) =
        text.startsWith(EncryptionUtils.FORMAT_V2_PREFIX) || SignalManager.isSignalText(text)


    /**
     * Возвращает локальный файл вложения. Если он уже в кэше — колбэк вызывается сразу.
     */
    fun resolve(view: MessageView, conversationId: String, onResult: (Result<File>) -> Unit) {
        loadAttachment(view, conversationId) { loaded ->
            val attachment = loaded.getOrElse { onResult(Result.failure(it)); return@loadAttachment }
            if (attachment.url.isEmpty()) {
                onResult(Result.failure(IOException("Файл недоступен")))
                return@loadAttachment
            }

            val target = MediaCache.fileFor(attachment.url, extensionOf(attachment.name))
            if (target.isFile && target.length() > 0) {
                MediaCache.touch(target)
                onResult(Result.success(target))
                return@loadAttachment
            }

            synchronized(inFlight) {
                val waiting = inFlight[target.name]
                if (waiting != null) {
                    waiting.add(onResult)
                    return@loadAttachment
                }
                inFlight[target.name] = mutableListOf(onResult)
            }

            executor.execute {
                val result = runCatching { download(attachment, target) }
                val callbacks = synchronized(inFlight) { inFlight.remove(target.name).orEmpty() }
                mainHandler.post { callbacks.forEach { it(result) } }
            }
        }
    }

    private fun download(attachment: MediaAttachment, target: File): File {
        val tmp = MediaCache.tmpFile(target.name + ".part")
        try {
            val connection = (URL(attachment.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            try {
                connection.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val key = attachment.fileKey
                        if (key == null) {
                            input.copyTo(out)
                        } else {
                            MediaCrypto.decrypt(input, out, SecretKeySpec(key, "AES"), attachment.aad)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            if (!tmp.renameTo(target)) throw IOException("Не удалось сохранить файл в кэш")
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        MediaCache.trim()
        return target
    }

    private fun extensionOf(name: String): String =
        if (name.contains('.')) name.substringAfterLast('.') else ""
}
