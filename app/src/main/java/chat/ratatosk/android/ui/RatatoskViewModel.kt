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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import org.ratatosk.core.*
import java.util.concurrent.ConcurrentHashMap

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RatatoskViewModel private constructor(
    application: Application,
    private val models: chat.ratatosk.android.ui.model.AppModels,
) : AndroidViewModel(application),
    // Разрезка: часть работы уехала в модели по назначению, а экраны
    // по-прежнему зовут `viewModel.x` — интерфейсы моделей делегируются.
    chat.ratatosk.android.ui.model.BackupApi by models.backup,
    chat.ratatosk.android.ui.model.PairingApi by models.pairing,
    chat.ratatosk.android.ui.model.TransportsApi by models.transports,
    chat.ratatosk.android.ui.model.GroupsApi by models.groups,
    chat.ratatosk.android.ui.model.ContactsApi by models.contacts,
    chat.ratatosk.android.ui.model.FilesApi by models.files {

    constructor(application: Application) : this(application, chat.ratatosk.android.ui.model.AppModels(application))

    init {
        models.onAccountsChanged = { refreshAccounts() }
        models.onContactsChanged = { refreshContacts() }
        models.onLoadMessages = { loadMessages(it) }
        models.onOpenContact = { setActiveContact(it) }
    }

    override fun onCleared() {
        models.close()
        super.onCleared()
    }

    private val settingsRepository = SettingsRepository(application)
    
    private val _events = MutableStateFlow<List<FfiEvent>>(emptyList())
    val events = _events.asStateFlow()



    private val _messages = MutableStateFlow<Map<String, List<FfiMessage>>>(emptyMap())
    val messages = _messages.asStateFlow()

    private val _messageStatuses = MutableStateFlow<Map<String, FfiDeliveryStatus>>(emptyMap())
    val messageStatuses = _messageStatuses.asStateFlow()

    private val _repliedMessages = MutableStateFlow<Map<String, FfiMessage?>>(emptyMap())
    val repliedMessages = _repliedMessages.asStateFlow()

    private val _unreadCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unreadCounts = _unreadCounts.asStateFlow()


    // Полоса **у отправителя**: сколько чанков отдано транспорту.
    //
    // Отдельно от fileProgress, и это не дубль: то — ход приёма у нас,
    // это — ход отдачи наружу. Раньше исходящий файл питался чужим
    // событием и потому не двигался вовсе.

    /**
     * Сколько вложения уже легло **на это устройство**, по `fileId` в hex.
     *
     * Это не то же, что [fileProgress]: там — сколько собрал владелец файла
     * (у компаньона это телефон, и у готового файла всегда сто процентов),
     * а здесь — ход выкладывания сюда. Событий на каждый кусок ядро не даёт,
     * поэтому доля берётся из растущего файла на диске.
     */

    /** Приём сюда ждёт связи с телефоном, а не сорвался (`FetchPaused`). */

    // Почему передача файла стоит (§10.3). Состояние, а не происшествие:
    // показывать его надо на самом файле, пока оно держится, а не
    // всплывающей подсказкой — ждать файл может столько, сколько
    // собеседник вне сети.




    // Идущие загрузки истории, по одной на чат. См. loadMessages.
    private val messageLoads = ConcurrentHashMap<String, Job>()

    private val _searchResults = MutableStateFlow<List<FfiMessage>>(emptyList())
    val searchResults = _searchResults.asStateFlow()






    private val _isSearching = MutableStateFlow(false)
    val isSearching = _isSearching.asStateFlow()

    /** Идёт открытие аккаунта: вывод ключа из PIN занимает заметные секунды. */
    private val _isOpening = MutableStateFlow(false)
    val isOpening: StateFlow<Boolean> = _isOpening.asStateFlow()

    /** Тихая попытка открыть без PIN не удалась — теперь его надо спросить. */
    private val _pinRequired = MutableStateFlow(false)
    val pinRequired: StateFlow<Boolean> = _pinRequired.asStateFlow()

    private val _isFindingHidden = MutableStateFlow(false)
    val isFindingHidden = _isFindingHidden.asStateFlow()

    val activeAccountId = models.session.activeAccountId

    private val _availableAccounts = MutableStateFlow<List<FfiAccount>>(emptyList())
    val availableAccounts = _availableAccounts.asStateFlow()

    private val _selectedAccount = MutableStateFlow<FfiAccount?>(null)
    val selectedAccount = _selectedAccount.asStateFlow()

    private val _isCreatingNewAccount = MutableStateFlow(false)
    val isCreatingNewAccount = _isCreatingNewAccount.asStateFlow()

    private val _isCompanionMode = MutableStateFlow<Boolean>(RatatoskCore.isCompanionMode())
    val isCompanionMode: StateFlow<Boolean> = _isCompanionMode.asStateFlow()

    val isCompanionLinked = models.session.isCompanionLinked

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




    /**
     * Показанное приехало с телефона (`true`) или поднято из кэша (`false`).
     *
     * Это не то же, что «есть связь»: связь может быть, а список ещё из кэша.
     */
    private val _isCompanionFresh = MutableStateFlow(false)
    val isCompanionFresh: StateFlow<Boolean> = _isCompanionFresh.asStateFlow()

    /** Наблюдатели за растущими файлами: по одному на сохранение. */


    private var activeChatId: String? = null
    private var currentCompanionLabel: String? = null

    private val _honestNotices = MutableStateFlow<List<String>>(emptyList())
    val honestNotices = _honestNotices.asStateFlow()
    
    private val _isInitialized = MutableStateFlow(RatatoskCore.isInitialized())
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _accountExists = MutableStateFlow(false)
    val accountExists: StateFlow<Boolean> = _accountExists.asStateFlow()
    


    /**
     * То, чем с нами поделились из другого приложения, уже сложенное
     * в файлы и ждущее своего чата.
     *
     * Черновиком, а не отправкой: человек выбрал получателя в чужом
     * окне «поделиться», и показать ему, что и куда уходит, надо до
     * отправки, а не после. Заодно он успеет добавить подпись.
     */
    data class SharedDraft(
        val chatIdHex: String,
        val text: String,
        val files: List<java.io.File>
    )

    private val _sharedDraft = MutableStateFlow<SharedDraft?>(null)
    val sharedDraft: StateFlow<SharedDraft?> = _sharedDraft.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()



    val userName = activeAccountId.flatMapLatest { id ->
        when {
            id == null -> flowOf(null)
            id.startsWith("companion:") -> flowOf(currentCompanionLabel)
            else -> settingsRepository.getDisplayName(id)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)



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
            withContext(Dispatchers.Main) { models.transports.onBtRadio(has) }
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

    // Журнал ядра в файл: собирать или нет. Общий на приложение.
    val coreFileLog = settingsRepository.coreFileLog
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setCoreFileLog(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setCoreFileLog(enabled) }
    }

    /** Файл журнала — чтобы экран мог показать размер и отдать его наружу. */
    fun coreLogFile(): java.io.File = RatatoskCore.coreLogFile(getApplication())

    fun btMissingPermissions(): List<String> =
        org.ratatosk.bt.BtRadio.Permissions.missing(getApplication())

    val nostrEnabled = transportsEnabled.map { it[FfiTransport.NOSTR] ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)











    private val _onionAddress = MutableStateFlow<String?>(null)
    val onionAddress = _onionAddress.asStateFlow()

    private val _cardVersion = MutableStateFlow<ULong?>(null)
    val cardVersion = _cardVersion.asStateFlow()

    init {
        // Журнал ядра — первым делом, раньше всего остального: подписчик
        // ставится один раз на процесс, и всё, что ядро скажет до этого,
        // пропадёт. Читать настройку приходится здесь, а не внутри ядра:
        // она в DataStore, то есть достаётся корутиной.
        viewModelScope.launch(Dispatchers.IO) {
            val toFile = try {
                settingsRepository.coreFileLog.first()
            } catch (e: Exception) {
                false
            }
            RatatoskCore.startLogging(application, toFile)
        }

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
                android.util.Log.w("RatatoskVM", "Failed to open registry", e)
                _error.value = e.message?.takeIf { it.isNotBlank() }
                    ?: getApplication<Application>().getString(R.string.error_accounts_unavailable)
            }
        }

        // Monitor core initialization status
        viewModelScope.launch {
            while (true) {
                if (!_isInitialized.value && RatatoskCore.isInitialized()) {
                    android.util.Log.i("RatatoskVM", "Core initialized externally, setting up engine")
                    _isInitialized.value = true
                    models.session._activeAccountId.value = RatatoskCore.getActiveAccountId()
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
                val fingerprint = companion.deviceId().toHexString()
                val phoneName = try { companion.phoneName() } catch (e: Exception) { "Companion" }
                
                withContext(Dispatchers.Main) {
                    models.contacts.setFingerprint(fingerprint)
                    if (currentCompanionLabel == null) currentCompanionLabel = phoneName
                    _isInitialized.value = true
                    models.session._isCompanionLinked.value = false
                }

                android.util.Log.d("RatatoskVM", "Initial companion chats() call for cache")
                companion.chats()
                // Своё лицо здесь не спрашиваем: телефона на линии ещё нет,
                // и запрос уходит в пустоту. Его место — в ветке `Linked`
                // («Свою — раз за подключение», FFI о `avatar`).
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to setup companion engine", e)
            }
        }
    }

    private fun handleCompanionEvent(event: FfiCompanionEvent) {
        when (event) {
            is FfiCompanionEvent.Chats -> {
                android.util.Log.d("RatatoskVM", "Companion received ${event.chats.size} chats, fresh=${event.fresh}")
                _isCompanionFresh.value = event.fresh
                val (groupChats, contactChats) = event.chats.partition { it.isGroup }

                val mappedContacts = contactChats.map { mapCompanionChat(it) }
                models.contacts.setContacts(mappedContacts)

                val existingGroupsMap = models.groups.currentGroups().associateBy { it.chatId.toHexString() }
                val mappedGroups = groupChats.map { chat ->
                    mapCompanionGroup(chat, existingGroupsMap[chat.chatId.toHexString()])
                }
                models.groups.setGroups(mappedGroups)

                event.chats.forEach { chat ->
                    val hex = chat.chatId.toHexString()
                    if (chat.isGroup && models.session._isCompanionLinked.value) {
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
                models.groups.updateGroups { currentGroups ->
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
                    !member.mine && models.contacts.currentContacts().none { it.chatId.contentEquals(member.chatId) }
                }.map { member ->
                    FfiContact(
                        peerIk = member.chatId,
                        chatId = member.chatId,
                        fingerprint = "",
                        displayName = member.name,
                        localName = null,
                        verified = false,
                        // Присутствие у компаньона не спрашивают: эфиры
                        // слушает телефон, а не пара. Оба признака ложны,
                        // и это правда, а не заглушка.
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
                        anomalies = FfiAnomalies(0UL, 0UL, 0UL, 0UL, 0UL)
                    )
                }
                if (memberContacts.isNotEmpty()) {
                    models.contacts.updateContacts { currentContacts ->
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
                    models.contacts.setAvatarStamp(hex, event.avatarMs)
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            RatatoskCore.getCompanion().avatar(event.chatId)
                        } catch (e: Exception) { /* ignore */ }
                    }
                } else {
                    models.contacts.removeAvatarStamp(hex)
                    if (hex == "mine") models.contacts.onOwnAvatar(null)
                    else models.contacts.removeAvatar(hex)
                }
            }
            is FfiCompanionEvent.History -> {
                android.util.Log.d("RatatoskVM", "Companion received ${event.page.size} messages for chat ${event.chatId.toHexString()}, fresh=${event.fresh}")
                _isCompanionFresh.value = event.fresh
                val mappedMessages = event.page.map { mapCompanionMessage(it) }
                val chatIdHex = event.chatId.toHexString()
                _messages.update { it + (chatIdHex to mappedMessages) }
            }
            is FfiCompanionEvent.Arrived -> {
                loadMessages(event.message.chatId)
            }
            is FfiCompanionEvent.Linked -> {
                android.util.Log.i("RatatoskVM", "Companion LINKED")
                models.session._isCompanionLinked.value = true
                RatatoskCore.getCompanion().chats()
                // Телефон на линии — самое время спросить своё лицо: метки для
                // сравнения у него нет, поэтому спрашиваем раз за подключение.
                try {
                    RatatoskCore.getCompanion().avatar(null)
                } catch (e: Exception) { /* ignore */ }
            }
            is FfiCompanionEvent.Unlinked -> {
                android.util.Log.w("RatatoskVM", "Companion UNLINKED")
                models.session._isCompanionLinked.value = false
                // Телефон ушёл со связи — `FileSaved` уже не придёт.
                models.files.failPendingSaves(getApplication<Application>().getString(R.string.companion_offline))
            }
            is FfiCompanionEvent.Revoked -> {
                // Сопряжение отозвано: объект жив, но на любую команду отвечает
                // отказом. Молчать об этом нельзя — окно иначе вечно «подключается».
                android.util.Log.w("RatatoskVM", "Companion REVOKED")
                models.session._isCompanionLinked.value = false
                models.files.failPendingSaves(getApplication<Application>().getString(R.string.companion_revoked))
            }
            is FfiCompanionEvent.FileSaved -> {
                models.files.finishSave(event.fileId.toHexString(), event.path, deliver = true)
            }
            // Приём сюда не сорвался, а ждёт: записанное лежит на диске
            // и допишется с того же места (FFI, FetchPaused).
            is FfiCompanionEvent.FetchPaused -> {
                android.util.Log.i("RatatoskVM", "Companion fetch paused")
                models.files.setFetchPaused(true)
            }
            is FfiCompanionEvent.FetchResumed -> {
                models.files.setFetchPaused(false)
                val hex = models.files.currentSaveFileId()
                if (hex != null && event.total > 0uL) {
                    val done = (event.done.toFloat() / event.total.toFloat()).coerceIn(0f, 1f)
                    models.files.onSaveProgress(hex, done)
                }
            }
            is FfiCompanionEvent.FilePreview -> {
                val hex = event.fileId.toHexString()
                models.files.onFilePreview(hex, event.bytes)
            }
            is FfiCompanionEvent.Avatar -> {
                val hex = event.chatId?.toHexString() ?: "mine"
                if (event.bytes != null) {
                    if (hex == "mine") {
                        models.contacts.onOwnAvatar(event.bytes)
                    } else {
                        models.contacts.putAvatar(hex, event.bytes)
                    }
                } else {
                    if (hex == "mine") models.contacts.onOwnAvatar(null)
                    else models.contacts.removeAvatar(hex)
                }
            }
            is FfiCompanionEvent.FileGone -> {
                models.files.forgetSave(event.fileId.toHexString())
            }
            is FfiCompanionEvent.FileProgress -> {
                val hex = event.fileId.toHexString()
                val progress = if (event.chunkTotal > 0uL) {
                    event.haveChunks.toFloat() / event.chunkTotal.toFloat()
                } else 0f
                models.files.onFileProgress(hex, progress)
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
                // Отказ мог прийти и на просьбу забрать вложение: тогда ждать
                // `FileSaved` больше нечего.
                models.files.failPendingSaves(event.reason)
            }
            else -> {}
        }
    }

    private fun mapCompanionGroup(chat: FfiCompanionChat, existingGroup: FfiGroup?): FfiGroup {
        val chatIdHex = chat.chatId.toHexString()
        val oldMs = models.contacts.avatarStamp(chatIdHex) ?: 0UL
        if (chat.avatarMs != 0UL && chat.avatarMs != oldMs) {
            models.contacts.setAvatarStamp(chatIdHex, chat.avatarMs)
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
        val oldMs = models.contacts.avatarStamp(chatIdHex) ?: 0UL
        if (chat.avatarMs != 0UL && chat.avatarMs != oldMs) {
            models.contacts.setAvatarStamp(chatIdHex, chat.avatarMs)
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
            seenOnBt = false,
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
        val grp = models.groups.currentGroups().find { it.chatId.contentEquals(msg.chatId) }
        val member = if (grp != null && !msg.author.isNullOrBlank()) {
            grp.members.find { m ->
                m.name == msg.author ||
                models.contacts.currentContacts().find { c -> c.peerIk.contentEquals(m.ik) }?.let { (it.localName ?: it.displayName) == msg.author } == true
            }
        } else null

        val authorIk = member?.ik

        if (authorIk != null && models.contacts.contactAvatars.value[authorIk.toHexString()] == null && RatatoskCore.isCompanionMode()) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    RatatoskCore.getCompanion().avatar(authorIk)
                } catch (e: Exception) { /* ignore */ }
            }
        }

        // Ключа в карточке компаньона нет вовсе (§13.4 не пускает `IK` через
        // границу устройства), поэтому на его месте — известный `chatId`,
        // а отпечатка нет: показывать пустую строку вместо него нельзя.
        val mappedSharedContact = msg.shared?.let { shared ->
            FfiSharedContact(
                peerIk = shared.chatId ?: ByteArray(0),
                displayName = shared.name,
                fingerprint = "",
                alreadyKnown = shared.chatId != null,
                // Своя карточка, вернувшаяся из чата, — это «это вы»,
                // а не предложение добавить себя в контакты.
                mine = msg.mine
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
            reactions = msg.reactions.map { FfiReaction(it.emoji, if (it.mine) models.contacts.fingerprint.value?.hexToByteArray() ?: ByteArray(0) else ByteArray(0), it.mine) },
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
            // Нарезку компаньон теперь сообщает, и её обязательно вернуть
            // в save_file: у каждого файла она своя — эфирный кусок это
            // четыре килобайта, сетевой мебибайт (FFI.md, §10.2). Раньше
            // тут стоял ноль («неизвестно»), потому что взять было негде.
            //
            // u64 -> u32 здесь ничего не теряет: в FfiFile ядро объявляет
            // то же самое число как u32, то есть само ручается, что оно
            // туда влезает.
            chunkBytes = att.chunkBytes.toUInt(),
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

                // Радио эфира — сразу после открытия аккаунта.
                //
                // Раньше его вручала только служба, при своём старте.
                // Этого хватало там, где аккаунт открывается сам
                // (без PIN, восстановлением сессии): служба сама его
                // и открывала, а следом вручала радио. Но если аккаунт
                // открывает человек — ввёл PIN, выбрал другой, поставил
                // приложение заново, — служба к тому моменту уже
                // отработала вхолостую: клиента ещё не было, вручать
                // было нечего, а второй раз её никто не позовёт.
                //
                // Снаружи это выглядело так: на одном телефоне эфир
                // поднимается с запуском, на другом не поднимается вовсе.
                // Вызов идемпотентен и под замком, так что лишним он
                // не будет даже там, где служба успела раньше.
                RatatoskCore.ensureBtRadio(getApplication())

                android.util.Log.d("RatatoskVM", "Engine setup: getting fingerprint and limits")
                val client = RatatoskCore.getClient()
                val fingerprint = client.fingerprint()
                val maxAvatar = try { maxAvatarBytes().toInt() } catch (e: Exception) { 32768 }
                
                withContext(Dispatchers.Main) {
                    models.contacts.setFingerprint(fingerprint)
                    models.contacts.setMaxAvatarBytes(maxAvatar)
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
                        withContext(Dispatchers.Main) { models.contacts.onOwnAvatar(avatar) }
                    } catch (e: Exception) {
                        android.util.Log.e("RatatoskVM", "Failed to load my avatar", e)
                    }
                }

                // Load auto-accept limit
                launch(Dispatchers.IO) {
                    try {
                        android.util.Log.d("RatatoskVM", "Engine setup: getting auto accept bytes")
                        val limit = client.autoAcceptBytes()
                        withContext(Dispatchers.Main) { models.files.setAutoAcceptLimitValue(limit) }
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
                            models.contacts.setContacts(currentContacts)
                            models.groups.setGroups(currentGroups)
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
                    _error.value = e.message?.takeIf { it.isNotBlank() }
                        ?: getApplication<Application>().getString(R.string.error_identity_load_failed)
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
                android.util.Log.w("RatatoskVM", "Failed to wipe account", e)
                _error.value = e.message?.takeIf { it.isNotBlank() }
                    ?: getApplication<Application>().getString(R.string.error_account_delete_failed)
            }
        }
    }

    /**
     * Выгружает архив.
     *
     * Ответ приходит и при неудаче: раньше при ошибке колбэк не звали вовсе,
     * и диалог оставался «в работе» навсегда — ни закрыть, ни отменить.
     */



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
                    models.session._activeAccountId.value = idHex
                    _isCompanionMode.value = false
                    settingsRepository.setLastAccountId(idHex)
                    setupEngine()
                    refreshAccounts()
                    _error.value = null
                }
            } catch (e: Exception) {
                android.util.Log.w("RatatoskVM", "Failed to initialize account", e)
                _error.value = e.message?.takeIf { it.isNotBlank() }
                    ?: getApplication<Application>().getString(R.string.error_account_open_failed)
            }
        }
    }

    /**
     * Порт и ключ этого устройства — их вводят на телефоне руками, когда
     * он не находит второй экран сам (гостевой Wi-Fi, VPN, изоляция клиентов).
     * `null` — не компаньон или связи ещё нет.
     */
    fun companionEndpoint(): Pair<Int, String>? = try {
        if (!RatatoskCore.isCompanionMode()) null
        else {
            val companion = RatatoskCore.getCompanion()
            companion.port().toInt() to companion.desktopIk().toHexString()
        }
    } catch (e: Exception) {
        null
    }

    /** Хранится ли снимок переписки этого второго экрана на диске. */
    private val _companionCacheEnabled = MutableStateFlow(false)
    val companionCacheEnabled: StateFlow<Boolean> = _companionCacheEnabled.asStateFlow()

    /**
     * Включает или выключает снимок переписки.
     *
     * Выключение зовёт `set_cache_path(null)` — файл стирает само ядро.
     * Включить можно только когда ссылка сопряжения сохранена: без неё
     * снимок было бы нечем открыть в следующий раз.
     */
    fun setCompanionCache(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val link = settingsRepository.companionLinks.first()
                    .firstOrNull { it.label == currentCompanionLabel }
                if (!enabled) {
                    RatatoskCore.getCompanion().setCachePath(null)
                    if (link != null) {
                        settingsRepository.saveCompanionLink(link.copy(cachePath = null))
                    }
                    withContext(Dispatchers.Main) { _companionCacheEnabled.value = false }
                    return@launch
                }
                if (link == null) {
                    _error.value = getApplication<Application>().getString(R.string.companion_cache_needs_link)
                    return@launch
                }
                val path = java.io.File(
                    getApplication<Application>().filesDir,
                    "companion_cache_${link.inviteUri.hashCode()}"
                ).absolutePath
                RatatoskCore.getCompanion().setCachePath(path)
                settingsRepository.saveCompanionLink(link.copy(cachePath = path))
                withContext(Dispatchers.Main) { _companionCacheEnabled.value = true }
            } catch (e: Exception) {
                android.util.Log.w("RatatoskVM", "Failed to switch companion cache", e)
                _error.value = e.message?.takeIf { it.isNotBlank() }
                    ?: getApplication<Application>().getString(R.string.companion_cache_failed)
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
                _companionCacheEnabled.value = resolvedCachePath != null
                if (resolvedCachePath != null) {
                    settingsRepository.saveCompanionLink(
                        chat.ratatosk.android.data.CompanionLink(label, inviteUri, port, peerAddr, resolvedCachePath, torDir)
                    )
                }
                withContext(Dispatchers.Main) {
                    _isInitialized.value = true
                    _isCompanionMode.value = true
                    val id = "companion:${inviteUri.hashCode()}"
                    models.session._activeAccountId.value = id
                    settingsRepository.setLastAccountId(id)
                    setupEngine()
                    _error.value = null
                }
            } catch (e: Exception) {
                android.util.Log.w("RatatoskVM", "Failed to link companion", e)
                _error.value = e.message?.takeIf { it.isNotBlank() }
                    ?: getApplication<Application>().getString(R.string.error_pairing_failed)
            }
        }
    }

    fun unlock(account: FfiAccount, pin: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            _isOpening.value = true
            try {
                val idHex = account.id.toHexString()
                val savedName = settingsRepository.getDisplayName(idHex).firstOrNull() ?: account.label
                RatatoskCore.initialize(account.id, pin, null, savedName)
                settingsRepository.setLastAccountId(idHex)
                // Открылся без PIN — в следующий раз и спрашивать не будем.
                settingsRepository.setNeedsPinHint(idHex, pin != null)
                withContext(Dispatchers.Main) {
                    _isInitialized.value = true
                    models.session._activeAccountId.value = idHex
                    _isCompanionMode.value = false
                    _pinRequired.value = false
                    setupEngine()
                    _error.value = null
                }
            } catch (e: Exception) {
                if (pin == null && e is RatatoskException.Locked) {
                    // Не подошло — значит PIN всё-таки есть. Это не ошибка:
                    // остаёмся на экране и просим его.
                    android.util.Log.d("RatatoskVM", "Account needs PIN to unlock")
                    settingsRepository.setNeedsPinHint(account.id.toHexString(), true)
                    withContext(Dispatchers.Main) { _pinRequired.value = true }
                    return@launch
                }
                android.util.Log.w("RatatoskVM", "Failed to unlock account", e)
                _error.value = e.message?.takeIf { it.isNotBlank() }
                    ?: getApplication<Application>().getString(R.string.error_account_open_failed)
            } finally {
                _isOpening.value = false
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
        models.session._isCompanionLinked.value = false
        _companionCacheEnabled.value = false
        resetCompanionFreshness()
        currentCompanionLabel = null
        models.session._activeAccountId.value = null
        _selectedAccount.value = null
        _isCreatingNewAccount.value = false
        
        // Clear all session state
        _activeChatIdFlow.value = null
        chat.ratatosk.android.util.VisibleChat.clear()
        _activeContactIdFlow.value = null
        models.contacts.reset()
        _messages.value = emptyMap()
        _messageStatuses.value = emptyMap()
        _unreadCounts.value = emptyMap()
        models.files.reset()
        // Расшифрованные копии вложений в кэше — вместе с сессией. Они
        // лежат открытым текстом, и переживать выход из аккаунта им незачем.
        try {
            FileUtils.clearDecryptedCaches(getApplication())
        } catch (e: Exception) {
            android.util.Log.w("RatatoskVM", "Failed to clear decrypted caches: ${e.message}")
        }
        _repliedMessages.value = emptyMap()
        _searchResults.value = emptyList()
        models.pairing.reset()
        models.transports.reset()
        
        // Остальное состояние прошлого аккаунта: оно принадлежит человеку,
        // который уже вышел, и всплывать у следующего ему незачем.
        models.groups.reset()
        _isSearching.value = false
        _sharedDraft.value = null
        models.contacts.setFingerprint(null)
        _pendingScrollToMsgId.value = null
        _pendingAvatarUri.value = null
        _pendingAvatarChatId.value = null
        _honestNotices.value = emptyList()
        _cardVersion.value = null
        _error.value = null

        
    }

    fun clearError() {
        _error.value = null
    }

    fun selectAccount(account: FfiAccount?) {
        _selectedAccount.value = account
        _pinRequired.value = false
        if (account == null) {
            _isCreatingNewAccount.value = false
            return
        }
        // Спрашивать PIN у аккаунта, у которого его нет, — вопрос о том, чего
        // нет. Пробуем открыть молча; подсказка избавляет от бессмысленного
        // счёта там, где PIN уже спрашивали в прошлый раз.
        viewModelScope.launch {
            if (settingsRepository.needsPinHint(account.id.toHexString()).first()) {
                _pinRequired.value = true
            } else {
                unlock(account, null)
            }
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
                    if (!models.session._isCompanionLinked.value) {
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
                android.util.Log.w("RatatoskVM", "Failed to send text", e)
                withContext(Dispatchers.Main) {
                    _error.value = e.message?.takeIf { it.isNotBlank() }
                        ?: getApplication<Application>().getString(R.string.error_send_failed)
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
                    if (!models.session._isCompanionLinked.value) {
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
                android.util.Log.w("RatatoskVM", "Failed to send files", e)
                withContext(Dispatchers.Main) {
                    _error.value = e.message?.takeIf { it.isNotBlank() }
                        ?: getApplication<Application>().getString(R.string.error_send_files_failed)
                }
            }
        }
    }

    /**
     * Принимает «поделиться» в выбранный чат: копирует вложения к себе
     * и кладёт их черновиком.
     *
     * Копировать надо здесь и сразу: право на чужой `content:`-адрес
     * живёт, пока жива наша задача, и к моменту отправки может кончиться.
     */
    fun shareInto(chatId: ByteArray, text: String, uris: List<android.net.Uri>) {
        val hex = chatId.toHexString()
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val accepted = uris.filter { FileUtils.isSafeIncomingUri(app, it) }
            val files = accepted.mapNotNull { FileUtils.copyUriToInternalStorage(app, it) }
            val lost = uris.size - files.size
            withContext(Dispatchers.Main) {
                if (lost > 0) {
                    // Молчать нельзя: иначе человек отправит три файла
                    // из пяти и узнает об этом от собеседника.
                    _error.value = app.resources.getQuantityString(
                        R.plurals.share_files_dropped, lost, lost
                    )
                }
                if (text.isNotBlank() || files.isNotEmpty()) {
                    _sharedDraft.value = SharedDraft(hex, text, files)
                }
            }
        }
    }

    fun clearSharedDraft() {
        _sharedDraft.value = null
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


    /**
     * Останавливает приём файла, **не отказываясь** от него.
     *
     * Приехавшее остаётся, предложение живёт, и [acceptFile] продолжит
     * с того же места. Это не отмена: отказ (`declineFile`) выбрасывает
     * принятое, а здесь человек говорит «не сейчас».
     */


    // Просит превью вложения. Ответ кладётся в [filePreviews] — читать надо
    // оттуда, а не из возвращаемого значения: у компаньона байты приезжают
    // позже, событием FilePreview, и вернуть их отсюда нечем.
    //
    // Звать можно сколько угодно: уже полученное и уже заказанное
    // второй раз не спрашивается. Раньше эта функция возвращала байты
    // и звалась прямо из composable — экран читал снимок `.value`,
    // на который Compose не подписан, и превью не появлялось до тех пор,
    // пока что-нибудь другое не перерисует строку.

    /**
     * Роняет ожидание выкладывания: ядро отказало или телефон ушёл со связи.
     *
     * Без этого ожидание висело вечно — крутилка не гасла, файл не открывался,
     * и человеку не говорили ни слова о причине.
     */
    /**
     * Следит за растущим файлом и переводит его размер в долю.
     *
     * Ядро пишет вложение в файл с припиской `.part` и до конца приёма
     * о ходе не сообщает: его события говорят только о том, сколько собрал
     * телефон, — а это давно сто процентов.
     */


    private fun resetCompanionFreshness() {
        _isCompanionFresh.value = false
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
                    if (!models.session._isCompanionLinked.value) {
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
                    if (!models.session._isCompanionLinked.value) {
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
                    _error.value = e.message?.takeIf { it.isNotBlank() }
                        ?: getApplication<Application>().getString(R.string.error_forward_failed)
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






    fun setActiveChat(chatId: ByteArray?) {
        _activeChatIdFlow.value = chatId
        // Сервису уведомлений это нужно, а общей модели у них с экраном нет.
        chat.ratatosk.android.util.VisibleChat.setOpenChat(chatId?.toHexString())
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
                                models.contacts.putAvatar(hex, bytes)
                            } else {
                                models.contacts.removeAvatar(hex)
                            }
                        }
                    } catch (t: Throwable) {
                        android.util.Log.w("RatatoskVM", "Failed to refresh avatar for ${event.peerIk.toHexString()}: ${t.message}")
                    }
                }
            }
            is FfiEvent.FileWaitsForChannel -> {
                models.files.onFileWaiting(event.fileId.toHexString(), event.reason)
            }
            is FfiEvent.FileGone -> {
                val hex = event.fileId.toHexString()
                // Вложения больше нет — и ожидания вместе с ним.
                models.files.forgetFile(hex)
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
                models.files.onFileSending(hex, progress)
                // Ядро называет снимающим ожидание только FileProgress,
                // но он про приём. У отдачи движение видно отсюда, и
                // держать «стоит» поверх идущей отправки было бы враньём.
                models.files.onFileWaiting(hex, null)
            }
            is FfiEvent.FileProgress -> {
                val hex = event.fileId.toHexString()
                // Ход передачи и означает, что она пошла: ядро прямо
                // говорит, что это событие снимает ожидание.
                models.files.onFileWaiting(hex, null)
                val progress = if (event.total > 0UL) event.received.toFloat() / event.total.toFloat() else 0f
                models.files.onFileProgress(hex, progress)
                
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
                models.transports.onTorStatus(FfiTorStatus(event.fraction, event.note, event.blocked))
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
                android.util.Log.i("RatatoskVM", "Mail account ready")
                refreshTransportStatus()
            }
            is FfiEvent.MailAccountFailed -> {
                _error.value = event.reason.takeIf { it.isNotBlank() }
                    ?: getApplication<Application>().getString(R.string.error_mail_setup_failed)
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
                // Ссылка сопряжения — это и есть секрет: у кого она, тот второй
                // экран этого телефона до отзыва. В журнал она не идёт.
                android.util.Log.i("RatatoskVM", "Pairing ready event received")
                models.pairing.onPairingReady(event.uri)
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
                                models.contacts.putAvatar(hex, bytes)
                            } else {
                                models.contacts.removeAvatar(hex)
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
