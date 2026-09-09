package chat.ratatosk.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
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
import chat.ratatosk.android.util.MarkdownUtils
import chat.ratatosk.android.util.toHexString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ratatosk.core.FfiEvent
import org.ratatosk.core.FfiCompanionEvent
import org.ratatosk.core.FfiCompanionMessage

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

        // Recovery: if service was killed and restarted but process lived, auto-unlock
        if (!RatatoskCore.isInitialized()) {
            RatatoskCore.tryAutoInitialize()
        }

        // Listen to core events for notifications
        RatatoskCore.events
            .onEach { event ->
                android.util.Log.d("RatatoskService", "Service received event: $event")
                if (event is FfiEvent.MessageReceived && !RatatoskCore.isCompanionMode()) {
                    showIncomingMessageNotification(event)
                }
            }
            .launchIn(serviceScope)

        RatatoskCore.companionEvents
            .onEach { event ->
                android.util.Log.d("RatatoskService", "Service received companion event: $event")
                if (event is FfiCompanionEvent.Arrived && !event.message.mine) {
                    showCompanionMessageNotification(event.message)
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
                if (RatatoskCore.isInitialized() && !RatatoskCore.isCompanionMode()) {
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
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Ratatosk core is active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun showIncomingMessageNotification(event: FfiEvent.MessageReceived) {
        val chatIdHex = event.chatId.toHexString()
        val accountId = RatatoskCore.getActiveAccountId() ?: return
        
        serviceScope.launch {
            val msg = try {
                if (RatatoskCore.isInitialized() && !RatatoskCore.isCompanionMode()) {
                    RatatoskCore.getClient().message(event.msgId)
                        ?: RatatoskCore.getClient().messages(event.chatId, 10u).firstOrNull { it.msgId.contentEquals(event.msgId) }
                } else null
            } catch (e: Exception) {
                null
            }

            if (msg != null && msg.mine) {
                android.util.Log.d("RatatoskService", "Skipping notification for own message (msgId=${event.msgId.toHexString()})")
                return@launch
            }

            val settings = SettingsRepository(this@RatatoskService)
            val showName = settings.getNotificationsShowName(accountId).first()
            val showText = settings.getNotificationsShowText(accountId).first()

            val (contact, group) = try {
                if (RatatoskCore.isInitialized() && !RatatoskCore.isCompanionMode()) {
                    val client = RatatoskCore.getClient()
                    val c = client.contacts().find { it.chatId.contentEquals(event.chatId) }
                    val g = if (c == null) client.groups().find { it.chatId.contentEquals(event.chatId) } else null
                    Pair(c, g)
                } else Pair(null, null)
            } catch (e: Exception) {
                Pair(null, null)
            }

            val title = if (showName) {
                contact?.let { it.localName ?: it.displayName }
                    ?: group?.title
                    ?: getString(R.string.chat)
            } else {
                getString(R.string.app_name)
            }

            val body = if (showText) {
                val rawBody = msg?.body ?: getString(R.string.message)
                val formatted = MarkdownUtils.formatForNotification(rawBody, getString(R.string.spoiler))
                if (group != null && !msg?.author.isNullOrBlank()) {
                    "${msg.author}: $formatted"
                } else {
                    formatted
                }
            } else {
                getString(R.string.message)
            }

            val intent = Intent(this@RatatoskService, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("chatId", chatIdHex)
            }
            
            val pendingIntent = PendingIntent.getActivity(
                this@RatatoskService, chatIdHex.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val avatarBitmap = if (showName) {
                try {
                    if (RatatoskCore.isInitialized() && !RatatoskCore.isCompanionMode()) {
                        val client = RatatoskCore.getClient()
                        val bytes = if (contact != null) {
                            client.avatarOf(contact.peerIk)
                        } else if (group != null) {
                            client.groupAvatar(group.chatId)
                        } else null
                        bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    } else null
                } catch (e: Exception) { null }
            } else null

            val notification = NotificationCompat.Builder(this@RatatoskService, MESSAGE_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setLargeIcon(avatarBitmap)
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

    private fun showCompanionMessageNotification(message: FfiCompanionMessage) {
        if (message.mine) {
            android.util.Log.d("RatatoskService", "Skipping notification for own companion message (msgId=${message.msgId.toHexString()})")
            return
        }

        val chatIdHex = message.chatId.toHexString()
        val accountId = RatatoskCore.getActiveAccountId() ?: return
        
        serviceScope.launch {
            val settings = SettingsRepository(this@RatatoskService)
            val showName = settings.getNotificationsShowName(accountId).first()
            val showText = settings.getNotificationsShowText(accountId).first()

            val title = if (showName) {
                RatatoskCore.getCompanionChatTitle(message.chatId) ?: getString(R.string.chat)
            } else {
                getString(R.string.app_name)
            }

            val body = if (showText) {
                val formatted = MarkdownUtils.formatForNotification(message.body, getString(R.string.spoiler))
                if (!message.author.isNullOrBlank()) {
                    "${message.author}: $formatted"
                } else {
                    formatted
                }
            } else {
                getString(R.string.message)
            }

            val intent = Intent(this@RatatoskService, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("chatId", chatIdHex)
            }
            
            val pendingIntent = PendingIntent.getActivity(
                this@RatatoskService, chatIdHex.hashCode(), intent,
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
