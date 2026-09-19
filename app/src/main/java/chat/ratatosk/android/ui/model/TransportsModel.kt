package chat.ratatosk.android.ui.model

import chat.ratatosk.android.R
import chat.ratatosk.android.util.hexToByteArray
import chat.ratatosk.android.util.toHexString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ratatosk.core.FfiMailAccount
import org.ratatosk.core.FfiMailStatus
import org.ratatosk.core.FfiNostrRelay
import org.ratatosk.core.FfiTorStatus
import org.ratatosk.core.FfiTransport
import org.ratatosk.core.FfiYggMode
import org.ratatosk.core.FfiYggPeer

/**
 * Ступени доставки: локальная сеть, Tor, почта, Yggdrasil, Nostr, Bluetooth.
 *
 * Всё это — возможности полного клиента: у второго экрана своих ступеней нет,
 * команды в этом режиме молча ничего не делают.
 */
interface TransportsApi {
    val transportsEnabled: StateFlow<Map<FfiTransport, Boolean>>
    val transportsReady: StateFlow<Map<FfiTransport, Boolean>>
    val torStatus: StateFlow<FfiTorStatus?>
    val mailStatus: StateFlow<FfiMailStatus?>
    val mailAccount: StateFlow<FfiMailAccount?>
    val yggMode: StateFlow<FfiYggMode>
    val yggKey: StateFlow<String?>
    val yggAddress: StateFlow<String?>
    val yggPeers: StateFlow<List<String>>
    val yggPeersAlive: StateFlow<List<FfiYggPeer>?>
    val btHasRadio: StateFlow<Boolean>
    val nostrRelays: StateFlow<List<String>>
    val nostrRelaysAlive: StateFlow<List<FfiNostrRelay>?>
    val nostrNpub: StateFlow<String?>
    val nostrDirect: StateFlow<Boolean>
    val nostrAdvertisedRelays: StateFlow<List<String>>

    /** Включена ли ступень — отдельными признаками, как их спрашивают экраны. */
    val lanEnabled: StateFlow<Boolean>
    val torEnabled: StateFlow<Boolean>
    val mailEnabled: StateFlow<Boolean>
    val yggEnabled: StateFlow<Boolean>
    val btEnabled: StateFlow<Boolean>
    val nostrEnabled: StateFlow<Boolean>

    fun refreshTransportStatus()
    /** Пробует вручить ядру радио — звать после выдачи разрешений. */
    fun handBtRadio()
    /** Включён ли сам адаптер Bluetooth. */
    fun btAdapterEnabled(): Boolean
    /** Каких разрешений не хватает Bluetooth. */
    fun btMissingPermissions(): List<String>
    fun setTransportEnabled(transport: FfiTransport, enabled: Boolean)
    fun setMailAccount(address: String, password: String, imapHost: String, imapPort: Int, smtpHost: String, smtpPort: Int, viaTor: Boolean)
    fun createMailAccount(serverUrl: String, viaTor: Boolean)
    fun clearMailAccount()
    fun setLanEnabled(enabled: Boolean)
    fun setTorEnabled(enabled: Boolean)
    fun setYggEnabled(enabled: Boolean)
    fun setYggMode(mode: FfiYggMode)
    fun setYggKey(hex: String)
    fun setYggPeers(peers: List<String>)
    fun setNostrRelays(relays: List<String>)
    fun setNostrDirect(enabled: Boolean)
    /** Тексты ядра — их показывают **до** переключения, а не после. */
    fun getNostrWarning(): String
    fun getNostrDirectWarning(): String
    fun getNostrNoFilesNotice(): String
}

