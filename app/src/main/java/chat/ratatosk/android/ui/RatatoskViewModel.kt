package chat.ratatosk.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import chat.ratatosk.android.R
import chat.ratatosk.android.core.RatatoskCore
import chat.ratatosk.android.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.ratatosk.core.honestNotices

/**
 * Окно и то, что общего у всех его экранов.
 *
 * Работы здесь почти не осталось: она разобрана по моделям в `ui/model`
 * (`AppModels`), а их интерфейсы делегируются — экраны по-прежнему зовут
 * `viewModel.x`. Тут — жизнь сессии: подъём журнала и реестра, выход из
 * аккаунта и та мелочь, что не принадлежит ни одной модели.
 */
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
    chat.ratatosk.android.ui.model.ClientApi by models.client,
    chat.ratatosk.android.ui.model.ShareApi by models.share,
    chat.ratatosk.android.ui.model.PrefsApi by models.prefs,
    chat.ratatosk.android.ui.model.ChannelsApi by models.channels,
    chat.ratatosk.android.ui.model.NotifyApi by models.notify {

    constructor(application: Application) : this(application, chat.ratatosk.android.ui.model.AppModels(application))

    init {
        models.onAccountsChanged = { refreshAccounts() }
        models.onContactsChanged = { refreshContacts() }
        models.onOpenContact = { setActiveContact(it) }
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

    val activeAccountId = models.session.activeAccountId

    private val _isCompanionMode = MutableStateFlow<Boolean>(RatatoskCore.isCompanionMode())
    val isCompanionMode: StateFlow<Boolean> = _isCompanionMode.asStateFlow()

    val isCompanionLinked = models.session.isCompanionLinked

    val totalUnreadCount = models.chats.totalUnreadCount

    private val _activeContactIdFlow = MutableStateFlow<ByteArray?>(null)
    val activeContactIdFlow = _activeContactIdFlow.asStateFlow()

    private val _honestNotices = MutableStateFlow<List<String>>(emptyList())
    val honestNotices = _honestNotices.asStateFlow()

    private val _isInitialized = MutableStateFlow(RatatoskCore.isInitialized())
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    /**
     * Ошибка — общая с моделями: своя копия здесь означала бы, что всё,
     * о чём сообщают они, человек не увидит.
     */
    private val _error = models.session._error
    val error: StateFlow<String?> = models.session.error

    private val _pendingAvatarUri = MutableStateFlow<android.net.Uri?>(null)
    val pendingAvatarUri = _pendingAvatarUri.asStateFlow()

    private val _pendingAvatarChatId = MutableStateFlow<ByteArray?>(null)
    val pendingAvatarChatId = _pendingAvatarChatId.asStateFlow()

    init {
        // Журнал ядра — первым делом, раньше всего остального: подписчик
        // ставится один раз на процесс, и всё, что ядро скажет до этого,
        // пропадёт. Читать настройку приходится здесь, а не внутри ядра:
        // она в DataStore, то есть достаётся корутиной.
        viewModelScope.launch(Dispatchers.IO) {
            val toFile = try {
                models.session.settings.coreFileLog.first()
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

    fun logout() {
        RatatoskCore.logout()
        viewModelScope.launch {
            models.session.settings.setLastAccountId(null)
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
        _pendingAvatarUri.value = null
        _pendingAvatarChatId.value = null
        _honestNotices.value = emptyList()
        _error.value = null
    }

    fun clearError() {
        _error.value = null
    }

    /**
     * Отправляет записанное голосовое.
     *
     * Волна кладётся в превью до отправки: ядро просит его в момент
     * отправки, а после — неоткуда взять.
     */
    fun sendVoice(chatId: ByteArray, file: java.io.File, waveform: ByteArray?) {
        if (waveform != null) models.rememberRecordedPreview(file.absolutePath, waveform)
        sendFiles(chatId, listOf(file), "")
    }

    /**
     * Отправляет записанный кружок.
     *
     * Кадр-обложка кладётся в превью до отправки — по той же причине,
     * что и волна голосового: ядро просит превью в момент отправки,
     * а после — неоткуда взять. Обложка важнее, чем у голосового:
     * по ней кружок виден ещё не принятым.
     */
    fun sendVideo(chatId: ByteArray, file: java.io.File, poster: ByteArray?) {
        if (poster != null) models.rememberRecordedPreview(file.absolutePath, poster)
        sendFiles(chatId, listOf(file), "")
    }

    fun setPendingAvatarUri(uri: android.net.Uri?, chatId: ByteArray? = null) {
        _pendingAvatarUri.value = uri
        _pendingAvatarChatId.value = chatId
    }

    fun setActiveContact(chatId: ByteArray?) {
        _activeContactIdFlow.value = chatId
        if (chatId != null) models.chats.clearActiveChat()
    }
}
