package chat.ratatosk.android.util

/**
 * Имя голосового сообщения: `12345.voice.ogg`.
 *
 * Голосовое едет обычным вложением, а его вид и длительность зашиты
 * в имя — отдельного поля у ядра нет. Имя путешествует вместе
 * с предложением файла и подписано, как всё остальное: переписать его
 * по дороге нельзя, выбирает его отправитель — та же степень доверия,
 * что и к самим байтам.
 *
 * Двойное расширение нарочно. `.voice` отличает запись от присланного
 * `.ogg` из чужой коллекции, а хвост `.ogg` оставляет файл проигрываемым
 * для системы и для клиентов, которые про нашу договорённость не знают.
 *
 * Здесь только имя. Вид говорит, **как показать**, а не «можно ли
 * доверять байтам»: перед проигрыванием формат всё равно проверяется
 * по сигнатуре.
 */
object VoiceFile {
    const val SUFFIX = ".voice.ogg"

    /** Час записи — потолок: дальше это не голосовое сообщение. */
    const val MAX_DURATION_MS = 60L * 60 * 1000

    private val NAME = Regex("""^(\d{1,9})\.voice\.ogg$""")

    /** Имя для записи длиной [durationMs]. */
    fun name(durationMs: Long): String = "${durationMs.coerceIn(0, MAX_DURATION_MS)}$SUFFIX"

    /**
     * Длительность записи по имени; `null` — это не наше голосовое.
     *
     * Число проверяется, а не принимается на веру: пустое, нечисловое
     * или несусветное имя означает обычное вложение, а не полосу
     * воспроизведения длиной в годы.
     */
    fun durationMsOf(name: String): Long? {
        val match = NAME.matchEntire(name.trim()) ?: return null
        val ms = match.groupValues[1].toLongOrNull() ?: return null
        return if (ms in 1..MAX_DURATION_MS) ms else null
    }

    fun isVoice(name: String): Boolean = durationMsOf(name) != null

    /**
     * Человеческое имя для сохранения на диск.
     *
     * Сырое `12345.voice.ogg` не годится: человек ищет запись по дате,
     * а система выбирает приложение по расширению.
     */
    fun humanName(whenMs: Long, prefix: String): String {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH-mm", java.util.Locale.getDefault())
            .format(java.util.Date(whenMs))
        return "$prefix $stamp.ogg"
    }

    /** Ogg начинается с `OggS` — проверяем формат, а не имя. */
    fun looksLikeOgg(file: java.io.File): Boolean = try {
        file.inputStream().use { input ->
            val head = ByteArray(4)
            input.read(head) == 4 && head.decodeToString() == "OggS"
        }
    } catch (e: Exception) {
        false
    }
}
