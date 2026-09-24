package chat.ratatosk.android.ui.model

import android.app.Application
import chat.ratatosk.android.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Модели по назначению и общий для них [SessionContext].
 *
 * Собирается один раз на `RatatoskViewModel`, который к ним и обращается
 * (и делегирует их интерфейсы, чтобы экраны не переписывать). Своя область
 * корутин, а не `viewModelScope`: он доступен только после создания объекта,
 * а моделям он нужен уже в конструкторе. Закрывается вместе с моделью.
 */
class AppModels(
    application: Application,
    /** Ядро за швом: в проверках сюда кладут двойника. */
    core: Backend = CoreBackend,
    /** Секрет устройства; в проверках сюда кладут свой. */
    secrets: chat.ratatosk.android.data.DeviceSecrets =
        chat.ratatosk.android.data.KeystoreSecrets(application),
    /** Строки ресурсов: в проверках ресурсов нет. */
    strings: (Int) -> String = { application.getString(it) },
) {
    private val modelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val session = SessionContext(
        app = application,
        scope = modelScope,
        settings = SettingsRepository(application),
        core = core,
        secrets = secrets,
        strings = strings,
    )

    /** Перечитать список аккаунтов после ввоза архива; ставит `RatatoskViewModel`. */
    var onAccountsChanged: () -> Unit = {}

    val backup: BackupModel = BackupModel(
        session = session,
        onImported = { onAccountsChanged() },
        onContactsMerged = { onContactsChanged() },
    )
    val pairing: PairingModel = PairingModel(session)
    val transports: TransportsModel = TransportsModel(session)
    /** Перечитать контакты и группы; ставит `RatatoskViewModel`. */
    var onContactsChanged: () -> Unit = {}

    /** Карточку человека открывает `RatatoskViewModel` — пока. */
    var onOpenContact: (ByteArray) -> Unit = {}

    val groups: GroupsModel = GroupsModel(session) { onContactsChanged() }
    /** Карточку человека закрывает `RatatoskViewModel` — она ещё у него. */
    var onChatOpened: () -> Unit = {}

    val share: ShareModel = ShareModel(session)

    /** Записанная волна — к превью вложения; ставит запись голосового. */
    fun rememberVoiceWaveform(path: String, png: ByteArray) = share.rememberWaveform(path, png)

    val chats: ChatsModel = ChatsModel(
        session = session,
        previewFor = { share.previewFor(it) },
        onChatOpened = { onChatOpened() },
    )
    val files: FilesModel = FilesModel(session) { chats.loadMessages(it) }

    val contacts: ContactsModel = ContactsModel(
        session = session,
        groups = groups,
        loadMessages = { chats.loadMessages(it) },
        openContact = { onOpenContact(it) },
    )

    /**
     * Сессия закрыта: каждая модель забывает состояние прошлого аккаунта.
     *
     * Собрано в одном месте не ради красоты: раньше этот список жил внутри
     * `logout`, и каждое новое поле надо было не забыть туда дописать —
     * половину забывали, и чужие данные всплывали у следующего аккаунта.
     */
    /** Аккаунт открыт и сессию надо поднять; ставит `RatatoskViewModel`. */
    var onAccountOpened: (Boolean) -> Unit = {}
    var onStartSession: () -> Unit = {}

    val accounts: AccountsModel = AccountsModel(
        session = session,
        onOpened = { onAccountOpened(it) },
        startSession = { onStartSession() },
    )

    /** Компаньон открыт; сессию поднимает `RatatoskViewModel`. */
    var onCompanionOpened: () -> Unit = {}

    /** Режим сессии — его показывает окно. */
    var onCompanionMode: (Boolean) -> Unit = {}

    val companion: CompanionModel = CompanionModel(
        session = session,
        chats = chats,
        contacts = contacts,
        groups = groups,
        files = files,
        onCompanionOpened = { onCompanionOpened() },
        startClientEvents = { client.ensureClientEvents() },
    )

    val channels: ChannelsModel = ChannelsModel(
        session = session,
        refreshGroups = { onContactsChanged() },
        loadMessages = { chats.loadMessages(it) },
    )

    /** Уведомления чата (§14): выбор человека держит ядро. */
    val notify: NotifyModel = NotifyModel(session)

    val client: ClientModel = ClientModel(
        session = session,
        chats = chats,
        notify = notify,
        contacts = contacts,
        groups = groups,
        files = files,
        transports = transports,
        pairing = pairing,
        channels = channels,
        openCompanion = { companion.setupCompanionEngine() },
        onCompanionMode = { onCompanionMode(it) },
    )

    val prefs: PrefsModel = PrefsModel(session) { companion.currentCompanionLabel }

    fun resetAll() {
        accounts.reset()
        notify.reset()
        channels.reset()
        client.reset()
        share.reset()
        companion.reset()
        chats.reset()
        contacts.reset()
        files.reset()
        groups.reset()
        pairing.reset()
        transports.reset()
        session._isCompanionLinked.value = false
        session._activeAccountId.value = null
        session._error.value = null
    }

    fun close() {
        modelScope.cancel()
    }
}
