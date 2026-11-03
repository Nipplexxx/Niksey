package com.example.niksey.database

import android.net.Uri
import com.example.niksey.utillits.APP_ACTIVITY
import com.example.niksey.utillits.ChatEncryptionManager
import com.example.niksey.utillits.EncryptionUtils
import com.example.niksey.utillits.FOLDER_FILES
import com.example.niksey.utillits.REF_STORAGE_ROOT
import com.example.niksey.utillits.TYPE_MESSAGE_IMAGE
import com.example.niksey.utillits.TYPE_MESSAGE_VOICE
import com.example.niksey.utillits.media.MediaCache
import com.example.niksey.utillits.media.MediaCrypto
import com.example.niksey.utillits.media.MediaRepository
import com.example.niksey.utillits.signal.SecureMessaging
import com.example.niksey.utillits.showToast
import com.google.firebase.storage.StorageMetadata
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
    run {

        encryptExecutor.execute {
            val fileKey = MediaCrypto.newFileKey()
            val tmp = MediaCache.tmpFile("upload_$messageKey")
            val name = filename.ifEmpty { defaultName(typeMessage) }

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
                                tmp.delete()
                                SecureMessaging.encryptText(messageKey, peer, conversationId, metaJson) { wire ->
                                    APP_ACTIVITY.runOnUiThread {
                                        if (wire != null) onDone(url.toString(), wire)
                                        else showToast("Не удалось зашифровать вложение")
                                    }
                                }
                            }
                            .addOnFailureListener {
                                tmp.delete()
                                showToast("Не удалось получить ссылку на файл")
                            }
                    }
                    .addOnFailureListener {
                        tmp.delete()
                        showToast("Ошибка загрузки файла")
                    }
            }
        }
    }
}

/** Имя по умолчанию — нужно плеерам, чтобы выбрать декодер по расширению. */
private fun defaultName(typeMessage: String): String = when (typeMessage) {
    TYPE_MESSAGE_IMAGE -> "image.jpg"
    TYPE_MESSAGE_VIDEO -> "video.mp4"
    TYPE_MESSAGE_VOICE -> "voice.m4a"
    else -> "file"
}