class TransportsModel(private val session: SessionContext) : TransportsApi {
    private val _transportsEnabled = MutableStateFlow<Map<FfiTransport, Boolean>>(emptyMap())
    override val transportsEnabled = _transportsEnabled.asStateFlow()
    private val _transportsReady = MutableStateFlow<Map<FfiTransport, Boolean>>(emptyMap())
    override val transportsReady = _transportsReady.asStateFlow()
    private val _torStatus = MutableStateFlow<FfiTorStatus?>(null)
    override val torStatus = _torStatus.asStateFlow()
    private val _mailStatus = MutableStateFlow<FfiMailStatus?>(null)
    override val mailStatus = _mailStatus.asStateFlow()
    private val _mailAccount = MutableStateFlow<FfiMailAccount?>(null)
    override val mailAccount = _mailAccount.asStateFlow()
    private val _yggMode = MutableStateFlow(FfiYggMode.OFF)
    override val yggMode = _yggMode.asStateFlow()
    private val _yggKey = MutableStateFlow<String?>(null)
    override val yggKey = _yggKey.asStateFlow()
    private val _yggAddress = MutableStateFlow<String?>(null)
    override val yggAddress = _yggAddress.asStateFlow()
    private val _yggPeers = MutableStateFlow<List<String>>(emptyList())
    override val yggPeers = _yggPeers.asStateFlow()
    private val _yggPeersAlive = MutableStateFlow<List<org.ratatosk.core.FfiYggPeer>?>(null)
    override val yggPeersAlive = _yggPeersAlive.asStateFlow()
    private val _btHasRadio = MutableStateFlow(false)
    override val btHasRadio = _btHasRadio.asStateFlow()
    private val _nostrRelays = MutableStateFlow<List<String>>(emptyList())
    override val nostrRelays = _nostrRelays.asStateFlow()
    private val _nostrRelaysAlive = MutableStateFlow<List<org.ratatosk.core.FfiNostrRelay>?>(null)
    override val nostrRelaysAlive = _nostrRelaysAlive.asStateFlow()
    private val _nostrNpub = MutableStateFlow<String?>(null)
    override val nostrNpub = _nostrNpub.asStateFlow()
    private val _nostrDirect = MutableStateFlow(false)
    override val nostrDirect = _nostrDirect.asStateFlow()
    private val _nostrAdvertisedRelays = MutableStateFlow<List<String>>(emptyList())
    override val nostrAdvertisedRelays = _nostrAdvertisedRelays.asStateFlow()

    private fun enabledFlow(transport: FfiTransport): StateFlow<Boolean> =
        _transportsEnabled
            .map { it[transport] ?: false }
            .stateIn(session.scope, SharingStarted.WhileSubscribed(5000), false)

    override val lanEnabled = enabledFlow(FfiTransport.LAN)
    override val torEnabled = enabledFlow(FfiTransport.ONION)
    override val mailEnabled = enabledFlow(FfiTransport.MAIL)
    override val yggEnabled = enabledFlow(FfiTransport.YGG)
    override val btEnabled = enabledFlow(FfiTransport.BT)
    override val nostrEnabled = enabledFlow(FfiTransport.NOSTR)

    /**
     * Разрешения спрашивает экран: из модели системный запрос не показать,
     * для него нужна Activity.
     */
    override fun handBtRadio() {
        session.scope.launch(Dispatchers.IO) {
            session.core.ensureBtRadio(session.app)
            val has = session.core.hasBtRadio()
            withContext(Dispatchers.Main) { onBtRadio(has) }
        }
    }

    /**
     * Нужно, чтобы отличить «радио есть, но эфир выключен человеком»
     * от «радио есть, а ступень всё равно не поднялась». Причину ядро
     * словами наружу не отдаёт — оно пишет её в журнал, — но обе
     * возможные причины приложение знает про себя само.
     */
    override fun btAdapterEnabled(): Boolean = try {
        val manager = session.app
            .getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        manager?.adapter?.isEnabled == true
    } catch (t: Throwable) {
        false
    }

    override fun btMissingPermissions(): List<String> =
        org.ratatosk.bt.BtRadio.Permissions.missing(session.app)

