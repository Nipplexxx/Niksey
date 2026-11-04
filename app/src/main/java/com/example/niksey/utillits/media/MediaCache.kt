package com.example.niksey.utillits.media

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Локальный кэш расшифрованных медиа.
 *
 * • Файлы лежат в cacheDir, поэтому Android может их удалить сам — это безопасно: они восстанавливаются
 *   из Storage и ключа беседы.
 * • Ограничение по размеру (LRU): при превышении удаляются самые давно открывавшиеся файлы.
 * • Очистка удаляет только файлы медиа. Сообщения и ключи остаются нетронутыми.
 */
object MediaCache {

    private const val MEDIA_DIR = "media_cache"
    private const val TMP_DIR = "media_tmp"
    private const val PICASSO_DIR = "picasso-cache"

    /** Порог, после которого начинаем вытеснять старые файлы. */
    const val LIMIT_BYTES = 512L * 1024 * 1024

    private lateinit var appContext: Context
    private val lock = Any()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private val mediaDir: File get() = File(appContext.cacheDir, MEDIA_DIR).apply { mkdirs() }
    private val tmpDir: File get() = File(appContext.cacheDir, TMP_DIR).apply { mkdirs() }

    /**
     * Поколение кэша: растёт при каждой очистке. Холдеры держат миниатюры/длительности,
     * привязанные к путям файлов; после clear() путь тот же, а содержимое будет заново
     * скачано — старые миниатюры обязаны устареть, иначе покажется чужой кадр.
     */
    @Volatile
    var generation: Long = 0L
        private set

    /** Итоговый путь расшифрованного файла для ссылки [url]. Расширение нужно плеерам. */
    fun fileFor(url: String, extension: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it) }.take(40)
        val ext = extension.lowercase().filter { it.isLetterOrDigit() }.take(5)
        return File(mediaDir, if (ext.isEmpty()) name else "$name.$ext")
    }

    /** Временный файл для загрузки/скачивания; удаляется после использования. */
    fun tmpFile(name: String): File = File(tmpDir, name)

    /** Помечает файл как недавно использованный. */
    fun touch(file: File) {
        file.setLastModified(System.currentTimeMillis())
    }

    /** Размер кэша медиа (включая временные файлы), в байтах. */
    fun sizeBytes(): Long = directorySize(mediaDir) + directorySize(tmpDir) + directorySize(picassoDir())

    /**
     * Удаляет кэш медиа и кэш картинок аватаров. Сообщения и ключи не затрагиваются.
     * Каталоги пересоздаются: загрузки, которые идут прямо сейчас, должны иметь куда
     * сохраниться (MediaRepository восстановит папку при записи).
     */
    fun clear() {
        synchronized(lock) {
            mediaDir.deleteRecursively()
            tmpDir.deleteRecursively()
            picassoDir().deleteRecursively()
            generation += 1
            mediaDir.mkdirs()
            tmpDir.mkdirs()
        }
    }

    /** Вытесняет самые старые файлы, пока размер не станет меньше [LIMIT_BYTES]. */
    fun trim() {
        synchronized(lock) {
            var total = directorySize(mediaDir)
            if (total <= LIMIT_BYTES) return
            val oldestFirst = mediaDir.listFiles().orEmpty().sortedBy { it.lastModified() }
            for (file in oldestFirst) {
                if (total <= LIMIT_BYTES) break
                total -= file.length()
                file.delete()
            }
        }
    }

    private fun picassoDir() = File(appContext.cacheDir, PICASSO_DIR)

    private fun directorySize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
