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
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import org.ratatosk.core.*
import java.util.concurrent.ConcurrentHashMap

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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

    private val _fileProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val fileProgress = _fileProgress.asStateFlow()

    private val _filePreviews = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val filePreviews = _filePreviews.asStateFlow()

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val _activeJobsFlow = MutableStateFlow<Set<String>>(emptySet())
    val activeJobsFlow = _activeJobsFlow.asStateFlow()

    private val _searchResults = MutableStateFlow<List<FfiMessage>>(emptyList())
    val searchResults = _searchResults.asStateFlow()

    private val _torStatus = MutableStateFlow<FfiTorStatus?>(null)
    val torStatus = _torStatus.asStateFlow()

    private val _mailStatus = MutableStateFlow<FfiMailStatus?>(null)
    val mailStatus = _mailStatus.asStateFlow()

    private val _mailAccount = MutableStateFlow<FfiMailAccount?>(null)
    val mailAccount = _mailAccount.asStateFlow()

    private val _transportsEnabled = MutableStateFlow<Map<FfiTransport, Boolean>>(emptyMap())
    val transportsEnabled = _transportsEnabled.asStateFlow()

    private val _transportsReady = MutableStateFlow<Map<FfiTransport, Boolean>>(emptyMap())
    val transportsReady = _transportsReady.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching = _isSearching.asStateFlow()

    private val _isFindingHidden = MutableStateFlow(false)
    val isFindingHidden = _isFindingHidden.asStateFlow()

    private val _activeAccountId = MutableStateFlow<String?>(RatatoskCore.getActiveAccountId())
    val activeAccountId = _activeAccountId.asStateFlow()

    private val _availableAccounts = MutableStateFlow<List<FfiAccount>>(emptyList())
    val availableAccounts = _availableAccounts.asStateFlow()

    private val _selectedAccount = MutableStateFlow<FfiAccount?>(null)
    val selectedAccount = _selectedAccount.asStateFlow()

    private val _isCreatingNewAccount = MutableStateFlow(false)
    val isCreatingNewAccount = _isCreatingNewAccount.asStateFlow()

    val totalUnreadCount: StateFlow<Int> = _unreadCounts
        .map { it.values.sum() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _activeChatIdFlow = MutableStateFlow<ByteArray?>(null)
    val activeChatIdFlow = _activeChatIdFlow.asStateFlow()

    private val _activeContactIdFlow = MutableStateFlow<ByteArray?>(null)
    val activeContactIdFlow = _activeContactIdFlow.asStateFlow()

    private val _activeMediaFile = MutableStateFlow<FfiFile?>(null)
    val activeMediaFile = _activeMediaFile.asStateFlow()

    private val _mediaExportedPath = MutableStateFlow<String?>(null)
    val mediaExportedPath = _mediaExportedPath.asStateFlow()

    private var activeChatId: String? = null

    private val _honestNotices = MutableStateFlow<List<String>>(emptyList())
    val honestNotices = _honestNotices.asStateFlow()
    
    private val _isInitialized = MutableStateFlow(RatatoskCore.isInitialized())
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _accountExists = MutableStateFlow(false)
    val accountExists: StateFlow<Boolean> = _accountExists.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _fingerprint = MutableStateFlow<String?>(null)
    val fingerprint = _fingerprint.asStateFlow()

    private val _maxAvatarBytes = MutableStateFlow<Int>(128 * 1024)
    val maxAvatarBytes = _maxAvatarBytes.asStateFlow()

    val userName = activeAccountId.flatMapLatest { id ->
        if (id == null) flowOf(null) else settingsRepository.getDisplayName(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _myAvatar = MutableStateFlow<ByteArray?>(null)
    val myAvatar = _myAvatar.asStateFlow()

    private val _contactAvatars = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val contactAvatars = _contactAvatars.asStateFlow()

    private val _pendingAvatarUri = MutableStateFlow<android.net.Uri?>(null)
    val pendingAvatarUri = _pendingAvatarUri.asStateFlow()

    private val _autoAcceptLimit = MutableStateFlow<ULong?>(null)
    val autoAcceptLimit = _autoAcceptLimit.asStateFlow()

    val chatTheme = settingsRepository.chatTheme.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ChatThemeData()
    )

    val notificationsShowName = activeAccountId.flatMapLatest { id ->
        if (id == null) flowOf(true) else settingsRepository.getNotificationsShowName(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val notificationsShowText = activeAccountId.flatMapLatest { id ->
        if (id == null) flowOf(true) else settingsRepository.getNotificationsShowText(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val downloadDirUri = activeAccountId.flatMapLatest { id ->
        if (id == null) flowOf(null) else settingsRepository.getDownloadDirUri(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val lanEnabled = transportsEnabled.map { it[FfiTransport.LAN] ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val torEnabled = transportsEnabled.map { it[FfiTransport.ONION] ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
        
    val mailEnabled = transportsEnabled.map { it[FfiTransport.MAIL] ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _onionAddress = MutableStateFlow<String?>(null)
    val onionAddress = _onionAddress.asStateFlow()

    private val _cardVersion = MutableStateFlow<ULong?>(null)
    val cardVersion = _cardVersion.asStateFlow()

    init {
        // Initialize Registry and monitor accounts
        viewModelScope.launch {
            try {
                RatatoskCore.initializeRegistry(application)
                refreshAccounts()
            } catch (e: Exception) {
                _error.value = "Failed to open registry: ${e.message}"
            }
        }

        // Monitor core initialization status
        viewModelScope.launch {
            while (true) {
                if (!_isInitialized.value && RatatoskCore.isInitialized()) {
                    android.util.Log.i("RatatoskVM", "Core initialized externally, setting up engine")
                    _isInitialized.value = true
                    _activeAccountId.value = RatatoskCore.getActiveAccountId()
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
        
        // Periodically refresh contacts and transport status
        viewModelScope.launch {
            while (true) {
                if (RatatoskCore.isInitialized()) {
                    refreshContacts()
                    refreshTransportStatus()
                }
                kotlinx.coroutines.delay(30000)
            }
        }
    }

    private var eventsJob: kotlinx.coroutines.Job? = null

    private fun setupEngine() {
        android.util.Log.d("RatatoskVM", "Setting up engine components...")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                android.util.Log.d("RatatoskVM", "Engine setup: getting fingerprint and limits")
                val client = RatatoskCore.getClient()
                val fingerprint = client.fingerprint()
                val maxAvatar = try { maxAvatarBytes().toInt() } catch (e: Exception) { 32768 }
                
                withContext(Dispatchers.Main) {
                    _fingerprint.value = fingerprint
                    _maxAvatarBytes.value = maxAvatar
                }

                // Collect events from core
                withContext(Dispatchers.Main) {
                    if (eventsJob == null) {
                        android.util.Log.d("RatatoskVM", "Starting event collection job")
                        eventsJob = RatatoskCore.events
                            .onEach { handleEvent(it) }
                            .launchIn(viewModelScope)
                    }
                }

                // Load own avatar (non-critical, separate launch)
                launch(Dispatchers.IO) {
                    try {
                        android.util.Log.d("RatatoskVM", "Engine setup: loading own avatar")
                        val avatar = client.myAvatar()
                        withContext(Dispatchers.Main) { _myAvatar.value = avatar }
                    } catch (e: Exception) {
                        android.util.Log.e("RatatoskVM", "Failed to load my avatar", e)
                    }
                }

                // Load auto-accept limit
                launch(Dispatchers.IO) {
                    try {
                        android.util.Log.d("RatatoskVM", "Engine setup: getting auto accept bytes")
                        val limit = client.autoAcceptBytes()
                        withContext(Dispatchers.Main) { _autoAcceptLimit.value = limit }
                    } catch (e: Exception) {
                        android.util.Log.e("RatatoskVM", "Failed to load auto-accept limit", e)
                    }
                }
                
                // Initial load of contacts and messages
                launch(Dispatchers.IO) {
                    try {
                        android.util.Log.d("RatatoskVM", "Engine setup: refreshing contacts, transports and preloading messages")
                        val currentContacts = client.contacts()
                        withContext(Dispatchers.Main) { _contacts.value = currentContacts }
                        
                        refreshTransportStatus()
                        
                        currentContacts.forEach { contact ->
                            loadMessages(contact.chatId)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("RatatoskVM", "Failed to preload contacts/messages", e)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Critical failure during setupEngine", e)
                withContext(Dispatchers.Main) {
                    _error.value = "Failed to load identity: ${e.message}"
                }
            }
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
        
        // If a new message arrived, we must ask for at least currentSize + 1
        // to avoid losing the oldest message in the current window.
        // We use a small buffer (5) to handle rapid bursts.
        val targetLimit = when {
            limit != null -> limit
            currentSize > 0 -> currentSize + 5
            else -> 100
        }
        
        android.util.Log.d("RatatoskVM", "loadMessages for $chatIdHex, current: $currentSize, target: $targetLimit")
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!RatatoskCore.isInitialized()) {
                    android.util.Log.w("RatatoskVM", "loadMessages called but core not initialized")
                    return@launch
                }
                val msgs = RatatoskCore.getClient().messages(chatId, maxOf(targetLimit, 1).toUInt())
                android.util.Log.d("RatatoskVM", "Fetched ${msgs.size} messages for $chatIdHex")
                
                _messages.update { currentMap ->
                    val existing = currentMap[chatIdHex] ?: emptyList()
                    // If we fetched fewer messages than we already have (and we didn't specify a smaller limit),
                    // it might be a race or a problem with the core state.
                    if (limit == null && msgs.size < existing.size) {
                        android.util.Log.w("RatatoskVM", "Fetched fewer messages (${msgs.size}) than current (${existing.size}) for $chatIdHex. Merging instead of replacing.")
                        // This shouldn't happen often with the +5 logic, but if it does, 
                        // we prefer the larger set or we could try to merge.
                        // For simplicity and safety, we keep the one with most messages.
                        currentMap
                    } else {
                        currentMap + (chatIdHex to msgs)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to load messages for $chatIdHex", e)
            }
        }
    }

    fun refreshAccounts() {
        _availableAccounts.value = RatatoskCore.listAccounts()
    }

    fun findHiddenAccount(pin: String, onFound: (ByteArray) -> Unit, onNotFound: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            _isFindingHidden.value = true
            try {
                val id = RatatoskCore.findHidden(pin)
                viewModelScope.launch {
                    if (id != null) onFound(id) else onNotFound()
                }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Find hidden failed", e)
                viewModelScope.launch { onNotFound() }
            } finally {
                _isFindingHidden.value = false
            }
        }
    }

    fun initialize(label: String, pin: String?, displayName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val account = RatatoskCore.createAccount(label)
                RatatoskCore.initialize(account.id, pin, null, displayName)
                
                val idHex = account.id.toHexString()
                settingsRepository.registerAccount(idHex, displayName)
                
                withContext(Dispatchers.Main) {
                    _isInitialized.value = true
                    _activeAccountId.value = idHex
                    setupEngine()
                    refreshAccounts()
                    _error.value = null
                }
            } catch (e: Exception) {
                _error.value = "Failed to initialize: ${e.message}"
            }
        }
    }

    fun unlock(account: FfiAccount, pin: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val idHex = account.id.toHexString()
                val savedName = settingsRepository.getDisplayName(idHex).firstOrNull() ?: account.label
                RatatoskCore.initialize(account.id, pin, null, savedName)
                withContext(Dispatchers.Main) {
                    _isInitialized.value = true
                    _activeAccountId.value = idHex
                    setupEngine()
                    _error.value = null
                }
            } catch (e: Exception) {
                _error.value = "Failed to unlock: ${e.message}"
            }
        }
    }

    fun logout() {
        RatatoskCore.logout()
        _isInitialized.value = false
        _activeAccountId.value = null
        _selectedAccount.value = null
        _isCreatingNewAccount.value = false
        // Clear all session state
        _contacts.value = emptyList()
        _messages.value = emptyMap()
        _messageStatuses.value = emptyMap()
        _unreadCounts.value = emptyMap()
    }

    fun clearError() {
        _error.value = null
    }

    fun selectAccount(account: FfiAccount?) {
        _selectedAccount.value = account
        if (account == null) {
            _isCreatingNewAccount.value = false
        }
    }

    fun setCreatingNewAccount(creating: Boolean) {
        _isCreatingNewAccount.value = creating
        if (creating) {
            _selectedAccount.value = null
        }
    }

    private var searchJob: Job? = null
    fun searchMessages(chatId: ByteArray?, query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _isSearching.value = false
            return
        }

        _isSearching.value = true
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val results = RatatoskCore.getClient().search(chatId, query, 50u)
                _searchResults.value = results
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Search failed", e)
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _searchResults.value = emptyList()
        _isSearching.value = false
    }

    fun sendMessage(chatId: ByteArray, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                RatatoskCore.getClient().sendText(chatId, text)
                loadMessages(chatId)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = "Failed to send: ${e.message}"
                }
            }
        }
    }

    fun sendText(chatId: ByteArray, text: String) {
        sendMessage(chatId, text)
    }

    fun sendFiles(chatId: ByteArray, files: List<java.io.File>, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val outgoingFiles = files.map { file ->
                    val preview = if (file.extension.lowercase() in listOf("jpg", "jpeg", "png", "webp")) {
                        generatePreview(file)
                    } else null
                    FfiOutgoingFile(file.absolutePath, preview)
                }
                RatatoskCore.getClient().sendFiles(chatId, outgoingFiles, text)
                loadMessages(chatId)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = "Failed to send files: ${e.message}"
                }
            }
        }
    }

    private fun generatePreview(file: java.io.File): ByteArray? {
        try {
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return null
            val reqWidth = 320
            val reqHeight = 320
            val ratio = Math.min(reqWidth.toFloat() / bitmap.width, reqHeight.toFloat() / bitmap.height)
            if (ratio >= 1.0f) return null
            
            val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
            val stream = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, stream)
            val bytes = stream.toByteArray()
            if (bytes.size > maxPreviewBytes().toInt()) return null
            return bytes
        } catch (e: Exception) {
             android.util.Log.e("RatatoskVM", "Failed to generate preview", e)
             return null
        }
    }

    fun acceptFile(chatId: ByteArray, fileId: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                RatatoskCore.getClient().acceptFile(fileId)
                loadMessages(chatId)
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to accept file", e)
            }
        }
    }

    fun declineFile(chatId: ByteArray, fileId: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                RatatoskCore.getClient().declineFile(fileId)
                loadMessages(chatId)
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to decline file", e)
            }
        }
    }

    fun getFilePreview(fileId: ByteArray): ByteArray? {
        val hex = fileId.toHexString()
        _filePreviews.value[hex]?.let { return it }
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = RatatoskCore.getClient().previewOf(fileId)
                if (bytes != null) {
                    _filePreviews.update { it + (hex to bytes) }
                }
            } catch (e: Exception) { /* ignore */ }
        }
        return null
    }

    fun saveFile(file: FfiFile, destination: java.io.File, onComplete: (java.io.File) -> Unit) {
        val fileIdHex = file.fileId.toHexString()
        
        val job = viewModelScope.launch(Dispatchers.IO) {
            var reader: FfiFileReader? = null
            try {
                destination.parentFile?.mkdirs()
                
                reader = RatatoskCore.getClient().openFile(file.fileId)
                if (reader == null) return@launch

                destination.outputStream().use { output ->
                    val total = reader.chunkTotal()
                    for (i in 0UL until total) {
                        ensureActive()
                        val chunk = reader.chunk(i)
                        if (chunk != null) {
                            output.write(chunk)
                            output.flush()
                            _fileProgress.update { it + (fileIdHex to (i.toFloat() / total.toFloat())) }
                        } else {
                            throw Exception("Chunk $i missing")
                        }
                    }
                }
                _fileProgress.update { it + (fileIdHex to 1f) }
                viewModelScope.launch { onComplete(destination) }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    android.util.Log.e("RatatoskVM", "Failed to save file ${file.name}", e)
                }
            } finally {
                reader?.destroy()
                activeJobs.remove(fileIdHex)
                _activeJobsFlow.update { it - fileIdHex }
            }
        }
        
        activeJobs[fileIdHex]?.cancel()
        activeJobs[fileIdHex] = job
        _activeJobsFlow.update { it + fileIdHex }
    }

    fun downloadFile(file: FfiFile, onComplete: (String) -> Unit) {
        val fileIdHex = file.fileId.toHexString()

        val job = viewModelScope.launch(Dispatchers.IO) {
            var reader: FfiFileReader? = null
            try {
                reader = RatatoskCore.getClient().openFile(file.fileId)
                if (reader == null) return@launch

                val dirUriString = downloadDirUri.value
                val dirUri = dirUriString?.let { android.net.Uri.parse(it) }
                
                if (dirUri != null) {
                    val root = DocumentFile.fromTreeUri(getApplication(), dirUri)
                    if (root != null && root.canWrite()) {
                        val target = root.createFile("*/*", file.name)
                        if (target != null) {
                            getApplication<Application>().contentResolver.openOutputStream(target.uri)?.use { output ->
                                val total = reader.chunkTotal()
                                for (i in 0UL until total) {
                                    ensureActive()
                                    val chunk = reader.chunk(i)
                                    if (chunk != null) {
                                        output.write(chunk)
                                        output.flush()
                                        _fileProgress.update { it + (fileIdHex to (i.toFloat() / total.toFloat())) }
                                    }
                                }
                            }
                            _fileProgress.update { it + (fileIdHex to 1f) }
                            viewModelScope.launch { onComplete(file.name) }
                            return@launch
                        }
                    }
                }
                
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val ratatoskDir = java.io.File(downloadsDir, "ratatosk")
                ratatoskDir.mkdirs()
                val dest = java.io.File(ratatoskDir, file.name)
                
                dest.outputStream().use { output ->
                    val total = reader.chunkTotal()
                    for (i in 0UL until total) {
                        ensureActive()
                        val chunk = reader.chunk(i)
                        if (chunk != null) {
                            output.write(chunk)
                            output.flush()
                            _fileProgress.update { it + (fileIdHex to (i.toFloat() / total.toFloat())) }
                        }
                    }
                }
                _fileProgress.update { it + (fileIdHex to 1f) }
                viewModelScope.launch { onComplete(dest.absolutePath) }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    android.util.Log.e("RatatoskVM", "Failed to download file", e)
                }
            } finally {
                reader?.destroy()
                activeJobs.remove(fileIdHex)
                _activeJobsFlow.update { it - fileIdHex }
            }
        }
        
        activeJobs[fileIdHex]?.cancel()
        activeJobs[fileIdHex] = job
        _activeJobsFlow.update { it + fileIdHex }
    }

    fun cancelFileJob(fileId: ByteArray) {
        val hex = fileId.toHexString()
        activeJobs[hex]?.cancel()
        activeJobs.remove(hex)
        _activeJobsFlow.update { it - hex }
    }

    fun sweepOrphanFiles(onResult: (FfiSwept) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = RatatoskCore.getClient().sweepOrphanFiles()
                viewModelScope.launch { onResult(result) }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to sweep orphan files", e)
            }
        }
    }

    fun resendMessage(chatId: ByteArray, body: String) {
        sendMessage(chatId, body)
    }

    fun setNotificationsShowName(show: Boolean) {
        val id = activeAccountId.value ?: return
        viewModelScope.launch {
            settingsRepository.setNotificationsShowName(id, show)
        }
    }

    fun setNotificationsShowText(show: Boolean) {
        val id = activeAccountId.value ?: return
        viewModelScope.launch {
            settingsRepository.setNotificationsShowText(id, show)
        }
    }

    fun setDownloadDirUri(uri: android.net.Uri?) {
        val id = activeAccountId.value ?: return
        viewModelScope.launch {
            settingsRepository.setDownloadDirUri(id, uri?.toString())
        }
    }
    
    fun refreshTransportStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = RatatoskCore.getClient()
                val en = FfiTransport.values().associateWith { client.transportEnabled(it) }
                val re = FfiTransport.values().associateWith { client.transportReady(it) }
                val ts = client.torStatus()
                val ms = client.mailStatus()
                val ma = client.mailAccount()
                
                withContext(Dispatchers.Main) {
                    _transportsEnabled.value = en
                    _transportsReady.value = re
                    _torStatus.value = ts
                    _mailStatus.value = ms
                    _mailAccount.value = ma
                }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to refresh transport status", e)
            }
        }
    }

    fun setTransportEnabled(transport: FfiTransport, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                RatatoskCore.getClient().setTransportEnabled(transport, enabled)
                refreshTransportStatus()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = "Failed to toggle transport: ${e.message}"
                }
            }
        }
    }

    fun setMailAccount(address: String, password: String, imapHost: String, imapPort: Int, smtpHost: String, smtpPort: Int, viaTor: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                RatatoskCore.getClient().setMailAccount(address, password, imapHost, imapPort.toUShort(), smtpHost, smtpPort.toUShort(), viaTor)
                refreshTransportStatus()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = "Failed to set mail account: ${e.message}"
                }
            }
        }
    }

    fun createMailAccount(url: String, viaTor: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                RatatoskCore.getClient().createMailAccount(url, viaTor)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = "Failed to create mail account: ${e.message}"
                }
            }
        }
    }

    fun clearMailAccount() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                RatatoskCore.getClient().clearMailAccount()
                refreshTransportStatus()
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to clear mail account", e)
            }
        }
    }

    fun setLanEnabled(enabled: Boolean) {
        setTransportEnabled(FfiTransport.LAN, enabled)
    }

    fun setTorEnabled(enabled: Boolean) {
        setTransportEnabled(FfiTransport.ONION, enabled)
    }

    fun addContact(uri: String, metInPerson: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                RatatoskCore.getClient().addContact(uri, metInPerson)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = "Failed to add contact: ${e.message}"
                }
            }
        }
    }

    fun addSharedContact(msgId: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                RatatoskCore.getClient().addSharedContact(msgId)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = "Failed to add shared contact: ${e.message}"
                }
            }
        }
    }

    fun shareContact(chatId: ByteArray, peerIk: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                RatatoskCore.getClient().shareContact(chatId, peerIk)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = "Failed to share contact: ${e.message}"
                }
            }
        }
    }

    private val _myContactUri = MutableStateFlow<String?>(null)
    val myContactUri = _myContactUri.asStateFlow()

    fun getMyContactUri() {
        viewModelScope.launch(Dispatchers.IO) {
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

    fun setDisplayName(name: String) {
        val id = activeAccountId.value ?: return
        viewModelScope.launch {
            settingsRepository.setDisplayName(id, name)
            android.util.Log.d("RatatoskVM", "Display name updated to: $name. Will be applied to core on next restart.")
        }
    }

    fun setLocalName(peerIk: ByteArray, name: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                RatatoskCore.getClient().setLocalName(peerIk, name)
                refreshContacts()
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to set local name", e)
            }
        }
    }

    fun deleteContact(peerIk: ByteArray, purgeHistory: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                RatatoskCore.getClient().deleteContact(peerIk, purgeHistory)
                refreshContacts()
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to delete contact", e)
            }
        }
    }

    fun getContactByChatId(chatId: ByteArray): FfiContact? {
        return _contacts.value.find { it.chatId.contentEquals(chatId) }
    }

    fun clearChat(chatId: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                RatatoskCore.getClient().clearChat(chatId)
                _messages.update { it + (chatId.toHexString() to emptyList()) }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to clear chat", e)
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

    fun forwardMessages(chatId: ByteArray, msgIds: List<ByteArray>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                RatatoskCore.getClient().forwardMessages(chatId, msgIds)
                loadMessages(chatId)
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to forward", e)
            }
        }
    }

    fun markRead(chatId: ByteArray, upTo: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                RatatoskCore.getClient().markRead(chatId, upTo)
                _unreadCounts.update { it + (chatId.toHexString() to 0) }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to mark read", e)
            }
        }
    }

    fun markVerified(peerIk: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                RatatoskCore.getClient().markVerified(peerIk)
                refreshContacts()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = "Failed to mark as verified: ${e.message}"
                }
            }
        }
    }

    fun revokeVerification(peerIk: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                RatatoskCore.getClient().revokeVerification(peerIk)
                refreshContacts()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = "Failed to revoke verification: ${e.message}"
                }
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

    fun getAvatarOf(peerIk: ByteArray): ByteArray? {
        val hex = peerIk.toHexString()
        _contactAvatars.value[hex]?.let { return it }
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = RatatoskCore.getClient().avatarOf(peerIk)
                if (bytes != null) {
                    _contactAvatars.update { it + (hex to bytes) }
                }
            } catch (e: Exception) { /* ignore */ }
        }
        return null
    }

    fun setMyAvatar(bytes: ByteArray?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                RatatoskCore.getClient().setAvatar(bytes)
                _myAvatar.value = bytes
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to set avatar", e)
            }
        }
    }

    fun setAvatar(bytes: ByteArray?) {
        setMyAvatar(bytes)
    }

    fun setPendingAvatarUri(uri: android.net.Uri?) {
        _pendingAvatarUri.value = uri
    }

    fun getMessage(msgId: ByteArray): FfiMessage? {
        return _repliedMessages.value[msgId.toHexString()] ?: run {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val msg = RatatoskCore.getClient().message(msgId)
                    _repliedMessages.update { it + (msgId.toHexString() to msg) }
                } catch (e: Exception) { /* ignore */ }
            }
            null
        }
    }

    fun setAutoAcceptLimit(limit: ULong?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                RatatoskCore.getClient().setAutoAcceptBytes(limit)
                _autoAcceptLimit.value = limit
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to set auto-accept limit", e)
            }
        }
    }

    fun setActiveChat(chatId: ByteArray?) {
        _activeChatIdFlow.value = chatId
        activeChatId = chatId?.toHexString()
        if (chatId != null) {
            _unreadCounts.update { it + (chatId.toHexString() to 0) }
        }
    }

    fun setActiveContact(chatId: ByteArray?) {
        _activeContactIdFlow.value = chatId
    }

    fun setActiveMediaFile(file: FfiFile?) {
        _activeMediaFile.value = file
    }

    fun openMedia(file: FfiFile, cacheDir: java.io.File) {
        _activeMediaFile.value = file
        _mediaExportedPath.value = null
        
        val mediaDir = java.io.File(cacheDir, "media_viewer")
        mediaDir.mkdirs()
        val dest = java.io.File(mediaDir, "${file.fileId.toHexString()}_${file.name}")
        
        if (dest.exists() && dest.length() == file.sizeBytes.toLong()) {
            _mediaExportedPath.value = dest.absolutePath
            return
        }
        
        saveFile(file, dest) { savedFile ->
            if (_activeMediaFile.value?.fileId?.contentEquals(file.fileId) == true) {
                _mediaExportedPath.value = savedFile.absolutePath
            }
        }
    }

    fun closeMedia() {
        _activeMediaFile.value = null
        _mediaExportedPath.value = null
    }

    fun updateChatTheme(updater: (ChatThemeData) -> ChatThemeData) {
        viewModelScope.launch {
            val newData = updater(chatTheme.value)
            settingsRepository.updateChatTheme(newData)
        }
    }

    fun getRetractionNotice(): String {
        return try {
            retractionNotice()
        } catch (e: Exception) {
            "This message will be deleted for everyone."
        }
    }

    fun getWaitingNotice(): String {
        return try {
            waitingNotice()
        } catch (e: Exception) {
            "Waiting for peer to come online..."
        }
    }

    private var isAnnouncingTor = false

    private fun handleEvent(event: FfiEvent) {
        when (event) {
            is FfiEvent.MessageReceived -> {
                loadMessages(event.chatId)
                if (activeChatId != event.chatId.toHexString()) {
                    _unreadCounts.update { it + (event.chatId.toHexString() to (it[event.chatId.toHexString()] ?: 0) + 1) }
                }
            }
            is FfiEvent.StatusChanged -> {
                val hex = event.msgId.toHexString()
                _messageStatuses.update { it + (hex to event.status) }
                
                // If we don't have this message in our current lists, it might be a new outgoing message
                // or something we haven't loaded yet. Reload to be sure.
                val messageExists = _messages.value.values.any { list -> list.any { it.msgId.contentEquals(event.msgId) } }
                if (!messageExists) {
                    android.util.Log.d("RatatoskVM", "StatusChanged for unknown message $hex. Reloading messages.")
                    // We don't know the chatId here easily from the event, 
                    // but we can reload the active chat if it's open.
                    activeChatId?.let { loadMessages(it.hexToByteArray()) }
                }
            }
            is FfiEvent.ContactAdded, is FfiEvent.ContactChanged, is FfiEvent.ContactRemoved -> {
                refreshContacts()
            }
            is FfiEvent.MessagesDeleted -> {
                loadMessages(event.chatId)
            }
            is FfiEvent.MessageEdited -> {
                loadMessages(event.chatId)
            }
            is FfiEvent.ReactionChanged -> {
                loadMessages(event.chatId)
            }
            is FfiEvent.AvatarChanged -> {
                val hex = event.peerIk.toHexString()
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val bytes = RatatoskCore.getClient().avatarOf(event.peerIk)
                        withContext(Dispatchers.Main) {
                            if (bytes != null) {
                                _contactAvatars.update { it + (hex to bytes) }
                            } else {
                                _contactAvatars.update { it - hex }
                            }
                        }
                    } catch (e: Exception) { /* ignore */ }
                }
            }
            is FfiEvent.FileProgress -> {
                val hex = event.fileId.toHexString()
                val progress = if (event.total > 0UL) event.received.toFloat() / event.total.toFloat() else 0f
                _fileProgress.update { it + (hex to progress) }
            }
            is FfiEvent.TorStatus -> {
                android.util.Log.i("RatatoskVM", "TorStatus event: fraction=${event.fraction}, note=${event.note}, blocked=${event.blocked}")
                _torStatus.value = FfiTorStatus(event.fraction, event.note, event.blocked)
                if (event.fraction >= 1.0f && _onionAddress.value == null && !isAnnouncingTor) {
                    // Tor is up, announce addresses if Tor is enabled
                    isAnnouncingTor = true
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            val client = RatatoskCore.getClient()
                            val card = client.myAddresses()
                            
                            withContext(Dispatchers.Main) {
                                _onionAddress.value = card.onion.takeIf { it.isNotEmpty() }
                                _cardVersion.value = card.version
                            }
                            
                            if (client.transportEnabled(FfiTransport.ONION) && card.onion.isNotEmpty()) {
                                android.util.Log.i("RatatoskVM", "Tor is up, announcing onion address: ${card.onion}")
                                client.announceAddresses(card.onion, card.chatmail)
                                // Refresh again to get new version if it changed
                                val updatedCard = client.myAddresses()
                                withContext(Dispatchers.Main) {
                                    _cardVersion.value = updatedCard.version
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("RatatoskVM", "Failed to announce addresses", e)
                        } finally {
                            isAnnouncingTor = false
                        }
                    }
                }
                refreshTransportStatus()
            }
            is FfiEvent.CommandRefused -> {
                _error.value = event.reason
            }
            is FfiEvent.MailAccountReady -> {
                android.util.Log.i("RatatoskVM", "Mail account ready: ${event.address}")
                refreshTransportStatus()
            }
            is FfiEvent.MailAccountFailed -> {
                _error.value = "Mail setup failed: ${event.reason}"
                refreshTransportStatus()
            }
            is FfiEvent.MailLoginFailed -> {
                android.util.Log.w("RatatoskVM", "Mail login failed: ${event.reason}")
                refreshTransportStatus()
            }
            is FfiEvent.MailLimits -> {
                android.util.Log.i("RatatoskVM", "Mail limits updated: usage=${event.mailboxUsed}, limit=${event.mailboxLimit}")
                refreshTransportStatus()
            }
            is FfiEvent.HonestNotice -> {
                // Could show as a global notice or snackbar
            }
            else -> {}
        }
    }
}
