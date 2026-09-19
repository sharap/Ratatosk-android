package chat.ratatosk.android.fake

import org.ratatosk.core.*

/** Модель позвала то, чего проверка не ждала, — это и есть находка. */
internal fun notCalled(name: String): Nothing =
    throw AssertionError("ядро позвали там, где не ждали: $name")

/**
 * Заглушка [RatatoskClientInterface]: каждый вызов падает с именем метода.
 *
 * Проверка объявляет только то, что ей нужно, — а всё, чего модель звать
 * не должна была, само скажет об этом в отчёте, вместо `NullPointerException`
 * где-то в середине.
 */
open class FakeClient : RatatoskClientInterface {
    override fun `acceptFile`(`fileId`: kotlin.ByteArray): Unit = notCalled("acceptFile")
    override fun `addContact`(`uri`: kotlin.String, `metInPerson`: kotlin.Boolean): Unit = notCalled("addContact")
    override fun `addSharedContact`(`msgId`: kotlin.ByteArray): Unit = notCalled("addSharedContact")
    override fun `announceAddresses`(`onion`: kotlin.String?, `chatmail`: kotlin.String?): Unit = notCalled("announceAddresses")
    override fun `autoAcceptBytes`(): kotlin.ULong? = notCalled("autoAcceptBytes")
    override fun `avatarOf`(`peerIk`: kotlin.ByteArray): kotlin.ByteArray? = notCalled("avatarOf")
    override fun `bluetooth`(): FfiBluetooth = notCalled("bluetooth")
    override fun `chatIdFor`(`peerIk`: kotlin.ByteArray): kotlin.ByteArray = notCalled("chatIdFor")
    override fun `clearChat`(`chatId`: kotlin.ByteArray): Unit = notCalled("clearChat")
    override fun `clearMailAccount`(): Unit = notCalled("clearMailAccount")
    override fun `contacts`(): List<FfiContact> = notCalled("contacts")
    override fun `createGroup`(`title`: kotlin.String): Unit = notCalled("createGroup")
    override fun `createMailAccount`(`url`: kotlin.String, `viaTor`: kotlin.Boolean): Unit = notCalled("createMailAccount")
    override fun `declineFile`(`fileId`: kotlin.ByteArray): Unit = notCalled("declineFile")
    override fun `deleteContact`(`peerIk`: kotlin.ByteArray, `purgeHistory`: kotlin.Boolean): Unit = notCalled("deleteContact")
    override fun `deleteMessages`(`chatId`: kotlin.ByteArray, `msgIds`: List<kotlin.ByteArray>): Unit = notCalled("deleteMessages")
    override fun `devices`(): List<FfiPairedDevice> = notCalled("devices")
    override fun `editMessage`(`chatId`: kotlin.ByteArray, `msgId`: kotlin.ByteArray, `text`: kotlin.String): Unit = notCalled("editMessage")
    override fun `evictFromGroup`(`chatId`: kotlin.ByteArray, `peerIk`: kotlin.ByteArray): Unit = notCalled("evictFromGroup")
    override fun `exportHistory`(`path`: kotlin.String, `scope`: FfiExportScope, `phrase`: kotlin.String?): FfiExported = notCalled("exportHistory")
    override fun `fingerprint`(): kotlin.String = notCalled("fingerprint")
    override fun `forwardMessages`(`chatId`: kotlin.ByteArray, `msgIds`: List<kotlin.ByteArray>): Unit = notCalled("forwardMessages")
    override fun `groupAvatar`(`chatId`: kotlin.ByteArray): kotlin.ByteArray? = notCalled("groupAvatar")
    override fun `groups`(): List<FfiGroup> = notCalled("groups")
    override fun `inviteToGroup`(`chatId`: kotlin.ByteArray, `peerIk`: kotlin.ByteArray): Unit = notCalled("inviteToGroup")
    override fun `leaveGroup`(`chatId`: kotlin.ByteArray): Unit = notCalled("leaveGroup")
    override fun `mailAccount`(): FfiMailAccount? = notCalled("mailAccount")
    override fun `mailStatus`(): FfiMailStatus = notCalled("mailStatus")
    override fun `markRead`(`chatId`: kotlin.ByteArray, `upTo`: kotlin.ByteArray): Unit = notCalled("markRead")
    override fun `markVerified`(`peerIk`: kotlin.ByteArray): Unit = notCalled("markVerified")
    override fun `mergeContacts`(`archive`: kotlin.String, `unlock`: FfiArchiveUnlock, `scratchDir`: kotlin.String): FfiMerged = notCalled("mergeContacts")
    override fun `message`(`msgId`: kotlin.ByteArray): FfiMessage? = notCalled("message")
    override fun `messages`(`chatId`: kotlin.ByteArray, `limit`: kotlin.UInt): List<FfiMessage> = notCalled("messages")
    override fun `messagesBefore`(`chatId`: kotlin.ByteArray, `before`: kotlin.ByteArray, `limit`: kotlin.UInt): List<FfiMessage> = notCalled("messagesBefore")
    override fun `myAddresses`(): FfiOwnCard = notCalled("myAddresses")
    override fun `myAvatar`(): kotlin.ByteArray? = notCalled("myAvatar")
    override fun `myContactUri`(): kotlin.String = notCalled("myContactUri")
    override fun `networkChanged`(): Unit = notCalled("networkChanged")
    override fun `nostrAdvertisedRelays`(): List<kotlin.String> = notCalled("nostrAdvertisedRelays")
    override fun `nostrDirect`(): kotlin.Boolean = notCalled("nostrDirect")
    override fun `nostrNpub`(): kotlin.String = notCalled("nostrNpub")
    override fun `nostrRelays`(): List<kotlin.String> = notCalled("nostrRelays")
    override fun `nostrRelaysAlive`(): List<FfiNostrRelay>? = notCalled("nostrRelaysAlive")
    override fun `openFile`(`fileId`: kotlin.ByteArray): FfiFileReader? = notCalled("openFile")
    override fun `pairDevice`(`label`: kotlin.String): Unit = notCalled("pairDevice")
    override fun `pauseFile`(`fileId`: kotlin.ByteArray): Unit = notCalled("pauseFile")
    override fun `previewOf`(`fileId`: kotlin.ByteArray): kotlin.ByteArray? = notCalled("previewOf")
    override fun `renameGroup`(`chatId`: kotlin.ByteArray, `title`: kotlin.String): Unit = notCalled("renameGroup")
    override fun `reply`(`chatId`: kotlin.ByteArray, `replyTo`: kotlin.ByteArray, `text`: kotlin.String): Unit = notCalled("reply")
    override fun `retractMessages`(`chatId`: kotlin.ByteArray, `msgIds`: List<kotlin.ByteArray>): Unit = notCalled("retractMessages")
    override fun `revokePairing`(`deviceId`: kotlin.ByteArray): Unit = notCalled("revokePairing")
    override fun `revokeVerification`(`peerIk`: kotlin.ByteArray): Unit = notCalled("revokeVerification")
    override fun `search`(`chatId`: kotlin.ByteArray?, `query`: kotlin.String, `limit`: kotlin.UInt): List<FfiMessage> = notCalled("search")
    override fun `sendFiles`(`chatId`: kotlin.ByteArray, `files`: List<FfiOutgoingFile>, `text`: kotlin.String): Unit = notCalled("sendFiles")
    override fun `sendText`(`chatId`: kotlin.ByteArray, `text`: kotlin.String): Unit = notCalled("sendText")
    override fun `setAutoAcceptBytes`(`limit`: kotlin.ULong?): Unit = notCalled("setAutoAcceptBytes")
    override fun `setAvatar`(`bytes`: kotlin.ByteArray?): Unit = notCalled("setAvatar")
    override fun `setForeground`(`front`: kotlin.Boolean): Unit = notCalled("setForeground")
    override fun `setGroupAvatar`(`chatId`: kotlin.ByteArray, `bytes`: kotlin.ByteArray?): Unit = notCalled("setGroupAvatar")
    override fun `setLocalName`(`peerIk`: kotlin.ByteArray, `name`: kotlin.String?): Unit = notCalled("setLocalName")
    override fun `setMailAccount`(`address`: kotlin.String, `password`: kotlin.String, `imapHost`: kotlin.String, `imapPort`: kotlin.UShort, `smtpHost`: kotlin.String, `smtpPort`: kotlin.UShort, `viaTor`: kotlin.Boolean): Unit = notCalled("setMailAccount")
    override fun `setNostrDirect`(`direct`: kotlin.Boolean): Unit = notCalled("setNostrDirect")
    override fun `setNostrRelays`(`relays`: List<kotlin.String>): Unit = notCalled("setNostrRelays")
    override fun `setObserver`(`observer`: EventObserver): Unit = notCalled("setObserver")
    override fun `setReaction`(`chatId`: kotlin.ByteArray, `msgId`: kotlin.ByteArray, `emoji`: kotlin.String?): Unit = notCalled("setReaction")
    override fun `setTransportEnabled`(`transport`: FfiTransport, `enabled`: kotlin.Boolean): Unit = notCalled("setTransportEnabled")
    override fun `setYggKey`(`key`: kotlin.ByteArray): Unit = notCalled("setYggKey")
    override fun `setYggMode`(`mode`: FfiYggMode): Unit = notCalled("setYggMode")
    override fun `setYggPeers`(`peers`: List<kotlin.String>): Unit = notCalled("setYggPeers")
    override fun `shareContact`(`chatId`: kotlin.ByteArray, `peerIk`: kotlin.ByteArray): Unit = notCalled("shareContact")
    override fun `sweepOrphanFiles`(): FfiSwept = notCalled("sweepOrphanFiles")
    override fun `torStatus`(): FfiTorStatus? = notCalled("torStatus")
    override fun `transportEnabled`(`transport`: FfiTransport): kotlin.Boolean = notCalled("transportEnabled")
    override fun `transportReady`(`transport`: FfiTransport): kotlin.Boolean = notCalled("transportReady")
    override fun `yggKey`(): kotlin.ByteArray = notCalled("yggKey")
    override fun `yggMode`(): FfiYggMode = notCalled("yggMode")
    override fun `yggPeers`(): List<kotlin.String> = notCalled("yggPeers")
    override fun `yggPeersAlive`(): List<FfiYggPeer>? = notCalled("yggPeersAlive")
}

