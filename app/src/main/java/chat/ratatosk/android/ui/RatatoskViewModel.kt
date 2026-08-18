package chat.ratatosk.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import chat.ratatosk.android.R
import chat.ratatosk.android.core.RatatoskCore
import chat.ratatosk.android.data.SettingsRepository
import chat.ratatosk.android.ui.theme.ChatThemeData
import chat.ratatosk.android.util.hexToByteArray
import chat.ratatosk.android.util.toHexString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import uniffi.ratatosk_ffi.*

class RatatoskViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsRepository = SettingsRepository(application)
    
    private val _events = MutableStateFlow<List<FfiEvent>>(emptyList())
    val events = _events.asStateFlow()

    private val _contacts = MutableStateFlow<List<FfiContact>>(emptyList())
    val contacts = _contacts.asStateFlow()

    private val _messages = MutableStateFlow<Map<String, List<FfiMessage>>>(emptyMap())
    val messages = _messages.asStateFlow()

    private val _messageStatuses = MutableStateFlow<Map<String, FfiDeliveryStatus>>(emptyMap())
    val messageStatuses = _messageStatuses.asStateFlow()

    private val _repliedMessages = MutableStateFlow<Map<String, FfiMessage?>>(emptyMap())
    val repliedMessages = _repliedMessages.asStateFlow()

    private val _unreadCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unreadCounts = _unreadCounts.asStateFlow()

    val totalUnreadCount: StateFlow<Int> = _unreadCounts
        .map { it.values.sum() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private var activeChatId: String? = null

    private val _honestNotices = MutableStateFlow<List<String>>(emptyList())
    val honestNotices = _honestNotices.asStateFlow()
    
    private val _isInitialized = MutableStateFlow<Boolean>(RatatoskCore.isInitialized())
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _accountExists = MutableStateFlow<Boolean>(RatatoskCore.accountExists(application))
    val accountExists: StateFlow<Boolean> = _accountExists.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _fingerprint = MutableStateFlow<String?>(null)
    val fingerprint = _fingerprint.asStateFlow()

    private val _maxAvatarBytes = MutableStateFlow<Int>(128 * 1024) // Default fallback
    val maxAvatarBytes = _maxAvatarBytes.asStateFlow()

    private val _userName = MutableStateFlow<String?>(null)
    val userName = _userName.asStateFlow()

    private val _myAvatar = MutableStateFlow<ByteArray?>(null)
    val myAvatar = _myAvatar.asStateFlow()

    private val _contactAvatars = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val contactAvatars = _contactAvatars.asStateFlow()

    private val _pendingAvatarUri = MutableStateFlow<android.net.Uri?>(null)
    val pendingAvatarUri = _pendingAvatarUri.asStateFlow()

    val chatTheme = settingsRepository.chatTheme.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ChatThemeData()
    )

    val lanEnabled = settingsRepository.lanEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val notificationsShowName = settingsRepository.notificationsShowName.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = true
    )

    val notificationsShowText = settingsRepository.notificationsShowText.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = true
    )

    init {
        // Monitor core initialization status (e.g. if service initialized it)
        viewModelScope.launch {
            while (true) {
                if (!_isInitialized.value && RatatoskCore.isInitialized()) {
                    android.util.Log.i("RatatoskVM", "Core initialized externally, setting up engine")
                    _isInitialized.value = true
                    setupEngine()
                }
                kotlinx.coroutines.delay(1000)
            }
        }

        val noticesResult = RatatoskCore.safeCall { honestNotices() }
        noticesResult.onSuccess {
            _honestNotices.value = it
        }.onFailure {
            _error.value = getApplication<Application>().getString(R.string.error_core_unavailable, it.localizedMessage)
        }

        if (RatatoskCore.isInitialized()) {
            setupEngine()
        }
        
        // Periodically refresh contacts to update online status (seenOnLan)
        viewModelScope.launch {
            while (true) {
                if (RatatoskCore.isInitialized()) {
                    refreshContacts()
                }
                kotlinx.coroutines.delay(30000) // Every 30 seconds
            }
        }
    }

    private var eventsJob: kotlinx.coroutines.Job? = null

    private fun setupEngine() {
        android.util.Log.d("RatatoskVM", "Setting up engine components...")
        try {
            val client = RatatoskCore.getClient()
            _fingerprint.value = client.fingerprint()
            android.util.Log.d("RatatoskVM", "Identity loaded: ${_fingerprint.value}")
            
            _maxAvatarBytes.value = maxAvatarBytes().toInt()

            viewModelScope.launch {
                _userName.value = settingsRepository.displayName.first()
            }

            // Load own avatar
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    _myAvatar.value = client.myAvatar()
                } catch (e: Exception) {
                    android.util.Log.e("RatatoskVM", "Failed to load my avatar", e)
                }
            }

            refreshContacts()
            
            // Collect events from core (only once)
            if (eventsJob == null) {
                android.util.Log.d("RatatoskVM", "Starting event collection job")
                eventsJob = RatatoskCore.events
                    .onEach { handleEvent(it) }
                    .launchIn(viewModelScope)
            }
            
            // Initial load of messages for all contacts to show in list
            viewModelScope.launch {
                try {
                    val currentContacts = client.contacts()
                    android.util.Log.d("RatatoskVM", "Preloading messages for ${currentContacts.size} contacts")
                    currentContacts.forEach { contact ->
                        loadMessages(contact.chatId)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("RatatoskVM", "Failed to preload messages", e)
                }
            }
            
            // Sync LAN preference to core whenever it changes
            lanEnabled
                .onEach { enabled ->
                    try {
                        android.util.Log.d("RatatoskVM", "Syncing LAN enabled=$enabled to core")
                        client.setLanEnabled(enabled)
                    } catch (e: Exception) {
                        android.util.Log.e("RatatoskVM", "Failed to sync LAN state", e)
                    }
                }
                .launchIn(viewModelScope)
        } catch (e: Exception) {
            android.util.Log.e("RatatoskVM", "Critical failure during setupEngine", e)
            _error.value = "Failed to load identity: ${e.message}"
        }
    }

    fun refreshContacts() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val list = RatatoskCore.getClient().contacts()
                _contacts.value = list
            } catch (e: Exception) {
                viewModelScope.launch {
                    _error.value = "Failed to refresh contacts: ${e.message}"
                }
            }
        }
    }

    fun loadMessages(chatId: ByteArray, limit: Int? = null) {
        val chatIdHex = chatId.toHexString()
        val currentSize = _messages.value[chatIdHex]?.size ?: 0
        
        if (limit != null && limit <= currentSize) {
            android.util.Log.d("RatatoskVM", "loadMessages: already have $currentSize messages, requested $limit. Skipping redundant load.")
            return
        }

        val targetLimit = limit ?: if (currentSize > 0) currentSize else 100
        android.util.Log.d("RatatoskVM", "loadMessages for $chatIdHex, targetLimit: $targetLimit (current size: $currentSize)")
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!RatatoskCore.isInitialized()) {
                    android.util.Log.e("RatatoskVM", "loadMessages failed: Core not initialized")
                    return@launch
                }
                val msgs = RatatoskCore.getClient().messages(chatId, maxOf(targetLimit, 1).toUInt())
                android.util.Log.d("RatatoskVM", "Core returned ${msgs.size} messages for $chatIdHex (requested: $targetLimit)")
                
                _messages.update { it + (chatIdHex to msgs) }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to load messages for $chatIdHex", e)
                viewModelScope.launch {
                    _error.value = "Failed to load messages: ${e.message}"
                }
            }
        }
    }

    fun initialize(pin: String?, displayName: String) {
        viewModelScope.launch {
            try {
                settingsRepository.setDisplayName(displayName)
                RatatoskCore.initialize(getApplication(), pin, displayName)
                setupEngine()
                
                val noticesResult = RatatoskCore.safeCall { honestNotices() }
                _honestNotices.value = noticesResult.getOrDefault(emptyList())
                
                _isInitialized.value = true
                _error.value = null
            } catch (e: RatatoskException) {
                _error.value = when (e) {
                    is RatatoskException.Locked -> getApplication<Application>().getString(R.string.error_db_locked)
                    is RatatoskException.Internal -> getApplication<Application>().getString(R.string.error_internal, e.reason)
                }
            } catch (e: Throwable) {
                _error.value = getApplication<Application>().getString(R.string.error_init_failed, e.message)
            }
        }
    }

    fun unlock(pin: String?) {
        viewModelScope.launch {
            try {
                val savedName = settingsRepository.displayName.first() ?: ""
                RatatoskCore.initialize(getApplication(), pin, savedName)
                setupEngine()
                
                _isInitialized.value = true
                _error.value = null
            } catch (e: RatatoskException) {
                _error.value = when (e) {
                    is RatatoskException.Locked -> getApplication<Application>().getString(R.string.error_db_locked)
                    is RatatoskException.Internal -> getApplication<Application>().getString(R.string.error_internal, e.reason)
                }
            } catch (e: Throwable) {
                _error.value = "Unlock failed: ${e.message}"
            }
        }
    }

    private fun handleEvent(event: FfiEvent) {
        android.util.Log.d("RatatoskVM", "Handling event: $event")
        // UI events are handled on the main thread for StateFlow updates
        _events.update { (it + event).takeLast(100) } // Keep history reasonable
        when (event) {
            is FfiEvent.MessageReceived -> {
                val hexId = event.chatId.toHexString()
                android.util.Log.i("RatatoskVM", "New message received for chat: $hexId")
                loadMessages(event.chatId)
                
                if (activeChatId != hexId) {
                    _unreadCounts.update { current -> 
                        val newCount = (current[hexId] ?: 0) + 1
                        current + (hexId to newCount)
                    }
                }
                
                refreshContacts()
            }
            is FfiEvent.StatusChanged -> {
                val msgIdHex = event.msgId.toHexString()
                android.util.Log.d("RatatoskVM", "Message $msgIdHex status changed to ${event.status}")
                _messageStatuses.update { it + (msgIdHex to event.status) }
            }
            is FfiEvent.ContactAdded -> {
                android.util.Log.i("RatatoskVM", "Contact added: ${event.fingerprint}")
                refreshContacts()
            }
            is FfiEvent.GroupMembershipChanged -> {
                android.util.Log.i("RatatoskVM", "Group membership changed for: ${event.chatId.toHexString()}")
                refreshContacts()
            }
            is FfiEvent.MessagesDeleted -> {
                val hexId = event.chatId.toHexString()
                android.util.Log.i("RatatoskVM", "Messages deleted in chat: $hexId. IDs: ${event.msgIds.size}")
                loadMessages(event.chatId)
            }
            is FfiEvent.MessageEdited -> {
                val hexId = event.chatId.toHexString()
                android.util.Log.i("RatatoskVM", "Message edited in chat: $hexId. Msg: ${event.msgId.toHexString()}")
                loadMessages(event.chatId)
            }
            is FfiEvent.ReactionChanged -> {
                val hexId = event.chatId.toHexString()
                android.util.Log.i("RatatoskVM", "Reaction changed in chat: $hexId. Msg: ${event.msgId.toHexString()}")
                loadMessages(event.chatId)
            }
            is FfiEvent.AvatarChanged -> {
                val ikHex = event.peerIk.toHexString()
                android.util.Log.i("RatatoskVM", "Avatar changed for contact: $ikHex")
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val bytes = RatatoskCore.getClient().avatarOf(event.peerIk)
                        if (bytes != null) {
                            _contactAvatars.update { it + (ikHex to bytes) }
                        } else {
                            _contactAvatars.update { it - ikHex }
                        }
                        refreshContacts() // Update hasAvatar flag
                    } catch (e: Exception) {
                        android.util.Log.e("RatatoskVM", "Failed to fetch avatar for $ikHex", e)
                    }
                }
            }
            else -> {
                android.util.Log.d("RatatoskVM", "Ignored or unhandled event: $event")
            }
        }
    }
    
    fun setActiveChat(chatId: ByteArray?) {
        val hexId = chatId?.toHexString()
        activeChatId = hexId
        if (hexId != null) {
            _unreadCounts.update { it + (hexId to 0) }
            
            // Mark last message as read if it's from the peer
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    _messages.value[hexId]?.lastOrNull { !it.mine }?.let { lastPeerMsg ->
                        markRead(chatId, lastPeerMsg.msgId)
                    }
                    // Also notify core about network just to be sure we are reachable
                    if (RatatoskCore.isInitialized()) {
                        RatatoskCore.getClient().networkChanged()
                    }
                } catch (e: Exception) { /* ignore */ }
            }
        }
    }

    fun markRead(chatId: ByteArray, msgId: ByteArray) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                if (RatatoskCore.isInitialized()) {
                    RatatoskCore.getClient().markRead(chatId, msgId)
                }
            } catch (e: Exception) {
                // Ignore errors for read receipts as per spec recommendation
            }
        }
    }
    
    fun sendText(chatId: ByteArray, text: String) {
        val chatIdHex = chatId.toHexString()
        android.util.Log.d("RatatoskVM", "Attempting to send message to $chatIdHex. Text length: ${text.length}")
        
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                if (!RatatoskCore.isInitialized()) {
                    android.util.Log.e("RatatoskVM", "Cannot send: Core NOT initialized")
                    viewModelScope.launch { _error.value = "Core not initialized" }
                    return@launch
                }
                
                RatatoskCore.getClient().sendText(chatId, text)
                android.util.Log.d("RatatoskVM", "sendText call returned successfully")
                
                // Reload messages for this chat immediately to show user's own message
                loadMessages(chatId)
            } catch (e: Exception) {
                val errorMsg = "Failed to send to $chatIdHex: ${e.message}"
                android.util.Log.e("RatatoskVM", errorMsg, e)
                viewModelScope.launch {
                    _error.value = errorMsg
                }
            }
        }
    }

    fun resendMessage(chatId: ByteArray, body: String) {
        // Since core doesn't have a direct resend by ID yet, we just send a new message with same body
        sendText(chatId, body)
    }
    
    fun setLanEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                settingsRepository.setLanEnabled(enabled)
                RatatoskCore.getClient().setLanEnabled(enabled)
            } catch (e: Exception) {
                _error.value = "Failed to toggle LAN: ${e.message}"
            }
        }
    }

    fun addContact(uri: String, metInPerson: Boolean) {
        viewModelScope.launch {
            try {
                RatatoskCore.getClient().addContact(uri, metInPerson)
            } catch (e: Exception) {
                _error.value = "Failed to add contact: ${e.message}"
            }
        }
    }

    fun getMyContactUri(): String? {
        return try {
            RatatoskCore.getClient().myContactUri()
        } catch (e: Exception) {
            _error.value = "Failed to get my URI: ${e.message}"
            null
        }
    }

    fun setDisplayName(name: String) {
        viewModelScope.launch {
            settingsRepository.setDisplayName(name)
            _userName.value = name
            android.util.Log.d("RatatoskVM", "Display name updated to: $name. Will be applied to core on next restart.")
        }
    }

    fun getContactByChatId(chatId: ByteArray): FfiContact? {
        val hexId = chatId.toHexString()
        return _contacts.value.find { it.chatId.toHexString() == hexId }
    }

    fun markVerified(peerIk: ByteArray) {
        viewModelScope.launch {
            try {
                RatatoskCore.getClient().markVerified(peerIk)
                refreshContacts()
            } catch (e: Exception) {
                _error.value = "Failed to mark as verified: ${e.message}"
            }
        }
    }

    fun revokeVerification(peerIk: ByteArray) {
        viewModelScope.launch {
            try {
                RatatoskCore.getClient().revokeVerification(peerIk)
                refreshContacts()
            } catch (e: Exception) {
                _error.value = "Failed to revoke verification: ${e.message}"
            }
        }
    }

    fun setLocalName(peerIk: ByteArray, name: String?) {
        viewModelScope.launch {
            try {
                RatatoskCore.getClient().setLocalName(peerIk, name)
                refreshContacts()
            } catch (e: Exception) {
                _error.value = "Failed to set local name: ${e.message}"
            }
        }
    }

    fun deleteContact(peerIk: ByteArray, purgeHistory: Boolean) {
        viewModelScope.launch {
            try {
                RatatoskCore.getClient().deleteContact(peerIk, purgeHistory)
                refreshContacts()
            } catch (e: Exception) {
                _error.value = "Failed to delete contact: ${e.message}"
            }
        }
    }

    fun deleteMessages(chatId: ByteArray, msgIds: List<ByteArray>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                RatatoskCore.getClient().deleteMessages(chatId, msgIds)
                loadMessages(chatId)
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to delete messages", e)
            }
        }
    }

    fun retractMessages(chatId: ByteArray, msgIds: List<ByteArray>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                RatatoskCore.getClient().retractMessages(chatId, msgIds)
                loadMessages(chatId)
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to retract messages", e)
            }
        }
    }

    fun clearChat(chatId: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                RatatoskCore.getClient().clearChat(chatId)
                loadMessages(chatId)
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to clear chat", e)
            }
        }
    }

    fun editMessage(chatId: ByteArray, msgId: ByteArray, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                RatatoskCore.getClient().editMessage(chatId, msgId, text)
                loadMessages(chatId)
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to edit message", e)
            }
        }
    }

    fun setReaction(chatId: ByteArray, msgId: ByteArray, emoji: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                RatatoskCore.getClient().setReaction(chatId, msgId, emoji)
                loadMessages(chatId)
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to set reaction", e)
            }
        }
    }

    fun forwardMessages(chatId: ByteArray, msgIds: List<ByteArray>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                RatatoskCore.getClient().forwardMessages(chatId, msgIds)
                loadMessages(chatId)
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to forward messages", e)
            }
        }
    }

    fun reply(chatId: ByteArray, replyTo: ByteArray, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                RatatoskCore.getClient().reply(chatId, replyTo, text)
                loadMessages(chatId)
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to reply", e)
            }
        }
    }

    fun getMessage(msgId: ByteArray): FfiMessage? {
        val hexId = msgId.toHexString()
        _repliedMessages.value[hexId]?.let { return it }
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val msg = RatatoskCore.getClient().message(msgId)
                _repliedMessages.update { it + (hexId to msg) }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to fetch single message $hexId", e)
            }
        }
        return null
    }

    fun getRetractionNotice(): String {
        return try {
            retractionNotice()
        } catch (e: Exception) {
            "Are you sure you want to retract selected messages?"
        }
    }

    fun updateChatTheme(update: (ChatThemeData) -> ChatThemeData) {
        viewModelScope.launch {
            val newData = update(chatTheme.value)
            settingsRepository.updateChatTheme(newData)
        }
    }

    fun setNotificationsShowName(show: Boolean) {
        viewModelScope.launch {
            settingsRepository.setNotificationsShowName(show)
        }
    }

    fun setNotificationsShowText(show: Boolean) {
        viewModelScope.launch {
            settingsRepository.setNotificationsShowText(show)
        }
    }

    fun setPendingAvatarUri(uri: android.net.Uri?) {
        _pendingAvatarUri.value = uri
    }

    fun setAvatar(bytes: ByteArray?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                RatatoskCore.getClient().setAvatar(bytes)
                _myAvatar.value = bytes
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to set avatar", e)
                viewModelScope.launch { _error.value = "Failed to set avatar: ${e.message}" }
            }
        }
    }

    fun getAvatarOf(peerIk: ByteArray): ByteArray? {
        val ikHex = peerIk.toHexString()
        _contactAvatars.value[ikHex]?.let { return it }
        
        // If not in cache, try to fetch it
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = RatatoskCore.getClient().avatarOf(peerIk)
                if (bytes != null) {
                    _contactAvatars.update { it + (ikHex to bytes) }
                }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to background fetch avatar for $ikHex", e)
            }
        }
        return null
    }
}
