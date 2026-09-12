package chat.ratatosk.android.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.InputStream

object ImageUtils {
    
    /**
     * Наибольшая сторона битмапа, который мы готовы держать в памяти.
     *
     * Картинка идёт под обрезку аватарки, а та ужимается до 512 — держать
     * ради этого исходник снимка незачем. Без предела снимок с современной
     * камеры (50 Мп) разворачивается в ARGB_8888 примерно в 200 МБ и кладёт
     * приложение: OutOfMemoryError это Error, а не Exception, и прежний
     * `catch (e: Exception)` его не ловил.
     */
    private const val MAX_DIMENSION = 2048

    fun loadBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            val resolver = context.contentResolver

            // Первый проход — только размеры, без выделения памяти под пиксели.
            //
            // **Возвращает он null всегда**, и это не ошибка, а весь смысл
            // inJustDecodeBounds: заполняются лишь outWidth/outHeight.
            // Проверять здесь результат декодирования нельзя — именно на
            // этом loadBitmap и отдавал null на любой картинке, из-за чего
            // экран обрезки ждал битмап бесконечно. Проверяем поток.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val boundsStream = resolver.openInputStream(uri) ?: return null
            boundsStream.use { input -> BitmapFactory.decodeStream(input, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            // Второй — с прореживанием. inSampleSize округляется вниз до
            // степени двойки самим декодером, так что считаем ею же.
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            }
            // Здесь результат уже настоящий: decodeStream возвращает битмап,
            // и null означает, что картинку не разобрать.
            val bitmap = resolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            } ?: return null

            val orientation = resolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL

            rotateBitmap(bitmap, orientation)
        } catch (e: Exception) {
            android.util.Log.w("ImageUtils", "Failed to load bitmap: ${e.message}")
            null
        } catch (e: OutOfMemoryError) {
            // Ловим отдельно и намеренно: прореживание делает это
            // маловероятным, но «маловероятно» не значит «никогда», а падать
            // приложению из-за выбранной картинки нельзя.
            android.util.Log.w("ImageUtils", "Out of memory decoding bitmap")
            null
        }
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        // Условие на **итоговый** размер, а не на следующий шаг: иначе
        // предел срабатывал вдвое позже обещанного и снимок на 12 Мп
        // всё равно разворачивался целиком.
        var sample = 1
        while (width / sample > MAX_DIMENSION || height / sample > MAX_DIMENSION) {
            sample *= 2
        }
        return sample
    }

    private fun rotateBitmap(bitmap: Bitmap?, orientation: Int): Bitmap? {
        if (bitmap == null) return null
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun cropAndResize(bitmap: Bitmap, x: Float, y: Float, size: Float, targetSize: Int = 512): Bitmap {
        // x, y, size are percentages (0..1)
        val left = (x * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val top = (y * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val width = (size * bitmap.width).toInt().coerceIn(1, bitmap.width - left)
        val height = (size * bitmap.height).toInt().coerceIn(1, bitmap.height - top)
        
        val cropSize = Math.min(width, height)
        
        val cropped = Bitmap.createBitmap(bitmap, left, top, cropSize, cropSize)
        return Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)
    }

    fun compressToWebp(bitmap: Bitmap, maxBytes: Int): ByteArray? {
        var quality = 90
        var result: ByteArray
        do {
            val out = ByteArrayOutputStream()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out)
            } else {
                @Suppress("DEPRECATION")
                bitmap.compress(Bitmap.CompressFormat.WEBP, quality, out)
            }
            result = out.toByteArray()
            quality -= 10
        } while (result.size > maxBytes && quality > 10)
        
        return if (result.size <= maxBytes) result else null
    }
}
