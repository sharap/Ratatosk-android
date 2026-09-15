package chat.ratatosk.android.core

import android.content.Context
import org.ratatosk.core.EventObserver
import org.ratatosk.core.CompanionObserver
import org.ratatosk.core.FfiEvent
import org.ratatosk.core.FfiCompanionEvent
import org.ratatosk.core.RatatoskClient
import org.ratatosk.core.RatatoskCompanion
import org.ratatosk.core.RatatoskException
import org.ratatosk.core.AccountRegistry
import org.ratatosk.core.FfiAccount
import chat.ratatosk.android.util.toHexString
import org.ratatosk.bt.BtRadio
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import java.io.File

object RatatoskCore : EventObserver, CompanionObserver {
    @Volatile
    private var client: RatatoskClient? = null

    @Volatile
    private var companion: RatatoskCompanion? = null

    @Volatile
    private var registry: AccountRegistry? = null
    
    @Volatile
    private var nativeError: Throwable? = null

    // @Volatile обязателен: пишутся они под synchronized(this), а читаются
    // без него — isCompanionMode() и getActiveAccountId() зовут из фоновых
    // корутин на каждый вызов ядра. Без барьера фоновый поток может увидеть
    // прежний режим и уйти к клиенту вместо компаньона.
    @Volatile
    private var activeAccountIdHex: String? = null

    @Volatile
    private var isCompanionMode: Boolean = false

    private val companionChatCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    // Session credentials stored ONLY in RAM
    private data class SessionCredentials(
        val accountId: ByteArray,
        val pin: String?,
        val deviceKey: ByteArray?,
        val displayName: String
    )
    private var sessionCredentials: SessionCredentials? = null

