package chat.ratatosk.android.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

object FileUtils {
    /**
     * Годится ли ссылка на вложение, пришедшая из чужого приложения.
     *
     * `content://` — обычный случай: читаем через провайдера, чужими
     * правами, и дальше своего доступа не уйдём.
     *
     * `file://` шлют редко и в основном по небрежности, а принять его
     * как есть нельзя. Отправитель называет **путь**, а открываем мы его
     * своими правами — значит, назвав `file:///data/data/<нас>/databases/…`,
     * чужое приложение получит нашу же базу отправленной в чат. Поэтому
     * файловые ссылки внутрь нашего каталога отбрасываем, а всё, что не
     * `content:` и не `file:`, не принимаем вовсе.
     */
    fun isSafeIncomingUri(context: Context, uri: Uri): Boolean =
        when (uri.scheme?.lowercase()) {
            "content" -> true
            "file" -> {
                val path = uri.path?.let { runCatching { File(it).canonicalPath }.getOrNull() }
                val own = runCatching { File(context.applicationInfo.dataDir).canonicalPath }.getOrNull()
                path != null && own != null && path != own && !path.startsWith("$own/")
            }
            else -> false
        }

    fun copyUriToInternalStorage(context: Context, uri: Uri): File? {
        return try {
            val contentResolver = context.contentResolver
            val fileName = safeName(getFileName(context, uri)) ?: "file_${System.currentTimeMillis()}"
            val tempDir = File(context.cacheDir, "attachments_out")
            tempDir.mkdirs()
            val tempFile = File(tempDir, fileName)
            contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            tempFile
        } catch (e: Exception) {
            android.util.Log.e("FileUtils", "Failed to copy URI to internal storage", e)
            null
        }
    }

