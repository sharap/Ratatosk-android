package chat.ratatosk.android.ui.model

import chat.ratatosk.android.R
import chat.ratatosk.android.util.toHexString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ratatosk.core.FfiAccount
import org.ratatosk.core.RatatoskException

/** Аккаунты: список, создание, открытие, удаление. */
interface AccountsApi {
    val availableAccounts: StateFlow<List<FfiAccount>>
    val selectedAccount: StateFlow<FfiAccount?>
    val isCreatingNewAccount: StateFlow<Boolean>
    val isFindingHidden: StateFlow<Boolean>
    /** Идёт открытие: вывод ключа из PIN занимает заметные секунды. */
    val isOpening: StateFlow<Boolean>
    /** Без PIN открыть не вышло — теперь его надо спросить. */
    val pinRequired: StateFlow<Boolean>
    val accountExists: StateFlow<Boolean>

    fun refreshAccounts()
    fun wipeAccount(id: ByteArray)
    fun findHiddenAccount(pin: String, onFound: (ByteArray) -> Unit, onNotFound: () -> Unit)
    /**
     * Заводит аккаунт.
     *
     * @param bindToDevice привязать базу к этому телефону секретом из
     *   Keystore: тогда её не открыть больше нигде — ни с PIN, ни без.
     *   Обратная сторона: потеря телефона — потеря переписки.
     */
    fun initialize(label: String, pin: String?, displayName: String, bindToDevice: Boolean = false)
    fun unlock(account: FfiAccount, pin: String?)
    fun selectAccount(account: FfiAccount?)
    fun setCreatingNewAccount(creating: Boolean)
}

/**
 * @param onOpened аккаунт открыт: сессию поднимает тот, кто ей владеет.
 * @param startSession подписки на события ядра и первые запросы.
 */