    override fun refreshTransportStatus() {
        if (session.isCompanion) return
        session.scope.launch(Dispatchers.IO) {
            try {
                val client = session.core.client()
                val en = FfiTransport.values().associateWith { client.transportEnabled(it) }
                val re = FfiTransport.values().associateWith { client.transportReady(it) }
                val ts = client.torStatus()
                val ms = client.mailStatus()
                val ma = client.mailAccount()
                val ym = client.yggMode()
                val ykBytes = client.yggKey()
                val ykHex = if (ykBytes.isNotEmpty()) ykBytes.toHexString() else null
                val ya = if (ykBytes.isNotEmpty()) org.ratatosk.core.yggAddress(ykBytes) else null
                val yp = client.yggPeers()
                val ypa = try { client.yggPeersAlive() } catch (e: Exception) { null }
                val btRadio = session.core.hasBtRadio()
                val nr = try { client.nostrRelays() } catch (e: Exception) { emptyList() }
                val nra = try { client.nostrRelaysAlive() } catch (e: Exception) { null }
                val npub = try { client.nostrNpub() } catch (e: Exception) { "" }
                val nd = try { client.nostrDirect() } catch (e: Exception) { false }
                val nar = try { client.nostrAdvertisedRelays() } catch (e: Exception) { emptyList() }
                
                withContext(Dispatchers.Main) {
                    _transportsEnabled.value = en
                    _transportsReady.value = re
                    _torStatus.value = ts
                    _mailStatus.value = ms
                    _mailAccount.value = ma
                    _yggMode.value = ym
                    _yggKey.value = ykHex
                    _yggAddress.value = ya
                    _yggPeers.value = yp
                    _yggPeersAlive.value = ypa
                    _btHasRadio.value = btRadio
                    _nostrRelays.value = nr
                    _nostrRelaysAlive.value = nra
                    _nostrNpub.value = if (npub.isNotBlank()) npub else null
                    _nostrDirect.value = nd
                    _nostrAdvertisedRelays.value = nar
                }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to refresh transport status", e)
            }
        }
    }

    override fun setTransportEnabled(transport: FfiTransport, enabled: Boolean) {
        session.scope.launch(Dispatchers.IO) {
            try {
                val client = session.core.client()
                client.setTransportEnabled(transport, enabled)
                client.networkChanged()
                refreshTransportStatus()
            } catch (e: Exception) {
                android.util.Log.w("RatatoskVM", "Failed to toggle transport", e)
                withContext(Dispatchers.Main) {
                    session._error.value = e.message?.takeIf { it.isNotBlank() }
                        ?: session.string(R.string.error_transport_switch_failed)
                }
            }
        }
    }

    override fun setMailAccount(address: String, password: String, imapHost: String, imapPort: Int, smtpHost: String, smtpPort: Int, viaTor: Boolean) {
        session.scope.launch(Dispatchers.IO) {
            try {
                session.core.client().setMailAccount(address, password, imapHost, imapPort.toUShort(), smtpHost, smtpPort.toUShort(), viaTor)
                refreshTransportStatus()
            } catch (e: Exception) {
                android.util.Log.w("RatatoskVM", "Failed to set mail account", e)
                withContext(Dispatchers.Main) {
                    session._error.value = e.message?.takeIf { it.isNotBlank() }
                        ?: session.string(R.string.error_mail_setup_failed)
                }
            }
        }
    }

    override fun createMailAccount(url: String, viaTor: Boolean) {
        session.scope.launch(Dispatchers.IO) {
            try {
                session.core.client().createMailAccount(url, viaTor)
            } catch (e: Exception) {
                android.util.Log.w("RatatoskVM", "Failed to create mail account", e)
                withContext(Dispatchers.Main) {
                    session._error.value = e.message?.takeIf { it.isNotBlank() }
                        ?: session.string(R.string.error_mail_register_failed)
                }
            }
        }
    }

    override fun clearMailAccount() {
        session.scope.launch(Dispatchers.IO) {
            try {
                session.core.client().clearMailAccount()
                refreshTransportStatus()
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to clear mail account", e)
            }
        }
    }

    override fun setLanEnabled(enabled: Boolean) {
        setTransportEnabled(FfiTransport.LAN, enabled)
    }

    override fun setTorEnabled(enabled: Boolean) {
        setTransportEnabled(FfiTransport.ONION, enabled)
    }

    override fun setYggEnabled(enabled: Boolean) {
        setTransportEnabled(FfiTransport.YGG, enabled)
    }

    override fun setYggMode(mode: FfiYggMode) {
        if (session.isCompanion) return
        session.scope.launch(Dispatchers.IO) {
            try {
                val client = session.core.client()
                client.setYggMode(mode)
                client.setTransportEnabled(FfiTransport.YGG, mode != FfiYggMode.OFF)
                client.networkChanged()
                refreshTransportStatus()
            } catch (e: Exception) {
                android.util.Log.w("RatatoskVM", "Failed to set Yggdrasil mode", e)
                withContext(Dispatchers.Main) {
                    session._error.value = e.message?.takeIf { it.isNotBlank() }
                        ?: session.string(R.string.error_ygg_settings_failed)
                }
            }
        }
    }

