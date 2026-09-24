package chat.ratatosk.android.util

/**
 * Имя видеосообщения: `12345.video.mp4`.
 *
 * Та же договорённость, что и у голосового ([VoiceFile]): вид и
 * длительность зашиты в имя, потому что отдельного поля у ядра нет.
 * Имя едет вместе с предложением файла и подписано, как всё остальное;
 * выбирает его отправитель — та же степень доверия, что и к байтам.
 *
 * Двойное расширение нарочно: `.video` отличает запись от присланного
 * `.mp4` из чужой коллекции, а хвост `.mp4` оставляет файл смотрибельным
 * в системе и в клиентах, которые про нашу договорённость не знают.
 *
 * Здесь только имя. Вид говорит, **как показать**, а не «можно ли
 * доверять байтам»: перед проигрыванием формат проверяется по сигнатуре.
 */
object VideoFile {
    const val SUFFIX = ".video.mp4"

    /**
     * Пять минут — потолок: дальше это не сообщение, а фильм, и едет он
     * обычным вложением, которое видно целиком.
     */
    const val MAX_DURATION_MS = 5L * 60 * 1000

    /** Сколько даём записать за раз: минута кружка — и так много. */
    const val RECORD_LIMIT_MS = 60L * 1000

    private val NAME = Regex("""^(\d{1,9})\.video\.mp4$""")

    /** Имя для записи длиной [durationMs]. */
    fun name(durationMs: Long): String = "${durationMs.coerceIn(0, MAX_DURATION_MS)}$SUFFIX"

    /**
     * Длительность записи по имени; `null` — это не наше видеосообщение.
     *
     * Число проверяется, а не принимается на веру: пустое, нечисловое
     * или несусветное имя означает обычное вложение, а не кружок
     * длиной в годы.
     */
    fun durationMsOf(name: String): Long? {
        val match = NAME.matchEntire(name.trim()) ?: return null
        val ms = match.groupValues[1].toLongOrNull() ?: return null
        return if (ms in 1..MAX_DURATION_MS) ms else null
    }

    fun isVideo(name: String): Boolean = durationMsOf(name) != null

    /**
     * Человеческое имя для сохранения на диск.
     *
     * Сырое `12345.video.mp4` не годится: человек ищет запись по дате,
     * а система выбирает приложение по расширению.
     */
    fun humanName(whenMs: Long, prefix: String): String {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH-mm", java.util.Locale.getDefault())
            .format(java.util.Date(whenMs))
        return "$prefix $stamp.mp4"
    }

    /**
     * MP4 узнаётся по `ftyp` в начале второго слова — проверяем формат,
     * а не имя: «видеосообщением» отправитель может назвать что угодно.
     */
    fun looksLikeMp4(file: java.io.File): Boolean = try {
        file.inputStream().use { input ->
            val head = ByteArray(12)
            input.read(head) == 12 && head.copyOfRange(4, 8).decodeToString() == "ftyp"
        }
    } catch (e: Exception) {
        false
    }
}
