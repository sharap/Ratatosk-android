package chat.ratatosk.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Записи сопряжения переживают сохранение и чтение.
 *
 * Метку задаёт человек или сам телефон своим именем, а формат хранится
 * строкой с разделителями — «Петя | дом» рвал запись на куски: порт
 * переставал читаться, и второй экран молча терял сопряжение.
 */
class CompanionLinksTest {
    private fun link(
        label: String = "Телефон",
        uri: String = "ratatosk://aa",
        port: Int = 4242,
        peer: String? = null,
        cache: String? = null,
        tor: String? = null,
    ) = CompanionLink(label, uri, port, peer, cache, tor)

    @Test
    fun linkSurvivesSaveAndRead() {
        val saved = CompanionLinks.upsert("", link(peer = "10.0.0.2", cache = "/data/c", tor = "/data/tor"))
        val read = CompanionLinks.parse(saved).single()

        assertEquals("Телефон", read.label)
        assertEquals("ratatosk://aa", read.inviteUri)
        assertEquals(4242, read.port)
        assertEquals("10.0.0.2", read.peerAddr)
        assertEquals("/data/c", read.cachePath)
        assertEquals("/data/tor", read.torDir)
    }

    @Test
    fun separatorsInTheLabelDoNotBreakTheRecord() {
        var raw = CompanionLinks.upsert("", link(label = "A|B;;C%D", uri = "ratatosk://aa"))
        raw = CompanionLinks.upsert(raw, link(label = "Обычное", uri = "ratatosk://bb"))

        val read = CompanionLinks.parse(raw)
        assertEquals(2, read.size)
        assertEquals("A|B;;C%D", read.first { it.inviteUri == "ratatosk://aa" }.label)
        assertEquals(4242, read.first { it.inviteUri == "ratatosk://aa" }.port)
    }

    @Test
    fun savingAgainReplacesTheSamePhone() {
        var raw = CompanionLinks.upsert("", link(label = "Старое", cache = "/old"))
        raw = CompanionLinks.upsert(raw, link(label = "Новое", cache = null))

        val read = CompanionLinks.parse(raw)
        assertEquals(1, read.size)
        assertEquals("Новое", read.single().label)
        assertNull("выключенный кэш не должен воскресать", read.single().cachePath)
    }

    @Test
    fun removingForgetsOnlyThatPhone() {
        var raw = CompanionLinks.upsert("", link(uri = "ratatosk://aa"))
        raw = CompanionLinks.upsert(raw, link(uri = "ratatosk://bb"))

        val left = CompanionLinks.parse(CompanionLinks.remove(raw, "ratatosk://aa"))
        assertEquals(listOf("ratatosk://bb"), left.map { it.inviteUri })
    }

    /** Ссылка, попавшая внутрь чужого поля, не должна уносить чужую запись. */
    @Test
    fun aLinkMentionedInAnotherFieldIsNotTouched() {
        var raw = CompanionLinks.upsert("", link(uri = "ratatosk://aa", cache = "/cache/ratatosk://bb"))
        raw = CompanionLinks.upsert(raw, link(uri = "ratatosk://bb"))

        val left = CompanionLinks.parse(CompanionLinks.remove(raw, "ratatosk://bb"))
        assertEquals(listOf("ratatosk://aa"), left.map { it.inviteUri })
    }

    /** Записи прошлого формата читаются: человек не должен терять сопряжение. */
    @Test
    fun oldRecordsAreStillRead() {
        val old = "Телефон|ratatosk://aa|4242|null|/data/c" +
            ";;Второй|ratatosk://bb|5000|10.0.0.3|null|/data/tor"

        val read = CompanionLinks.parse(old)
        assertEquals(2, read.size)
        assertEquals(4242, read[0].port)
        assertNull(read[0].peerAddr)
        assertEquals("/data/c", read[0].cachePath)
        assertEquals("/data/tor", read[1].torDir)
    }

    @Test
    fun brokenRecordsAreSkippedInsteadOfBreakingTheList() {
        val raw = "мусор;;" + CompanionLinks.upsert("", link(uri = "ratatosk://aa"))
        val read = CompanionLinks.parse(raw)
        assertEquals(listOf("ratatosk://aa"), read.map { it.inviteUri })
        assertTrue(read.single().label.isNotEmpty())
    }
}
