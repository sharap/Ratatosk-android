package chat.ratatosk.android.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream

/**
 * Волна голосового сообщения — картинкой в превью вложения.
 *
 * Превью у нас уже есть и приезжает **до** приёма файла: по нему видно,
 * что это за запись, ещё до того, как её скачают. Рисуем здесь, а не
 * читаем из файла: декодировать Opus ради картинки нечем, а громкость
 * известна прямо с записи.
 */
object Waveform {
    private const val WIDTH = 240
    private const val HEIGHT = 48
    private const val BARS = 48

    /**
     * Рисует волну по снятой громкости.
     *
     * @return PNG или `null`, если рисовать нечего.
     */
    fun png(levels: List<Int>): ByteArray? {
        if (levels.isEmpty()) return null
        val peak = levels.max().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 3f
            strokeCap = Paint.Cap.ROUND
        }

        val step = WIDTH.toFloat() / BARS
        for (i in 0 until BARS) {
            // Каждому столбику — своя доля записи; при короткой записи
            // столбики повторяются, и это честнее, чем рисовать пустоту.
            val from = i * levels.size / BARS
            val to = ((i + 1) * levels.size / BARS).coerceAtLeast(from + 1)
            val slice = levels.subList(from.coerceAtMost(levels.size - 1), to.coerceAtMost(levels.size))
            val level = (slice.maxOrNull() ?: 0).toFloat() / peak
            val height = (HEIGHT - 6) * level.coerceIn(0.05f, 1f)
            val x = step * i + step / 2
            canvas.drawLine(x, (HEIGHT - height) / 2, x, (HEIGHT + height) / 2, paint)
        }

        val out = ByteArrayOutputStream()
        val ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return if (ok) out.toByteArray() else null
    }
}
