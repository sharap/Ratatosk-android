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
    chat.ratatosk.android.ui.model.CompanionApi by models.companion,
    chat.ratatosk.android.ui.model.ClientApi by models.client {

    constructor(application: Application) : this(application, chat.ratatosk.android.ui.model.AppModels(application))

    init {
        models.onAccountsChanged = { refreshAccounts() }
        models.onContactsChanged = { refreshContacts() }
        models.onOpenContact = { setActiveContact(it) }
        models.onPreviewFor = { generatePreview(it) }
        models.onChatOpened = { _activeContactIdFlow.value = null }
        models.onAccountOpened = { opened -> _isInitialized.value = opened; _isCompanionMode.value = false }
        models.onStartSession = { models.client.setupEngine() }
        models.onCompanionMode = { on -> _isCompanionMode.value = on }
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

    /**
     * Ошибка — общая с моделями: своя копия здесь означала бы, что всё,
     * о чём сообщают они, человек не увидит.
     */
    private val _error = models.session._error
    val error: StateFlow<String?> = models.session.error

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
                    models.client.setupEngine()
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
            models.client.setupEngine()
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

    /** Поднимает поток событий ядра — он нужен и полному клиенту, и второму экрану. */

    fun logout() {
        RatatoskCore.logout()
        viewModelScope.launch {
            settingsRepository.setLastAccountId(null)
        }
        
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

}
