package chat.ratatosk.android.ui.model

import chat.ratatosk.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.ratatosk.core.FfiPairedDevice

/** Вторые экраны этого телефона: список, заведение, отзыв. */
interface PairingApi {
    val pairedDevices: StateFlow<List<FfiPairedDevice>>
    /** Ссылка сопряжения; показать её надо сразу — второго показа не будет. */
    val pairingUri: StateFlow<String?>
    fun loadPairedDevices()
    fun startPairing(label: String)
    fun stopPairing()
    fun revokePairing(deviceId: ByteArray)
}

/**
 * Сопряжение со стороны телефона.
 *
 * Всё это умеет только полный клиент: у второго экрана своих вторых экранов
 * не бывает, поэтому в режиме компаньона команды молча ничего не делают.
 */
class PairingModel(private val session: SessionContext) : PairingApi {
    private val _pairedDevices = MutableStateFlow<List<FfiPairedDevice>>(emptyList())
    override val pairedDevices: StateFlow<List<FfiPairedDevice>> = _pairedDevices.asStateFlow()

    private val _pairingUri = MutableStateFlow<String?>(null)
    override val pairingUri: StateFlow<String?> = _pairingUri.asStateFlow()

    override fun loadPairedDevices() {
        if (session.isCompanion) return
        session.io("Failed to load paired devices") {
            if (!session.core.isInitialized) return@io
            val devices = session.core.client().devices()
            withContext(Dispatchers.Main) { _pairedDevices.value = devices }
        }
    }

    override fun startPairing(label: String) {
        if (session.isCompanion) return
        session.io("Failed to start pairing", R.string.error_pairing_failed) {
            if (!session.core.isInitialized) return@io
            // Ссылка придёт событием `PairingReady`: команда пересекает границу
            // в одну сторону, и ответ у ядра один на все команды.
            _pairingUri.value = null
            session.core.client().pairDevice(label)
        }
    }

    override fun stopPairing() {
        _pairingUri.value = null
    }

    override fun revokePairing(deviceId: ByteArray) {
        if (session.isCompanion) return
        session.io("Failed to revoke pairing", R.string.error_pairing_revoke_failed) {
            if (!session.core.isInitialized) return@io
            session.core.client().revokePairing(deviceId)
            loadPairedDevices()
        }
    }

    /** Ссылка приехала событием ядра. */
    fun onPairingReady(uri: String) {
        _pairingUri.value = uri
    }

    /** Сессия закрыта: чужих устройств и чужой ссылки здесь быть не должно. */
    fun reset() {
        _pairedDevices.value = emptyList()
        _pairingUri.value = null
    }
}
