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
    
    fun loadBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            
            // Fix orientation
            val exifInputStream = context.contentResolver.openInputStream(uri)
            val exif = exifInputStream?.let { ExifInterface(it) }
            val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            exifInputStream?.close()
            
            rotateBitmap(bitmap, orientation ?: ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            null
        }
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
