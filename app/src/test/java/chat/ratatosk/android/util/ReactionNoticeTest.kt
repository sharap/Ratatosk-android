package chat.ratatosk.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.ratatosk.core.FfiMessage
import org.ratatosk.core.FfiReaction
import org.junit.Test

/**
 * Событие ядра одно на постановку и на снятие реакции, поэтому решение
 * «уведомлять или молчать» состоит из трёх отказов. Живьём это без второго
 * устройства не проверить — проверяем здесь.
 */
class ReactionNoticeTest {

    private val me = byteArrayOf(1, 1, 1)
    private val other = byteArrayOf(2, 2, 2)

    private fun message(
        mine: Boolean,
        reactions: List<FfiReaction>
    ) = FfiMessage(
        msgId = byteArrayOf(9),
        body = "привет",
        mine = mine,
        author = null,
        authorIk = null,
        wallMs = 0uL,
        status = null,
        editedAtMs = null,
        forwarded = false,
        reactions = reactions,
        files = emptyList(),
        replyTo = null,
        sharedContact = null,
        inTheChannel = null,
    )

    @Test
    fun `чужая реакция на моё сообщение — уведомляем`() {
        val reaction = FfiReaction(emoji = "👍", authorIk = other, mine = false)
        val found = reactionToAnnounce(message(mine = true, reactions = listOf(reaction)), other)
        assertEquals("👍", found?.emoji)
    }

    @Test
    fun `реакцию сняли — молчим`() {
        // Реакции этого автора в сообщении больше нет.
        val msg = message(mine = true, reactions = emptyList())
        assertNull(reactionToAnnounce(msg, other))
    }

    @Test
    fun `реакция на чужое сообщение — молчим`() {
        val reaction = FfiReaction(emoji = "👍", authorIk = other, mine = false)
        val msg = message(mine = false, reactions = listOf(reaction))
        assertNull(reactionToAnnounce(msg, other))
    }

    @Test
    fun `своя реакция — молчим`() {
        val reaction = FfiReaction(emoji = "👍", authorIk = me, mine = true)
        val msg = message(mine = true, reactions = listOf(reaction))
        assertNull(reactionToAnnounce(msg, me))
    }

    @Test
    fun `берётся реакция именно этого автора`() {
        val msg = message(
            mine = true,
            reactions = listOf(
                FfiReaction(emoji = "👍", authorIk = other, mine = false),
                FfiReaction(emoji = "🔥", authorIk = byteArrayOf(3, 3, 3), mine = false)
            )
        )
        assertEquals("👍", reactionToAnnounce(msg, other)?.emoji)
        assertEquals("🔥", reactionToAnnounce(msg, byteArrayOf(3, 3, 3))?.emoji)
    }

    @Test
    fun `сменил смайлик — уведомляем о новом`() {
        val msg = message(mine = true, reactions = listOf(FfiReaction("🎉", other, false)))
        assertEquals("🎉", reactionToAnnounce(msg, other)?.emoji)
    }
}
