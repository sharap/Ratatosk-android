package chat.ratatosk.android.ui.model

import chat.ratatosk.android.data.CompanionLink
import chat.ratatosk.android.ui.theme.ChatThemeData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Настройки окна: они живут в `DataStore`, а не в ядре. */
interface PrefsApi {
    /** Как зовут это устройство: имя аккаунта или метка второго экрана. */
    val userName: StateFlow<String?>
    val chatTheme: StateFlow<ChatThemeData>
    val notificationsShowName: StateFlow<Boolean>
    val notificationsShowText: StateFlow<Boolean>
    val coreFileLog: StateFlow<Boolean>
    val companionLinks: StateFlow<List<CompanionLink>>
    val lastAccountId: StateFlow<String?>

    fun updateChatTheme(updater: (ChatThemeData) -> ChatThemeData)
    fun setNotificationsShowName(show: Boolean)
    fun setNotificationsShowText(show: Boolean)
    fun setCoreFileLog(enabled: Boolean)
    /** Файл журнала — чтобы экран мог показать размер и отдать его наружу. */
    fun coreLogFile(): java.io.File
}

/**
 * @param companionLabel имя второго экрана: у него нет своего аккаунта,
 *   и звать его по идентификатору сопряжения человеку нечего.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrefsModel(
    private val session: SessionContext,
    private val companionLabel: () -> String?,
) : PrefsApi {

    override val userName = session.activeAccountId.flatMapLatest { id ->
        when {
            id == null -> flowOf(null)
            id.startsWith("companion:") -> flowOf(companionLabel())
            else -> session.settings.getDisplayName(id)
        }
    }.stateIn(session.scope, SharingStarted.WhileSubscribed(5000), null)

    override val chatTheme = session.settings.chatTheme.stateIn(
        scope = session.scope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ChatThemeData(),
    )

    override val notificationsShowName = session.activeAccountId.flatMapLatest { id ->
        if (id == null) flowOf(true) else session.settings.getNotificationsShowName(id)
    }.stateIn(session.scope, SharingStarted.WhileSubscribed(5000), true)

    override val notificationsShowText = session.activeAccountId.flatMapLatest { id ->
        if (id == null) flowOf(true) else session.settings.getNotificationsShowText(id)
    }.stateIn(session.scope, SharingStarted.WhileSubscribed(5000), true)

    // Журнал ядра в файл: собирать или нет. Общий на приложение.
    override val coreFileLog = session.settings.coreFileLog
        .stateIn(session.scope, SharingStarted.WhileSubscribed(5000), false)

    override val companionLinks = session.settings.companionLinks.stateIn(
        scope = session.scope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList(),
    )

    override val lastAccountId = session.settings.lastAccountId.stateIn(
        scope = session.scope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null,
    )

    override fun updateChatTheme(updater: (ChatThemeData) -> ChatThemeData) {
        session.scope.launch {
            session.settings.updateChatTheme(updater(chatTheme.value))
        }
    }

    override fun setNotificationsShowName(show: Boolean) {
        val id = session.activeAccountId.value ?: return
        session.scope.launch { session.settings.setNotificationsShowName(id, show) }
    }

    override fun setNotificationsShowText(show: Boolean) {
        val id = session.activeAccountId.value ?: return
        session.scope.launch { session.settings.setNotificationsShowText(id, show) }
    }

    override fun setCoreFileLog(enabled: Boolean) {
        session.scope.launch { session.settings.setCoreFileLog(enabled) }
    }

    override fun coreLogFile(): java.io.File = session.core.coreLogFile(session.app)
}
