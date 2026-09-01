package chat.ratatosk.android.ui.chatlist

import org.ratatosk.core.FfiContact
import org.ratatosk.core.FfiGroup

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
