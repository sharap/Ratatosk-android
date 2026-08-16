package chat.ratatosk.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import chat.ratatosk.android.MainActivity
import chat.ratatosk.android.R
import chat.ratatosk.android.core.RatatoskCore
import chat.ratatosk.android.data.SettingsRepository
import chat.ratatosk.android.util.toHexString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.ratatosk_ffi.FfiEvent

class RatatoskService : Service() {
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            notifyCore()
        }

        override fun onLost(network: Network) {
            notifyCore()
        }
    }

    companion object {
        const val SERVICE_CHANNEL_ID = "ratatosk_service_channel"
        const val MESSAGE_CHANNEL_ID = "ratatosk_message_channel"
        const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        android.util.Log.i("RatatoskService", "Service creating...")
        createNotificationChannels()
        val notification = createServiceNotification()
        startForeground(NOTIFICATION_ID, notification)
        
        // Acquire WakeLock to keep core running when screen is off
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Ratatosk:CoreWakeLock").apply {
            acquire()
        }

        // Try to auto-initialize if account exists and core is not ready
        serviceScope.launch {
            if (!RatatoskCore.isInitialized() && RatatoskCore.accountExists(this@RatatoskService)) {
                try {
                    val settings = SettingsRepository(this@RatatoskService)
                    val displayName = settings.displayName.first() ?: "User"
                    android.util.Log.i("RatatoskService", "Auto-initializing core for $displayName")
                    // Try with null PIN (if DB is not encrypted)
                    RatatoskCore.initialize(this@RatatoskService, null, displayName)
                } catch (e: Exception) {
                    android.util.Log.w("RatatoskService", "Auto-initialization failed (likely requires PIN): ${e.message}")
                }
            }
        }

        // Listen to core events for notifications
        RatatoskCore.events
            .onEach { event ->
                android.util.Log.d("RatatoskService", "Service received event: $event")
                if (event is FfiEvent.MessageReceived) {
                    showIncomingMessageNotification(event)
                }
            }
            .launchIn(serviceScope)

        // Acquire multicast lock for mDNS (required for LAN transport)
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("ratatosk_mdns_lock").apply {
            setReferenceCounted(false)
            acquire()
        }
        android.util.Log.i("RatatoskService", "Multicast lock acquired: ${multicastLock?.isHeld}")

        // Monitor network changes to notify the core
        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            android.util.Log.i("RatatoskService", "Network callback registered")
        } catch (e: Exception) {
            android.util.Log.e("RatatoskService", "Failed to register network callback", e)
        }
    }

    override fun onDestroy() {
        android.util.Log.i("RatatoskService", "Service destroying...")
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.unregisterNetworkCallback(networkCallback)

        multicastLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        super.onDestroy()
    }

    private fun notifyCore() {
        serviceScope.launch {
            try {
                if (RatatoskCore.isInitialized()) {
                    android.util.Log.d("RatatoskService", "Notifying core about network change")
                    RatatoskCore.getClient().networkChanged()
                }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskService", "Failed to notify core about network change", e)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                SERVICE_CHANNEL_ID,
                "Ratatosk Core Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val messageChannel = NotificationChannel(
                MESSAGE_CHANNEL_ID,
                "Incoming Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shows notifications for new messages"
                enableVibration(true)
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
            manager?.createNotificationChannel(messageChannel)
        }
    }

    private fun createServiceNotification(): Notification {
        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Ratatosk core is active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun showIncomingMessageNotification(event: FfiEvent.MessageReceived) {
        val chatIdHex = event.chatId.toHexString()
        
        serviceScope.launch {
            val settings = SettingsRepository(this@RatatoskService)
            val showName = settings.notificationsShowName.first()
            val showText = settings.notificationsShowText.first()

            val contact = try {
                if (RatatoskCore.isInitialized()) {
                    RatatoskCore.getClient().contacts().find { it.chatId.contentEquals(event.chatId) }
                } else null
            } catch (e: Exception) {
                null
            }

            val title = if (showName) {
                contact?.displayName ?: getString(R.string.chat)
            } else {
                getString(R.string.app_name)
            }

            val body = if (showText) {
                try {
                    if (RatatoskCore.isInitialized()) {
                        // Get last message to get the body
                        RatatoskCore.getClient().messages(event.chatId, 1u).firstOrNull { it.msgId.contentEquals(event.msgId) }?.body
                            ?: getString(R.string.message)
                    } else {
                        getString(R.string.message)
                    }
                } catch (e: Exception) {
                    getString(R.string.message)
                }
            } else {
                getString(R.string.message)
            }

            val intent = Intent(this@RatatoskService, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("chatId", chatIdHex)
            }
            
            val pendingIntent = PendingIntent.getActivity(
                this@RatatoskService, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this@RatatoskService, MESSAGE_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            try {
                NotificationManagerCompat.from(this@RatatoskService).notify(chatIdHex.hashCode(), notification)
            } catch (e: SecurityException) {
                // Permission not granted yet
            }
        }
    }
}
