package chat.ratatosk.android.ui.model

import chat.ratatosk.android.R
import chat.ratatosk.android.core.RatatoskCore
import chat.ratatosk.android.util.hexToByteArray
import chat.ratatosk.android.util.toHexString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ratatosk.core.FfiContact
import java.util.concurrent.ConcurrentHashMap

/** Контакты, свои данные и лица — свои и чужие. */
interface ContactsApi {
    val contacts: StateFlow<List<FfiContact>>
    val fingerprint: StateFlow<String?>
    val maxAvatarBytes: StateFlow<Int>
    val myAvatar: StateFlow<ByteArray?>
    /** Лица по `peerIk` (а у групп — по `chatId`) в hex. */
    val contactAvatars: StateFlow<Map<String, ByteArray>>
    val myContactUri: StateFlow<String?>

    fun refreshContacts()
    fun addContact(uri: String, metInPerson: Boolean)
    /** Добавляет того, чья карточка пришла этим сообщением; сверки это не даёт. */
    fun addSharedContact(msgId: ByteArray)
    fun shareContact(chatId: ByteArray, peerIk: ByteArray)
    fun getMyContactUri()
    fun setDisplayName(name: String)
    fun setLocalName(peerIk: ByteArray, name: String?)
    fun deleteContact(peerIk: ByteArray, purgeHistory: Boolean)
    fun getContactByChatId(chatId: ByteArray): FfiContact?
    fun markVerified(peerIk: ByteArray)
    fun revokeVerification(peerIk: ByteArray)
    /** Лицо из кэша; чего нет — просит у ядра и вернёт `null` в этот раз. */
    fun getAvatarOf(peerIk: ByteArray): ByteArray?
    fun setMyAvatar(bytes: ByteArray?)
    fun setAvatar(bytes: ByteArray?)
    fun getGroupAvatar(chatId: ByteArray): ByteArray?
    fun setGroupAvatar(chatId: ByteArray, bytes: ByteArray?)
}

