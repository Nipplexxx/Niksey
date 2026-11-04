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

    /**
     * Вызывается, когда медиа появилось в кэше (скачано и расшифровано).
     * Экран чата подписывается на это и перерисовывает список — картинки и длительности
     * подставляются автоматически, без ручных notifyDataSetChanged.
     */
    @Volatile
    var onMediaReady: (() -> Unit)? = null

    private val executor = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val inFlight = HashMap<String, MutableList<(Result<File>) -> Unit>>()

    /** Меньше этого считаем файл в кэше битым (пустой остаток прерванной загрузки). */
    private const val MIN_VALID_FILE_BYTES = 16L

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
            // «empty» — заглушка из CommonModel по умолчанию: сообщения без файла
            if (attachment.url.isEmpty() || attachment.url == "empty") {
                onResult(Result.failure(IOException("Файл недоступен")))
                return@loadAttachment
            }

            val target = MediaCache.fileFor(attachment.url, extensionOf(attachment.name))
            // Файл мог остаться от прерванной загрузки (нулевой) или быть битым —
            // проверяем размер: пустые и подозрительно маленькие файлы перекачиваем
            if (target.isFile && target.length() > MIN_VALID_FILE_BYTES) {
                MediaCache.touch(target)
                onResult(Result.success(target))
                return@loadAttachment
            }
            if (target.isFile) target.delete() // битый/пустой остаток — качаем заново

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
                // Снимаем задачу с учёта только ПОСЛЕ завершения: если снять раньше,
                // повторный resolve во время загрузки начинал вторую загрузку того же файла
                val callbacks = synchronized(inFlight) { inFlight.remove(target.name).orEmpty() }
                mainHandler.post {
                    callbacks.forEach { it(result) }
                    // Файл впервые появился в кэше — сообщаем экрану, чтобы перерисовать
                    // превью картинок, длительности голосовых и миниатюры видео
                    if (result.isSuccess) onMediaReady?.invoke()
                }
            }
        }
    }

    private fun download(attachment: MediaAttachment, target: File): File {
        // Имя временного файла уникально: параллельные загрузки одного вложения
        // (гонка между удалением из inFlight и постом колбэков) не пишут в один файл
        val tmp = MediaCache.tmpFile("${target.name}.${System.nanoTime()}.part")
        try {
            val connection = (URL(attachment.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            try {
                // Ошибку сервера (403/404) нельзя «скачивать»: раньше тело ошибки
                // записывалось в кэш как файл и медиа навсегда показывалось битым
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("HTTP $code: ${attachment.url}")
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

            // Кэш могли очистить, пока шла загрузка: папку восстанавливаем,
            // а битый/устаревший target заменяем — атомарно, через переименование
            target.parentFile?.mkdirs()
            if (target.exists() && !target.delete()) throw IOException("Не удалось заменить файл в кэше")
            if (!tmp.renameTo(target)) {
                try {
                    tmp.copyTo(target, overwrite = true)
                } finally {
                    tmp.delete()
                }
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        MediaCache.touch(target)
        MediaCache.trim()
        return target
    }

    private fun extensionOf(name: String): String =
        if (name.contains('.')) name.substringAfterLast('.') else ""
}
