package chat.ratatosk.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Три ссылки начинаются одинаково и значат разное: «добавь меня
 * в контакты» (§4.2), «стань моим терминалом» (§13.3) и «читай этот
 * канал» (§10.1). Весь разбор — в порядке проверок, и перепутать их
 * значит подписать человека вместо сопряжения или наоборот.
 */
class IncomingLinksTest {
    @Test
    fun aChannelLinkIsNotAContact() {
        val link = IncomingIntents.link("ratatosk:v0:channel:AAAA")
        assertTrue(link.toString(), link is Incoming.SubscribeChannel)
        assertEquals("ratatosk:v0:channel:AAAA", (link as Incoming.SubscribeChannel).uri)
    }

    @Test
    fun aPairLinkStaysAPairLink() {
        assertTrue(IncomingIntents.link("ratatosk:v0:pair:AAAA") is Incoming.PairDevice)
    }

    @Test
    fun anythingElseOfOursIsAContact() {
        assertTrue(IncomingIntents.link("ratatosk:v0:AAAA") is Incoming.AddContact)
    }

    /** Схему система приводит к нижнему регистру, набранное руками — нет. */
    @Test
    fun theSchemeCaseDoesNotMatter() {
        assertTrue(IncomingIntents.link("RATATOSK:V0:CHANNEL:AAAA") is Incoming.SubscribeChannel)
    }

    @Test
    fun foreignLinksAreNotOurs() {
        assertNull(IncomingIntents.link("https://example.com"))
        assertNull(IncomingIntents.link("ratatosk:"))
        assertNull(IncomingIntents.link(null))
    }
}
