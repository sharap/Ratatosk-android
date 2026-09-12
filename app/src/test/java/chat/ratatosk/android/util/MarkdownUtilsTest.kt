package chat.ratatosk.android.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Проверка того, что в уведомление и в список чатов уезжает текст,
 * а не разметка. Прежняя реализация резала регулярками только `**`,
 * `_` и `~~`, поэтому код, ссылки и заголовки проходили насквозь —
 * тесты написаны прежде всего на них.
 */
class MarkdownUtilsTest {

    private fun plain(text: String) = MarkdownUtils.toPlainText(text, "спойлер")

    @Test
    fun `обратные кавычки не видны, содержимое остаётся`() {
        assertEquals("вызови foo() потом", plain("вызови `foo()` потом"))
    }

    @Test
    fun `огороженный блок кода отдаёт только код`() {
        assertEquals("val x = 1", plain("```kotlin\nval x = 1\n```"))
    }

    @Test
    fun `блок кода с отступом отдаёт только код`() {
        assertEquals("val x = 1", plain("    val x = 1"))
    }

    @Test
    fun `от ссылки остаётся подпись, адрес отбрасывается`() {
        assertEquals("смотри тут", plain("смотри [тут](https://example.com/very/long)"))
    }

    @Test
    fun `заголовок теряет решётки`() {
        assertEquals("Привет", plain("# Привет"))
    }

    @Test
    fun `цитата теряет угловую скобку`() {
        assertEquals("так он и сказал", plain("> так он и сказал"))
    }

    @Test
    fun `жирный и курсив снимаются`() {
        assertEquals("очень важно", plain("**очень** _важно_"))
    }

    @Test
    fun `зачёркнутый снимается`() {
        assertEquals("было стало", plain("~~было~~ стало"))
    }

    @Test
    fun `список превращается в одну строку`() {
        assertEquals("раз два", plain("- раз\n- два"))
    }

    @Test
    fun `переносы строк становятся пробелами`() {
        assertEquals("первая вторая", plain("первая\nвторая"))
    }

    @Test
    fun `спойлер заменяется подписью`() {
        assertEquals("это [спойлер] конец", plain("это ||тайна|| конец"))
    }

    @Test
    fun `пустой текст остаётся пустым`() {
        assertEquals("", plain(""))
        assertEquals("", plain("   "))
    }
}
