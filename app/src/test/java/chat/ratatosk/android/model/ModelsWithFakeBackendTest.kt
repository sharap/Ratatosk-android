package chat.ratatosk.android.model

import android.app.Application
import chat.ratatosk.android.data.SettingsRepository
import chat.ratatosk.android.fake.FakeBackend
import chat.ratatosk.android.fake.FakeClient
import chat.ratatosk.android.fake.FakeCompanion
import chat.ratatosk.android.ui.model.ChatsModel
import chat.ratatosk.android.ui.model.ClientModel
import chat.ratatosk.android.ui.model.ContactsModel
import chat.ratatosk.android.ui.model.FilesModel
import chat.ratatosk.android.ui.model.GroupsModel
import chat.ratatosk.android.ui.model.PairingModel
import chat.ratatosk.android.ui.model.SessionContext
import chat.ratatosk.android.ui.model.TransportsModel
import chat.ratatosk.android.util.toHexString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ratatosk.core.FfiEvent
import org.ratatosk.core.FfiFileWaitReason
import org.ratatosk.core.FfiMessage
import java.util.Collections

/**
 * Модели поверх подставного ядра: без библиотеки, без сети, без телефона.
 *
 * Главное здесь — куда уходят команды. Во втором экране клиента нет вовсе,
 * и команда, ушедшая в него, не «не сработает», а бросит исключение,
 * которое модель проглотит в `catch` и запишет в журнал: снаружи это
 * выглядит как «кнопка не работает», без единого слова о причине.
 */
class ModelsWithFakeBackendTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val dataDir: java.io.File = java.nio.file.Files.createTempDirectory("ratatosk-test").toFile()

    /**
     * Приложение ровно в той мере, в какой его трогают модели: настройки
     * живут в `DataStore`, а тому нужен каталог. Всё остальное в сборке
     * для проверок не подделано и честно падает, если его позвать.
     */
    private inner class TestApp : Application() {
        override fun getApplicationContext(): android.content.Context = this
        override fun getFilesDir(): java.io.File = dataDir
        override fun getCacheDir(): java.io.File = dataDir
    }

    private val app: Application = TestApp()

    private val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private inner class RecordingClient : FakeClient() {
        override fun `setAvatar`(`bytes`: kotlin.ByteArray?) {
            calls += "client.setAvatar:${bytes?.size ?: "null"}"
        }

        override fun `sendText`(`chatId`: kotlin.ByteArray, `text`: kotlin.String) {
            calls += "client.sendText:${chatId.toHexString()}:$text"
        }

        override fun `acceptFile`(`fileId`: kotlin.ByteArray) {
            calls += "client.acceptFile:${fileId.toHexString()}"
        }

        override fun `pauseFile`(`fileId`: kotlin.ByteArray) {
            calls += "client.pauseFile:${fileId.toHexString()}"
        }

        override fun `messages`(`chatId`: kotlin.ByteArray, `limit`: kotlin.UInt): List<FfiMessage> {
            calls += "client.messages:${chatId.toHexString()}"
            return emptyList()
        }
    }

    private inner class RecordingCompanion : FakeCompanion() {
        override fun `sendText`(`chatId`: kotlin.ByteArray, `text`: kotlin.String) {
            calls += "phone.sendText:${chatId.toHexString()}:$text"
        }

        override fun `acceptFile`(`fileId`: kotlin.ByteArray) {
            calls += "phone.acceptFile:${fileId.toHexString()}"
        }

        override fun `pauseFile`(`fileId`: kotlin.ByteArray) {
            calls += "phone.pauseFile:${fileId.toHexString()}"
        }

        override fun `history`(`chatId`: kotlin.ByteArray, `limit`: kotlin.UInt, `before`: kotlin.ByteArray?) {
            calls += "phone.history:${chatId.toHexString()}"
        }
    }

    private val backend = FakeBackend()

    private fun session(companion: Boolean = false): SessionContext {
        backend.isCompanion = companion
        backend.clientImpl = RecordingClient()
        backend.companionImpl = RecordingCompanion()
        return SessionContext(
            app = app,
            scope = scope,
            settings = SettingsRepository(app),
            core = backend,
            strings = { "строка:$it" },
        )
    }

    /**
     * Модели возвращаются в главный поток, чтобы состояние менялось в одном
     * месте. В проверках главного потока нет — подставляем свой.
     */
    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        scope.cancel()
        dataDir.deleteRecursively()
    }

    /** Снимок: список пополняют корутины моделей, и обход живого падает. */
    private fun seen(): List<String> = synchronized(calls) { calls.toList() }

    private fun waitUntil(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(5)
        }
        throw AssertionError("не дождались: $what; было: ${seen()}")
    }

    private val chatA = byteArrayOf(1, 2, 3)
    private val fileA = byteArrayOf(9, 9)

    @Test
    fun commandsOfTheFullClientGoToTheClient() {
        val s = session(companion = false)
        val chats = ChatsModel(s, previewFor = { null }, onChatOpened = {})
        val files = FilesModel(s) { chats.loadMessages(it) }

        chats.sendText(chatA, "привет")
        files.acceptFile(chatA, fileA)
        files.pauseFile(chatA, fileA)

        waitUntil("команды дошли до клиента") { calls.size >= 3 }
        assertTrue(seen().toString(), seen().contains("client.sendText:${chatA.toHexString()}:привет"))
        assertTrue(seen().toString(), seen().contains("client.acceptFile:${fileA.toHexString()}"))
        assertTrue(seen().toString(), seen().contains("client.pauseFile:${fileA.toHexString()}"))
        assertTrue("во втором экране делать нечего: ${seen()}", seen().none { it.startsWith("phone.") })
    }

    @Test
    fun commandsOfTheSecondScreenGoToThePhone() {
        val s = session(companion = true)
        s._isCompanionLinked.value = true
        val chats = ChatsModel(s, previewFor = { null }, onChatOpened = {})
        val files = FilesModel(s) { chats.loadMessages(it) }

        chats.sendText(chatA, "привет")
        files.acceptFile(chatA, fileA)
        files.pauseFile(chatA, fileA)
        chats.loadMessages(chatA)

        waitUntil("команды дошли до телефона") { calls.size >= 4 }
        assertTrue(seen().toString(), seen().contains("phone.sendText:${chatA.toHexString()}:привет"))
        assertTrue(seen().toString(), seen().contains("phone.acceptFile:${fileA.toHexString()}"))
        assertTrue(seen().toString(), seen().contains("phone.pauseFile:${fileA.toHexString()}"))
        assertTrue(seen().toString(), seen().contains("phone.history:${chatA.toHexString()}"))
        assertTrue("клиента во втором экране нет: ${seen()}", seen().none { it.startsWith("client.") })
    }

    /** Во втором экране без связи команда не уходит, а объясняет почему. */
    @Test
    fun withoutTheLinkTheSecondScreenSaysSoInsteadOfSending() {
        val s = session(companion = true)
        s._isCompanionLinked.value = false
        val chats = ChatsModel(s, previewFor = { null }, onChatOpened = {})

        chats.sendText(chatA, "привет")

        waitUntil("ошибка показана") { s.error.value != null }
        assertTrue("отправлять было некуда: ${seen()}", seen().none { it.contains("sendText") })
    }

    private fun clientModel(s: SessionContext, files: FilesModel, chats: ChatsModel): ClientModel {
        val groups = GroupsModel(s) {}
        return ClientModel(
            session = s,
            chats = chats,
            contacts = ContactsModel(s, groups, loadMessages = {}, openContact = {}),
            groups = groups,
            files = files,
            transports = TransportsModel(s),
            pairing = PairingModel(s),
            openCompanion = {},
            onCompanionMode = {},
        )
    }

    /**
     * Ход приёма и ход выкладывания сюда — разные вещи.
     *
     * `FileProgress` говорит, сколько собрал владелец файла; у второго
     * экрана это телефон, и у готового файла там всегда сто процентов.
     * Смешать их — значит показать полную полосу на файле, который сюда
     * ещё не приехал.
     */
    @Test
    fun receiveAndSaveProgressDoNotMix() {
        val s = session(companion = false)
        val chats = ChatsModel(s, previewFor = { null }, onChatOpened = {})
        val files = FilesModel(s) { chats.loadMessages(it) }
        val client = clientModel(s, files, chats)
        client.ensureClientEvents()

        val hex = fileA.toHexString()
        backend.eventFlow.tryEmit(FfiEvent.FileProgress(fileA, 3UL, 10UL))

        waitUntil("ход приёма отмечен") { files.fileProgress.value[hex] != null }
        assertEquals(0.3f, files.fileProgress.value[hex]!!, 0.001f)
        assertTrue("сюда ещё ничего не выкладывали", files.saveProgress.value.isEmpty())
    }

    /** Пошли байты — значит, ждать больше нечего (§10.3). */
    @Test
    fun movingBytesClearTheWaitingMark() {
        val s = session(companion = false)
        val chats = ChatsModel(s, previewFor = { null }, onChatOpened = {})
        val files = FilesModel(s) { chats.loadMessages(it) }
        val client = clientModel(s, files, chats)
        client.ensureClientEvents()

        val hex = fileA.toHexString()
        backend.eventFlow.tryEmit(FfiEvent.FileWaitsForChannel(fileA, FfiFileWaitReason.NOWHERE))
        waitUntil("ожидание отмечено") { files.fileWaiting.value[hex] != null }

        backend.eventFlow.tryEmit(FfiEvent.FileProgress(fileA, 1UL, 10UL))
        waitUntil("ожидание снято") { files.fileWaiting.value[hex] == null }
    }

    /** Непрочитанное считается только тому чату, который не открыт. */
    @Test
    fun unreadCountsOnlyForChatsThatAreNotOpen() {
        val s = session(companion = false)
        val chats = ChatsModel(s, previewFor = { null }, onChatOpened = {})
        val files = FilesModel(s) { chats.loadMessages(it) }
        val client = clientModel(s, files, chats)
        client.ensureClientEvents()

        val other = byteArrayOf(7, 7, 7)
        chats.setActiveChat(chatA)

        backend.eventFlow.tryEmit(FfiEvent.MessageReceived(chatA, byteArrayOf(1)))
        backend.eventFlow.tryEmit(FfiEvent.MessageReceived(other, byteArrayOf(2)))

        waitUntil("непрочитанное посчитано") { chats.unreadCounts.value[other.toHexString()] == 1 }
        // Открытый чат прочитан по определению: ноль, а не «одно новое».
        assertEquals(0, chats.unreadCounts.value[chatA.toHexString()] ?: 0)
    }

    /**
     * Выход из аккаунта не оставляет от него ничего.
     *
     * Проверка поведением, а не списком полей: `ModelsHygieneTest` следит,
     * чтобы модель не забыли позвать, а здесь — что позванное и правда
     * стирает. Чужая переписка, мелькнувшая у следующего аккаунта, —
     * худшее, что может сделать этот раздел.
     */
    @Test
    fun logoutLeavesNothingOfThePreviousAccount() {
        val models = chat.ratatosk.android.ui.model.AppModels(app, backend) { "строка:$it" }
        backend.isCompanion = false
        backend.clientImpl = RecordingClient()
        models.client.ensureClientEvents()

        val other = byteArrayOf(7, 7, 7)
        backend.eventFlow.tryEmit(FfiEvent.MessageReceived(other, byteArrayOf(2)))
        backend.eventFlow.tryEmit(FfiEvent.FileProgress(fileA, 3UL, 10UL))
        backend.eventFlow.tryEmit(FfiEvent.FileWaitsForChannel(fileA, FfiFileWaitReason.NOWHERE))
        models.share.shareInto(chatA, "черновик", emptyList())

        waitUntil("состояние набралось") {
            models.chats.unreadCounts.value.isNotEmpty() &&
                models.files.fileProgress.value.isNotEmpty() &&
                models.files.fileWaiting.value.isNotEmpty() &&
                models.share.sharedDraft.value != null
        }

        models.resetAll()

        assertTrue("непрочитанное", models.chats.unreadCounts.value.isEmpty())
        assertTrue("переписка", models.chats.messages.value.isEmpty())
        assertTrue("ход приёма", models.files.fileProgress.value.isEmpty())
        assertTrue("ожидание файлов", models.files.fileWaiting.value.isEmpty())
        assertTrue("контакты", models.contacts.contacts.value.isEmpty())
        assertTrue("группы", models.groups.groups.value.isEmpty())
        assertEquals(null, models.share.sharedDraft.value)
        assertEquals(null, models.session.activeAccountId.value)
        assertEquals(null, models.session.error.value)
        models.close()
    }

    /**
     * Снятое фото доходит до ядра и пропадает с экрана.
     *
     * `set_avatar(null)` — это «лица больше нет», а не ошибка сжатия:
     * `null` тут значение, а не сбой, и перепутать их легко (этап A
     * чинил ровно обратное — случайный `null` стирал прежнее фото).
     */
    @Test
    fun removingTheOwnPhotoReachesTheCore() {
        val s = session(companion = false)
        val groups = GroupsModel(s) {}
        val contacts = ContactsModel(s, groups, loadMessages = {}, openContact = {})

        contacts.setAvatar(byteArrayOf(1, 2, 3))
        waitUntil("фото поставлено") { contacts.myAvatar.value != null }

        contacts.setAvatar(null)

        waitUntil("фото снято") { contacts.myAvatar.value == null }
        assertEquals(listOf("client.setAvatar:3", "client.setAvatar:null"), seen())
    }
}
