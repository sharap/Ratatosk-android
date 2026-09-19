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
class AppModels(application: Application) {
    private val modelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val session = SessionContext(
        app = application,
        scope = modelScope,
        settings = SettingsRepository(application),
    )

    /** Перечитать список аккаунтов после ввоза архива; ставит `RatatoskViewModel`. */
    var onAccountsChanged: () -> Unit = {}

    val backup: BackupModel = BackupModel(session) { onAccountsChanged() }
    val pairing: PairingModel = PairingModel(session)
    val transports: TransportsModel = TransportsModel(session)
    /** Перечитать контакты и группы; ставит `RatatoskViewModel`. */
    var onContactsChanged: () -> Unit = {}

    val groups: GroupsModel = GroupsModel(session) { onContactsChanged() }

    fun close() {
        modelScope.cancel()
    }
}
