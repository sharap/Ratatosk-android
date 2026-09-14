package chat.ratatosk.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import chat.ratatosk.android.R
import chat.ratatosk.android.core.RatatoskCore
import chat.ratatosk.android.data.SettingsRepository
import chat.ratatosk.android.ui.theme.ChatThemeData
import chat.ratatosk.android.util.FileUtils
import chat.ratatosk.android.util.hexToByteArray
import chat.ratatosk.android.util.toHexString
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    private val _groups = MutableStateFlow<List<FfiGroup>>(emptyList())
    val groups = _groups.asStateFlow()

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

    // Полоса **у отправителя**: сколько чанков отдано транспорту.
    //
    // Отдельно от fileProgress, и это не дубль: то — ход приёма у нас,
    // это — ход отдачи наружу. Раньше исходящий файл питался чужим
    // событием и потому не двигался вовсе.
    private val _fileSending = MutableStateFlow<Map<String, Float>>(emptyMap())
    val fileSending = _fileSending.asStateFlow()

    // Почему передача файла стоит (§10.3). Состояние, а не происшествие:
    // показывать его надо на самом файле, пока оно держится, а не
    // всплывающей подсказкой — ждать файл может столько, сколько
    // собеседник вне сети.
    private val _fileWaiting = MutableStateFlow<Map<String, FfiFileWaitReason>>(emptyMap())
    val fileWaiting = _fileWaiting.asStateFlow()

    /**
     * Текст для стоящей передачи. Берётся у ядра и переписыванию
     * не подлежит: он обещает ровно то, что протокол делает. «Ошибка
     * отправки» и «загрузка…» здесь одинаково неправда.
     */
    fun fileWaitingText(reason: FfiFileWaitReason): String =
        try { org.ratatosk.core.fileWaitingText(reason) } catch (e: Exception) { "" }

    private val _filePreviews = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val filePreviews = _filePreviews.asStateFlow()

    // Превью, которые уже заказаны. Держит от лавины запросов: заказ идёт
    // из composable, то есть на каждую перерисовку строки.
    private val previewRequests: MutableSet<String> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private val activeJobs = ConcurrentHashMap<String, Job>()

    // Идущие загрузки истории, по одной на чат. См. loadMessages.
    private val messageLoads = ConcurrentHashMap<String, Job>()
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

    private val _isCompanionMode = MutableStateFlow<Boolean>(RatatoskCore.isCompanionMode())
    val isCompanionMode: StateFlow<Boolean> = _isCompanionMode.asStateFlow()

    private val _isCompanionLinked = MutableStateFlow(false)
    val isCompanionLinked = _isCompanionLinked.asStateFlow()

    val companionLinks: StateFlow<List<chat.ratatosk.android.data.CompanionLink>> = settingsRepository.companionLinks.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val totalUnreadCount: StateFlow<Int> = _unreadCounts
        .map { it.values.sum() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val lastAccountId = settingsRepository.lastAccountId.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    private val _activeChatIdFlow = MutableStateFlow<ByteArray?>(null)
    val activeChatIdFlow = _activeChatIdFlow.asStateFlow()

    private val _activeContactIdFlow = MutableStateFlow<ByteArray?>(null)
    val activeContactIdFlow = _activeContactIdFlow.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<FfiPairedDevice>>(emptyList())
    val pairedDevices = _pairedDevices.asStateFlow()

    private val _pairingUri = MutableStateFlow<String?>(null)
    val pairingUri = _pairingUri.asStateFlow()

    private val _activeMediaFile = MutableStateFlow<FfiFile?>(null)
    val activeMediaFile = _activeMediaFile.asStateFlow()

    private val _mediaExportedPath = MutableStateFlow<String?>(null)
    val mediaExportedPath = _mediaExportedPath.asStateFlow()

    private val pendingCompanionSaves = ConcurrentHashMap<String, (java.io.File) -> Unit>()
    private val pendingCompanionPaths = ConcurrentHashMap<String, String>()
    private var currentCompanionSaveFileId: String? = null
    private val companionAvatarMs = ConcurrentHashMap<String, ULong>()

    private var activeChatId: String? = null
    private var currentCompanionLabel: String? = null

    private val _honestNotices = MutableStateFlow<List<String>>(emptyList())
    val honestNotices = _honestNotices.asStateFlow()
    
    private val _isInitialized = MutableStateFlow(RatatoskCore.isInitialized())
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _accountExists = MutableStateFlow(false)
    val accountExists: StateFlow<Boolean> = _accountExists.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _myIk = MutableStateFlow<ByteArray?>(null)
    val myIk = _myIk.asStateFlow()

    private val _fingerprint = MutableStateFlow<String?>(null)
    val fingerprint = _fingerprint.asStateFlow()

    private val _maxAvatarBytes = MutableStateFlow<Int>(128 * 1024)
    val maxAvatarBytes = _maxAvatarBytes.asStateFlow()

    val userName = activeAccountId.flatMapLatest { id ->
        when {
            id == null -> flowOf(null)
            id.startsWith("companion:") -> flowOf(currentCompanionLabel)
            else -> settingsRepository.getDisplayName(id)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _myAvatar = MutableStateFlow<ByteArray?>(null)
    val myAvatar = _myAvatar.asStateFlow()

    private val _contactAvatars = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val contactAvatars = _contactAvatars.asStateFlow()

    // К какому сообщению просят перейти при открытии чата.
    //
    // Ставится снаружи — сейчас из уведомления о реакции, — а снимает его
    // сам экран чата, когда доскроллил. Через ViewModel, а не через аргумент
    // экрана, потому что чат к моменту нажатия может быть уже открыт:
    // тогда менять нечего, нужен именно сигнал.
    private val _pendingScrollToMsgId = MutableStateFlow<String?>(null)
    val pendingScrollToMsgId = _pendingScrollToMsgId.asStateFlow()

    fun requestScrollToMessage(msgIdHex: String?) {
        _pendingScrollToMsgId.value = msgIdHex
    }

    fun consumeScrollRequest() {
        _pendingScrollToMsgId.value = null
    }

    private val _pendingAvatarUri = MutableStateFlow<android.net.Uri?>(null)
    val pendingAvatarUri = _pendingAvatarUri.asStateFlow()

    private val _pendingAvatarChatId = MutableStateFlow<ByteArray?>(null)
    val pendingAvatarChatId = _pendingAvatarChatId.asStateFlow()

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

    val yggEnabled = transportsEnabled.map { it[FfiTransport.YGG] ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val btEnabled = transportsEnabled.map { it[FfiTransport.BT] ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // Вручено ли ядру радио. Отдельно от «включено»: ступень можно включить,
    // не выдав разрешений, и тогда она не поднимется — экран обязан
    // различать «выключено человеком» и «включено, а радио нет».
    private val _btHasRadio = MutableStateFlow(false)
    val btHasRadio = _btHasRadio.asStateFlow()

    /**
     * Пробует вручить радио — звать после выдачи разрешений.
     *
     * Разрешения спрашивает экран: из ViewModel системный запрос
     * не показать, для него нужна Activity.
     */
    fun handBtRadio() {
        viewModelScope.launch(Dispatchers.IO) {
            RatatoskCore.ensureBtRadio(getApplication())
            val has = RatatoskCore.hasBtRadio()
            withContext(Dispatchers.Main) { _btHasRadio.value = has }
        }
    }

    /**
     * Включён ли сам адаптер Bluetooth.
     *
     * Нужно, чтобы отличить «радио есть, но эфир выключен человеком»
     * от «радио есть, а ступень всё равно не поднялась». Причину ядро
     * словами наружу не отдаёт — оно пишет её в журнал, — но обе
     * возможные причины приложение знает про себя само.
     */
    fun btAdapterEnabled(): Boolean = try {
        val manager = getApplication<Application>()
            .getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        manager?.adapter?.isEnabled == true
    } catch (t: Throwable) {
        false
    }

    fun btMissingPermissions(): List<String> =
        org.ratatosk.bt.BtRadio.Permissions.missing(getApplication())

    val nostrEnabled = transportsEnabled.map { it[FfiTransport.NOSTR] ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _nostrRelays = MutableStateFlow<List<String>>(emptyList())
    val nostrRelays = _nostrRelays.asStateFlow()

    private val _nostrRelaysAlive = MutableStateFlow<List<org.ratatosk.core.FfiNostrRelay>?>(null)
    val nostrRelaysAlive = _nostrRelaysAlive.asStateFlow()

    private val _nostrNpub = MutableStateFlow<String?>(null)
    val nostrNpub = _nostrNpub.asStateFlow()

    private val _nostrDirect = MutableStateFlow(false)
    val nostrDirect = _nostrDirect.asStateFlow()

    private val _nostrAdvertisedRelays = MutableStateFlow<List<String>>(emptyList())
    val nostrAdvertisedRelays = _nostrAdvertisedRelays.asStateFlow()

    private val _yggMode = MutableStateFlow(FfiYggMode.OFF)
    val yggMode = _yggMode.asStateFlow()

    private val _yggKey = MutableStateFlow<String?>(null)
    val yggKey = _yggKey.asStateFlow()

    private val _yggAddress = MutableStateFlow<String?>(null)
    val yggAddress = _yggAddress.asStateFlow()

    private val _yggPeers = MutableStateFlow<List<String>>(emptyList())
    val yggPeers = _yggPeers.asStateFlow()

    private val _yggPeersAlive = MutableStateFlow<List<org.ratatosk.core.FfiYggPeer>?>(null)
    val yggPeersAlive = _yggPeersAlive.asStateFlow()

    private val _onionAddress = MutableStateFlow<String?>(null)
    val onionAddress = _onionAddress.asStateFlow()

    private val _cardVersion = MutableStateFlow<ULong?>(null)
    val cardVersion = _cardVersion.asStateFlow()

    init {
        // Расшифрованные копии, пережившие прошлый запуск (приложение
        // могли убить, не дав выйти из аккаунта). Сутки — чтобы не тронуть
        // то, с чем человек работает прямо сейчас, но и не копить вечно.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                FileUtils.clearDecryptedCaches(application, olderThanMs = 24L * 60 * 60 * 1000)
            } catch (e: Exception) {
                android.util.Log.w("RatatoskVM", "Failed to sweep decrypted caches: ${e.message}")
            }
        }

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
        
        // Periodically refresh contacts and transport status (only in main client mode)
        viewModelScope.launch {
            while (true) {
                if (RatatoskCore.isInitialized() && !RatatoskCore.isCompanionMode()) {
                    refreshContacts()
                    refreshTransportStatus()
                    loadPairedDevices()
                }
                kotlinx.coroutines.delay(30000)
            }
        }
    }

    private var eventsJob: kotlinx.coroutines.Job? = null
    private var companionEventsJob: kotlinx.coroutines.Job? = null

    private fun setupCompanionEngine() {
        android.util.Log.d("RatatoskVM", "Setting up companion engine...")
        
        // Start collecting events BEFORE making calls
        if (companionEventsJob == null) {
            companionEventsJob = viewModelScope.launch(Dispatchers.IO) {
                RatatoskCore.companionEvents.collect { event ->
                    handleCompanionEvent(event)
                }
            }
        }

        if (eventsJob == null) {
            eventsJob = viewModelScope.launch(Dispatchers.IO) {
                RatatoskCore.events.collect { event ->
                    handleEvent(event)
                }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val companion = RatatoskCore.getCompanion()
                val ik = companion.deviceId()
                val fingerprint = ik.toHexString()
                val phoneName = try { companion.phoneName() } catch (e: Exception) { "Companion" }
                
                withContext(Dispatchers.Main) {
                    _myIk.value = ik
                    _fingerprint.value = fingerprint
                    if (currentCompanionLabel == null) currentCompanionLabel = phoneName
                    _isInitialized.value = true
                    _isCompanionLinked.value = false
                }

                android.util.Log.d("RatatoskVM", "Initial companion chats() call for cache")
                companion.chats()
                
                // Fetch own avatar
                try {
                    companion.avatar(null)
                } catch (e: Exception) { /* ignore */ }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to setup companion engine", e)
            }
        }
    }

    private fun handleCompanionEvent(event: FfiCompanionEvent) {
        when (event) {
            is FfiCompanionEvent.Chats -> {
                android.util.Log.d("RatatoskVM", "Companion received ${event.chats.size} chats, fresh=${event.fresh}")
                val (groupChats, contactChats) = event.chats.partition { it.isGroup }

                val mappedContacts = contactChats.map { mapCompanionChat(it) }
                _contacts.value = mappedContacts

                val existingGroupsMap = _groups.value.associateBy { it.chatId.toHexString() }
                val mappedGroups = groupChats.map { chat ->
                    mapCompanionGroup(chat, existingGroupsMap[chat.chatId.toHexString()])
                }
                _groups.value = mappedGroups

                event.chats.forEach { chat ->
                    val hex = chat.chatId.toHexString()
                    if (chat.isGroup && _isCompanionLinked.value) {
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                RatatoskCore.getCompanion().members(chat.chatId)
                            } catch (e: Exception) { /* ignore */ }
                        }
                    }
                    if (_messages.value[hex].isNullOrEmpty() || hex == activeChatId) {
                        loadMessages(chat.chatId)
                    }
                }
            }
            is FfiCompanionEvent.Members -> {
                val hex = event.chatId.toHexString()
                val groupMembers = event.members.map { member ->
                    FfiGroupMember(
                        ik = member.chatId,
                        name = member.name,
                        mine = member.mine
                    )
                }
                val isOwnerMine = event.members.any { it.mine && it.owner }
                _groups.update { currentGroups ->
                    currentGroups.map { grp ->
                        if (grp.chatId.toHexString() == hex) {
                            grp.copy(
                                members = groupMembers,
                                mine = isOwnerMine
                            )
                        } else grp
                    }
                }

                val memberContacts = event.members.filter { member ->
                    !member.mine && _contacts.value.none { it.chatId.contentEquals(member.chatId) }
                }.map { member ->
                    FfiContact(
                        peerIk = member.chatId,
                        chatId = member.chatId,
                        fingerprint = "",
                        displayName = member.name,
                        localName = null,
                        verified = false,
                        seenOnLan = false,
                        hasAvatar = false,
                        onion = null,
                        chatmail = null,
                        ygg = null,
                        nostrRelays = emptyList(),
                        cardVersion = 0UL,
                        addedMs = 0UL,
                        reachability = FfiReachability(emptyList(), null, null),
                        directChannel = null,
                        anomalies = FfiAnomalies(0UL, 0UL, 0UL, 0UL, 0UL)
                    )
                }
                if (memberContacts.isNotEmpty()) {
                    _contacts.update { currentContacts ->
                        val existingHexes = currentContacts.map { it.chatId.toHexString() }.toSet()
                        currentContacts + memberContacts.filter { !existingHexes.contains(it.chatId.toHexString()) }
                    }
                }
                event.members.forEach { member ->
                    if (!member.mine) {
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                RatatoskCore.getCompanion().avatar(member.chatId)
                            } catch (e: Exception) { /* ignore */ }
                        }
                    }
                }
            }
            is FfiCompanionEvent.GroupCreated -> {
                android.util.Log.i("RatatoskVM", "Companion group created: ${event.chatId.toHexString()}")
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        RatatoskCore.getCompanion().chats()
                        RatatoskCore.getCompanion().members(event.chatId)
                    } catch (e: Exception) { /* ignore */ }
                }
            }
            is FfiCompanionEvent.ChatsChanged -> {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        RatatoskCore.getCompanion().chats()
                    } catch (e: Exception) { /* ignore */ }
                }
            }
            is FfiCompanionEvent.AvatarChanged -> {
                val hex = event.chatId?.toHexString() ?: "mine"
                if (event.avatarMs != 0UL) {
                    companionAvatarMs[hex] = event.avatarMs
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            RatatoskCore.getCompanion().avatar(event.chatId)
                        } catch (e: Exception) { /* ignore */ }
                    }
                } else {
                    companionAvatarMs.remove(hex)
                    if (hex == "mine") _myAvatar.value = null
                    else _contactAvatars.update { it - hex }
                }
            }
            is FfiCompanionEvent.History -> {
                android.util.Log.d("RatatoskVM", "Companion received ${event.page.size} messages for chat ${event.chatId.toHexString()}, fresh=${event.fresh}")
                val mappedMessages = event.page.map { mapCompanionMessage(it) }
                val chatIdHex = event.chatId.toHexString()
                _messages.update { it + (chatIdHex to mappedMessages) }
            }
            is FfiCompanionEvent.Arrived -> {
                loadMessages(event.message.chatId)
            }
            is FfiCompanionEvent.Linked -> {
                android.util.Log.i("RatatoskVM", "Companion LINKED")
                _isCompanionLinked.value = true
                RatatoskCore.getCompanion().chats()
            }
            is FfiCompanionEvent.Unlinked -> {
                android.util.Log.w("RatatoskVM", "Companion UNLINKED")
                _isCompanionLinked.value = false
            }
            is FfiCompanionEvent.FileSaved -> {
                val hex = event.fileId.toHexString()
                _fileProgress.update { it + (hex to 1f) }
                _activeJobsFlow.update { it - hex }
                if (currentCompanionSaveFileId == hex) currentCompanionSaveFileId = null
                pendingCompanionSaves.remove(hex)?.let { callback ->
                    pendingCompanionPaths.remove(hex)?.let { path ->
                        viewModelScope.launch(Dispatchers.Main) {
                            callback(java.io.File(path))
                        }
                    }
                }
            }
            is FfiCompanionEvent.FilePreview -> {
                val hex = event.fileId.toHexString()
                if (event.bytes != null) {
                    _filePreviews.update { it + (hex to event.bytes) }
                }
            }
            is FfiCompanionEvent.Avatar -> {
                val hex = event.chatId?.toHexString() ?: "mine"
                if (event.bytes != null) {
                    if (hex == "mine") {
                        _myAvatar.value = event.bytes
                    } else {
                        _contactAvatars.update { it + (hex to event.bytes) }
                    }
                } else {
                    if (hex == "mine") _myAvatar.value = null
                    else _contactAvatars.update { it - hex }
                }
            }
            is FfiCompanionEvent.FileGone -> {
                val hex = event.fileId.toHexString()
                _activeJobsFlow.update { it - hex }
                if (currentCompanionSaveFileId == hex) currentCompanionSaveFileId = null
                pendingCompanionSaves.remove(hex)
                pendingCompanionPaths.remove(hex)
            }
            is FfiCompanionEvent.FileProgress -> {
                val hex = event.fileId.toHexString()
                val progress = if (event.chunkTotal > 0uL) {
                    event.haveChunks.toFloat() / event.chunkTotal.toFloat()
                } else 0f
                _fileProgress.update { it + (hex to progress) }
            }
            is FfiCompanionEvent.FilesSent -> {
                android.util.Log.i("RatatoskVM", "Companion files sent: ${event.fileIds.size} files")
                val activeId = activeChatId
                if (activeId != null) {
                    loadMessages(activeId.hexToByteArray())
                }
                RatatoskCore.getCompanion().chats()
            }
            is FfiCompanionEvent.Refused -> {
                android.util.Log.w("RatatoskVM", "Companion refused command: ${event.reason}")
                if (!event.reason.contains("прежние просьбы") && !event.reason.contains("не отвечает на прежние")) {
                    _error.value = event.reason
                }
            }
            else -> {}
        }
    }

    private fun mapCompanionGroup(chat: FfiCompanionChat, existingGroup: FfiGroup?): FfiGroup {
        val chatIdHex = chat.chatId.toHexString()
        val oldMs = companionAvatarMs[chatIdHex] ?: 0UL
        if (chat.avatarMs != 0UL && chat.avatarMs != oldMs) {
            companionAvatarMs[chatIdHex] = chat.avatarMs
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    RatatoskCore.getCompanion().avatar(chat.chatId)
                } catch (e: Exception) { /* ignore */ }
            }
        }

        return FfiGroup(
            chatId = chat.chatId,
            title = chat.title,
            createdMs = existingGroup?.createdMs ?: chat.lastMs,
            members = existingGroup?.members ?: emptyList(),
            mine = existingGroup?.mine ?: false,
            joined = chat.joined,
            avatarMs = chat.avatarMs
        )
    }

    private fun mapCompanionChat(chat: FfiCompanionChat): FfiContact {
        val chatIdHex = chat.chatId.toHexString()
        val oldMs = companionAvatarMs[chatIdHex] ?: 0UL
        if (chat.avatarMs != 0UL && chat.avatarMs != oldMs) {
            companionAvatarMs[chatIdHex] = chat.avatarMs
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    RatatoskCore.getCompanion().avatar(chat.chatId)
                } catch (e: Exception) { /* ignore */ }
            }
        }

        return FfiContact(
            peerIk = chat.chatId,
            chatId = chat.chatId,
            fingerprint = "",
            displayName = chat.title,
            localName = null,
            verified = chat.verified,
            seenOnLan = false,
            hasAvatar = chat.avatarMs != 0UL,
            onion = null,
            chatmail = null,
            ygg = null,
            nostrRelays = emptyList(),
            cardVersion = 0UL,
            addedMs = 0UL,
            reachability = FfiReachability(emptyList(), null, null),
            directChannel = null,
            anomalies = FfiAnomalies(0UL, 0UL, 0UL, 0UL, 0UL)
        )
    }

    private fun mapCompanionMessage(msg: FfiCompanionMessage): FfiMessage {
        val grp = _groups.value.find { it.chatId.contentEquals(msg.chatId) }
        val member = if (grp != null && !msg.author.isNullOrBlank()) {
            grp.members.find { m ->
                m.name == msg.author ||
                _contacts.value.find { c -> c.peerIk.contentEquals(m.ik) }?.let { (it.localName ?: it.displayName) == msg.author } == true
            }
        } else null

        val authorIk = member?.ik

        if (authorIk != null && _contactAvatars.value[authorIk.toHexString()] == null && RatatoskCore.isCompanionMode()) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    RatatoskCore.getCompanion().avatar(authorIk)
                } catch (e: Exception) { /* ignore */ }
            }
        }

        val mappedSharedContact = msg.shared?.let { shared ->
            FfiSharedContact(
                peerIk = shared.chatId ?: ByteArray(0),
                displayName = shared.name,
                fingerprint = "",
                alreadyKnown = shared.chatId != null,
                mine = false
            )
        }

        return FfiMessage(
            msgId = msg.msgId,
            body = msg.body,
            mine = msg.mine,
            author = msg.author,
            authorIk = authorIk,
            wallMs = msg.wallMs,
            status = msg.status,
            editedAtMs = msg.editedAtMs,
            forwarded = msg.forwarded,
            reactions = msg.reactions.map { FfiReaction(it.emoji, if (it.mine) _fingerprint.value?.hexToByteArray() ?: ByteArray(0) else ByteArray(0), it.mine) },
            files = msg.files.map { mapCompanionAttachment(it, msg.mine) },
            replyTo = msg.replyTo,
            sharedContact = mappedSharedContact
        )
    }

    private fun mapCompanionAttachment(att: FfiCompanionAttachment, mine: Boolean): FfiFile {
        return FfiFile(
            fileId = att.fileId,
            name = att.name,
            sizeBytes = att.sizeBytes,
            incoming = !mine,
            accepted = att.accepted,
            complete = att.haveChunks == att.chunkTotal,
            receivedChunks = att.haveChunks,
            chunkTotal = att.chunkTotal,
            // Компаньон нарезку не сообщает, а свободную chunk_bytes()
            // сюда брать нельзя: она отвечает «каким куском режем мы
            // сейчас», а этот файл нарезал чужой аппарат и, возможно,
            // другой ступенью (FFI.md, §10.2). Ноль — «неизвестно»;
            // ход передачи мы всё равно считаем чанками, не байтами.
            chunkBytes = 0u,
            hasPreview = att.hasPreview
        )
    }

    private fun setupEngine() {
        android.util.Log.d("RatatoskVM", "Setting up engine components... Companion mode: ${RatatoskCore.isCompanionMode()}")
        _isCompanionMode.value = RatatoskCore.isCompanionMode()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (RatatoskCore.isCompanionMode()) {
                    setupCompanionEngine()
                    return@launch
                }

                android.util.Log.d("RatatoskVM", "Engine setup: getting fingerprint and limits")
                val client = RatatoskCore.getClient()
                val fingerprint = client.fingerprint()
                val ik = activeAccountId.value?.hexToByteArray()
                val maxAvatar = try { maxAvatarBytes().toInt() } catch (e: Exception) { 32768 }
                
                withContext(Dispatchers.Main) {
                    _myIk.value = ik
                    _fingerprint.value = fingerprint
                    _maxAvatarBytes.value = maxAvatar
                }

                // Collect events from core
                if (eventsJob == null) {
                    android.util.Log.d("RatatoskVM", "Starting event collection job")
                    eventsJob = viewModelScope.launch(Dispatchers.IO) {
                        RatatoskCore.events.collect { event ->
                            handleEvent(event)
                        }
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
                
                // Initial load of contacts, groups and messages (instant local DB queries)
                launch(Dispatchers.IO) {
                    try {
                        android.util.Log.d("RatatoskVM", "Engine setup: preloading contacts and groups from local DB")
                        val currentContacts = client.contacts()
                        val currentGroups = client.groups()
                        withContext(Dispatchers.Main) {
                            _contacts.value = currentContacts
                            _groups.value = currentGroups
                        }
                        
                        (currentContacts.map { it.chatId } + currentGroups.map { it.chatId }).forEach { chatId ->
                            launch(Dispatchers.IO) {
                                loadMessages(chatId)
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("RatatoskVM", "Failed to preload contacts/messages", e)
                    }
                }

                // Refresh transport status and paired devices in background without blocking UI
                launch(Dispatchers.IO) {
                    try {
                        refreshTransportStatus()
                        loadPairedDevices()
                    } catch (e: Exception) {
                        android.util.Log.e("RatatoskVM", "Failed background transport refresh", e)
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
                if (RatatoskCore.isCompanionMode()) {
                    RatatoskCore.getCompanion().chats()
                } else {
                    val contactList = RatatoskCore.getClient().contacts()
                    val groupList = RatatoskCore.getClient().groups()
                    withContext(Dispatchers.Main) {
                        _contacts.value = contactList
                        _groups.value = groupList
                    }
                    (contactList.map { it.chatId } + groupList.map { it.chatId }).forEach { chatId ->
                        launch(Dispatchers.IO) {
                            loadMessages(chatId)
                        }
                    }
                }
            } catch (e: Exception) {
                viewModelScope.launch {
                    _error.value = "Failed to refresh contacts: ${e.message}"
                }
            }
        }
    }

    fun loadMessages(chatId: ByteArray, limit: Int? = null) {
        if (RatatoskCore.isCompanionMode()) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    RatatoskCore.getCompanion().history(chatId, limit?.toUInt() ?: 100u, null)
                } catch (e: Exception) {
                    android.util.Log.e("RatatoskVM", "Failed to load companion messages", e)
                }
            }
            return
        }
        val chatIdHex = chatId.toHexString()
        val currentSize = _messages.value[chatIdHex]?.size ?: 0
        
        val targetLimit = when {
            limit != null -> limit
            currentSize > 0 -> maxOf(currentSize, 100)
            else -> 100
        }
        
        android.util.Log.d("RatatoskVM", "loadMessages for $chatIdHex, current: $currentSize, target: $targetLimit")
        
        // Одна загрузка на чат. Прежде каждый вызов запускал свою корутину,
        // и две наложившиеся приходили в произвольном порядке: медленная
        // выборка на 100 сообщений перезаписывала уже приехавшие 500,
        // и список на экране схлопывался. Зовут её часто — из событий,
        // из открытия чата, из «загрузить ещё».
        val load = viewModelScope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                if (!RatatoskCore.isInitialized()) {
                    android.util.Log.w("RatatoskVM", "loadMessages called but core not initialized")
                    return@launch
                }
                val msgs = RatatoskCore.getClient().messages(chatId, maxOf(targetLimit, 1).toUInt())
                android.util.Log.d("RatatoskVM", "Fetched ${msgs.size} messages for $chatIdHex")

                _messages.update { currentMap ->
                    currentMap + (chatIdHex to msgs)
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    android.util.Log.e("RatatoskVM", "Failed to load messages for $chatIdHex", e)
                }
            } finally {
                messageLoads.remove(chatIdHex, coroutineContext[Job])
            }
        }
        // Регистрируем до запуска и снимаем прежнюю — по тем же причинам,
        // что и у файловых задач.
        messageLoads.put(chatIdHex, load)?.cancel()
        load.start()
    }

    fun refreshAccounts() {
        _availableAccounts.value = RatatoskCore.listAccounts()
    }

    fun wipeAccount(id: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                RatatoskCore.wipeAccount(id)
                refreshAccounts()
            } catch (e: Exception) {
                _error.value = "Failed to wipe account: ${e.message}"
            }
        }
    }

    fun exportHistory(scope: FfiExportScope, phrase: String?, onResult: (FfiExported) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Каталог приложения на общем хранилище, а не публичные
                // «Загрузки». Архив пишет **ядро**, ему нужен настоящий путь
                // в файловой системе, поэтому MediaStore здесь не подходит,
                // а прямая запись в публичные «Загрузки» с Android 10
                // запрещена без разрешений, которых у приложения нет: она
                // отваливалась EACCES, а человек видел только «Export failed».
                //
                // Этот путь разрешений не требует и виден проводником:
                // Android/data/chat.ratatosk.android/files/Download/ratatosk_backups.
                val downloadsDir = getApplication<Application>()
                    .getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                    ?: getApplication<Application>().filesDir
                val ratatoskDir = java.io.File(downloadsDir, "ratatosk_backups")
                ratatoskDir.mkdirs()
                val fileName = "ratatosk_backup_${System.currentTimeMillis()}.db"
                val dest = java.io.File(ratatoskDir, fileName)
                
                val result = RatatoskCore.exportHistory(dest.absolutePath, scope, phrase)
                withContext(Dispatchers.Main) {
                    onResult(result)
                }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Export failed", e)
                withContext(Dispatchers.Main) {
                    _error.value = "Export failed: ${e.message}"
                }
            }
        }
    }

    fun importArchive(path: String, unlock: FfiArchiveUnlock, label: String, onResult: (Result<FfiImported>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = RatatoskCore.importArchive(getApplication(), path, unlock, label)
                withContext(Dispatchers.Main) {
                    refreshAccounts()
                    onResult(Result.success(result))
                }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Import failed", e)
                withContext(Dispatchers.Main) {
                    onResult(Result.failure(e))
                }
            }
        }
    }

    fun peekArchive(path: String, onResult: (FfiArchivePeek?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = RatatoskCore.peekArchive(path)
                withContext(Dispatchers.Main) {
                    onResult(result)
                }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Peek failed", e)
                withContext(Dispatchers.Main) {
                    _error.value = "Failed to peek archive: ${e.message}"
                    onResult(null)
                }
            }
        }
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
                    _isCompanionMode.value = false
                    settingsRepository.setLastAccountId(idHex)
                    setupEngine()
                    refreshAccounts()
                    _error.value = null
                }
            } catch (e: Exception) {
                _error.value = "Failed to initialize: ${e.message}"
            }
        }
    }

    fun initializeCompanion(inviteUri: String, port: Int, peerAddr: String?, cachePath: String?, label: String, torDir: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolvedCachePath = if (!cachePath.isNullOrBlank()) {
                    if (cachePath.startsWith("/")) cachePath
                    else java.io.File(getApplication<Application>().filesDir, cachePath).absolutePath
                } else null

                RatatoskCore.initializeCompanion(inviteUri, port.toUShort(), peerAddr, resolvedCachePath, torDir)
                currentCompanionLabel = label
                if (resolvedCachePath != null) {
                    settingsRepository.saveCompanionLink(
                        chat.ratatosk.android.data.CompanionLink(label, inviteUri, port, peerAddr, resolvedCachePath, torDir)
                    )
                }
                withContext(Dispatchers.Main) {
                    _isInitialized.value = true
                    _isCompanionMode.value = true
                    val id = "companion:${inviteUri.hashCode()}"
                    _activeAccountId.value = id
                    settingsRepository.setLastAccountId(id)
                    setupEngine()
                    _error.value = null
                }
            } catch (e: Exception) {
                _error.value = "Failed to link: ${e.message}"
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
                    _isCompanionMode.value = false
                    settingsRepository.setLastAccountId(idHex)
                    setupEngine()
                    _error.value = null
                }
            } catch (e: Exception) {
                if (pin == null && e is RatatoskException.Locked) {
                    // Account is locked and needs a PIN, we stay on the unlock screen
                    android.util.Log.d("RatatoskVM", "Account needs PIN to unlock")
                    return@launch
                }
                _error.value = "Failed to unlock: ${e.message}"
            }
        }
    }

    fun unlockCompanion(link: chat.ratatosk.android.data.CompanionLink) {
        initializeCompanion(link.inviteUri, link.port, link.peerAddr, link.cachePath, link.label, link.torDir)
    }

    fun removeCompanionLink(inviteUri: String) {
        viewModelScope.launch {
            settingsRepository.removeCompanionLink(inviteUri)
        }
    }

    fun logout() {
        RatatoskCore.logout()
        viewModelScope.launch {
            settingsRepository.setLastAccountId(null)
        }
        
        eventsJob?.cancel()
        eventsJob = null
        companionEventsJob?.cancel()
        companionEventsJob = null
        
        _isInitialized.value = false
        _isCompanionLinked.value = false
        currentCompanionLabel = null
        _activeAccountId.value = null
        _selectedAccount.value = null
        _isCreatingNewAccount.value = false
        
        // Clear all session state
        _activeChatIdFlow.value = null
        _activeContactIdFlow.value = null
        _contacts.value = emptyList()
        _messages.value = emptyMap()
        _messageStatuses.value = emptyMap()
        _unreadCounts.value = emptyMap()
        _fileProgress.value = emptyMap()
        _fileSending.value = emptyMap()
        _fileWaiting.value = emptyMap()
        _filePreviews.value = emptyMap()
        previewRequests.clear()
        // Расшифрованные копии вложений в кэше — вместе с сессией. Они
        // лежат открытым текстом, и переживать выход из аккаунта им незачем.
        try {
            FileUtils.clearDecryptedCaches(getApplication())
        } catch (e: Exception) {
            android.util.Log.w("RatatoskVM", "Failed to clear decrypted caches: ${e.message}")
        }
        _repliedMessages.value = emptyMap()
        _activeJobsFlow.value = emptySet()
        _searchResults.value = emptyList()
        _pairedDevices.value = emptyList()
        _pairingUri.value = null
        _myAvatar.value = null
        _contactAvatars.value = emptyMap()
        _torStatus.value = null
        _mailStatus.value = null
        _mailAccount.value = null
        
        companionAvatarMs.clear()
        pendingCompanionSaves.clear()
        pendingCompanionPaths.clear()
        currentCompanionSaveFileId = null
    }

    fun clearError() {
        _error.value = null
    }

    fun selectAccount(account: FfiAccount?) {
        _selectedAccount.value = account
        if (account != null) {
            // Automatically attempt to unlock with no PIN
            unlock(account, null)
        } else {
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
                if (RatatoskCore.isCompanionMode()) {
                    // Search not supported in companion mode yet
                    _searchResults.value = emptyList()
                } else {
                    val results = RatatoskCore.getClient().search(chatId, query, 50u)
                    _searchResults.value = results
                }
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
                if (RatatoskCore.isCompanionMode()) {
                    if (!_isCompanionLinked.value) {
                        withContext(Dispatchers.Main) {
                            _error.value = getApplication<Application>().getString(R.string.connecting_to_phone_cached)
                        }
                        return@launch
                    }
                    RatatoskCore.getCompanion().sendText(chatId, text)
                    loadMessages(chatId)
                } else {
                    RatatoskCore.getClient().sendText(chatId, text)
                    loadMessages(chatId)
                    delay(150)
                    loadMessages(chatId)
                }
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
                if (RatatoskCore.isCompanionMode()) {
                    if (!_isCompanionLinked.value) {
                        withContext(Dispatchers.Main) {
                            _error.value = getApplication<Application>().getString(R.string.connecting_to_phone_cached)
                        }
                        return@launch
                    }
                    val companionFiles = files.map { file ->
                        val preview = if (file.extension.lowercase() in listOf("jpg", "jpeg", "png", "webp")) {
                            generatePreview(file)
                        } else null
                        FfiCompanionOutgoing(file.absolutePath, preview)
                    }
                    RatatoskCore.getCompanion().sendFiles(chatId, companionFiles, text)
                    loadMessages(chatId)
                } else {
                    val outgoingFiles = files.map { file ->
                        val preview = if (file.extension.lowercase() in listOf("jpg", "jpeg", "png", "webp")) {
                            generatePreview(file)
                        } else null
                        FfiOutgoingFile(file.absolutePath, preview)
                    }
                    RatatoskCore.getClient().sendFiles(chatId, outgoingFiles, text)
                    loadMessages(chatId)
                    delay(150)
                    loadMessages(chatId)
                }
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
            
            val scaled = if (ratio >= 1.0f) bitmap else android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
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
                if (RatatoskCore.isCompanionMode()) {
                    RatatoskCore.getCompanion().acceptFile(fileId)
                } else {
                    RatatoskCore.getClient().acceptFile(fileId)
                    loadMessages(chatId)
                }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to accept file", e)
            }
        }
    }

    fun declineFile(chatId: ByteArray, fileId: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (RatatoskCore.isCompanionMode()) {
                    RatatoskCore.getCompanion().declineFile(fileId)
                } else {
                    RatatoskCore.getClient().declineFile(fileId)
                    loadMessages(chatId)
                }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to decline file", e)
            }
        }
    }

    // Просит превью вложения. Ответ кладётся в [filePreviews] — читать надо
    // оттуда, а не из возвращаемого значения: у компаньона байты приезжают
    // позже, событием FilePreview, и вернуть их отсюда нечем.
    //
    // Звать можно сколько угодно: уже полученное и уже заказанное
    // второй раз не спрашивается. Раньше эта функция возвращала байты
    // и звалась прямо из composable — экран читал снимок `.value`,
    // на который Compose не подписан, и превью не появлялось до тех пор,
    // пока что-нибудь другое не перерисует строку.
    fun requestFilePreview(fileId: ByteArray) {
        val hex = fileId.toHexString()
        if (_filePreviews.value.containsKey(hex)) return
        // Заказ уже в пути — второй ни к чему: composable зовёт нас
        // на каждую перерисовку.
        if (!previewRequests.add(hex)) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (RatatoskCore.isCompanionMode()) {
                    // Это запрос, а не чтение: байты приедут событием
                    // FfiCompanionEvent.FilePreview и лягут в _filePreviews.
                    RatatoskCore.getCompanion().preview(fileId)
                } else {
                    val bytes = RatatoskCore.getClient().previewOf(fileId)
                    if (bytes != null) {
                        _filePreviews.update { it + (hex to bytes) }
                    }
                    // bytes == null означает «превью у этого файла нет».
                    // Отметку заказа не снимаем: спрашивать снова незачем.
                }
            } catch (e: Exception) {
                // Отметку снимаем, чтобы следующая попытка состоялась:
                // ядро могло быть ещё не поднято.
                previewRequests.remove(hex)
                android.util.Log.w("RatatoskVM", "Failed to fetch preview for $hex: ${e.message}")
            }
        }
    }

    fun saveFile(file: FfiFile, destination: java.io.File, onComplete: (java.io.File) -> Unit) {
        val fileIdHex = file.fileId.toHexString()
        
        if (RatatoskCore.isCompanionMode()) {
            if (currentCompanionSaveFileId != null && currentCompanionSaveFileId != fileIdHex) {
                // Automatically cancel previous save if a new one is requested
                cancelFileJob(currentCompanionSaveFileId!!.hexToByteArray())
            }

            currentCompanionSaveFileId = fileIdHex
            pendingCompanionSaves[fileIdHex] = onComplete
            pendingCompanionPaths[fileIdHex] = destination.absolutePath
            _activeJobsFlow.update { it + fileIdHex }

            viewModelScope.launch(Dispatchers.IO) {
                try {
                    destination.parentFile?.mkdirs()
                    RatatoskCore.getCompanion().saveFile(file.fileId, file.chunkTotal, destination.absolutePath)
                } catch (e: Exception) {
                    android.util.Log.e("RatatoskVM", "Failed companion save", e)
                    pendingCompanionSaves.remove(fileIdHex)
                    pendingCompanionPaths.remove(fileIdHex)
                    _activeJobsFlow.update { it - fileIdHex }
                    if (currentCompanionSaveFileId == fileIdHex) currentCompanionSaveFileId = null
                }
            }
            return
        }

        // start = LAZY, потому что регистрация обязана произойти раньше, чем
        // задача успеет закончиться. При DEFAULT корутина уже бежала, и на
        // быстром отказе её finally снимал отметку до того, как строки ниже
        // её поставят: вложение навсегда оставалось с крутилкой «отменить».
        val job = viewModelScope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
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
                // По ключу **и значению**: по одному ключу нас могла уже
                // сменить следующая задача, и remove(key) снёс бы её
                // регистрацию — она качала бы дальше, но без прогресса
                // и без возможности отмены.
                if (activeJobs.remove(fileIdHex, coroutineContext[Job])) {
                    _activeJobsFlow.update { it - fileIdHex }
                }
            }
        }
        
        // Прежнюю задачу снимаем и **ждать её finally не нужно**: удаление
        // идёт по совпадению значения (см. ниже), так что её уборка нашу
        // регистрацию не затрёт.
        activeJobs.put(fileIdHex, job)?.cancel()
        _activeJobsFlow.update { it + fileIdHex }
        job.start()
    }

    fun downloadFile(file: FfiFile, onComplete: (String) -> Unit) {
        val fileIdHex = file.fileId.toHexString()

        if (RatatoskCore.isCompanionMode()) {
            val tempFile = java.io.File(getApplication<Application>().cacheDir, "downloads/${file.fileId.toHexString()}_${file.name}")
            saveFile(file, tempFile) { savedFile ->
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val dirUriString = downloadDirUri.value
                        val dirUri = dirUriString?.let { android.net.Uri.parse(it) }
                        
                        if (dirUri != null) {
                            val root = DocumentFile.fromTreeUri(getApplication(), dirUri)
                            if (root != null && root.canWrite()) {
                                val target = root.createFile("*/*", file.name)
                                if (target != null) {
                                    getApplication<Application>().contentResolver.openOutputStream(target.uri)?.use { output ->
                                        savedFile.inputStream().use { input ->
                                            input.copyTo(output)
                                        }
                                    }
                                    viewModelScope.launch { onComplete(file.name) }
                                    savedFile.delete()
                                    return@launch
                                }
                            }
                        }
                        
                        // Запасной путь, когда каталог через SAF не выбран.
                        // Через MediaStore: прямая запись в публичные
                        // «Загрузки» на Android 10+ запрещена без разрешений.
                        val sink = FileUtils.openDownloadSink(getApplication(), file.name)
                        if (sink == null) {
                            withContext(Dispatchers.Main) {
                                _error.value = "Не удалось сохранить файл в «Загрузки»"
                            }
                            savedFile.delete()
                            return@launch
                        }
                        sink.stream.use { output ->
                            savedFile.inputStream().use { input -> input.copyTo(output) }
                        }
                        savedFile.delete()
                        viewModelScope.launch { onComplete(sink.displayPath) }
                    } catch (e: Exception) {
                        android.util.Log.e("RatatoskVM", "Failed companion download copy", e)
                    }
                }
            }
            return
        }

        // start = LAZY, потому что регистрация обязана произойти раньше, чем
        // задача успеет закончиться. При DEFAULT корутина уже бежала, и на
        // быстром отказе её finally снимал отметку до того, как строки ниже
        // её поставят: вложение навсегда оставалось с крутилкой «отменить».
        val job = viewModelScope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
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
                
                // См. пояснение выше: только через MediaStore.
                val sink = FileUtils.openDownloadSink(getApplication(), file.name)
                if (sink == null) {
                    withContext(Dispatchers.Main) {
                        _error.value = "Не удалось сохранить файл в «Загрузки»"
                    }
                    return@launch
                }

                sink.stream.use { output ->
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
                viewModelScope.launch { onComplete(sink.displayPath) }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    android.util.Log.e("RatatoskVM", "Failed to download file", e)
                }
            } finally {
                reader?.destroy()
                // По ключу **и значению**: по одному ключу нас могла уже
                // сменить следующая задача, и remove(key) снёс бы её
                // регистрацию — она качала бы дальше, но без прогресса
                // и без возможности отмены.
                if (activeJobs.remove(fileIdHex, coroutineContext[Job])) {
                    _activeJobsFlow.update { it - fileIdHex }
                }
            }
        }
        
        // Прежнюю задачу снимаем и **ждать её finally не нужно**: удаление
        // идёт по совпадению значения (см. ниже), так что её уборка нашу
        // регистрацию не затрёт.
        activeJobs.put(fileIdHex, job)?.cancel()
        _activeJobsFlow.update { it + fileIdHex }
        job.start()
    }

    fun cancelFileJob(fileId: ByteArray) {
        val hex = fileId.toHexString()
        if (RatatoskCore.isCompanionMode()) {
            if (currentCompanionSaveFileId == hex) {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        RatatoskCore.getCompanion().cancelSave()
                    } catch (e: Exception) {
                        android.util.Log.e("RatatoskVM", "Failed to cancel companion save", e)
                    }
                }
                currentCompanionSaveFileId = null
                pendingCompanionSaves.remove(hex)
                pendingCompanionPaths.remove(hex)
                _activeJobsFlow.update { it - hex }
            }
            return
        }
        activeJobs[hex]?.cancel()
        activeJobs.remove(hex)
        _activeJobsFlow.update { it - hex }
    }

    fun sweepOrphanFiles(onResult: (FfiSwept) -> Unit) {
        if (RatatoskCore.isCompanionMode()) return
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
        if (RatatoskCore.isCompanionMode()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = RatatoskCore.getClient()
                val en = FfiTransport.values().associateWith { client.transportEnabled(it) }
                val re = FfiTransport.values().associateWith { client.transportReady(it) }
                val ts = client.torStatus()
                val ms = client.mailStatus()
                val ma = client.mailAccount()
                val ym = client.yggMode()
                val ykBytes = client.yggKey()
                val ykHex = if (ykBytes.isNotEmpty()) ykBytes.toHexString() else null
                val ya = if (ykBytes.isNotEmpty()) org.ratatosk.core.yggAddress(ykBytes) else null
                val yp = client.yggPeers()
                val ypa = try { client.yggPeersAlive() } catch (e: Exception) { null }
                val btRadio = RatatoskCore.hasBtRadio()
                val nr = try { client.nostrRelays() } catch (e: Exception) { emptyList() }
                val nra = try { client.nostrRelaysAlive() } catch (e: Exception) { null }
                val npub = try { client.nostrNpub() } catch (e: Exception) { "" }
                val nd = try { client.nostrDirect() } catch (e: Exception) { false }
                val nar = try { client.nostrAdvertisedRelays() } catch (e: Exception) { emptyList() }
                
                withContext(Dispatchers.Main) {
                    _transportsEnabled.value = en
                    _transportsReady.value = re
                    _torStatus.value = ts
                    _mailStatus.value = ms
                    _mailAccount.value = ma
                    _yggMode.value = ym
                    _yggKey.value = ykHex
                    _yggAddress.value = ya
                    _yggPeers.value = yp
                    _yggPeersAlive.value = ypa
                    _btHasRadio.value = btRadio
                    _nostrRelays.value = nr
                    _nostrRelaysAlive.value = nra
                    _nostrNpub.value = if (npub.isNotBlank()) npub else null
                    _nostrDirect.value = nd
                    _nostrAdvertisedRelays.value = nar
                }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to refresh transport status", e)
            }
        }
    }

    fun setTransportEnabled(transport: FfiTransport, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = RatatoskCore.getClient()
                client.setTransportEnabled(transport, enabled)
                client.networkChanged()
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

    fun setYggEnabled(enabled: Boolean) {
        setTransportEnabled(FfiTransport.YGG, enabled)
    }

    fun setYggMode(mode: FfiYggMode) {
        if (RatatoskCore.isCompanionMode()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = RatatoskCore.getClient()
                client.setYggMode(mode)
                client.setTransportEnabled(FfiTransport.YGG, mode != FfiYggMode.OFF)
                client.networkChanged()
                refreshTransportStatus()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = "Failed to set Yggdrasil mode: ${e.message}"
                }
            }
        }
    }

    fun setYggKey(keyHex: String) {
        if (RatatoskCore.isCompanionMode()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val clean = keyHex.trim().replace(" ", "").replace(":", "")
                val bytes = if (clean.isEmpty()) byteArrayOf() else clean.hexToByteArray()
                val client = RatatoskCore.getClient()
                client.setYggKey(bytes)
                client.networkChanged()
                refreshTransportStatus()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = "Failed to set Yggdrasil key: ${e.message ?: "Invalid key"}"
                }
            }
        }
    }

    fun setYggPeers(peers: List<String>) {
        if (RatatoskCore.isCompanionMode()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cleanedPeers = peers.map { it.trim() }.filter { it.isNotEmpty() }
                val client = RatatoskCore.getClient()
                client.setYggPeers(cleanedPeers)
                client.networkChanged()
                refreshTransportStatus()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = "Failed to set Yggdrasil peers: ${e.message}"
                }
            }
        }
    }

    fun setNostrRelays(relays: List<String>) {
        if (RatatoskCore.isCompanionMode()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cleanedRelays = relays.map { it.trim() }.filter { it.isNotEmpty() }
                val client = RatatoskCore.getClient()
                client.setNostrRelays(cleanedRelays)
                client.networkChanged()
                refreshTransportStatus()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = "Failed to set Nostr relays: ${e.message}"
                }
            }
        }
    }

    fun setNostrDirect(direct: Boolean) {
        if (RatatoskCore.isCompanionMode()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = RatatoskCore.getClient()
                client.setNostrDirect(direct)
                client.networkChanged()
                refreshTransportStatus()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = "Failed to set Nostr direct mode: ${e.message}"
                }
            }
        }
    }

    fun getNostrWarning(): String = try { org.ratatosk.core.nostrWarning() } catch (e: Exception) { "" }
    fun getNostrDirectWarning(): String = try { org.ratatosk.core.nostrDirectWarning() } catch (e: Exception) { "" }
    fun getNostrNoFilesNotice(): String = try { org.ratatosk.core.nostrNoFilesNotice() } catch (e: Exception) { "" }

    fun addContact(uri: String, metInPerson: Boolean) {
        if (RatatoskCore.isCompanionMode()) return
        viewModelScope.launch(Dispatchers.IO) {
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
                    _groups.value = groupList
                    if (addedContact != null) {
                        setActiveContact(addedContact.chatId)
                    }
                }

                (contactList.map { it.chatId } + groupList.map { it.chatId }).forEach { chatId ->
                    launch(Dispatchers.IO) {
                        loadMessages(chatId)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = "Failed to add contact: ${e.message}"
                }
            }
        }
    }

    fun createGroup(title: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (RatatoskCore.isCompanionMode()) {
                    RatatoskCore.getCompanion().createGroup(title)
                } else {
                    RatatoskCore.getClient().createGroup(title)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = "Failed to create group: ${e.message}"
                }
            }
        }
    }

    fun renameGroup(chatId: ByteArray, title: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (RatatoskCore.isCompanionMode()) {
                    RatatoskCore.getCompanion().renameGroup(chatId, title)
                } else {
                    RatatoskCore.getClient().renameGroup(chatId, title)
                    refreshContacts()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = "Failed to rename group: ${e.message}"
                }
            }
        }
    }

    fun inviteToGroup(chatId: ByteArray, peerIk: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (RatatoskCore.isCompanionMode()) {
                    RatatoskCore.getCompanion().inviteToGroup(chatId, peerIk)
                    RatatoskCore.getCompanion().members(chatId)
                } else {
                    RatatoskCore.getClient().inviteToGroup(chatId, peerIk)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = "Failed to invite: ${e.message}"
                }
            }
        }
    }

    fun evictFromGroup(chatId: ByteArray, peerIk: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (RatatoskCore.isCompanionMode()) {
                    RatatoskCore.getCompanion().evictFromGroup(chatId, peerIk)
                    RatatoskCore.getCompanion().members(chatId)
                } else {
                    RatatoskCore.getClient().evictFromGroup(chatId, peerIk)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = "Failed to evict: ${e.message}"
                }
            }
        }
    }

    fun leaveGroup(chatId: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (RatatoskCore.isCompanionMode()) {
                    RatatoskCore.getCompanion().leaveGroup(chatId)
                } else {
                    RatatoskCore.getClient().leaveGroup(chatId)
                    refreshContacts()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = "Failed to leave group: ${e.message}"
                }
            }
        }
    }

    fun loadCompanionMembers(chatId: ByteArray) {
        if (RatatoskCore.isCompanionMode()) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    RatatoskCore.getCompanion().members(chatId)
                } catch (e: Exception) {
                    android.util.Log.e("RatatoskVM", "Failed to load companion members", e)
                }
            }
        }
    }

    fun getGroupJoinNotice(): String {
        return try {
            groupJoinNotice()
        } catch (e: Exception) {
            ""
        }
    }

    fun getEvictionNotice(): String {
        return try {
            evictionNotice()
        } catch (e: Exception) {
            ""
        }
    }

    fun getDeletionNotice(): String {
        return try {
            deletionNotice()
        } catch (e: Exception) {
            ""
        }
    }

    fun getEditNotice(): String {
        return try {
            editNotice()
        } catch (e: Exception) {
            ""
        }
    }

    fun getForwardNotice(): String {
        return try {
            forwardNotice()
        } catch (e: Exception) {
            ""
        }
    }

    fun getLeaveNotice(): String {
        return try {
            leaveNotice()
        } catch (e: Exception) {
            ""
        }
    }

    fun getOwnerLeaveNotice(): String {
        return try {
            ownerLeaveNotice()
        } catch (e: Exception) {
            ""
        }
    }

    fun getMaxGroupTitleChars(): UInt {
        return try {
            maxGroupTitleChars()
        } catch (e: Exception) {
            255u
        }
    }

    fun addSharedContact(msgId: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (RatatoskCore.isCompanionMode()) {
                    if (!_isCompanionLinked.value) return@launch
                    RatatoskCore.getCompanion().addSharedContact(msgId)
                    refreshContacts()
                } else {
                    RatatoskCore.getClient().addSharedContact(msgId)
                    refreshContacts()
                }
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
                if (RatatoskCore.isCompanionMode()) {
                    if (!_isCompanionLinked.value) {
                        withContext(Dispatchers.Main) {
                            _error.value = getApplication<Application>().getString(R.string.connecting_to_phone_cached)
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
                withContext(Dispatchers.Main) {
                    _error.value = "Failed to share contact: ${e.message}"
                }
            }
        }
    }

    private val _myContactUri = MutableStateFlow<String?>(null)
    val myContactUri = _myContactUri.asStateFlow()

    fun getMyContactUri() {
        if (RatatoskCore.isCompanionMode()) {
            _myContactUri.value = null
            return
        }
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
        if (RatatoskCore.isCompanionMode()) return
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
        if (RatatoskCore.isCompanionMode()) return
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
                if (RatatoskCore.isCompanionMode()) {
                    RatatoskCore.getCompanion().clearChat(chatId)
                } else {
                    RatatoskCore.getClient().clearChat(chatId)
                }
                _messages.update { it + (chatId.toHexString() to emptyList()) }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to clear chat", e)
            }
        }
    }

    fun deleteMessages(chatId: ByteArray, msgIds: List<ByteArray>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (RatatoskCore.isCompanionMode()) {
                    RatatoskCore.getCompanion().deleteMessages(chatId, msgIds)
                } else {
                    RatatoskCore.getClient().deleteMessages(chatId, msgIds)
                    loadMessages(chatId)
                }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to delete messages", e)
            }
        }
    }

    fun retractMessages(chatId: ByteArray, msgIds: List<ByteArray>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (RatatoskCore.isCompanionMode()) {
                    RatatoskCore.getCompanion().retractMessages(chatId, msgIds)
                } else {
                    RatatoskCore.getClient().retractMessages(chatId, msgIds)
                    loadMessages(chatId)
                }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to retract messages", e)
            }
        }
    }

    fun editMessage(chatId: ByteArray, msgId: ByteArray, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (RatatoskCore.isCompanionMode()) {
                    RatatoskCore.getCompanion().editMessage(chatId, msgId, text)
                } else {
                    RatatoskCore.getClient().editMessage(chatId, msgId, text)
                    loadMessages(chatId)
                }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to edit message", e)
            }
        }
    }

    fun reply(chatId: ByteArray, replyTo: ByteArray, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (RatatoskCore.isCompanionMode()) {
                    if (!_isCompanionLinked.value) {
                        withContext(Dispatchers.Main) {
                            _error.value = getApplication<Application>().getString(R.string.connecting_to_phone_cached)
                        }
                        return@launch
                    }
                    RatatoskCore.getCompanion().sendReply(chatId, replyTo, text)
                    loadMessages(chatId)
                } else {
                    RatatoskCore.getClient().reply(chatId, replyTo, text)
                    loadMessages(chatId)
                }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to reply", e)
            }
        }
    }

    fun forwardMessages(chatId: ByteArray, msgIds: List<ByteArray>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (RatatoskCore.isCompanionMode()) {
                    if (!_isCompanionLinked.value) {
                        withContext(Dispatchers.Main) {
                            _error.value = getApplication<Application>().getString(R.string.connecting_to_phone_cached)
                        }
                        return@launch
                    }
                    RatatoskCore.getCompanion().forwardMessages(chatId, msgIds)
                    loadMessages(chatId)
                } else {
                    RatatoskCore.getClient().forwardMessages(chatId, msgIds)
                    loadMessages(chatId)
                }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to forward", e)
                withContext(Dispatchers.Main) {
                    _error.value = "Failed to forward: ${e.message}"
                }
            }
        }
    }

    fun markRead(chatId: ByteArray, upTo: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (RatatoskCore.isCompanionMode()) {
                    RatatoskCore.getCompanion().markRead(chatId, upTo)
                } else {
                    RatatoskCore.getClient().markRead(chatId, upTo)
                }
                _unreadCounts.update { it + (chatId.toHexString() to 0) }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to mark read", e)
            }
        }
    }

    fun markVerified(peerIk: ByteArray) {
        if (RatatoskCore.isCompanionMode()) return
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
        if (RatatoskCore.isCompanionMode()) return
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
                if (RatatoskCore.isCompanionMode()) {
                    RatatoskCore.getCompanion().setReaction(chatId, msgId, emoji ?: "")
                } else {
                    RatatoskCore.getClient().setReaction(chatId, msgId, emoji)
                    loadMessages(chatId)
                }
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
                if (RatatoskCore.isCompanionMode()) {
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

    fun setMyAvatar(bytes: ByteArray?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (RatatoskCore.isCompanionMode()) {
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

    fun setAvatar(bytes: ByteArray?) {
        setMyAvatar(bytes)
    }

    fun getGroupAvatar(chatId: ByteArray): ByteArray? {
        val hex = chatId.toHexString()
        _contactAvatars.value[hex]?.let { return it }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (RatatoskCore.isCompanionMode()) {
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

    fun setGroupAvatar(chatId: ByteArray, bytes: ByteArray?) {
        val hex = chatId.toHexString()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (RatatoskCore.isCompanionMode()) {
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
                    if (!RatatoskCore.isCompanionMode()) {
                        refreshContacts()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to set group avatar", e)
            }
        }
    }

    fun setPendingAvatarUri(uri: android.net.Uri?, chatId: ByteArray? = null) {
        _pendingAvatarUri.value = uri
        _pendingAvatarChatId.value = chatId
    }

    fun getMessage(msgId: ByteArray): FfiMessage? {
        val hex = msgId.toHexString()
        _repliedMessages.value[hex]?.let { return it }
        
        if (RatatoskCore.isCompanionMode()) return null

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val msg = RatatoskCore.getClient().message(msgId)
                _repliedMessages.update { it + (hex to msg) }
            } catch (e: Exception) { /* ignore */ }
        }
        return null
    }

    fun setAutoAcceptLimit(limit: ULong?) {
        if (RatatoskCore.isCompanionMode()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                RatatoskCore.getClient().setAutoAcceptBytes(limit)
                _autoAcceptLimit.value = limit
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to set auto-accept limit", e)
            }
        }
    }

    fun loadPairedDevices() {
        if (RatatoskCore.isCompanionMode()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!RatatoskCore.isInitialized()) return@launch
                val devices = RatatoskCore.getClient().devices()
                withContext(Dispatchers.Main) {
                    _pairedDevices.value = devices
                }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to load paired devices", e)
            }
        }
    }

    fun startPairing(label: String) {
        if (RatatoskCore.isCompanionMode()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!RatatoskCore.isInitialized()) return@launch
                android.util.Log.d("RatatoskVM", "Starting pairing for label: $label")
                _pairingUri.value = null
                RatatoskCore.getClient().pairDevice(label)
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to start pairing", e)
                withContext(Dispatchers.Main) {
                    _error.value = "Failed to start pairing: ${e.message}"
                }
            }
        }
    }

    fun stopPairing() {
        _pairingUri.value = null
    }

    fun revokePairing(deviceId: ByteArray) {
        if (RatatoskCore.isCompanionMode()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!RatatoskCore.isInitialized()) return@launch
                RatatoskCore.getClient().revokePairing(deviceId)
                loadPairedDevices()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = "Failed to revoke: ${e.message}"
                }
            }
        }
    }

    fun setActiveChat(chatId: ByteArray?) {
        _activeChatIdFlow.value = chatId
        if (chatId != null) {
            _activeContactIdFlow.value = null
            activeChatId = chatId.toHexString()
            _unreadCounts.update { it + (chatId.toHexString() to 0) }
            loadMessages(chatId)
        } else {
            activeChatId = null
        }
    }

    fun setActiveContact(chatId: ByteArray?) {
        _activeContactIdFlow.value = chatId
        if (chatId != null) {
            _activeChatIdFlow.value = null
            activeChatId = null
        }
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
        val fileId = _activeMediaFile.value?.fileId
        if (fileId != null && RatatoskCore.isCompanionMode()) {
            cancelFileJob(fileId)
        }
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
                
                val targetChatHex = _messages.value.entries.find { entry ->
                    entry.value.any { it.msgId.contentEquals(event.msgId) }
                }?.key ?: activeChatId

                if (targetChatHex != null) {
                    loadMessages(targetChatHex.hexToByteArray())
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
                    } catch (t: Throwable) {
                        android.util.Log.w("RatatoskVM", "Failed to refresh avatar for ${event.peerIk.toHexString()}: ${t.message}")
                    }
                }
            }
            is FfiEvent.FileWaitsForChannel -> {
                _fileWaiting.update { it + (event.fileId.toHexString() to event.reason) }
            }
            is FfiEvent.FileGone -> {
                val hex = event.fileId.toHexString()
                // Вложения больше нет — и ожидания вместе с ним.
                _fileWaiting.update { it - hex }
                _fileProgress.update { it - hex }
                _fileSending.update { it - hex }
                // Строку вложения надо **убрать**, а не обнулить в ней
                // числа: само сообщение остаётся, текст к отвергнутой
                // картинке никуда не делся. Список сообщений об этом
                // не знает, поэтому перечитываем открытый чат.
                activeChatId?.let { loadMessages(it.hexToByteArray()) }
            }
            is FfiEvent.FileSending -> {
                // «Отдано транспорту», а не «доставлено»: говорить про
                // доставку этими числами нельзя (§14, FFI.md).
                val hex = event.fileId.toHexString()
                val progress = if (event.total > 0UL) {
                    event.sent.toFloat() / event.total.toFloat()
                } else 0f
                _fileSending.update { it + (hex to progress) }
                // Ядро называет снимающим ожидание только FileProgress,
                // но он про приём. У отдачи движение видно отсюда, и
                // держать «стоит» поверх идущей отправки было бы враньём.
                _fileWaiting.update { it - hex }
            }
            is FfiEvent.FileProgress -> {
                val hex = event.fileId.toHexString()
                // Ход передачи и означает, что она пошла: ядро прямо
                // говорит, что это событие снимает ожидание.
                _fileWaiting.update { it - hex }
                val progress = if (event.total > 0UL) event.received.toFloat() / event.total.toFloat() else 0f
                _fileProgress.update { it + (hex to progress) }
                
                if (event.received == event.total) {
                    // Перечитываем чат, чтобы у FfiFile обновился `complete`.
                    //
                    // FileProgress — единственный сигнал о завершении: ядро
                    // шлёт его на каждый чанк «и на завершение», отдельного
                    // события «файл собран» нет. Сам объект FfiFile приехал
                    // вместе со списком сообщений и об этом не знает.
                    //
                    // Раньше здесь стояло ещё и `&& isCompanionMode()`, то
                    // есть в обычном режиме — основном — статус не обновлялся
                    // вовсе: вложение до перезахода в чат висело с крутилкой
                    // и кнопками «принять/отклонить», а «открыть» и
                    // «сохранить» не появлялись.
                    //
                    // Условие `received == total` нарочно без `total > 0`:
                    // у пустого файла оба нуля, и это тоже завершение.
                    activeChatId?.let { loadMessages(it.hexToByteArray()) }
                }
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
            is FfiEvent.PairingReady -> {
                android.util.Log.i("RatatoskVM", "Pairing ready event received. URI: ${event.uri}")
                _pairingUri.value = event.uri
                loadPairedDevices()
            }
            is FfiEvent.PairingRevoked -> {
                android.util.Log.i("RatatoskVM", "Pairing revoked event for device: ${event.deviceId.toHexString()}")
                loadPairedDevices()
            }
            is FfiEvent.DeviceLink -> {
                android.util.Log.i("RatatoskVM", "Device ${event.deviceId.toHexString()} link status: ${event.connected}")
                loadPairedDevices()
            }
            is FfiEvent.GroupCreated -> {
                android.util.Log.i("RatatoskVM", "Group created: ${event.chatId.toHexString()} (${event.title})")
                refreshContacts()
                loadMessages(event.chatId)
            }
            is FfiEvent.GroupRenamed -> {
                android.util.Log.i("RatatoskVM", "Group renamed: chat=${event.chatId.toHexString()} title=${event.title}")
                refreshContacts()
                loadMessages(event.chatId)
            }
            is FfiEvent.GroupMembershipChanged -> {
                android.util.Log.i("RatatoskVM", "Group membership changed: chat=${event.chatId.toHexString()}")
                refreshContacts()
                loadMessages(event.chatId)
            }
            is FfiEvent.GroupAvatarChanged -> {
                val hex = event.chatId.toHexString()
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val bytes = RatatoskCore.getClient().groupAvatar(event.chatId)
                        withContext(Dispatchers.Main) {
                            if (bytes != null) {
                                _contactAvatars.update { it + (hex to bytes) }
                            } else {
                                _contactAvatars.update { it - hex }
                            }
                        }
                    } catch (t: Throwable) {
                        android.util.Log.w("RatatoskVM", "Failed to refresh group avatar for ${event.chatId.toHexString()}: ${t.message}")
                    }
                }
            }
            else -> {}
        }
    }
}
