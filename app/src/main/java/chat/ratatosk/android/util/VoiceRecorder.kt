package chat.ratatosk.android.util

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Запись голосового сообщения.
 *
 * Пишет сразу в Ogg/Opus — тем же, чем его проигрывают телефон и ПК;
 * перекодировать потом было бы нечем. Ядру всё равно, что внутри файла:
 * голосовое едет обычным вложением, а признаком служит имя
 * ([VoiceFile]).
 *
 * Громкость снимается по ходу записи (`maxAmplitude`) — из неё потом
 * рисуется волна. Декодировать Opus ради картинки не нужно, а другого
 * способа узнать громкость у нас нет.
 */
class VoiceRecorder(private val context: Context) {
    /** Ogg/Opus в `MediaRecorder` появился в Android 10. */
    companion object {
        val SUPPORTED: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        /** Раз в 50 мс: на глаз это 20 столбиков в секунду, больше не нужно. */
        private const val TICK_MS = 50L
    }

    private var recorder: MediaRecorder? = null
    private var target: File? = null
    private var startedAtMs = 0L
    private val levels = mutableListOf<Int>()

    /** Идёт ли запись прямо сейчас. */
    val isRecording: Boolean get() = recorder != null

    /**
     * Начинает запись во временный файл.
     *
     * @return `false` — записать нечем (старый Android или устройство
     *   занято); звать дальше нечего, и сказать об этом надо человеку.
     */
    fun start(): Boolean {
        if (!SUPPORTED || recorder != null) return false
        val dir = File(context.cacheDir, "voice").apply { mkdirs() }
        val file = File(dir, "recording-${System.currentTimeMillis()}.ogg")

        val created = try {
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                MediaRecorder()
            }
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            // Голос: моно, 48 кГц — то, что Opus умеет лучше всего.
            r.setAudioChannels(1)
            r.setAudioSamplingRate(48_000)
            r.setAudioEncodingBitRate(24_000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            r
        } catch (t: Throwable) {
            android.util.Log.w("RatatoskVM", "Failed to start recording", t)
            file.delete()
            return false
        }

        recorder = created
        target = file
        startedAtMs = System.currentTimeMillis()
        levels.clear()
        return true
    }

    /** Снимает громкость; звать примерно раз в [TICK_MS]. */
    fun sample() {
        val r = recorder ?: return
        levels += try {
            r.maxAmplitude
        } catch (t: Throwable) {
            0
        }
    }

    /** Сколько уже записано. */
    fun elapsedMs(): Long = if (recorder == null) 0 else System.currentTimeMillis() - startedAtMs

    /**
     * Останавливает запись.
     *
     * @return готовая запись или `null`, если писать не вышло: слишком
     *   короткая, пустая или оборвавшаяся. Пустой файл отправлять нельзя —
     *   человек увидит у собеседника полосу, за которой ничего нет.
     */
    fun stop(): Recording? {
        val r = recorder ?: return null
        val file = target
        recorder = null
        target = null

        val durationMs = System.currentTimeMillis() - startedAtMs
        val ok = try {
            r.stop()
            true
        } catch (t: Throwable) {
            // `stop` бросает, если записать не успели ничего.
            android.util.Log.w("RatatoskVM", "Recording stopped with nothing in it", t)
            false
        }
        try {
            r.release()
        } catch (t: Throwable) {
            // release не должен мешать ответу
        }

        if (!ok || file == null || !file.exists() || file.length() == 0L || durationMs < 500) {
            file?.delete()
            return null
        }
        if (!VoiceFile.looksLikeOgg(file)) {
            // Внутри оказалось не то, чем мы это назовём.
            file.delete()
            return null
        }

        // Имя — по договорённости: длительность и двойное расширение.
        val named = File(file.parentFile, VoiceFile.name(durationMs))
        file.renameTo(named)
        return Recording(named, durationMs.coerceAtMost(VoiceFile.MAX_DURATION_MS), levels.toList())
    }

    /** Бросает запись и стирает файл: человек передумал. */
    fun cancel() {
        val r = recorder ?: return
        recorder = null
        try {
            r.stop()
        } catch (t: Throwable) {
            // Записать могли ничего — это не ошибка.
        }
        try {
            r.release()
        } catch (t: Throwable) {
        }
        target?.delete()
        target = null
    }

    /** Готовая запись: файл, длительность и громкость по ходу. */
    class Recording(val file: File, val durationMs: Long, val levels: List<Int>)
}