class ContactsModel(
    private val session: SessionContext,
    /** Список приезжает одним ответом: и люди, и группы. */
    private val groups: GroupsModel,
    /** Историю чата держит модель переписки. */
    private val loadMessages: (ByteArray) -> Unit,
    /** Открыть карточку только что добавленного человека. */
    private val openContact: (ByteArray) -> Unit,
) : ContactsApi {
    private val _contacts = MutableStateFlow<List<FfiContact>>(emptyList())
    override val contacts = _contacts.asStateFlow()
    private val _fingerprint = MutableStateFlow<String?>(null)
    override val fingerprint = _fingerprint.asStateFlow()
    private val _maxAvatarBytes = MutableStateFlow<Int>(128 * 1024)
    override val maxAvatarBytes = _maxAvatarBytes.asStateFlow()
    private val _myAvatar = MutableStateFlow<ByteArray?>(null)
    override val myAvatar = _myAvatar.asStateFlow()
    private val _contactAvatars = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    override val contactAvatars = _contactAvatars.asStateFlow()
    private val _myContactUri = MutableStateFlow<String?>(null)
    override val myContactUri = _myContactUri.asStateFlow()

    /**
     * Метки лиц у компаньона: список чатов приезжает часто, а лицо весит
     * до 32 КиБ — спрашиваем только когда метка разошлась с прошлой.
     */
    private val companionAvatarMs = ConcurrentHashMap<String, ULong>()

    fun setContacts(list: List<FfiContact>) {
        _contacts.value = list
    }

    fun updateContacts(transform: (List<FfiContact>) -> List<FfiContact>) {
        _contacts.update(transform)
    }

    fun currentContacts(): List<FfiContact> = _contacts.value

    fun setFingerprint(value: String?) {
        _fingerprint.value = value
    }

    fun setMaxAvatarBytes(value: Int) {
        _maxAvatarBytes.value = value
    }

    /** Своё лицо приехало событием ядра. */
    fun onOwnAvatar(bytes: ByteArray?) {
        _myAvatar.value = bytes
    }

    fun putAvatar(hex: String, bytes: ByteArray) {
        _contactAvatars.update { it + (hex to bytes) }
    }

    fun removeAvatar(hex: String) {
        _contactAvatars.update { it - hex }
    }

    fun avatarStamp(hex: String): ULong? = companionAvatarMs[hex]

    fun setAvatarStamp(hex: String, ms: ULong) {
        companionAvatarMs[hex] = ms
    }

    fun removeAvatarStamp(hex: String) {
        companionAvatarMs.remove(hex)
    }

    /** Сессия закрыта: лица и контакты прошлого аккаунта — не наши. */
    fun reset() {
        _contacts.value = emptyList()
        _contactAvatars.value = emptyMap()
        _myAvatar.value = null
        _fingerprint.value = null
        _myContactUri.value = null
        companionAvatarMs.clear()
    }

    override fun refreshContacts() {
        session.scope.launch(Dispatchers.IO) {
            try {
                if (session.isCompanion) {
                    RatatoskCore.getCompanion().chats()
                } else {
                    val contactList = RatatoskCore.getClient().contacts()
                    val groupList = RatatoskCore.getClient().groups()
                    withContext(Dispatchers.Main) {
                        _contacts.value = contactList
                        groups.setGroups(groupList)
                    }
                    (contactList.map { it.chatId } + groupList.map { it.chatId }).forEach { chatId ->
                        launch(Dispatchers.IO) {
                            loadMessages(chatId)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("RatatoskVM", "Failed to refresh contacts", e)
                session.scope.launch {
                    session._error.value = e.message?.takeIf { it.isNotBlank() }
                        ?: session.string(R.string.error_contacts_refresh_failed)
                }
            }
        }
    }

    override fun addContact(uri: String, metInPerson: Boolean) {
        if (session.isCompanion) return
        session.scope.launch(Dispatchers.IO) {
            try {
                val oldContacts = RatatoskCore.getClient().contacts()
                RatatoskCore.getClient().addContact(uri, metInPerson)
                val contactList = RatatoskCore.getClient().contacts()
                val groupList = RatatoskCore.getClient().groups()

                val addedContact = contactList.find { newC ->
                    oldContacts.none { oldC -> oldC.chatId.contentEquals(newC.chatId) }
                } ?: contactList.find { c ->
                    val ikHex = c.peerIk.toHexString()
                    val chatIdHex = c.chatId.toHexString()
                    val base64Ik = android.util.Base64.encodeToString(c.peerIk, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE).trimEnd('=')
                    val base64ChatId = android.util.Base64.encodeToString(c.chatId, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE).trimEnd('=')
                    uri.contains(ikHex, ignoreCase = true) ||
                            uri.contains(chatIdHex, ignoreCase = true) ||
                            (base64Ik.length > 4 && uri.contains(base64Ik)) ||
                            (base64ChatId.length > 4 && uri.contains(base64ChatId))
                }

                withContext(Dispatchers.Main) {
                    _contacts.value = contactList
                    groups.setGroups(groupList)
                    if (addedContact != null) {
                        openContact(addedContact.chatId)
                    }
                }

                (contactList.map { it.chatId } + groupList.map { it.chatId }).forEach { chatId ->
                    launch(Dispatchers.IO) {
                        loadMessages(chatId)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("RatatoskVM", "Failed to add contact", e)
                withContext(Dispatchers.Main) {
                    session._error.value = e.message?.takeIf { it.isNotBlank() }
                        ?: session.string(R.string.error_add_contact_failed)
                }
            }
        }
    }

    override fun addSharedContact(msgId: ByteArray) {
        session.scope.launch(Dispatchers.IO) {
            try {
                if (session.isCompanion) {
                    if (!session.isCompanionLinked.value) return@launch
                    RatatoskCore.getCompanion().addSharedContact(msgId)
                    refreshContacts()
                } else {
                    RatatoskCore.getClient().addSharedContact(msgId)
                    refreshContacts()
                }
            } catch (e: Exception) {
                android.util.Log.w("RatatoskVM", "Failed to add shared contact", e)
                withContext(Dispatchers.Main) {
                    session._error.value = e.message?.takeIf { it.isNotBlank() }
                        ?: session.string(R.string.error_add_contact_failed)
                }
            }
        }
    }

    override fun shareContact(chatId: ByteArray, peerIk: ByteArray) {
        session.scope.launch(Dispatchers.IO) {
            try {
                if (session.isCompanion) {
                    if (!session.isCompanionLinked.value) {
                        withContext(Dispatchers.Main) {
                            session._error.value = session.string(R.string.connecting_to_phone_cached)
                        }
                        return@launch
                    }
                    val targetContact = _contacts.value.find { it.peerIk.contentEquals(peerIk) || it.chatId.contentEquals(peerIk) }
                    val whoChatId = targetContact?.chatId ?: peerIk
                    RatatoskCore.getCompanion().shareContact(chatId, whoChatId)
                    loadMessages(chatId)
                } else {
                    RatatoskCore.getClient().shareContact(chatId, peerIk)
                    loadMessages(chatId)
                }
            } catch (e: Exception) {
                android.util.Log.w("RatatoskVM", "Failed to share contact", e)
                withContext(Dispatchers.Main) {
                    session._error.value = e.message?.takeIf { it.isNotBlank() }
                        ?: session.string(R.string.error_share_contact_failed)
                }
            }
        }
    }

    override fun getMyContactUri() {
        if (session.isCompanion) {
            _myContactUri.value = null
            return
        }
        session.scope.launch(Dispatchers.IO) {
            try {
                val uri = RatatoskCore.getClient().myContactUri()
                withContext(Dispatchers.Main) {
                    _myContactUri.value = uri
                }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to get my URI", e)
            }
        }
    }

    override fun setDisplayName(name: String) {
        val id = session.activeAccountId.value ?: return
        session.scope.launch {
            session.settings.setDisplayName(id, name)
            android.util.Log.d("RatatoskVM", "Display name updated to: $name. Will be applied to core on next restart.")
        }
    }

    override fun setLocalName(peerIk: ByteArray, name: String?) {
        if (session.isCompanion) return
        session.scope.launch(Dispatchers.IO) {
            try {
                RatatoskCore.getClient().setLocalName(peerIk, name)
                refreshContacts()
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to set local name", e)
            }
        }
    }

    override fun deleteContact(peerIk: ByteArray, purgeHistory: Boolean) {
        if (session.isCompanion) return
        session.scope.launch(Dispatchers.IO) {
            try {
                RatatoskCore.getClient().deleteContact(peerIk, purgeHistory)
                refreshContacts()
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to delete contact", e)
            }
        }
    }

    override fun getContactByChatId(chatId: ByteArray): FfiContact? {
        return _contacts.value.find { it.chatId.contentEquals(chatId) }
    }

    override fun markVerified(peerIk: ByteArray) {
        if (session.isCompanion) return
        session.scope.launch(Dispatchers.IO) {
            try {
                RatatoskCore.getClient().markVerified(peerIk)
                refreshContacts()
            } catch (e: Exception) {
                android.util.Log.w("RatatoskVM", "Failed to mark as verified", e)
                withContext(Dispatchers.Main) {
                    session._error.value = e.message?.takeIf { it.isNotBlank() }
                        ?: session.string(R.string.error_verification_change_failed)
                }
            }
        }
    }

    override fun revokeVerification(peerIk: ByteArray) {
        if (session.isCompanion) return
        session.scope.launch(Dispatchers.IO) {
            try {
                RatatoskCore.getClient().revokeVerification(peerIk)
                refreshContacts()
            } catch (e: Exception) {
                android.util.Log.w("RatatoskVM", "Failed to revoke verification", e)
                withContext(Dispatchers.Main) {
                    session._error.value = e.message?.takeIf { it.isNotBlank() }
                        ?: session.string(R.string.error_verification_change_failed)
                }
            }
        }
    }

    override fun getAvatarOf(peerIk: ByteArray): ByteArray? {
        val hex = peerIk.toHexString()
        _contactAvatars.value[hex]?.let { return it }

        session.scope.launch(Dispatchers.IO) {
            try {
                if (session.isCompanion) {
                    RatatoskCore.getCompanion().avatar(peerIk)
                } else {
                    val bytes = RatatoskCore.getClient().avatarOf(peerIk)
                    withContext(Dispatchers.Main) {
                        if (bytes != null) {
                            _contactAvatars.update { it + (hex to bytes) }
                        }
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w("RatatoskVM", "Failed to get avatar for ${peerIk.toHexString()}: ${t.message}")
            }
        }
        return null
    }

    override fun setMyAvatar(bytes: ByteArray?) {
        session.scope.launch(Dispatchers.IO) {
            try {
                if (session.isCompanion) {
                    RatatoskCore.getCompanion().setAvatar(bytes)
                } else {
                    RatatoskCore.getClient().setAvatar(bytes)
                }
                _myAvatar.value = bytes
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to set avatar", e)
            }
        }
    }

    override fun setAvatar(bytes: ByteArray?) {
        setMyAvatar(bytes)
    }

    override fun getGroupAvatar(chatId: ByteArray): ByteArray? {
        val hex = chatId.toHexString()
        _contactAvatars.value[hex]?.let { return it }

        session.scope.launch(Dispatchers.IO) {
            try {
                if (session.isCompanion) {
                    RatatoskCore.getCompanion().avatar(chatId)
                } else {
                    val bytes = RatatoskCore.getClient().groupAvatar(chatId)
                    withContext(Dispatchers.Main) {
                        if (bytes != null) {
                            _contactAvatars.update { it + (hex to bytes) }
                        }
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w("RatatoskVM", "Failed to get group avatar for ${chatId.toHexString()}: ${t.message}")
            }
        }
        return null
    }

    override fun setGroupAvatar(chatId: ByteArray, bytes: ByteArray?) {
        val hex = chatId.toHexString()
        session.scope.launch(Dispatchers.IO) {
            try {
                if (session.isCompanion) {
                    RatatoskCore.getCompanion().setGroupAvatar(chatId, bytes)
                } else {
                    RatatoskCore.getClient().setGroupAvatar(chatId, bytes)
                }
                withContext(Dispatchers.Main) {
                    if (bytes != null) {
                        _contactAvatars.update { it + (hex to bytes) }
                    } else {
                        _contactAvatars.update { it - hex }
                    }
                    if (!session.isCompanion) {
                        refreshContacts()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to set group avatar", e)
            }
        }
    }
}
