package chat.ratatosk.android.core

import android.content.Context
import org.ratatosk.core.EventObserver
import org.ratatosk.core.FfiEvent
import org.ratatosk.core.RatatoskClient
import org.ratatosk.core.RatatoskException
import org.ratatosk.core.AccountRegistry
import org.ratatosk.core.FfiAccount
import chat.ratatosk.android.util.toHexString
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import java.io.File

object RatatoskCore : EventObserver {
    @Volatile
    private var client: RatatoskClient? = null

    @Volatile
    private var registry: AccountRegistry? = null
    
    @Volatile
    private var nativeError: Throwable? = null

    private var activeAccountIdHex: String? = null

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
        replay = 20, 
        extraBufferCapacity = 100,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events = _events.asSharedFlow()

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
            
            if (client != null && activeAccountIdHex == accountIdHex) {
                android.util.Log.d("RatatoskCore", "Returning existing client for same account")
                return client!!
            }

            // Close existing client if switching accounts
            client?.destroy()
            client = null
            
            return try {
                val reg = registry ?: throw IllegalStateException("Registry not initialized")
                val newClient = reg.openAccount(accountId, pin, deviceKey, displayName)
                android.util.Log.d("RatatoskCore", "Native client opened successfully")
                
                newClient.setObserver(this)
                newClient.networkChanged() // Kickstart discovery
                
                // §5.1: Announce this account in LAN
                reg.setForeground(accountId)
                
                // Save credentials for auto-recovery (in-RAM only)
                sessionCredentials = SessionCredentials(accountId, pin, deviceKey, displayName)
                
                client = newClient
                activeAccountIdHex = accountIdHex
                nativeError = null 
                newClient
            } catch (t: Throwable) {
                android.util.Log.e("RatatoskCore", "Failed to initialize native core for account $accountIdHex", t)
                nativeError = t
                throw t
            }
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

    fun logout() {
        synchronized(this) {
            registry?.setForeground(null)
            client?.destroy()
            client = null
            activeAccountIdHex = null
            sessionCredentials = null
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
        android.util.Log.e("RatatoskCore", "RECEIVING EVENT: $`event`")
        if (`event` is FfiEvent.TorStatus) {
            android.util.Log.i("RatatoskCore", "Tor status: [${(`event`.fraction * 100).toInt()}%] ${`event`.note}${`event`.blocked?.let { " (BLOCKED: $it)" } ?: ""}")
        } else {
            android.util.Log.d("RatatoskCore", "Event from native: $`event`")
        }
        val success = _events.tryEmit(`event`)
        if (!success) {
            android.util.Log.w("RatatoskCore", "Event buffer full, event might be delayed or dropped!")
        }
    }

    fun getClient(): RatatoskClient {
        val error = nativeError
        if (error != null) throw RuntimeException("Native core failed to load", error)
        return client ?: throw IllegalStateException("RatatoskCore not initialized")
    }
    
    fun isInitialized(): Boolean = client != null

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
