package chat.ratatosk.android

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * В журнал не должно попадать то, что пришло проводом.
 *
 * Тексты сообщений, имена файлов, адреса и ссылки сопряжения человек не
 * выбирал показывать, а `logcat` на отладочном устройстве читает кто угодно
 * с доступом к нему. Проверяется правило, а не поведение: соблазн написать
 * `println(event)` при отладке возвращается, и заметить это в ревью трудно.
 */
class LoggingPolicyTest {
    private val sources: List<File> =
        File("src/main/java/chat/ratatosk").walkTopDown().filter { it.extension == "kt" }.toList()

    @Test
    fun sourcesAreWhereWeThink() {
        assertTrue("исходники не найдены — тест смотрит не туда", sources.size > 20)
    }

    @Test
    fun nothingPrintsPastTheLogger() {
        val offenders = sources.filter { file ->
            file.readLines().any { line ->
                val code = line.substringBefore("//")
                "println(" in code || "printStackTrace(" in code
            }
        }
        assertTrue("печать мимо журнала: ${offenders.map { it.name }}", offenders.isEmpty())
    }

    @Test
    fun noContentGoesIntoTheLog() {
        // Запрещено двое: класть в журнал объект целиком (событие, сообщение,
        // контакт, файл — внутри них тексты и имена) и отдельные поля
        // с содержимым. Идентификаторы (`msgId`, `chatId`, `fileId`) можно:
        // по ним разбирают доставку, а прочитать по ним нечего.
        val wholeObject = Regex("""\$\{?(event|msg|message|contact|file)[}\s,)]""")
        val contentField = Regex("""\$\{[^}]*\.(body|text|name|displayName|address|uri|phrase|keyText)\b""")
        val offenders = sources.mapNotNull { file ->
            val hits = file.readLines().withIndex().filter { (_, line) ->
                val code = line.substringBefore("//")
                if ("Log." !in code) false
                else wholeObject.containsMatchIn(code) || contentField.containsMatchIn(code)
            }
            if (hits.isEmpty()) null else "${file.name}:${hits.map { it.index + 1 }}"
        }
        assertTrue("содержимое в журнале: $offenders", offenders.isEmpty())
    }
}
