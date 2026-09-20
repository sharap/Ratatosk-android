package chat.ratatosk.android.chatlist

import chat.ratatosk.android.ui.chatlist.ChatItem
import chat.ratatosk.android.ui.chatlist.buildChatList
import chat.ratatosk.android.util.toHexString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ratatosk.core.FfiAnomalies
import org.ratatosk.core.FfiContact
import org.ratatosk.core.FfiGroup
import org.ratatosk.core.FfiMessage
import org.ratatosk.core.FfiReachability

/** Кого показывает список чатов и в каком порядке. */
class ChatListTest {
    private fun contact(id: Int, name: String) = FfiContact(
        peerIk = ByteArray(32) { id.toByte() },
        chatId = ByteArray(16) { id.toByte() },
        fingerprint = "",
        displayName = name,
        localName = null,
        verified = false,
        seenOnLan = false,
        seenOnBt = false,
        hasAvatar = false,
        onion = null,
        chatmail = null,
        ygg = null,
        nostrRelays = emptyList(),
        cardVersion = 0UL,
        addedMs = 0UL,
        reachability = FfiReachability(emptyList(), null, null),
        directChannel = null,
        anomalies = FfiAnomalies(0UL, 0UL, 0UL, 0UL, 0UL),
    )

    private fun group(id: Int, title: String, joined: Boolean = true) = FfiGroup(
        chatId = ByteArray(16) { id.toByte() },
        title = title,
        createdMs = 0UL,
        members = emptyList(),
        mine = false,
        joined = joined,
        avatarMs = 0UL,
        freeSlots = 8u,
        channel = null,
    )

    private fun message(wallMs: ULong) = FfiMessage(
        msgId = byteArrayOf(1),
        body = "x",
        mine = false,
        author = null,
        authorIk = null,
        wallMs = wallMs,
        status = null,
        editedAtMs = null,
        forwarded = false,
        reactions = emptyList(),
        files = emptyList(),
        replyTo = null,
        sharedContact = null,
    )

    private fun messages(vararg pairs: Pair<ByteArray, ULong>) =
        pairs.associate { (chatId, ms) -> chatId.toHexString() to listOf(message(ms)) }

    /** Молчаливый контакт — это ещё не чат; открытый чат виден и пустым. */
    @Test
    fun silentContactsAreHiddenUntilOpened() {
        val quiet = contact(1, "Тихий")
        val talkative = contact(2, "Говорун")
        val msgs = messages(talkative.chatId to 100UL)

        val list = buildChatList(listOf(quiet, talkative), emptyList(), msgs, activeChatId = null)
        assertEquals(listOf("Говорун"), list.map { it.title })

        val opened = buildChatList(listOf(quiet, talkative), emptyList(), msgs, activeChatId = quiet.chatId)
        assertEquals(setOf("Тихий", "Говорун"), opened.map { it.title }.toSet())
    }

    /** Группа в списке всегда: в неё позвали, и это само по себе событие. */
    @Test
    fun groupsShowEvenEmptyAndLeft() {
        val left = group(3, "Покинутая", joined = false)
        val list = buildChatList(emptyList(), listOf(left), emptyMap(), activeChatId = null)
        assertEquals(listOf("Покинутая"), list.map { it.title })
        assertTrue(list.single() is ChatItem.Group)
    }

    @Test
    fun newestTalkComesFirst() {
        val a = contact(1, "Аня")
        val b = contact(2, "Боря")
        val g = group(3, "Группа")

        val list = buildChatList(
            listOf(a, b),
            listOf(g),
            messages(a.chatId to 100UL, b.chatId to 300UL, g.chatId to 200UL),
            activeChatId = null,
        )
        assertEquals(listOf("Боря", "Группа", "Аня"), list.map { it.title })
    }

    /** Своё имя для человека важнее того, как он назвался сам. */
    @Test
    fun localNameWins() {
        val renamed = contact(1, "Как назвался").copy(localName = "Как записан")
        val list = buildChatList(
            listOf(renamed),
            emptyList(),
            messages(renamed.chatId to 1UL),
            activeChatId = null,
        )
        assertEquals(listOf("Как записан"), list.map { it.title })
    }

    /** Чат без сообщений стоит внизу, а не наверху из-за пустого времени. */
    @Test
    fun emptyGroupDoesNotJumpAboveALiveChat() {
        val a = contact(1, "Аня")
        val silent = group(3, "Пустая")
        val list = buildChatList(
            listOf(a),
            listOf(silent),
            messages(a.chatId to 50UL),
            activeChatId = null,
        )
        assertEquals(listOf("Аня", "Пустая"), list.map { it.title })
    }
}
