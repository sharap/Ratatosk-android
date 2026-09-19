package chat.ratatosk.android.ui.model

import chat.ratatosk.android.R
import chat.ratatosk.android.core.RatatoskCore
import chat.ratatosk.android.util.toHexString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ratatosk.core.FfiGroup

/**
 * Группы: список, распоряжение составом и тексты ядра к каждому действию.
 *
 * Предупреждения (§11.4–11.5) показываются **до** нажатия: телефон и ядро
 * за этим не следят и следить не могут.
 */
interface GroupsApi {
    val groups: StateFlow<List<FfiGroup>>
    fun createGroup(title: String)
    fun renameGroup(chatId: ByteArray, title: String)
    fun inviteToGroup(chatId: ByteArray, peerIk: ByteArray)
    fun evictFromGroup(chatId: ByteArray, memberChatId: ByteArray)
    fun leaveGroup(chatId: ByteArray)
    /** У компаньона состав приходит отдельной просьбой. */
    fun loadCompanionMembers(chatId: ByteArray)
    fun getGroupJoinNotice(): String
    fun getEvictionNotice(): String
    fun getLeaveNotice(): String
    fun getOwnerLeaveNotice(): String
    fun getMaxGroupTitleChars(): UInt
}

class GroupsModel(
    private val session: SessionContext,
    /** Список контактов и групп перечитывает тот, кто им владеет. */
    private val onContactsChanged: () -> Unit,
) : GroupsApi {
    private val _groups = MutableStateFlow<List<FfiGroup>>(emptyList())
    override val groups: StateFlow<List<FfiGroup>> = _groups.asStateFlow()

    /** Список целиком — его перечитывают и ядро, и телефон. */
    fun setGroups(list: List<FfiGroup>) {
        _groups.value = list
    }

    /** Точечная правка: состав одной группы, название, признак участия. */
    fun updateGroups(transform: (List<FfiGroup>) -> List<FfiGroup>) {
        _groups.update(transform)
    }

    fun currentGroups(): List<FfiGroup> = _groups.value

    fun reset() {
        _groups.value = emptyList()
    }

    override fun createGroup(title: String) {
        session.scope.launch(Dispatchers.IO) {
            try {
                if (session.isCompanion) {
                    RatatoskCore.getCompanion().createGroup(title)
                } else {
                    RatatoskCore.getClient().createGroup(title)
                }
            } catch (e: Exception) {
                android.util.Log.w("RatatoskVM", "Failed to create group", e)
                withContext(Dispatchers.Main) {
                    session._error.value = e.message?.takeIf { it.isNotBlank() }
                        ?: session.string(R.string.error_group_create_failed)
                }
            }
        }
    }

    override fun renameGroup(chatId: ByteArray, title: String) {
        session.scope.launch(Dispatchers.IO) {
            try {
                if (session.isCompanion) {
                    RatatoskCore.getCompanion().renameGroup(chatId, title)
                } else {
                    RatatoskCore.getClient().renameGroup(chatId, title)
                    onContactsChanged()
                }
            } catch (e: Exception) {
                android.util.Log.w("RatatoskVM", "Failed to rename group", e)
                withContext(Dispatchers.Main) {
                    session._error.value = e.message?.takeIf { it.isNotBlank() }
                        ?: session.string(R.string.error_group_rename_failed)
                }
            }
        }
    }

    override fun inviteToGroup(chatId: ByteArray, peerIk: ByteArray) {
        session.scope.launch(Dispatchers.IO) {
            try {
                if (session.isCompanion) {
                    RatatoskCore.getCompanion().inviteToGroup(chatId, peerIk)
                    RatatoskCore.getCompanion().members(chatId)
                } else {
                    RatatoskCore.getClient().inviteToGroup(chatId, peerIk)
                }
            } catch (e: Exception) {
                android.util.Log.w("RatatoskVM", "Failed to invite to group", e)
                withContext(Dispatchers.Main) {
                    session._error.value = e.message?.takeIf { it.isNotBlank() }
                        ?: session.string(R.string.error_group_invite_failed)
                }
            }
        }
    }

    override fun evictFromGroup(chatId: ByteArray, peerIk: ByteArray) {
        session.scope.launch(Dispatchers.IO) {
            try {
                if (session.isCompanion) {
                    RatatoskCore.getCompanion().evictFromGroup(chatId, peerIk)
                    RatatoskCore.getCompanion().members(chatId)
                } else {
                    RatatoskCore.getClient().evictFromGroup(chatId, peerIk)
                }
            } catch (e: Exception) {
                android.util.Log.w("RatatoskVM", "Failed to evict from group", e)
                withContext(Dispatchers.Main) {
                    session._error.value = e.message?.takeIf { it.isNotBlank() }
                        ?: session.string(R.string.error_group_evict_failed)
                }
            }
        }
    }

    override fun leaveGroup(chatId: ByteArray) {
        session.scope.launch(Dispatchers.IO) {
            try {
                if (session.isCompanion) {
                    RatatoskCore.getCompanion().leaveGroup(chatId)
                } else {
                    RatatoskCore.getClient().leaveGroup(chatId)
                    onContactsChanged()
                }
            } catch (e: Exception) {
                android.util.Log.w("RatatoskVM", "Failed to leave group", e)
                withContext(Dispatchers.Main) {
                    session._error.value = e.message?.takeIf { it.isNotBlank() }
                        ?: session.string(R.string.error_group_leave_failed)
                }
            }
        }
    }

    override fun loadCompanionMembers(chatId: ByteArray) {
        if (session.isCompanion) {
            session.scope.launch(Dispatchers.IO) {
                try {
                    RatatoskCore.getCompanion().members(chatId)
                } catch (e: Exception) {
                    android.util.Log.e("RatatoskVM", "Failed to load companion members", e)
                }
            }
        }
    }

    override fun getGroupJoinNotice(): String {
        return try {
            org.ratatosk.core.groupJoinNotice()
        } catch (e: Exception) {
            ""
        }
    }

    override fun getEvictionNotice(): String {
        return try {
            org.ratatosk.core.evictionNotice()
        } catch (e: Exception) {
            ""
        }
    }

    override fun getLeaveNotice(): String {
        return try {
            org.ratatosk.core.leaveNotice()
        } catch (e: Exception) {
            ""
        }
    }

    override fun getOwnerLeaveNotice(): String {
        return try {
            org.ratatosk.core.ownerLeaveNotice()
        } catch (e: Exception) {
            ""
        }
    }

    override fun getMaxGroupTitleChars(): UInt {
        return try {
            org.ratatosk.core.maxGroupTitleChars()
        } catch (e: Exception) {
            255u
        }
    }
}
