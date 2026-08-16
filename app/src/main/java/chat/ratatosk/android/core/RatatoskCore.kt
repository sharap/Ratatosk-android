package chat.ratatosk.android.core

import android.content.Context
import uniffi.ratatosk_ffi.EventObserver
import uniffi.ratatosk_ffi.FfiEvent
import uniffi.ratatosk_ffi.RatatoskClient
import uniffi.ratatosk_ffi.RatatoskException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import java.io.File

object RatatoskCore : EventObserver {
    @Volatile
    private var client: RatatoskClient? = null
    
    @Volatile
    private var nativeError: Throwable? = null

    // Use a buffer with replay to ensure UI doesn't miss events during transitions
    private val _events = MutableSharedFlow<FfiEvent>(
        replay = 20, 
        extraBufferCapacity = 100,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events = _events.asSharedFlow()

    @Throws(RatatoskException::class)
    fun initialize(context: Context, pin: String?, displayName: String): RatatoskClient {
        synchronized(this) {
            android.util.Log.d("RatatoskCore", "Initialize called. Current client: ${if (client != null) "ACTIVE" else "NULL"}")
            
            val currentClient = client
            if (currentClient != null) {
                android.util.Log.d("RatatoskCore", "Returning existing client")
                return currentClient
            }
            
            return try {
                val dbFile = File(context.filesDir, "ratatosk.db")
                android.util.Log.d("RatatoskCore", "Opening database at: ${dbFile.absolutePath}")
                dbFile.parentFile?.mkdirs()
                
                val newClient = RatatoskClient.open(dbFile.absolutePath, pin, displayName)
                android.util.Log.d("RatatoskCore", "Native client opened successfully")
                
                newClient.setObserver(this)
                newClient.networkChanged() // Kickstart discovery
                client = newClient
                nativeError = null 
                newClient
            } catch (t: Throwable) {
                android.util.Log.e("RatatoskCore", "Failed to initialize native core", t)
                nativeError = t
                throw t
            }
        }
    }

    override fun onEvent(`event`: FfiEvent) {
        android.util.Log.d("RatatoskCore", "Event from native: $`event`")
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

    fun accountExists(context: Context): Boolean {
        val dbFile = File(context.filesDir, "ratatosk.db")
        return dbFile.exists()
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
