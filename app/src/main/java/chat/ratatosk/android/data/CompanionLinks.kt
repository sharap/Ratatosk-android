package chat.ratatosk.android.data

/**
 * Запись сопряжения со вторым экраном — строкой.
 *
 * Поля разделены `|`, записи — `;;`. Метку задаёт человек (или телефон
 * своим именем), и разделители в ней не редкость: «Петя | дом» рвал
 * запись на куски, порт переставал читаться, и сопряжение молча
 * пропадало из списка. Поэтому новые записи начинаются с пометки `v2`
 * и хранят поля закодированными.
 *
 * Старые записи (без пометки) читаются как раньше: переписывать чужой
 * файл настроек ради формата нельзя — их просто дочитывают до конца,
 * а перезапишутся они сами при следующем сохранении.
 */
internal object CompanionLinks {
    private const val RECORDS = ";;"
    private const val FIELDS = "|"
    private const val MARK = "v2"

    private fun escape(value: String): String = value
        .replace("%", "%25")
        .replace("|", "%7C")
        .replace(";", "%3B")

    private fun unescape(value: String): String = value
        .replace("%7C", "|")
        .replace("%3B", ";")
        .replace("%25", "%")

    /** `null` вместо строки: так пустое поле отличается от слова «null». */
    private fun field(value: String?): String = if (value == null) "" else escape(value)

    fun encode(link: CompanionLink): String = listOf(
        MARK,
        escape(link.label),
        escape(link.inviteUri),
        link.port.toString(),
        field(link.peerAddr),
        field(link.cachePath),
        field(link.torDir),
    ).joinToString(FIELDS)

    fun parse(raw: String): List<CompanionLink> =
        raw.split(RECORDS).filter { it.isNotBlank() }.mapNotNull { parseOne(it) }

    private fun parseOne(entry: String): CompanionLink? {
        val parts = entry.split(FIELDS)
        if (parts.firstOrNull() == MARK) {
            if (parts.size < 4) return null
            return CompanionLink(
                label = unescape(parts[1]),
                inviteUri = unescape(parts[2]),
                port = parts[3].toIntOrNull() ?: return null,
                peerAddr = parts.getOrNull(4)?.takeIf { it.isNotEmpty() }?.let { unescape(it) },
                cachePath = parts.getOrNull(5)?.takeIf { it.isNotEmpty() }?.let { unescape(it) },
                torDir = parts.getOrNull(6)?.takeIf { it.isNotEmpty() }?.let { unescape(it) },
            )
        }
        // Старый формат: пять полей и слово «null» вместо пустоты.
        if (parts.size < 5) return null
        return CompanionLink(
            label = parts[0],
            inviteUri = parts[1],
            port = parts[2].toIntOrNull() ?: 0,
            peerAddr = parts[3].takeIf { it != "null" },
            cachePath = parts[4].takeIf { it != "null" },
            torDir = parts.getOrNull(5)?.takeIf { it != "null" },
        )
    }

    /**
     * Кладёт запись, заменяя прежнюю для того же телефона.
     *
     * Сравнение по разобранной ссылке, а не поиском куска строки: прежний
     * поиск `"|$uri|"` находил и чужую запись, если ссылка попадалась
     * внутри другого поля.
     */
    fun upsert(raw: String, link: CompanionLink): String {
        val kept = parse(raw).filterNot { it.inviteUri == link.inviteUri }
        return (kept + link).joinToString(RECORDS) { encode(it) }
    }

    fun remove(raw: String, inviteUri: String): String =
        parse(raw).filterNot { it.inviteUri == inviteUri }.joinToString(RECORDS) { encode(it) }
}
