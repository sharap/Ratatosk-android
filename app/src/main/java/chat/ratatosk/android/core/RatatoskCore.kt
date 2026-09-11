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

    private var activeAccountIdHex: String? = null
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
        synchronized(this) {
            android.util.Log.d("RatatoskCore", "InitializeCompanion called for: $inviteUri")
            
            client?.destroy()
            client = null
            companion?.destroy()
            companion = null
            companionChatCache.clear()
            
            var lastError: Throwable? = null
            for (attempt in 1..5) {
                try {
                    val newCompanion = RatatoskCompanion.open(inviteUri, port, peerAddr, cachePath, torDir)
                    newCompanion.setObserver(this)
                    
                    companion = newCompanion
                    activeAccountIdHex = "companion:${inviteUri.hashCode()}"
                    isCompanionMode = true
                    nativeError = null
                    return newCompanion
                } catch (t: Throwable) {
                    lastError = t
                    val msg = t.message ?: ""
                    if (msg.contains("Address already in use") || msg.contains("98")) {
                        android.util.Log.w("RatatoskCore", "Port $port still busy after destroy (attempt $attempt), waiting...")
                        Thread.sleep(200 * attempt.toLong()) // Exponential backoff
                        continue
                    }
                    break
                }
            }
            
            android.util.Log.e("RatatoskCore", "Failed to initialize companion after retries", lastError)
            nativeError = lastError
            throw lastError ?: RuntimeException("Unknown initialization error")
        }
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
        val filesDir = File(root, idHex).absolutePath
        
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
        android.util.Log.i("RatatoskCore", "RECEIVING EVENT: $`event`")
        if (`event` is FfiEvent.TorStatus) {
            android.util.Log.i("RatatoskCore", "Tor status: [${(`event`.fraction * 100).toInt()}%] ${`event`.note}${`event`.blocked?.let { " (BLOCKED: $it)" } ?: ""}")
        }
        val success = _events.tryEmit(`event`)
        if (!success) {
            android.util.Log.w("RatatoskCore", "Event buffer full, event might be delayed or dropped!")
        }
    }

    override fun onEvent(`event`: FfiCompanionEvent) {
        android.util.Log.i("RatatoskCore", "RECEIVING COMPANION EVENT: $`event`")
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