/**
 * Заглушка [RatatoskCompanionInterface]: каждый вызов падает с именем метода.
 *
 * Проверка объявляет только то, что ей нужно, — а всё, чего модель звать
 * не должна была, само скажет об этом в отчёте, вместо `NullPointerException`
 * где-то в середине.
 */
open class FakeCompanion : RatatoskCompanionInterface {
    override fun `acceptFile`(`fileId`: kotlin.ByteArray): Unit = notCalled("acceptFile")
    override fun `addSharedContact`(`msgId`: kotlin.ByteArray): Unit = notCalled("addSharedContact")
    override fun `avatar`(`chatId`: kotlin.ByteArray?): Unit = notCalled("avatar")
    override fun `cancelSave`(): Unit = notCalled("cancelSave")
    override fun `cancelSend`(): Unit = notCalled("cancelSend")
    override fun `chats`(): Unit = notCalled("chats")
    override fun `clearChat`(`chatId`: kotlin.ByteArray): Unit = notCalled("clearChat")
    override fun `createGroup`(`title`: kotlin.String): Unit = notCalled("createGroup")
    override fun `declineFile`(`fileId`: kotlin.ByteArray): Unit = notCalled("declineFile")
    override fun `deleteMessages`(`chatId`: kotlin.ByteArray, `msgIds`: List<kotlin.ByteArray>): Unit = notCalled("deleteMessages")
    override fun `desktopIk`(): kotlin.ByteArray = notCalled("desktopIk")
    override fun `deviceId`(): kotlin.ByteArray = notCalled("deviceId")
    override fun `editMessage`(`chatId`: kotlin.ByteArray, `msgId`: kotlin.ByteArray, `text`: kotlin.String): Unit = notCalled("editMessage")
    override fun `evictFromGroup`(`chatId`: kotlin.ByteArray, `memberChatId`: kotlin.ByteArray): Unit = notCalled("evictFromGroup")
    override fun `forwardMessages`(`chatId`: kotlin.ByteArray, `msgIds`: List<kotlin.ByteArray>): Unit = notCalled("forwardMessages")
    override fun `history`(`chatId`: kotlin.ByteArray, `limit`: kotlin.UInt, `before`: kotlin.ByteArray?): Unit = notCalled("history")
    override fun `inviteToGroup`(`chatId`: kotlin.ByteArray, `memberChatId`: kotlin.ByteArray): Unit = notCalled("inviteToGroup")
    override fun `leaveGroup`(`chatId`: kotlin.ByteArray): Unit = notCalled("leaveGroup")
    override fun `markRead`(`chatId`: kotlin.ByteArray, `upTo`: kotlin.ByteArray): Unit = notCalled("markRead")
    override fun `members`(`chatId`: kotlin.ByteArray): Unit = notCalled("members")
    override fun `pauseFile`(`fileId`: kotlin.ByteArray): Unit = notCalled("pauseFile")
    override fun `phoneName`(): kotlin.String = notCalled("phoneName")
    override fun `port`(): kotlin.UShort = notCalled("port")
    override fun `preview`(`fileId`: kotlin.ByteArray): Unit = notCalled("preview")
    override fun `renameGroup`(`chatId`: kotlin.ByteArray, `title`: kotlin.String): Unit = notCalled("renameGroup")
    override fun `retractMessages`(`chatId`: kotlin.ByteArray, `msgIds`: List<kotlin.ByteArray>): Unit = notCalled("retractMessages")
    override fun `saveFile`(`fileId`: kotlin.ByteArray, `chunkTotal`: kotlin.ULong, `chunkBytes`: kotlin.ULong, `path`: kotlin.String): Unit = notCalled("saveFile")
    override fun `sendFiles`(`chatId`: kotlin.ByteArray, `files`: List<FfiCompanionOutgoing>, `text`: kotlin.String): Unit = notCalled("sendFiles")
    override fun `sendReply`(`chatId`: kotlin.ByteArray, `replyTo`: kotlin.ByteArray, `text`: kotlin.String): Unit = notCalled("sendReply")
    override fun `sendText`(`chatId`: kotlin.ByteArray, `text`: kotlin.String): Unit = notCalled("sendText")
    override fun `setAvatar`(`bytes`: kotlin.ByteArray?): Unit = notCalled("setAvatar")
    override fun `setCachePath`(`path`: kotlin.String?): Unit = notCalled("setCachePath")
    override fun `setGroupAvatar`(`chatId`: kotlin.ByteArray, `bytes`: kotlin.ByteArray?): Unit = notCalled("setGroupAvatar")
    override fun `setObserver`(`observer`: CompanionObserver): Unit = notCalled("setObserver")
    override fun `setReaction`(`chatId`: kotlin.ByteArray, `msgId`: kotlin.ByteArray, `emoji`: kotlin.String): Unit = notCalled("setReaction")
    override fun `shareContact`(`chatId`: kotlin.ByteArray, `whoChatId`: kotlin.ByteArray?): Unit = notCalled("shareContact")
}

