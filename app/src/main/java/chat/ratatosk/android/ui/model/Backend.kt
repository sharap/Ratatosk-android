package chat.ratatosk.android.ui.model

import android.content.Context
import chat.ratatosk.android.core.RatatoskCore
import kotlinx.coroutines.flow.Flow
import org.ratatosk.core.FfiAccount
import org.ratatosk.core.FfiArchivePeek
import org.ratatosk.core.FfiArchiveUnlock
import org.ratatosk.core.FfiCompanionEvent
import org.ratatosk.core.FfiEvent
import org.ratatosk.core.FfiExportScope
import org.ratatosk.core.FfiExported
import org.ratatosk.core.FfiImported
import org.ratatosk.core.RatatoskClientInterface
import org.ratatosk.core.RatatoskCompanionInterface

/**
 * Ядро — таким, каким его видят модели.
 *
 * Шов ради проверок: за ним живёт `RatatoskCore` с настоящей библиотекой,
 * а в тестах — подставной двойник, и модели можно гонять без ядра, сети
 * и телефона. Границу провели по тому, что модели действительно зовут:
 * почти всё это `client()` и `companion()` — объекты ядра, у которых
 * uniffi любезно сгенерировал интерфейсы.
 */
interface Backend {
    /** Второй экран телефона, а не полный клиент. */
    val isCompanion: Boolean

    /** Сессия поднята: есть клиент или связь со вторым экраном. */
    val isInitialized: Boolean

    /** Какой аккаунт открыт; `null` — никакой. */
    val activeAccountId: String?

    val events: Flow<FfiEvent>
    val companionEvents: Flow<FfiCompanionEvent>

    /** Бросает, если сессии нет: звать только там, где она заведомо есть. */
    fun client(): RatatoskClientInterface
    fun companion(): RatatoskCompanionInterface

    // Аккаунты: они живут в реестре, а не в клиенте.
    fun listAccounts(): List<FfiAccount>
    fun createAccount(label: String): FfiAccount
    fun openAccount(accountId: ByteArray, pin: String?, deviceKey: ByteArray?, displayName: String)
    fun wipeAccount(accountId: ByteArray)
    /** Ищет скрытый аккаунт по PIN; `null` — такого нет. */
    fun findHidden(pin: String): ByteArray?

    // Архив переписки.
    fun exportHistory(path: String, scope: FfiExportScope, phrase: String?): FfiExported
    fun importArchive(context: Context, path: String, unlock: FfiArchiveUnlock, label: String): FfiImported
    fun peekArchive(path: String): FfiArchivePeek

    fun openCompanion(inviteUri: String, port: UShort, peerAddr: String?, cachePath: String?, torDir: String?)

    // Этим нужен контекст: радио и журнал — вещи системные.
    fun ensureBtRadio(context: Context): Boolean
    fun hasBtRadio(): Boolean
    fun coreLogFile(context: Context): java.io.File

    /**
     * Слова к отказу канала (§15). Свой текст писать нельзя: причин
     * двенадцать, и половина различается оттенком, который человеку
     * важен, — «нет права» против «право есть, выдача бессмысленна».
     */
    fun refusalText(reason: org.ratatosk.core.FfiChannelRefusal): String
}

/** Настоящее ядро. */
object CoreBackend : Backend {
    override val isCompanion: Boolean get() = RatatoskCore.isCompanionMode()
    override val isInitialized: Boolean get() = RatatoskCore.isInitialized()
    override val activeAccountId: String? get() = RatatoskCore.getActiveAccountId()

    override val events: Flow<FfiEvent> get() = RatatoskCore.events
    override val companionEvents: Flow<FfiCompanionEvent> get() = RatatoskCore.companionEvents

    override fun client(): RatatoskClientInterface = RatatoskCore.getClient()
    override fun companion(): RatatoskCompanionInterface = RatatoskCore.getCompanion()

    override fun listAccounts(): List<FfiAccount> = RatatoskCore.listAccounts()
    override fun createAccount(label: String): FfiAccount = RatatoskCore.createAccount(label)

    override fun openAccount(accountId: ByteArray, pin: String?, deviceKey: ByteArray?, displayName: String) {
        RatatoskCore.initialize(accountId, pin, deviceKey, displayName)
    }

    override fun wipeAccount(accountId: ByteArray) = RatatoskCore.wipeAccount(accountId)
    override fun findHidden(pin: String): ByteArray? = RatatoskCore.findHidden(pin)

    override fun exportHistory(path: String, scope: FfiExportScope, phrase: String?): FfiExported =
        RatatoskCore.exportHistory(path, scope, phrase)

    override fun importArchive(context: Context, path: String, unlock: FfiArchiveUnlock, label: String): FfiImported =
        RatatoskCore.importArchive(context, path, unlock, label)

    override fun peekArchive(path: String): FfiArchivePeek = RatatoskCore.peekArchive(path)

    override fun openCompanion(inviteUri: String, port: UShort, peerAddr: String?, cachePath: String?, torDir: String?) {
        RatatoskCore.initializeCompanion(inviteUri, port, peerAddr, cachePath, torDir)
    }

    override fun ensureBtRadio(context: Context): Boolean = RatatoskCore.ensureBtRadio(context)
    override fun hasBtRadio(): Boolean = RatatoskCore.hasBtRadio()
    override fun coreLogFile(context: Context): java.io.File = RatatoskCore.coreLogFile(context)

    override fun refusalText(reason: org.ratatosk.core.FfiChannelRefusal): String =
        org.ratatosk.core.channelRefusalText(reason)
}
