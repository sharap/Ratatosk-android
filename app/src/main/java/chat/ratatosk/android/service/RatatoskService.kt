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
import chat.ratatosk.android.util.hexToByteArray
import chat.ratatosk.android.data.SettingsRepository
import chat.ratatosk.android.util.MessagePreview
import chat.ratatosk.android.util.reactionToAnnounce
import chat.ratatosk.android.util.toHexString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import org.ratatosk.core.RatatoskException
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ratatosk.core.FfiEvent
import org.ratatosk.core.FfiCompanionEvent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import org.ratatosk.core.FfiCompanionMessage

class RatatoskService : Service() {
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var networkChangeJob: Job? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            android.util.Log.d("RatatoskService", "NetworkCallback: onAvailable ($network)")
            notifyCore()
        }

        override fun onLost(network: Network) {
            android.util.Log.d("RatatoskService", "NetworkCallback: onLost ($network)")
            notifyCore()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            android.util.Log.d("RatatoskService", "NetworkCallback: onCapabilitiesChanged ($network)")
            notifyCore()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            android.util.Log.d("RatatoskService", "NetworkCallback: onLinkPropertiesChanged ($network)")
            notifyCore()
        }

        override fun onUnavailable() {
            android.util.Log.d("RatatoskService", "NetworkCallback: onUnavailable")
            notifyCore()
        }
    }

    private val networkReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            android.util.Log.d("RatatoskService", "BroadcastReceiver onReceive: action=${intent?.action}")
            notifyCore()
        }
    }

    companion object {
        // Набор живёт рядом с буфером повторов — в процессе, а не в экземпляре
        // сервиса. Сервис пересоздаётся (START_STICKY, watchdog), и с полем
        // экземпляра набор обнулялся, а буфер оставался полным: человек получал
        // пачку уведомлений о сообщениях, которые давно прочитал.
        //
        // Потолок нужен, чтобы набор не рос всю жизнь процесса; при вытеснении
        // худшее — повторное уведомление о совсем старом сообщении, чего
        // повтор всё равно не достанет.
        private val notifiedMsgIds = object : LinkedHashMap<String, Boolean>(64, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
                size > 256
        }

        const val SERVICE_CHANNEL_ID = "ratatosk_service_channel"
        const val MESSAGE_CHANNEL_ID = "ratatosk_message_channel"
        const val NOTIFICATION_ID = 1

        // Потолок жизни wake-lock и срок его продления. Смысл не в том,
        // чтобы отпускать замок на ходу, — ядру он нужен постоянно, — а в
        // том, чтобы зависший или убитый процесс не держал процессор
        // до перезагрузки телефона: система снимет замок по сроку сама.
        private const val WAKELOCK_TIMEOUT_MS = 24L * 60 * 60 * 1000
        private const val WAKELOCK_REFRESH_MS = 12L * 60 * 60 * 1000
    }

    override fun onCreate() {
        super.onCreate()
        android.util.Log.i("RatatoskService", "Service creating...")
        createNotificationChannels()
        val notification = createServiceNotification()
        startForeground(NOTIFICATION_ID, notification)
        
        // Acquire WakeLock to keep core running when screen is off
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        // С таймаутом, а не бессрочно. Бессрочный PARTIAL_WAKE_LOCK держится
        // всё время жизни сервиса, то есть до перезагрузки телефона, и не
        // отпускается даже если сервис зависнет. Сутки — потолок, а не срок
        // работы: пока сервис жив, замок продлевается корутиной ниже.
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Ratatosk:CoreWakeLock").apply {
            acquire(WAKELOCK_TIMEOUT_MS)
        }
        // Продление, пока сервис жив. Умрёт вместе с serviceScope в onDestroy.
        serviceScope.launch {
            while (true) {
                kotlinx.coroutines.delay(WAKELOCK_REFRESH_MS)
                try {
                    wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
                } catch (t: Throwable) {
                    android.util.Log.w("RatatoskService", "Failed to refresh wake lock: ${t.message}")
                }
            }
        }

        recoverSessionIfNeeded()
        scheduleWatchdog(this)

        // Listen to core events for notifications
        RatatoskCore.events
            .onEach { event ->
                android.util.Log.d("RatatoskService", "event: ${event::class.java.simpleName}")
                if (event is FfiEvent.MessageReceived && !RatatoskCore.isCompanionMode()) {
                    showIncomingMessageNotification(event)
                }
                if (event is FfiEvent.ReactionChanged && !RatatoskCore.isCompanionMode()) {
                    showReactionNotification(event)
                }
                if (event is FfiEvent.ChannelRequested && !RatatoskCore.isCompanionMode()) {
                    showChannelRequestNotification(event)
                }
            }
            .launchIn(serviceScope)

        RatatoskCore.companionEvents
            .onEach { event ->
                // Без полей: Arrived несёт текст сообщения.
                android.util.Log.d("RatatoskService", "companion event: ${event::class.java.simpleName}")
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
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                connectivityManager.registerDefaultNetworkCallback(networkCallback)
                android.util.Log.i("RatatoskService", "Default network callback registered")
            } catch (e: Exception) {
                android.util.Log.e("RatatoskService", "Failed to register default network callback", e)
            }
        }

        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
            android.util.Log.i("RatatoskService", "Network request callback registered")
        } catch (e: Exception) {
            android.util.Log.e("RatatoskService", "Failed to register network request callback", e)
        }

        try {
            val filter = IntentFilter().apply {
                @Suppress("DEPRECATION")
                addAction(ConnectivityManager.CONNECTIVITY_ACTION)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            }
            registerReceiver(networkReceiver, filter)
            android.util.Log.i("RatatoskService", "Network broadcast receiver registered")
        } catch (e: Exception) {
            android.util.Log.e("RatatoskService", "Failed to register network broadcast receiver", e)
        }

        // Capture initial network state snapshot on service startup
        lastNetworkSnapshot = getCurrentNetworkStateSnapshot()
        android.util.Log.i("RatatoskNetwork", "Captured initial network state snapshot on service startup: $lastNetworkSnapshot")
    }

    override fun onDestroy() {
        android.util.Log.i("RatatoskService", "Service destroying...")
        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            android.util.Log.e("RatatoskService", "Failed to unregister network callback", e)
        }

        try {
            unregisterReceiver(networkReceiver)
        } catch (e: Exception) {
            android.util.Log.e("RatatoskService", "Failed to unregister network receiver", e)
        }

        multicastLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        super.onDestroy()
    }

    private data class NetworkStateSnapshot(
        val defaultNetworkHandle: Long?,
        val isConnected: Boolean,
        val transports: Set<Int>,
        val ipAddresses: Set<String>
    )

    private var lastNetworkSnapshot: NetworkStateSnapshot? = null

    private fun getCurrentNetworkStateSnapshot(): NetworkStateSnapshot {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkStateSnapshot(null, false, emptySet(), emptySet())

        val activeNetwork = cm.activeNetwork
            ?: return NetworkStateSnapshot(null, false, emptySet(), emptySet())

        val handle = activeNetwork.networkHandle

        val caps = cm.getNetworkCapabilities(activeNetwork)
        val transports = mutableSetOf<Int>()
        if (caps != null) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports.add(NetworkCapabilities.TRANSPORT_WIFI)
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports.add(NetworkCapabilities.TRANSPORT_CELLULAR)
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports.add(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transports.add(NetworkCapabilities.TRANSPORT_VPN)
        }

        val linkProps = cm.getLinkProperties(activeNetwork)
        val ips = linkProps?.linkAddresses?.map { it.address.hostAddress ?: "" }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

        return NetworkStateSnapshot(
            defaultNetworkHandle = handle,
            isConnected = true,
            transports = transports,
            ipAddresses = ips
        )
    }

    private fun notifyCore() {
        networkChangeJob?.cancel()
        networkChangeJob = serviceScope.launch {
            delay(300) // Small debounce for Android network callbacks to settle
            val newSnapshot = getCurrentNetworkStateSnapshot()
            val oldSnapshot = lastNetworkSnapshot

            if (oldSnapshot == null) {
                lastNetworkSnapshot = newSnapshot
                android.util.Log.i("RatatoskNetwork", "Initial network snapshot captured: $newSnapshot (networkChanged NOT called on startup)")
                return@launch
            }

            if (newSnapshot != oldSnapshot) {
                android.util.Log.i("RatatoskNetwork", "Network state CHANGED!")
                android.util.Log.i("RatatoskNetwork", "  Old: $oldSnapshot")
                android.util.Log.i("RatatoskNetwork", "  New: $newSnapshot")
                lastNetworkSnapshot = newSnapshot

                try {
                    if (RatatoskCore.isInitialized() && !RatatoskCore.isCompanionMode()) {
                        android.util.Log.i("RatatoskNetwork", "Network changed, telling the core")
                        RatatoskCore.getClient().networkChanged()
                    } else {
                        android.util.Log.w("RatatoskNetwork", "Network change detected, but core is not ready")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("RatatoskNetwork", "Failed to notify core about network change", e)
                }
            } else {
                android.util.Log.d("RatatoskNetwork", "Network callback received, but state is unchanged ($newSnapshot)")
            }
        }
    }

    /**
     * Поднимает ядро после того, как процесс перезапустили.
     *
     * Раньше здесь стоял только `tryAutoInitialize()`, а он берёт учётные
     * данные из поля в памяти. Поле умирает вместе с процессом, поэтому
     * после ночного отстрела START_STICKY поднимал сервис, тот честно рисовал
     * уведомление — и на этом всё: ядро не открывалось никогда, сообщения
     * не приходили. Снаружи это и выглядело как «к утру приложение не живо».
     *
     * Восстанавливаем из того, что и так лежит на диске: какой аккаунт был
     * последним и как он называется. Ничего нового при этом не сохраняем.
     *
     * **Аккаунт под PIN не открываем.** Секрета у нас нет и быть не должно;
     * такой аккаунт дождётся человека. Отличаем по `RatatoskException.Locked`.
     */
    private fun recoverSessionIfNeeded() {
        serviceScope.launch {
            // Журнал раньше всего: служба может подняться без окна,
            // и тогда это единственное место, где его успеют завести.
            val toFile = try {
                SettingsRepository(applicationContext).coreFileLog.first()
            } catch (e: Exception) {
                false
            }
            RatatoskCore.startLogging(applicationContext, toFile)

            if (RatatoskCore.isInitialized()) {
                handBtRadio()
                return@launch
            }
            // Процесс жив, пересоздали только сервис — данные ещё в памяти.
            if (RatatoskCore.tryAutoInitialize() != null) {
                handBtRadio()
                return@launch
            }

            val settings = SettingsRepository(applicationContext)
            val lastId = settings.lastAccountId.firstOrNull() ?: return@launch
            try {
                RatatoskCore.initializeRegistry(applicationContext)
            } catch (t: Throwable) {
                android.util.Log.e("RatatoskService", "Registry not available for recovery", t)
                return@launch
            }

            if (lastId.startsWith("companion:")) {
                val link = settings.companionLinks.firstOrNull()
                    ?.firstOrNull { "companion:${it.inviteUri.hashCode()}" == lastId }
                if (link == null) {
                    android.util.Log.w("RatatoskService", "No stored companion link to recover")
                    return@launch
                }
                try {
                    // cachePath у сохранённой ссылки уже абсолютный.
                    RatatoskCore.initializeCompanion(
                        link.inviteUri, link.port.toUShort(), link.peerAddr, link.cachePath, link.torDir
                    )
                    android.util.Log.i("RatatoskService", "Companion session recovered")
                } catch (t: Throwable) {
                    android.util.Log.e("RatatoskService", "Companion recovery failed", t)
                }
                return@launch
            }

            val accountId = try {
                lastId.hexToByteArray()
            } catch (e: Exception) {
                return@launch
            }
            val name = settings.getDisplayName(lastId).firstOrNull() ?: "Ratatosk"
            // Привязанный к телефону аккаунт открывается секретом из Keystore.
            // Без него служба поднимала бы ядро «пустым ключом» и получала
            // отказ — то есть к утру приложение снова было бы не живо.
            val deviceKey = if (settings.isDeviceBound(lastId).firstOrNull() == true) {
                chat.ratatosk.android.data.KeystoreSecrets(applicationContext).get(lastId)
                    ?: run {
                        android.util.Log.w("RatatoskService", "Device secret is gone — waiting for the user")
                        return@launch
                    }
            } else null
            try {
                RatatoskCore.initialize(accountId, null, deviceKey, name)
                android.util.Log.i("RatatoskService", "Account session recovered")
                handBtRadio()
            } catch (locked: RatatoskException.Locked) {
                android.util.Log.i("RatatoskService", "Account needs a PIN — waiting for the user")
            } catch (t: Throwable) {
                android.util.Log.e("RatatoskService", "Session recovery failed", t)
            }
        }
    }

    /**
     * Вручает ядру радио Bluetooth.
     *
     * Именно из службы: обзор и объявление идут всё время, пока ступень
     * включена, и обычная Activity этого не переживёт. Разрешений может
     * не быть — тогда вручать нечего, и экран настроек покажет это
     * отдельной строкой, а не сломанной ступенью.
     */
    private fun handBtRadio() {
        if (RatatoskCore.ensureBtRadio(applicationContext)) return
        android.util.Log.i("RatatoskService", "Bluetooth radio not handed: no permissions yet")
    }

    /**
     * Карточку смахнули из недавних.
     *
     * Многие оболочки (MIUI в их числе) убивают при этом весь процесс,
     * не считаясь с foreground-сервисом. Просим систему поднять сервис
     * через секунду: ядро восстановится тем же путём, что и после
     * любой другой смерти процесса.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        scheduleWatchdog(this, delayMs = 1_000)
        super.onTaskRemoved(rootIntent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Каждый заход — повод проверить, живо ли ядро: сюда мы попадаем
        // и после START_STICKY, и по будильнику сторожа.
        recoverSessionIfNeeded()
        scheduleWatchdog(this)
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

    // Сообщения, о которых уже уведомляли.
    //
    // Шина событий отдаёт новому подписчику полсотни прошлых событий
    // (replay), а сервис подписывается заново при каждом своём создании —
    // START_STICKY поднимает его после смерти. Без этой отметки человек
    // получал бы пачку уведомлений о сообщениях, которые давно прочитал.
    //
    // Потолок нужен, чтобы набор не рос всю жизнь процесса; при вытеснении
    // худшее, что случится, — повторное уведомление о совсем старом
    // сообщении, чего replay всё равно не достанет.
    private fun dismissChatNotification(chatIdHex: String) {
        try {
            NotificationManagerCompat.from(this).cancel(chatIdHex.hashCode())
        } catch (e: Exception) {
            android.util.Log.w("RatatoskService", "Failed to dismiss notification: ${e.message}")
        }
    }

    private fun alreadyNotified(msgIdHex: String): Boolean = synchronized(notifiedMsgIds) {
        notifiedMsgIds.put(msgIdHex, true) != null
    }

    private fun showIncomingMessageNotification(event: FfiEvent.MessageReceived) {
        val chatIdHex = event.chatId.toHexString()
        val accountId = RatatoskCore.getActiveAccountId() ?: return
        if (alreadyNotified(event.msgId.toHexString())) return
        // Человек смотрит в этот чат — уведомлять его о том, что он и так
        // видит, незачем; заодно снимаем прежнее по этому чату.
        if (chat.ratatosk.android.util.VisibleChat.isVisible(chatIdHex)) {
            dismissChatNotification(chatIdHex)
            return
        }
        
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
                // Через общий помощник: он же снимает разметку и он же
                // подписывает вложение, когда текста нет вовсе.
                val formatted = if (msg != null) {
                    MessagePreview.of(
                        context = this@RatatoskService,
                        body = msg.body,
                        fileNames = msg.files.map { it.name },
                        hasSharedContact = msg.sharedContact != null
                    )
                } else {
                    getString(R.string.message)
                }
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

    /**
     * Уведомление о поставленной реакции.
     *
     * # Почему приходится смотреть в сообщение
     *
     * Событие ядра одно на постановку и на снятие: в нём есть чат,
     * сообщение и автор, но нет ни смайлика, ни того, что именно
     * случилось. Поэтому перечитываем сообщение и ищем реакцию этого
     * автора: нашлась — поставили, не нашлась — сняли, и уведомлять
     * не о чем (о снятии человека беспокоить не просили).
     *
     * # Только на свои сообщения
     *
     * Реакция на чужое сообщение в группе — не событие для человека,
     * а шум: в разговоре на десять участников он получал бы уведомление
     * на каждый смайлик каждого. Поэтому `msg.mine`.
     *
     * Своя же реакция пропускается отдельно: она прилетает тем же
     * событием, а уведомлять себя о себе незачем.
     */
    private fun showReactionNotification(event: FfiEvent.ReactionChanged) {
        val accountId = RatatoskCore.getActiveAccountId() ?: return
        val chatIdHex = event.chatId.toHexString()
        val msgIdHex = event.msgId.toHexString()

        serviceScope.launch {
            val msg = try {
                if (RatatoskCore.isInitialized() && !RatatoskCore.isCompanionMode()) {
                    RatatoskCore.getClient().message(event.msgId)
                } else null
            } catch (e: Exception) {
                null
            } ?: return@launch

            // Решение вынесено в чистую функцию и покрыто тестами:
            // живьём реакцию без второго устройства не воспроизвести.
            val reaction = reactionToAnnounce(msg, event.authorIk) ?: return@launch

            // Один и тот же смайлик от того же человека второй раз
            // не показываем: событие может приехать повторно из replay.
            if (alreadyNotified("reaction:$msgIdHex:${event.authorIk.toHexString()}:${reaction.emoji}")) {
                return@launch
            }

            val settings = SettingsRepository(this@RatatoskService)
            val showName = settings.getNotificationsShowName(accountId).first()
            val showText = settings.getNotificationsShowText(accountId).first()

            val who = if (showName) reactionAuthorName(event.chatId, event.authorIk) else null
            val title = who ?: getString(R.string.app_name)

            val body = if (showText) {
                val preview = MessagePreview.of(
                    context = this@RatatoskService,
                    body = msg.body,
                    fileNames = msg.files.map { it.name },
                    hasSharedContact = msg.sharedContact != null
                )
                getString(R.string.reaction_to_message, reaction.emoji, preview)
            } else {
                getString(R.string.reaction_received, reaction.emoji)
            }

            val intent = Intent(this@RatatoskService, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("chatId", chatIdHex)
                // Чат откроется на этом сообщении, а не просто в конце.
                putExtra("msgId", msgIdHex)
            }
            val pendingIntent = PendingIntent.getActivity(
                this@RatatoskService,
                // Свой код на сообщение: иначе намерение переиспользовалось бы
                // от прошлого уведомления и вело бы не туда.
                ("reaction:" + msgIdHex).hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this@RatatoskService, MESSAGE_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            try {
                // Идентификатор по сообщению: новая реакция на то же сообщение
                // заменяет прежнее уведомление, а не копится рядом.
                NotificationManagerCompat.from(this@RatatoskService)
                    .notify(("reaction:" + msgIdHex).hashCode(), notification)
            } catch (e: SecurityException) {
                // Разрешение на уведомления ещё не выдано.
            }
        }
    }

    /**
     * Кто-то просится в канал по приглашению (§10.4).
     *
     * Сказать об этом надо: заявка ждёт владельца сколько угодно, отказа
     * как ответа не бывает, и человек, который её не заметил, молча
     * отказывает — а просящий не узнает даже этого.
     *
     * Имя показывается по той же настройке, что и у сообщений: карточка
     * просящего у нас уже есть, но на замке экрана ей не место, если
     * человек попросил имён не показывать.
     */
    private fun showChannelRequestNotification(event: FfiEvent.ChannelRequested) {
        serviceScope.launch {
            val accountId = RatatoskCore.getActiveAccountId() ?: return@launch
            val chatIdHex = event.chatId.toHexString()

            // Канал открыт и виден — заявку человек и так увидит в карточке.
            if (chat.ratatosk.android.util.VisibleChat.isVisible(chatIdHex)) {
                return@launch
            }

            val settings = SettingsRepository(this@RatatoskService)
            val showName = settings.getNotificationsShowName(accountId).first()

            val body = if (showName) {
                val client = RatatoskCore.getClient()
                val who = try {
                    client.channelRequests(event.chatId)
                        .firstOrNull { it.who.contentEquals(event.who) }?.name
                } catch (t: Throwable) {
                    android.util.Log.w("RatatoskService", "Failed to read channel requests", t)
                    null
                }
                val title = try {
                    client.groups().firstOrNull { it.chatId.contentEquals(event.chatId) }?.title
                } catch (t: Throwable) {
                    null
                }
                if (who != null && !title.isNullOrBlank()) {
                    getString(R.string.channel_request_notification_named, who, title)
                } else {
                    getString(R.string.channel_request_notification_plain)
                }
            } else {
                getString(R.string.channel_request_notification_plain)
            }

            val intent = Intent(this@RatatoskService, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("chatId", chatIdHex)
            }
            val pendingIntent = PendingIntent.getActivity(
                this@RatatoskService,
                ("channel-request:" + chatIdHex).hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this@RatatoskService, MESSAGE_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.channel_request_notification_title))
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            try {
                // Один на канал: пока владелец не открыл карточку, вторая
                // заявка заменяет уведомление, а не копится рядом с первым.
                NotificationManagerCompat.from(this@RatatoskService)
                    .notify(("channel-request:" + chatIdHex).hashCode(), notification)
            } catch (e: SecurityException) {
                // Разрешение на уведомления ещё не выдано.
            }
        }
    }

    /** Имя того, кто поставил реакцию: из контактов, иначе из состава группы. */
    private fun reactionAuthorName(chatId: ByteArray, authorIk: ByteArray): String? = try {
        val client = RatatoskCore.getClient()
        val contact = client.contacts().firstOrNull { it.peerIk.contentEquals(authorIk) }
        contact?.let { it.localName ?: it.displayName }
            // Не контакт — значит участник группы: состав приезжает
            // вместе с самой группой, отдельного запроса не нужно.
            ?: client.groups()
                .firstOrNull { it.chatId.contentEquals(chatId) }
                ?.members
                ?.firstOrNull { it.ik.contentEquals(authorIk) }
                ?.name
    } catch (e: Exception) {
        null
    }

    private fun showCompanionMessageNotification(message: FfiCompanionMessage) {
        if (message.mine) {
            android.util.Log.d("RatatoskService", "Skipping notification for own companion message")
            return
        }
        if (alreadyNotified(message.msgId.toHexString())) return

        val chatIdHex = message.chatId.toHexString()
        // То же правило, что и у полного клиента: открытый и видимый чат — молчим.
        if (chat.ratatosk.android.util.VisibleChat.isVisible(chatIdHex)) {
            dismissChatNotification(chatIdHex)
            return
        }
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
                val formatted = MessagePreview.of(
                    context = this@RatatoskService,
                    body = message.body,
                    fileNames = message.files.map { it.name },
                    hasSharedContact = message.shared != null
                )
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
