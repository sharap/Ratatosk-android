package chat.ratatosk.android.ui.chatlist

import chat.ratatosk.android.util.toHexString
import org.ratatosk.core.FfiContact
import org.ratatosk.core.FfiGroup
import org.ratatosk.core.FfiMessage

sealed class ChatItem {
    abstract val chatId: ByteArray
    abstract val title: String

    data class Contact(val contact: FfiContact) : ChatItem() {
        override val chatId: ByteArray = contact.chatId
        override val title: String = contact.localName ?: contact.displayName
    }

    data class Group(val group: FfiGroup) : ChatItem() {
        override val chatId: ByteArray = group.chatId
        override val title: String = group.title
    }
}

/**
 * Список чатов: кого показывать и в каком порядке.
 *
 * Группа в списке всегда — в неё позвали, и это само по себе событие.
 * Человек — только если с ним есть переписка или его чат открыт прямо
 * сейчас: иначе список чатов повторял бы список контактов, и найти в нём
 * живую переписку было бы негде.
 *
 * Вынесено из экрана отдельно, чтобы порядок и отбор можно было
 * проверить без Compose.
 */
fun buildChatList(
    contacts: List<FfiContact>,
    groups: List<FfiGroup>,
    messages: Map<String, List<FfiMessage>>,
    activeChatId: ByteArray?,
): List<ChatItem> =
    (contacts.map { ChatItem.Contact(it) } + groups.map { ChatItem.Group(it) })
        .filter { chatItem ->
            when (chatItem) {
                is ChatItem.Group -> true
                is ChatItem.Contact -> {
                    val msgs = messages[chatItem.chatId.toHexString()]
                    !msgs.isNullOrEmpty() || activeChatId?.contentEquals(chatItem.chatId) == true
                }
            }
        }
        // Наверху — где говорили последними. Время берётся у последнего
        // сообщения: у ядра список приходит в порядке беседы.
        .sortedByDescending { chatItem ->
            messages[chatItem.chatId.toHexString()]?.lastOrNull()?.wallMs ?: 0UL
        }