class AccountsModel(
    private val session: SessionContext,
    private val onOpened: (Boolean) -> Unit,
    private val startSession: () -> Unit,
) : AccountsApi {
    private val _availableAccounts = MutableStateFlow<List<FfiAccount>>(emptyList())
    override val availableAccounts = _availableAccounts.asStateFlow()
    private val _selectedAccount = MutableStateFlow<FfiAccount?>(null)
    override val selectedAccount = _selectedAccount.asStateFlow()
    private val _isCreatingNewAccount = MutableStateFlow(false)
    override val isCreatingNewAccount = _isCreatingNewAccount.asStateFlow()
    private val _isFindingHidden = MutableStateFlow(false)
    override val isFindingHidden = _isFindingHidden.asStateFlow()
    private val _isOpening = MutableStateFlow(false)
    override val isOpening: StateFlow<Boolean> = _isOpening.asStateFlow()
    private val _pinRequired = MutableStateFlow(false)
    override val pinRequired: StateFlow<Boolean> = _pinRequired.asStateFlow()
    private val _accountExists = MutableStateFlow(false)
    override val accountExists: StateFlow<Boolean> = _accountExists.asStateFlow()

    override fun refreshAccounts() {
        _availableAccounts.value = session.core.listAccounts()
    }

    override fun wipeAccount(id: ByteArray) {
        session.scope.launch(Dispatchers.IO) {
            try {
                session.core.wipeAccount(id)
                // Секрет переживать аккаунт незачем: открывать им больше нечего.
                session.secrets.forget(id.toHexString())
                session.settings.setDeviceBound(id.toHexString(), false)
                refreshAccounts()
            } catch (e: Exception) {
                android.util.Log.w("RatatoskVM", "Failed to wipe account", e)
                session._error.value = e.message?.takeIf { it.isNotBlank() }
                    ?: session.string(R.string.error_account_delete_failed)
            }
        }
    }

    override fun findHiddenAccount(pin: String, onFound: (ByteArray) -> Unit, onNotFound: () -> Unit) {
        session.scope.launch(Dispatchers.IO) {
            _isFindingHidden.value = true
            try {
                val id = session.core.findHidden(pin)
                session.scope.launch {
                    if (id != null) onFound(id) else onNotFound()
                }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Find hidden failed", e)
                session.scope.launch { onNotFound() }
            } finally {
                _isFindingHidden.value = false
            }
        }
    }

    override fun initialize(label: String, pin: String?, displayName: String, bindToDevice: Boolean) {
        session.scope.launch(Dispatchers.IO) {
            try {
                val account = session.core.createAccount(label)
                val idHex = account.id.toHexString()
                // Секрет — раньше открытия: база, заведённая ключом, который
                // не удалось сохранить, не откроется больше никогда.
                val deviceKey = if (bindToDevice) {
                    if (!session.secrets.isAvailable) {
                        throw IllegalStateException(session.string(R.string.keystore_unavailable))
                    }
                    session.secrets.create(idHex).also { session.settings.setDeviceBound(idHex, true) }
                } else null
                session.core.openAccount(account.id, pin, deviceKey, displayName)

                session.settings.registerAccount(idHex, displayName)
                
                withContext(Dispatchers.Main) {
                    onOpened(true)
                    session._activeAccountId.value = idHex
                    
                    session.settings.setLastAccountId(idHex)
                    startSession()
                    refreshAccounts()
                            }
            } catch (e: Exception) {
                android.util.Log.w("RatatoskVM", "Failed to initialize account", e)
                session._error.value = e.message?.takeIf { it.isNotBlank() }
                    ?: session.string(R.string.error_account_open_failed)
            }
        }
    }

    override fun unlock(account: FfiAccount, pin: String?) {
        session.scope.launch(Dispatchers.IO) {
            _isOpening.value = true
            try {
                val idHex = account.id.toHexString()
                val savedName = session.settings.getDisplayName(idHex).firstOrNull() ?: account.label
                val deviceKey = if (session.settings.isDeviceBound(idHex).first()) {
                    session.secrets.get(idHex)
                        ?: throw IllegalStateException(session.string(R.string.device_secret_lost))
                } else null
                session.core.openAccount(account.id, pin, deviceKey, savedName)
                session.settings.setLastAccountId(idHex)
                // Открылся без PIN — в следующий раз и спрашивать не будем.
                session.settings.setNeedsPinHint(idHex, pin != null)
                withContext(Dispatchers.Main) {
                    onOpened(true)
                    session._activeAccountId.value = idHex
                    
                    _pinRequired.value = false
                    startSession()
                    session._error.value = null
                }
            } catch (e: Exception) {
                if (pin == null && e is RatatoskException.Locked) {
                    // Не подошло — значит PIN всё-таки есть. Это не ошибка:
                    // остаёмся на экране и просим его.
                    android.util.Log.d("RatatoskVM", "Account needs PIN to unlock")
                    session.settings.setNeedsPinHint(account.id.toHexString(), true)
                    withContext(Dispatchers.Main) { _pinRequired.value = true }
                    return@launch
                }
                android.util.Log.w("RatatoskVM", "Failed to unlock account", e)
                session._error.value = e.message?.takeIf { it.isNotBlank() }
                    ?: session.string(R.string.error_account_open_failed)
            } finally {
                _isOpening.value = false
            }
        }
    }

    override fun selectAccount(account: FfiAccount?) {
        _selectedAccount.value = account
        _pinRequired.value = false
        if (account == null) {
            _isCreatingNewAccount.value = false
            return
        }
        // Спрашивать PIN у аккаунта, у которого его нет, — вопрос о том, чего
        // нет. Пробуем открыть молча; подсказка избавляет от бессмысленного
        // счёта там, где PIN уже спрашивали в прошлый раз.
        session.scope.launch {
            if (session.settings.needsPinHint(account.id.toHexString()).first()) {
                _pinRequired.value = true
            } else {
                unlock(account, null)
            }
        }
    }

    override fun setCreatingNewAccount(creating: Boolean) {
        _isCreatingNewAccount.value = creating
        if (creating) {
            _selectedAccount.value = null
        }
    }

    /** Сессия закрыта: выбранного аккаунта и незавершённого создания нет. */
    fun reset() {
        _selectedAccount.value = null
        _isCreatingNewAccount.value = false
        _pinRequired.value = false
        _isOpening.value = false
    }
}
