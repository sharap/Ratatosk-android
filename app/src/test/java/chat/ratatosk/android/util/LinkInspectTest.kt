package chat.ratatosk.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Что показывать про ссылку до перехода.
 *
 * Ссылку и её подпись пишет собеседник, и обе — на его усмотрение.
 * Разбор отделён от окна, чтобы подмены можно было перечислить здесь,
 * а не ловить их руками на живом сообщении.
 */
class LinkInspectTest {
    @Test
    fun ordinaryLinkIsOrdinary() {
        val looks = inspectLink("https://example.com/page?a=1", "https://example.com/page?a=1")
        assertEquals("example.com", looks.host)
        assertEquals("https", looks.scheme)
        assertTrue(looks.web)
        assertFalse(looks.punycode)
        assertFalse(looks.hasUserInfo)
        assertNull("подпись совпадает с адресом", looks.shownHost)
    }

    /** Подпись — просто текст: в ней может стоять чужое имя. */
    @Test
    fun theTextCanNameAnotherSite() {
        val looks = inspectLink("http://zlo.example/pay", "sberbank.ru")
        assertEquals("zlo.example", looks.host)
        assertEquals("sberbank.ru", looks.shownHost)
    }

    @Test
    fun plainTextLabelIsNotAnAddress() {
        val looks = inspectLink("https://example.com", "вот ссылка")
        assertNull(looks.shownHost)
    }

    /** Всё, что до `@`, адресом не является. */
    @Test
    fun everythingBeforeTheAtSignIsNotTheHost() {
        val looks = inspectLink("https://sberbank.ru@zlo.example/pay", "sberbank.ru")
        assertEquals("zlo.example", looks.host)
        assertTrue(looks.hasUserInfo)
    }

    @Test
    fun punycodeIsNamed() {
        val looks = inspectLink("https://xn--80ak6aa92e.com", "apple.com")
        assertTrue(looks.punycode)
        assertEquals("xn--80ak6aa92e.com", looks.host)
    }

    @Test
    fun nonWebSchemesAreNamed() {
        val mail = inspectLink("mailto:someone@example.com", "написать")
        assertFalse(mail.web)
        assertEquals("mailto", mail.scheme)

        val intent = inspectLink("intent://evil/#Intent;scheme=http;end", "картинка")
        assertFalse(intent.web)
        assertEquals("intent", intent.scheme)
    }

    /** Кривой адрес не должен ронять разбор: пусть решает человек. */
    @Test
    fun brokenAddressDoesNotThrow() {
        val looks = inspectLink("http://[не адрес]/", "ссылка")
        assertEquals("http://[не адрес]/", looks.url)
        assertNull(looks.host)
    }

    @Test
    fun hostCaseDoesNotMatter() {
        val looks = inspectLink("HTTPS://Example.COM/A", "Example.com")
        assertEquals("example.com", looks.host)
        assertNull("это тот же хост", looks.shownHost)
    }
}