    override fun setYggKey(keyHex: String) {
        if (session.isCompanion) return
        session.scope.launch(Dispatchers.IO) {
            try {
                val clean = keyHex.trim().replace(" ", "").replace(":", "")
                val bytes = if (clean.isEmpty()) byteArrayOf() else clean.hexToByteArray()
                val client = session.core.client()
                client.setYggKey(bytes)
                client.networkChanged()
                refreshTransportStatus()
            } catch (e: Exception) {
                android.util.Log.w("RatatoskVM", "Failed to set Yggdrasil key", e)
                withContext(Dispatchers.Main) {
                    session._error.value = e.message?.takeIf { it.isNotBlank() }
                        ?: session.string(R.string.error_ygg_key_invalid)
                }
            }
        }
    }

    override fun setYggPeers(peers: List<String>) {
        if (session.isCompanion) return
        session.scope.launch(Dispatchers.IO) {
            try {
                val cleanedPeers = peers.map { it.trim() }.filter { it.isNotEmpty() }
                val client = session.core.client()
                client.setYggPeers(cleanedPeers)
                client.networkChanged()
                refreshTransportStatus()
            } catch (e: Exception) {
                android.util.Log.w("RatatoskVM", "Failed to set Yggdrasil peers", e)
                withContext(Dispatchers.Main) {
                    session._error.value = e.message?.takeIf { it.isNotBlank() }
                        ?: session.string(R.string.error_ygg_settings_failed)
                }
            }
        }
    }

    override fun setNostrRelays(relays: List<String>) {
        if (session.isCompanion) return
        session.scope.launch(Dispatchers.IO) {
            try {
                val cleanedRelays = relays.map { it.trim() }.filter { it.isNotEmpty() }
                val client = session.core.client()
                client.setNostrRelays(cleanedRelays)
                client.networkChanged()
                refreshTransportStatus()
            } catch (e: Exception) {
                android.util.Log.w("RatatoskVM", "Failed to set Nostr relays", e)
                withContext(Dispatchers.Main) {
                    session._error.value = e.message?.takeIf { it.isNotBlank() }
                        ?: session.string(R.string.error_nostr_settings_failed)
                }
            }
        }
    }

    override fun setNostrDirect(direct: Boolean) {
        if (session.isCompanion) return
        session.scope.launch(Dispatchers.IO) {
            try {
                val client = session.core.client()
                client.setNostrDirect(direct)
                client.networkChanged()
                refreshTransportStatus()
            } catch (e: Exception) {
                android.util.Log.w("RatatoskVM", "Failed to set Nostr direct mode", e)
                withContext(Dispatchers.Main) {
                    session._error.value = e.message?.takeIf { it.isNotBlank() }
                        ?: session.string(R.string.error_nostr_settings_failed)
                }
            }
        }
    }

    override fun getNostrWarning(): String = try { org.ratatosk.core.nostrWarning() } catch (e: Exception) { "" }

    override fun getNostrDirectWarning(): String = try { org.ratatosk.core.nostrDirectWarning() } catch (e: Exception) { "" }

    override fun getNostrNoFilesNotice(): String = try { org.ratatosk.core.nostrNoFilesNotice() } catch (e: Exception) { "" }

    /** Состояние ступеней приходит событиями ядра. */
    fun onTorStatus(status: FfiTorStatus) {
        _torStatus.value = status
    }

    /** Есть ли у ядра чем поднять эфир; спрашивается при выдаче радио. */
    fun onBtRadio(hasRadio: Boolean) {
        _btHasRadio.value = hasRadio
    }

    fun onMailStatus(status: FfiMailStatus) {
        _mailStatus.value = status
    }

    /** Сессия закрыта: ступени прошлого аккаунта — не наши. */
    fun reset() {
        _transportsEnabled.value = emptyMap()
        _transportsReady.value = emptyMap()
        _torStatus.value = null
        _mailStatus.value = null
        _mailAccount.value = null
        _yggMode.value = FfiYggMode.OFF
        _yggKey.value = null
        _yggAddress.value = null
        _yggPeers.value = emptyList()
        _yggPeersAlive.value = null
        _nostrRelays.value = emptyList()
        _nostrRelaysAlive.value = null
        _nostrNpub.value = null
        _nostrDirect.value = false
        _nostrAdvertisedRelays.value = emptyList()
    }
}