    // Use a buffer with replay to ensure UI doesn't miss events during transitions
    private val _events = MutableSharedFlow<FfiEvent>(
        replay = 50, 
        extraBufferCapacity = 500,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events = _events.asSharedFlow()

    private val _companionEvents = MutableSharedFlow<FfiCompanionEvent>(
        replay = 50,
        extraBufferCapacity = 500,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val companionEvents = _companionEvents.asSharedFlow()

    /**
     * Подробность журнала ядра. Синтаксис `RUST_LOG`.
     *
     * Пустая строка означала бы умолчание самого ядра — наши крейты
     * подробно, чужие по делу. Здесь оно сужено под текущую задачу:
     * эфир полностью, остальное как в умолчании. Разбираем сейчас именно
     * его, а `trace` на всех ступнях сразу утопил бы нужное.
     */
    private const val LOG_FILTER =
        "ratatosk_transport::bluetooth=trace,ratatosk_transport=debug," +
            "ratatosk_core=debug,ratatosk_ffi=debug,info"

    @Volatile
    private var loggingStarted = false

    /** Замок вручения радио: см. [ensureBtRadio]. */
    private val btRadioLock = Any()

    /**
     * Файл журнала ядра. Один на приложение, лежит в приватном каталоге.
     *
     * Не в кэше: кэш система вправе вычистить когда угодно, в том числе
     * ровно между поломкой и попыткой её показать.
     */
    fun coreLogFile(context: Context): java.io.File =
        java.io.File(java.io.File(context.filesDir, "logs"), "core.log")

    /**
     * Просит ядро завести журнал.
     *
     * Без просьбы ядро молчит по построению: `tracing` без подписчика
     * никуда не пишет. Ставить его само оно не вправе — решать, писать ли
     * внутренности приложения в журнал, не дело библиотеки.
     *
     * Два вида, и они взаимоисключающие (так устроено ядро):
     *
     * * **в файл** — когда человек включил сбор. Работает в любой сборке,
     *   и только отсюда журнал можно достать и прислать: у упакованного
     *   приложения потока ошибок нет, а `logcat` с чужого телефона
     *   не снимешь;
     * * **в logcat** — иначе и только в отладочной сборке.
     *
     * Подписчик ставится один раз на процесс, поэтому переключение
     * применяется со следующего запуска — это надо сказать человеку.
     *
     * Файл ядро перезаписывает на каждом запуске и каталог не создаёт:
     * создаём мы.
     */
    fun startLogging(context: Context, toFile: Boolean) {
        synchronized(this) {
            if (loggingStarted) return
            loggingStarted = true
        }
        try {
            if (toFile) {
                val file = coreLogFile(context)
                file.parentFile?.mkdirs()
                org.ratatosk.core.enableFileLogging(LOG_FILTER, file.absolutePath)
                android.util.Log.i("RatatoskCore", "core logging to file: ${file.absolutePath}")
            } else if (chat.ratatosk.android.BuildConfig.DEBUG) {
                org.ratatosk.core.enableLogging(LOG_FILTER)
                android.util.Log.i("RatatoskCore", "core logging to logcat")
            }
        } catch (t: Throwable) {
            android.util.Log.w("RatatoskCore", "core logging unavailable: ${t.message}")
        }
    }

    @Throws(RatatoskException::class)
    fun initializeRegistry(context: Context): AccountRegistry {
        synchronized(this) {
            registry?.let { return it }
            val root = File(context.filesDir, "ratatosk_root")
            root.mkdirs()
            val newRegistry = AccountRegistry.open(root.absolutePath)
            registry = newRegistry
            return newRegistry
        }
    }

    @Throws(RatatoskException::class)
    fun initialize(accountId: ByteArray, pin: String?, deviceKey: ByteArray? = null, displayName: String): RatatoskClient {
        synchronized(this) {
            val accountIdHex = accountId.toHexString()
            android.util.Log.d("RatatoskCore", "Initialize called for account: $accountIdHex. Current client: ${if (client != null) "ACTIVE" else "NULL"}")
            
            if (client != null && activeAccountIdHex == accountIdHex && !isCompanionMode) {
                android.util.Log.d("RatatoskCore", "Returning existing client for same account")
                return client!!
            }

            // Close existing client/companion if switching
            client?.destroy()
            client = null
            companion?.destroy()
            companion = null
            companionChatCache.clear()
            
            return try {
                val reg = registry ?: throw IllegalStateException("Registry not initialized")
                val newClient = reg.openAccount(accountId, pin, deviceKey, displayName)
                android.util.Log.d("RatatoskCore", "Native client opened successfully")
                
                newClient.setObserver(this)
                
                // §5.1: Announce this account in LAN
                reg.setForeground(accountId)
                
                // Save credentials for auto-recovery (in-RAM only)
                sessionCredentials = SessionCredentials(accountId, pin, deviceKey, displayName)
                
                client = newClient
                activeAccountIdHex = accountIdHex
                isCompanionMode = false
                nativeError = null 
                newClient
            } catch (t: Throwable) {
                android.util.Log.e("RatatoskCore", "Failed to initialize native core for account $accountIdHex", t)
                nativeError = t
                throw t
            }
        }
    }

    @Throws(RatatoskException::class)
    fun initializeCompanion(inviteUri: String, port: UShort, peerAddr: String?, cachePath: String?, torDir: String?): RatatoskCompanion {
        android.util.Log.d("RatatoskCore", "InitializeCompanion called")

        synchronized(this) {
            client?.destroy()
            client = null
            companion?.destroy()
            companion = null
            companionChatCache.clear()
        }

        // Ожидание между попытками — **вне** монитора, и это не мелочь.
        // Порт освобождает ядро, которое мы только что уничтожили; пауза
        // здесь может дойти до трёх секунд, и раньше все эти секунды
        // монитор был занят, то есть стоял любой другой поток, зашедший
        // в RatatoskCore.
        var lastError: Throwable? = null
        for (attempt in 1..5) {
            if (attempt > 1) {
                val pause = 200L * (attempt - 1)
                android.util.Log.w("RatatoskCore", "Port $port still busy, waiting ${pause}ms (attempt $attempt)")
                try {
                    Thread.sleep(pause)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw interrupted
                }
            }
            try {
                val newCompanion = RatatoskCompanion.open(inviteUri, port, peerAddr, cachePath, torDir)
                newCompanion.setObserver(this)
                synchronized(this) {
                    companion = newCompanion
                    activeAccountIdHex = "companion:${inviteUri.hashCode()}"
                    isCompanionMode = true
                    nativeError = null
                }
                return newCompanion
            } catch (t: Throwable) {
                lastError = t
                val msg = t.message ?: ""
                // Занятый порт — единственная причина, по которой стоит
                // пробовать снова: его вот-вот отпустит прежнее ядро.
                if (msg.contains("Address already in use") || msg.contains("98")) {
                    continue
                }
                break
            }
        }

        android.util.Log.e("RatatoskCore", "Failed to initialize companion after retries", lastError)
        nativeError = lastError
        throw lastError ?: RuntimeException("Unknown initialization error")
    }

    fun setForeground(accountId: ByteArray?) {
        registry?.setForeground(accountId)
    }

    fun findHidden(pin: String): ByteArray? {
        return registry?.findHidden(pin)
    }

    fun createHidden(): ByteArray {
        val reg = registry ?: throw IllegalStateException("Registry not initialized")
        return reg.createHidden()
    }

    fun createAccount(label: String): FfiAccount {
        val reg = registry ?: throw IllegalStateException("Registry not initialized")
        return reg.create(label)
    }

    fun wipeAccount(accountId: ByteArray) {
        synchronized(this) {
            registry?.wipe(accountId)
        }
    }

    @Throws(RatatoskException::class)
    fun exportHistory(path: String, scope: org.ratatosk.core.FfiExportScope, phrase: String?): org.ratatosk.core.FfiExported {
        return getClient().exportHistory(path, scope, phrase)
    }

    @Throws(RatatoskException::class)
    fun peekArchive(path: String): org.ratatosk.core.FfiArchivePeek {
        return org.ratatosk.core.peekArchive(path)
    }

    @Throws(RatatoskException::class)
    fun importArchive(context: Context, archivePath: String, unlock: org.ratatosk.core.FfiArchiveUnlock, label: String): org.ratatosk.core.FfiImported {
        val root = File(context.filesDir, "ratatosk_root")
        
        // Reverting to the simpler random-ID strategy that worked previously
        val accountId = java.util.UUID.randomUUID().toString().replace("-", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val idHex = accountId.toHexString()
        val destination = File(root, "$idHex.db").absolutePath
        // Каталог вложений — строго "<id>.files": так его строит ядро
        // (AccountRegistry: BLOBS_EXTENSION = "files"), и так же его будет
        // искать openAccount после восстановления. Без расширения куски
        // вложений ложились в "<id>/", аккаунт открывался и не находил
        // ни одного — вся переписка восстанавливалась без картинок.
        val filesDir = File(root, "$idHex.files").absolutePath
        
        android.util.Log.d("RatatoskCore", "Restoring archive to $idHex.db")
        val result = org.ratatosk.core.importArchive(archivePath, unlock, destination, filesDir)
        
        registry?.adopt(accountId, label)
        android.util.Log.i("RatatoskCore", "Account restored and adopted with ID $idHex")
        return result
    }
    fun logout() {
        synchronized(this) {
            registry?.setForeground(null)
            client?.destroy()
            client = null
            companion?.destroy()
            companion = null
            activeAccountIdHex = null
            sessionCredentials = null
            isCompanionMode = false
            companionChatCache.clear()
        }
    }

    fun tryAutoInitialize(): RatatoskClient? {
        val creds = sessionCredentials ?: return null
        return try {
            android.util.Log.i("RatatoskCore", "Attempting auto-reinitialization for account: ${creds.accountId.toHexString()}")
            initialize(creds.accountId, creds.pin, creds.deviceKey, creds.displayName)
        } catch (e: Exception) {
            android.util.Log.e("RatatoskCore", "Auto-reinitialization failed", e)
            null
        }
    }

    override fun onEvent(`event`: FfiEvent) {
        // Только вид события, без полей. FfiEvent несёт идентификаторы чатов
        // и сообщений, то есть метаданные переписки, а у FfiCompanionEvent
        // внутри лежит и сам текст. Журнал приложения переживает выключение
        // и читается отладчиком — писать туда то, что мы шифруем на диске,
        // значит обойти собственное шифрование.
        android.util.Log.i("RatatoskCore", "event: ${`event`.eventName()}")
        if (`event` is FfiEvent.TorStatus) {
            android.util.Log.i("RatatoskCore", "Tor status: [${(`event`.fraction * 100).toInt()}%] ${`event`.note}${`event`.blocked?.let { " (BLOCKED: $it)" } ?: ""}")
        }
        val success = _events.tryEmit(`event`)
        if (!success) {
            android.util.Log.w("RatatoskCore", "Event buffer full, event might be delayed or dropped!")
        }
    }

    override fun onEvent(`event`: FfiCompanionEvent) {
        // Вид события и ничего больше — см. пояснение у onEvent(FfiEvent).
        // Здесь это особенно важно: FfiCompanionEvent.Arrived везёт
        // FfiCompanionMessage целиком, вместе с полем body.
        android.util.Log.i("RatatoskCore", "companion event: ${`event`.eventName()}")
        _companionEvents.tryEmit(`event`)
        
        when (`event`) {
            is FfiCompanionEvent.Chats -> {
                `event`.chats.forEach { chat ->
                    companionChatCache[chat.chatId.toHexString()] = chat.title
                }
            }
            is FfiCompanionEvent.Arrived -> {
                _events.tryEmit(FfiEvent.MessageReceived(`event`.message.chatId, `event`.message.msgId))
            }
            is FfiCompanionEvent.Edited -> {
                _events.tryEmit(FfiEvent.MessageEdited(`event`.message.chatId, `event`.message.msgId))
            }
            is FfiCompanionEvent.Reacted -> {
                _events.tryEmit(FfiEvent.ReactionChanged(`event`.chatId, `event`.msgId, ByteArray(0)))
            }
            is FfiCompanionEvent.StatusChanged -> {
                _events.tryEmit(FfiEvent.StatusChanged(`event`.msgId, `event`.status))
            }
            is FfiCompanionEvent.Gone -> {
                _events.tryEmit(FfiEvent.MessagesDeleted(`event`.chatId, `event`.msgIds))
            }
            is FfiCompanionEvent.FileProgress -> {
                _events.tryEmit(FfiEvent.FileProgress(`event`.fileId, `event`.haveChunks, `event`.chunkTotal))
            }
            is FfiCompanionEvent.ChatsChanged -> {
                _events.tryEmit(FfiEvent.ContactChanged(ByteArray(0)))
            }
            is FfiCompanionEvent.Avatar -> {
                // Signals that an avatar was received
                _events.tryEmit(FfiEvent.AvatarChanged(event.chatId ?: ByteArray(0)))
            }
            else -> {}
        }
    }

    // Имя ветки события без её содержимого. Пишется вручную, а не через
    // toString(): toString() у data-класса печатает все поля, и однажды
    // добавленное поле с текстом уехало бы в журнал молча.
    private fun FfiEvent.eventName(): String = this::class.java.simpleName

    private fun FfiCompanionEvent.eventName(): String = this::class.java.simpleName

    /**
     * Вручает ядру радио Bluetooth, если есть чем и кому.
     *
     * На Linux ступень работает своим радио; на Android BlueZ нет, и
     * объявление, обзор и канал живут в Java. Поэтому радио приходит
     * в ядро **снаружи**, а формат объявления, опознание маяком
     * и кадрирование остаются внутри.
     *
     * До этого вызова ступень не поднимается никак: включённая без радио,
     * она честно объявляется потерянной, и §5.4 идёт дальше по лестнице.
     *
     * Разрешения проверяются **до** вручения, а не после: розданное без
     * них радио уходит в `onLost` с текстом «нет разрешений», и человек
     * видит сломанную ступень вместо запроса.
     *
     * @return вручено ли радио (уже стоявшее — тоже да).
     */
    fun ensureBtRadio(context: Context): Boolean {
        val live = client ?: return false
        return try {
            val bridge = live.bluetooth()
            // Проверка и вручение — под одним замком.
            //
            // Служба зовёт это из onCreate и из onStartCommand, и они
            // приходят разными потоками почти одновременно. Проверка
            // `hasRadio()` отдельно от `setRadio` их не разводила: оба
            // видели «радио нет» и вручали своё. В журнале это было видно
            // как два объявления с разными PSM на один ключ в одном слоте
            // — то есть два серверных сокета и двойной расход эфира.
            //
            // Замок свой, а не общий монитор объекта: тот держат открытие
            // и закрытие аккаунта, а здесь под ним поднимается сокет.
            synchronized(btRadioLock) {
                if (bridge.hasRadio()) return true
                if (BtRadio.Permissions.missing(context).isNotEmpty()) return false
                bridge.setRadio(BtRadio(context.applicationContext, bridge))
            }
            android.util.Log.i("RatatoskCore", "Bluetooth radio handed to the core")
            true
        } catch (t: Throwable) {
            android.util.Log.e("RatatoskCore", "Failed to hand the Bluetooth radio", t)
            false
        }
    }

    /** Стоит ли у ядра радио. Экран настроек отличает этим «выключено» от «нечем». */
    fun hasBtRadio(): Boolean = try {
        client?.bluetooth()?.hasRadio() ?: false
    } catch (t: Throwable) {
        false
    }

    fun getClient(): RatatoskClient {
        val error = nativeError
        if (error != null) throw RuntimeException("Native core failed to load", error)
        return client ?: throw IllegalStateException("RatatoskCore not initialized")
    }

    fun getCompanion(): RatatoskCompanion {
        val error = nativeError
        if (error != null) throw RuntimeException("Native core failed to load", error)
        return companion ?: throw IllegalStateException("RatatoskCompanion not initialized")
    }
    
    fun isInitialized(): Boolean = client != null || companion != null

    fun isCompanionMode(): Boolean = isCompanionMode

    fun getCompanionChatTitle(chatId: ByteArray): String? {
        return companionChatCache[chatId.toHexString()]
    }

    fun getActiveAccountId(): String? = activeAccountIdHex

    fun listAccounts(): List<FfiAccount> {
        return safeCall { registry?.list() ?: emptyList() }.getOrDefault(emptyList())
    }

    fun anyAccountExists(context: Context): Boolean {
        val root = File(context.filesDir, "ratatosk_root")
        if (!root.exists()) return false
        // Basic check for files in root
        return root.list()?.isNotEmpty() ?: false
    }

    fun getNativeError(): Throwable? = nativeError
    
    /**
     * Attempts to call a top-level UniFFI function safely.
     */
    fun <T> safeCall(block: () -> T): Result<T> {
        return try {
            Result.success(block())
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}