    /**
     * Приводит имя, пришедшее снаружи, к одному безопасному сегменту пути.
     *
     * `DISPLAY_NAME` даёт сторонний провайдер документов, то есть чужое
     * приложение. Имя вида `../../databases/x` в `File(dir, name)`
     * разрешается **за пределы** каталога — вплоть до внутренних файлов
     * приложения, где лежит зашифрованная база. Поэтому: берём только
     * последний сегмент, выбрасываем разделители и `..`.
     */
    fun safeName(raw: String?): String? {
        val name = raw?.substringAfterLast('/')?.substringAfterLast('\\')?.trim()
        if (name.isNullOrEmpty()) return null
        if (name == "." || name == "..") return null
        // Нулевой байт обрезает путь в нативном слое — имя с ним не годится.
        val cleaned = name.replace('\u0000', '_')
        return cleaned.take(200)
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        name = it.getString(index)
                    }
                }
            }
        }
        if (name == null) {
            name = uri.path
            val cut = name?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                name = name?.substring(cut + 1)
            }
        }
        return name
    }

    /** Подкаталоги кэша, где лежат **расшифрованные** копии вложений. */
    private val DECRYPTED_CACHE_DIRS = listOf(
        "temp_open",        // кнопка «открыть» в чате
        "media_viewer",     // просмотрщик картинок и видео
        "downloads"         // промежуточный файл сохранения у компаньона
    )

    // `attachments_out` в этот список **не входит**, и это не упущение.
    //
    // Туда кладётся то, что человек отправляет, и ядро по этому пути потом
    // и читает: у своего вложения оно не расшифровывает куски из хранилища,
    // а открывает исходный файл (`FileReader` с `source_path`). То есть это
    // не расшифрованная копия для показа, а единственный носитель своего
    // вложения, и живёт он столько же, сколько сообщение.
    //
    // Пока он был в списке, уборка сносила исходники — и свои вложения
    // переставали открываться навсегда, хотя чужие открывались.

    /**
     * Выбрасывает расшифрованные копии вложений из кэша.
     *
     * База и вложения на диске зашифрованы, а эти копии — нет: они лежат
     * открытым текстом ровно до тех пор, пока их кто-нибудь не уберёт.
     * Убирать было некому, и они копились до очистки данных приложения.
     *
     * `olderThanMs = 0` — вымести всё (выход из аккаунта, смена аккаунта).
     * Иначе трогаются только файлы старше срока: открытый прямо сейчас
     * просмотрщик держит свой файл, и выдёргивать его из-под него нельзя.
     */
    fun clearDecryptedCaches(context: Context, olderThanMs: Long = 0) {
        val cutoff = if (olderThanMs <= 0) Long.MAX_VALUE else System.currentTimeMillis() - olderThanMs
        for (dirName in DECRYPTED_CACHE_DIRS) {
            val dir = File(context.cacheDir, dirName)
            if (!dir.isDirectory) continue
            val files = dir.listFiles() ?: continue
            for (file in files) {
                if (file.lastModified() < cutoff) {
                    if (!file.deleteRecursively()) {
                        android.util.Log.w("FileUtils", "Failed to remove cached copy in $dirName")
                    }
                }
            }
        }
    }

    /** Куда легло сохранённое: поток для записи и имя для показа человеку. */
    class DownloadSink(val stream: java.io.OutputStream, val displayPath: String)

    /**
     * Открывает место для сохранения файла в «Загрузки».
     *
     * Прежде здесь стоял прямой `File` в
     * `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)`.
     * С Android 10 это scoped storage: без разрешений (а их у приложения нет
     * ни одного) запись отваливается `EACCES`, исключение уходило в журнал,
     * и человек не видел ничего — файл просто не сохранялся.
     *
     * Теперь: с API 29 через `MediaStore`, где разрешений не требуется
     * вовсе; ниже — прежним путём, там он законен.
     */
    fun openDownloadSink(context: Context, fileName: String): DownloadSink? {
        val name = safeName(fileName) ?: "file_${System.currentTimeMillis()}"
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, getMimeType(name))
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/ratatosk")
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: return null
                val stream = resolver.openOutputStream(uri) ?: run {
                    resolver.delete(uri, null, null)
                    return null
                }
                // IS_PENDING снимается после записи, иначе файл не виден
                // другим приложениям. Обёртка делает это на close().
                DownloadSink(PendingMediaStream(stream, resolver, uri), "Download/ratatosk/$name")
            } else {
                @Suppress("DEPRECATION")
                val downloads = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                )
                val dir = File(downloads, "ratatosk")
                dir.mkdirs()
                val dest = File(dir, name)
                DownloadSink(dest.outputStream(), dest.absolutePath)
            }
        } catch (e: Exception) {
            android.util.Log.e("FileUtils", "Failed to open download sink", e)
            null
        }
    }

    /** Поток MediaStore, снимающий `IS_PENDING` при закрытии. */
    private class PendingMediaStream(
        private val inner: java.io.OutputStream,
        private val resolver: android.content.ContentResolver,
        private val uri: Uri
    ) : java.io.OutputStream() {
        override fun write(b: Int) = inner.write(b)
        override fun write(b: ByteArray) = inner.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = inner.write(b, off, len)
        override fun flush() = inner.flush()
        override fun close() {
            inner.close()
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            }
            resolver.update(uri, values, null, null)
        }
    }

    fun formatFileSize(bytes: ULong): String {
        val b = bytes.toDouble()
        return when {
            b < 1024 -> "%.0f B".format(java.util.Locale.US, b)
            b < 1024 * 1024 -> "%.1f KB".format(java.util.Locale.US, b / 1024)
            b < 1024 * 1024 * 1024 -> "%.1f MB".format(java.util.Locale.US, b / (1024 * 1024))
            else -> "%.1f GB".format(java.util.Locale.US, b / (1024 * 1024 * 1024))
        }
    }

    fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "apk" -> "application/vnd.android.package-archive"
            else -> "*/*"
        }
    }

    fun isImage(fileName: String): Boolean = getMimeType(fileName).startsWith("image/")
    fun isVideo(fileName: String): Boolean = getMimeType(fileName).startsWith("video/")
    fun isAudio(fileName: String): Boolean = getMimeType(fileName).startsWith("audio/")
}
