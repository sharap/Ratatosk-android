package chat.ratatosk.android.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Кадр-обложка видеосообщения.
 *
 * Едет превью вложения — тем же способом, что волна у голосового: ядро
 * везёт к файлу картинку, и класть в неё кадр дешевле, чем заставлять
 * каждого получателя декодировать видео ради одной картинки. Особенно
 * это важно **до** приёма: кружок виден ещё не скачанным.
 */
object VideoPoster {
    /**
     * Первый кадр [file] как JPEG; `null` — кадра нет или он не влез
     * в отведённые байты, и тогда лучше без обложки, чем с обрезанной.
     */
    fun of(file: File, maxSide: Int = 320, maxBytes: Int = Int.MAX_VALUE): ByteArray? {
        // `use` у MediaMetadataRetriever появился в Android 10, а мы
        // живём с восьмёрки: закрываем руками.
        val frame = read(file) { it.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }
            ?: return null

        return try {
            val ratio = minOf(maxSide.toFloat() / frame.width, maxSide.toFloat() / frame.height)
            val scaled = if (ratio >= 1f) frame else Bitmap.createScaledBitmap(
                frame,
                (frame.width * ratio).toInt().coerceAtLeast(1),
                (frame.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
            val stream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, stream)
            stream.toByteArray().takeIf { it.size <= maxBytes }
        } catch (t: Throwable) {
            android.util.Log.w("RatatoskVM", "Failed to make a poster", t)
            null
        }
    }

    /** Длительность записи по самому файлу; `null` — не прочиталась. */
    fun durationMsOf(file: File): Long? =
        read(file) { it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() }

    private fun <T> read(file: File, block: (MediaMetadataRetriever) -> T?): T? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            block(retriever)
        } catch (t: Throwable) {
            android.util.Log.w("RatatoskVM", "Failed to read the video", t)
            null
        } finally {
            try {
                retriever.release()
            } catch (t: Throwable) {
                // release не должен мешать ответу
            }
        }
    }

    /** Картинка обложки из байтов превью; `null` — превью нет или оно не картинка. */
    fun bitmapOf(preview: ByteArray?): Bitmap? = preview?.let {
        runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
    }
}
