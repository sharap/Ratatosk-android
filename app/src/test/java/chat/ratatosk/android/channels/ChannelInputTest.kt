package chat.ratatosk.android.channels

import chat.ratatosk.android.ui.model.ChannelInput
import chat.ratatosk.android.ui.model.channelInput
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ratatosk.core.FfiChannel
import org.ratatosk.core.FfiChannelRights
import org.ratatosk.core.FfiGroup

/**
 * Почему в канале закрыто поле ввода.
 *
 * Причины разные, и слова к ним разные: «развозить некому» — про доставку
 * (§3.2: состав канала у владельца, значит держателю права писать некому
 * развозить), «нет права» — про §6.2, «ждём впуска» — про §10.4. Свалив их
 * в одно «нельзя», клиент соврал бы всем троим.
 */
class ChannelInputTest {
    private fun channel(
        write: Boolean = false,
        awaiting: Boolean = false,
        readable: Boolean = true,
        open: Boolean? = true,
    ) = FfiChannel(
        version = 1UL,
        open = open,
        ownerIk = ByteArray(32) { 1 },
        rights = FfiChannelRights(write = write, admit = false, evict = false, edit = false),
        rightsUntilMs = 0UL,
        powBits = 0u,
        awaiting = awaiting,
        readable = readable,
        generation = 1UL,
        mayRotate = false,
        ownerQuietMs = null,
        ownerUnseen = false,
        grantsExpiring = 0u,
        sourcesNow = 1u,
        seedsKnown = 1u,
        awaitingBlocks = 0u,
        rotationOverdue = false,
        waiting = null,
        signal = org.ratatosk.core.FfiChannelSignal.FINE,
        // Глубина истории (§5.4) в ядре только появилась; поле входа
        // не касается — входом правят права, а не архив.
        historyAll = true,
    )

    private fun group(mine: Boolean = false, channel: FfiChannel? = null) = FfiGroup(
        chatId = ByteArray(16) { 2 },
        title = "Канал",
        createdMs = 0UL,
        members = emptyList(),
        mine = mine,
        joined = true,
        avatarMs = 0UL,
        freeSlots = 0u,
        channel = channel,
    )

    @Test
    fun anOrdinaryGroupIsNotGated() {
        assertEquals(ChannelInput.ALLOWED, channelInput(group()))
        assertEquals(ChannelInput.ALLOWED, channelInput(null))
    }

    @Test
    fun theOwnerWrites() {
        assertEquals(ChannelInput.ALLOWED, channelInput(group(mine = true, channel = channel())))
    }

    /**
     * Держатель права пишет наравне с владельцем.
     *
     * Так было не всегда: доставки у делегата не было — состав канала
     * §3.2 оставляет владельцу, и развозить ему было некому. Теперь
     * своё слово уезжает владельцу и своим сидам, а дальше расходится
     * роем, и гасить поле по составу больше не за чем.
     */
    @Test
    fun aGranteeWritesToo() {
        assertEquals(
            ChannelInput.ALLOWED,
            channelInput(group(mine = false, channel = channel(write = true))),
        )
    }

    @Test
    fun aReaderIsToldAboutRights() {
        assertEquals(
            ChannelInput.NO_RIGHT,
            channelInput(group(mine = false, channel = channel(write = false))),
        )
    }

    /** Ожидание впуска важнее прочего: пока не впустили, разговора нет. */
    @Test
    fun awaitingComesFirst() {
        assertEquals(
            ChannelInput.AWAITING,
            channelInput(group(mine = false, channel = channel(write = true, awaiting = true))),
        )
    }

    @Test
    fun withoutAKeyThereIsNothingToReadWith() {
        assertEquals(
            ChannelInput.NOT_READABLE,
            channelInput(group(mine = false, channel = channel(readable = false))),
        )
    }
}