/**
 * Подставное ядро.
 *
 * Клиент и второй экран подменяются целиком, события кладутся руками —
 * так проверка может показать модели ровно ту последовательность,
 * которая на живом ядре ловится раз в месяц и только у одного человека.
 */
class FakeBackend(
    var clientImpl: RatatoskClientInterface = FakeClient(),
    var companionImpl: RatatoskCompanionInterface = FakeCompanion(),
    override var isCompanion: Boolean = false,
    override var isInitialized: Boolean = true,
    override var activeAccountId: String? = "aa",
) : chat.ratatosk.android.ui.model.Backend {

    // С повтором: проверка кладёт события когда ей удобно, а модель
    // подписывается в своей корутине — без повтора между этим успевает
    // пройти гонка, и событие теряется. Настоящее ядро тоже с повтором.
    val eventFlow = kotlinx.coroutines.flow.MutableSharedFlow<FfiEvent>(replay = 64, extraBufferCapacity = 64)
    val companionEventFlow =
        kotlinx.coroutines.flow.MutableSharedFlow<FfiCompanionEvent>(replay = 64, extraBufferCapacity = 64)

    override val events: kotlinx.coroutines.flow.Flow<FfiEvent> get() = eventFlow
    override val companionEvents: kotlinx.coroutines.flow.Flow<FfiCompanionEvent> get() = companionEventFlow

    override fun client(): RatatoskClientInterface = clientImpl
    override fun companion(): RatatoskCompanionInterface = companionImpl

    override fun listAccounts(): List<FfiAccount> = emptyList()
    override fun createAccount(label: String): FfiAccount = notCalled("createAccount")
    override fun openAccount(accountId: ByteArray, pin: String?, deviceKey: ByteArray?, displayName: String) =
        notCalled("openAccount")

    override fun wipeAccount(accountId: ByteArray) = notCalled("wipeAccount")
    override fun findHidden(pin: String): ByteArray? = null

    override fun exportHistory(path: String, scope: FfiExportScope, phrase: String?): FfiExported =
        notCalled("exportHistory")

    override fun importArchive(
        context: android.content.Context,
        path: String,
        unlock: FfiArchiveUnlock,
        label: String,
    ): FfiImported = notCalled("importArchive")

    override fun peekArchive(path: String): FfiArchivePeek = notCalled("peekArchive")

    override fun openCompanion(
        inviteUri: String,
        port: UShort,
        peerAddr: String?,
        cachePath: String?,
        torDir: String?,
    ) = notCalled("openCompanion")

    override fun ensureBtRadio(context: android.content.Context): Boolean = false
    override fun hasBtRadio(): Boolean = false
    override fun coreLogFile(context: android.content.Context): java.io.File = notCalled("coreLogFile")
}
