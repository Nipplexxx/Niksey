package com.example.niksey.database

import android.net.Uri
import com.example.niksey.utillits.APP_ACTIVITY
import com.example.niksey.utillits.FOLDER_FILES
import com.example.niksey.utillits.REF_STORAGE_ROOT
import com.example.niksey.utillits.TYPE_MESSAGE_IMAGE
import com.example.niksey.utillits.TYPE_MESSAGE_VOICE
import com.example.niksey.utillits.TYPE_MESSAGE_VIDEO
import com.example.niksey.utillits.media.MediaCache
import com.example.niksey.utillits.media.MediaCrypto
import com.example.niksey.utillits.media.MediaRepository
import com.example.niksey.utillits.signal.SecureMessaging
import com.example.niksey.utillits.showToast
import com.google.firebase.storage.StorageMetadata
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

private val encryptExecutor = Executors.newSingleThreadExecutor()

/**
 * Шифрует медиа на устройстве и загружает в Storage только шифротекст.
 *
 * 1. Генерируется случайный ключ файла (AES-256).
 * 2. Файл шифруется по частям во временный файл (см. MediaCrypto).
 * 3. В Storage уходит шифротекст с типом application/octet-stream — тип и содержимое не видны.
 * 4. Ключ файла и имя упаковываются в метаданные и шифруются ключом беседы (см. MediaRepository.encodeMeta).
 *
 * [onDone] получает (url, зашифрованные метаданные) — их отправляют в сообщении.
 * Колбэки и [onDone] вызываются на главном потоке.
 */
fun uploadEncryptedMedia(
    uri: Uri,
    messageKey: String,
    conversationId: String,
    typeMessage: String,
    filename: String,
    /** Собеседник для протокола Signal (личный чат); null — группа. */
    peer: String?,
    onDone: (fileUrl: String, metaText: String) -> Unit
) {
    encryptExecutor.execute {
        val fileKey = MediaCrypto.newFileKey()
        val tmp = MediaCache.tmpFile("upload_$messageKey")
        val name = filename.ifEmpty { defaultName(typeMessage) }

        // Локальный источник (запись голосового/видео из файлов приложения) удаляем
        // после отправки: копия уже зашифрована в tmp и лежит в Storage
        val localSource = localFileOf(uri)
        try {
            val source = APP_ACTIVITY.contentResolver.openInputStream(uri)
                ?: throw IOException("Файл недоступен")
            source.use { input ->
                tmp.outputStream().use { output ->
                    MediaCrypto.encrypt(
                        input, output, fileKey,
                        MediaRepository.fileAad(conversationId, messageKey)
                    )
                }
            }
        } catch (e: Exception) {
            tmp.delete()
            APP_ACTIVITY.runOnUiThread { showToast("Ошибка шифрования файла") }
            return@execute
        }

        // Ключ файла и имя шифруются протоколом Signal (после загрузки, когда известен url)
        val metaJson = MediaRepository.encodeMeta(fileKey.encoded, name)

        APP_ACTIVITY.runOnUiThread {
            val path = REF_STORAGE_ROOT.child("$FOLDER_FILES/$messageKey")
            val metadata = StorageMetadata.Builder()
                .setContentType("application/octet-stream")
                .build()

            path.putFile(Uri.fromFile(tmp), metadata)
                .addOnSuccessListener {
                    path.downloadUrl
                        .addOnSuccessListener { url ->
                            SecureMessaging.encryptText(messageKey, peer, conversationId, metaJson) { wire, error ->
                                tmp.delete()
                                localSource?.delete()
                                // encryptText отвечает на потоке шифрования: onDone и toast — только на главном
                                APP_ACTIVITY.runOnUiThread {
                                    if (wire != null) {
                                        onDone(url.toString(), wire)
                                    } else {
                                        // Сообщение не создано: шифротекст в Storage больше никому не нужен
                                        path.delete()
                                        showToast(APP_ACTIVITY.getString(SecureMessaging.errorMessageRes(error)))
                                    }
                                }
                            }
                        }
                        .addOnFailureListener {
                            tmp.delete()
                            localSource?.delete()
                            // Загрузка прошла, а ссылку взять не удалось — шифротекст осиротел
                            path.delete()
                            showToast("Не удалось получить ссылку на файл")
                        }
                }
                .addOnFailureListener {
                    tmp.delete()
                    localSource?.delete()
                    showToast("Ошибка загрузки файла")
                }
        }
    }
}

/** Файл из приватного хранилища приложения (file:// uri) — его можно безопасно удалить. */
private fun localFileOf(uri: Uri): File? =
    if (uri.scheme == "file" && uri.path != null) {
        val file = File(uri.path!!)
        // Удаляем только файлы самого приложения: чужие пути не трогаем
        if (file.absolutePath.startsWith(APP_ACTIVITY.filesDir.absolutePath)) file else null
    } else null

/** Имя по умолчанию — нужно плеерам, чтобы выбрать декодер по расширению. */
private fun defaultName(typeMessage: String): String = when (typeMessage) {
    TYPE_MESSAGE_IMAGE -> "image.jpg"
    TYPE_MESSAGE_VIDEO -> "video.mp4"
    TYPE_MESSAGE_VOICE -> "voice.m4a"
    else -> "file"
}
