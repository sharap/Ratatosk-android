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
    chat.ratatosk.android.ui.model.FilesApi by models.files,
    chat.ratatosk.android.ui.model.ChatsApi by models.chats,
    chat.ratatosk.android.ui.model.AccountsApi by models.accounts,
    chat.ratatosk.android.ui.model.CompanionApi by models.companion {

    constructor(application: Application) : this(application, chat.ratatosk.android.ui.model.AppModels(application))

    init {
        models.onAccountsChanged = { refreshAccounts() }
        models.onContactsChanged = { refreshContacts() }
        models.onOpenContact = { setActiveContact(it) }
        models.onPreviewFor = { generatePreview(it) }
        models.onChatOpened = { _activeContactIdFlow.value = null }
        models.onAccountOpened = { opened -> _isInitialized.value = opened; _isCompanionMode.value = false }
        models.onStartSession = { setupEngine() }
        models.onStartClientEvents = { ensureClientEvents() }
        models.onCompanionOpened = {
            _isInitialized.value = true
            _isCompanionMode.value = true
        }
    }

    override fun onCleared() {
        models.close()
        super.onCleared()
    }

    private val settingsRepository = SettingsRepository(application)
    
    private val _events = MutableStateFlow<List<FfiEvent>>(emptyList())
    val events = _events.asStateFlow()








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








    /** Идёт открытие аккаунта: вывод ключа из PIN занимает заметные секунды. */

    /** Тихая попытка открыть без PIN не удалась — теперь его надо спросить. */


    val activeAccountId = models.session.activeAccountId




    private val _isCompanionMode = MutableStateFlow<Boolean>(RatatoskCore.isCompanionMode())
    val isCompanionMode: StateFlow<Boolean> = _isCompanionMode.asStateFlow()

    val isCompanionLinked = models.session.isCompanionLinked

    val companionLinks: StateFlow<List<chat.ratatosk.android.data.CompanionLink>> = settingsRepository.companionLinks.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val totalUnreadCount = models.chats.totalUnreadCount

    val lastAccountId = settingsRepository.lastAccountId.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )


    private val _activeContactIdFlow = MutableStateFlow<ByteArray?>(null)
    val activeContactIdFlow = _activeContactIdFlow.asStateFlow()





    private val _honestNotices = MutableStateFlow<List<String>>(emptyList())
    val honestNotices = _honestNotices.asStateFlow()
    
    private val _isInitialized = MutableStateFlow(RatatoskCore.isInitialized())
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    


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
            id.startsWith("companion:") -> flowOf(models.companion.currentCompanionLabel)
            else -> settingsRepository.getDisplayName(id)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)



    // К какому сообщению просят перейти при открытии чата.
    //
    // Ставится снаружи — сейчас из уведомления о реакции, — а снимает его
    // сам экран чата, когда доскроллил. Через ViewModel, а не через аргумент
    // экрана, потому что чат к моменту нажатия может быть уже открыт:
    // тогда менять нечего, нужен именно сигнал.



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







    /** Поднимает поток событий ядра — он нужен и полному клиенту, и второму экрану. */
    private fun ensureClientEvents() {
        if (eventsJob != null) return
        android.util.Log.d("RatatoskVM", "Starting event collection job")
        eventsJob = viewModelScope.launch(Dispatchers.IO) {
            RatatoskCore.events.collect { event ->
                handleEvent(event)
            }
        }
    }

    private fun setupEngine() {
        android.util.Log.d("RatatoskVM", "Setting up engine components... Companion mode: ${RatatoskCore.isCompanionMode()}")
        _isCompanionMode.value = RatatoskCore.isCompanionMode()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (RatatoskCore.isCompanionMode()) {
                    models.companion.setupCompanionEngine()
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

                ensureClientEvents()

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















    fun logout() {
        RatatoskCore.logout()
        viewModelScope.launch {
            settingsRepository.setLastAccountId(null)
        }
        
        eventsJob?.cancel()
        eventsJob = null

        _isInitialized.value = false

        // Состояние прошлого аккаунта забывают все модели разом.
        models.resetAll()
        _activeContactIdFlow.value = null
        // Расшифрованные копии вложений в кэше — вместе с сессией. Они
        // лежат открытым текстом, и переживать выход из аккаунта им незачем.
        try {
            FileUtils.clearDecryptedCaches(getApplication())
        } catch (e: Exception) {
            android.util.Log.w("RatatoskVM", "Failed to clear decrypted caches: ${e.message}")
        }
        _sharedDraft.value = null
        _pendingAvatarUri.value = null
        _pendingAvatarChatId.value = null
        _honestNotices.value = emptyList()
        _cardVersion.value = null
        _error.value = null

        
    }

    fun clearError() {
        _error.value = null
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

    




















































    fun setPendingAvatarUri(uri: android.net.Uri?, chatId: ByteArray? = null) {
        _pendingAvatarUri.value = uri
        _pendingAvatarChatId.value = chatId
    }








    fun setActiveContact(chatId: ByteArray?) {
        _activeContactIdFlow.value = chatId
        if (chatId != null) {
            models.chats.clearActiveChat()
            models.chats.clearActiveChat()
        }
    }




    fun updateChatTheme(updater: (ChatThemeData) -> ChatThemeData) {
        viewModelScope.launch {
            val newData = updater(chatTheme.value)
            settingsRepository.updateChatTheme(newData)
        }
    }



    private var isAnnouncingTor = false

    private fun handleEvent(event: FfiEvent) {
        when (event) {
            is FfiEvent.MessageReceived -> {
                loadMessages(event.chatId)
                if (models.chats.activeChatIdHex != event.chatId.toHexString()) {
                    models.chats.bumpUnread(event.chatId.toHexString())
                }
            }
            is FfiEvent.StatusChanged -> {
                val hex = event.msgId.toHexString()
                models.chats.onStatusChanged(hex, event.status)
                
                val targetChatHex = models.chats.chatIdHexOf(event.msgId) ?: models.chats.activeChatIdHex

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
                models.chats.activeChatIdHex?.let { loadMessages(it.hexToByteArray()) }
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
                    models.chats.activeChatIdHex?.let { loadMessages(it.hexToByteArray()) }
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
