package chat.ratatosk.android.ui.model

import chat.ratatosk.android.R
import chat.ratatosk.android.util.PeerOrigins
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ratatosk.core.FfiYggMode

/**
 * Что помощник первого запуска нашёл человеку в меше.
 *
 * Отдельными состояниями, а не парой «идёт / готово»: действие
 * у каждого своё. Нет области — спросить человека; нет его страны —
 * показать, какие есть рядом; отказ источника — сказать словами ядра.
 */
sealed interface NearbyPeers {
    /** Ещё не искали. */
    data object Idle : NearbyPeers

    /** Ищем: поход за списком идёт секунды, а через Tor — десятки. */
    data object Looking : NearbyPeers

    /**
     * Нашли и предлагаем.
     *
     * **Это ещё не настройка.** Пир видит источник и адресата пакетов
     * в меше, и выбор остаётся за человеком (0.2.5): узлы показываются,
     * и включает их он сам.
     */
    data class Found(
        val region: String,
        val country: String,
        val peers: List<String>,
    ) : NearbyPeers

    /**
     * В стране человека узлов нет — но в его части света есть.
     *
     * Не отказ: страну он выберет сам, а догадка здесь была только
     * про «поближе».
     */
    data class PickCountry(val region: String, val countries: List<String>) : NearbyPeers

    /** Область телефон не называет: выбирать придётся руками. */
    data object NoRegion : NearbyPeers

    /** Отказ похода — словами ядра. */
    data class Refused(val text: String) : NearbyPeers
}

/**
 * Первый запуск: сеть, настроенная без слов «yggdrasil» и «реле».
 *
 * # Почему узлов нет в сборке
 *
 * Зашитого списка публичных пиров быть не должно (0.2.5), и зашитого
 * списка реле nostr тоже нет и не будет (FFI.md): предложи мы свой,
 * выбор «кто видит, кто с кем переписывается» сделали бы мы. Поэтому
 * помощник **приносит** список во время работы и показывает, что
 * выбрал, — а включает человек, одним нажатием и увидев, что включает.
 *
 * Разница с «несколькими пирами по умолчанию» не косметическая: список
 * в сборке протух бы вместе с релизом, и никто бы этого не заметил,
 * а узлы остались бы теми, кого выбрали мы.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkSetupModel(
    private val session: SessionContext,
    private val transports: TransportsModel,
) {
    private val _nearby = MutableStateFlow<NearbyPeers>(NearbyPeers.Idle)
    val nearby: StateFlow<NearbyPeers> = _nearby.asStateFlow()

    /**
     * Предлагали ли уже настроить сеть этому аккаунту.
     *
     * У второго экрана своих ступеней нет вовсе, и спрашивать его
     * не о чем: там признак всегда «предлагали».
     */
    val setupOffered: StateFlow<Boolean> = session.activeAccountId.flatMapLatest { id ->
        when {
            id == null || id.startsWith("companion:") -> flowOf(true)
            else -> session.settings.networkSetupOffered(id)
        }
    }.stateIn(session.scope, SharingStarted.Eagerly, true)

    /** Помощника показали (или человек прошёл мимо) — больше не звать. */
    fun markSetupOffered() {
        val id = session.activeAccountId.value ?: return
        session.scope.launch {
            session.settings.setNetworkSetupOffered(id, true)
        }
    }

    /**
     * Ищет узлы меша поближе: страна телефона, область — её часть света.
     *
     * Порядок такой: сначала перечень стран области, потом список узлов
     * найденной страны. Перечень нужен не для красоты — им **сверяется**
     * наша догадка об имени страны: имена ICU и имена файлов расходятся,
     * и промах по имени выглядел бы как «узлов в вашей стране нет».
     *
     * Отказ перечня (у источника предел запросов) походу за самим
     * списком не мешает: тогда имя пробуется как есть.
     */
    fun lookForNearbyPeers() {
        if (session.isCompanion) return
        val code = PeerOrigins.countryCode(session.app)
        val region = code?.let { PeerOrigins.regionOf(it) }
        if (code == null || region == null) {
            _nearby.value = NearbyPeers.NoRegion
            return
        }
        val guess = PeerOrigins.countryFile(code)
        _nearby.value = NearbyPeers.Looking
        session.scope.launch(Dispatchers.IO) {
            val step = look(region, guess)
            withContext(Dispatchers.Main) { _nearby.value = step }
        }
    }

    /** Та же работа для страны, названной человеком. */
    fun lookInCountry(region: String, country: String) {
        if (session.isCompanion) return
        _nearby.value = NearbyPeers.Looking
        session.scope.launch(Dispatchers.IO) {
            val step = peersOf(region, country) ?: NearbyPeers.PickCountry(region, emptyList())
            withContext(Dispatchers.Main) { _nearby.value = step }
        }
    }

    /**
     * Включает меш с теми узлами, которые человек подтвердил.
     *
     * Сначала узлы, потом режим: узел, поднятый без пиров, ни с кем
     * не соединён, и ступень в этом состоянии честно отвечает
     * «не работает» (0.2.5) — показывать человеку такую секунду незачем.
     */
    fun turnOnMesh(peers: List<String>) {
        if (session.isCompanion || peers.isEmpty()) return
        // Одной корутиной, а не двумя командами модели ступеней: две
        // ушли бы в разные корутины, и порядок между ними стал бы делом
        // планировщика, а здесь он обещан человеку текстом выше.
        session.io("Failed to turn the mesh on", R.string.error_ygg_settings_failed) {
            val client = session.core.client()
            client.setYggPeers(peers.map { it.trim() }.filter { it.isNotEmpty() })
            client.setYggMode(FfiYggMode.EMBEDDED)
            client.setTransportEnabled(org.ratatosk.core.FfiTransport.YGG, true)
            client.networkChanged()
            transports.refreshTransportStatus()
        }
    }

    /** Забыть принесённое: человек закрыл помощника, настройкой оно не стало. */
    fun forgetNearby() {
        _nearby.value = NearbyPeers.Idle
    }

    /** Сессия закрыта: узлы, подобранные прошлому аккаунту, — не наши. */
    fun reset() {
        _nearby.value = NearbyPeers.Idle
    }

    private fun look(region: String, guess: String?): NearbyPeers {
        val countries = try {
            session.core.client().yggPeerCountries(region)
        } catch (e: org.ratatosk.core.RatatoskException.PeerList) {
            // Перечень необязателен: пробуем догадку напрямую.
            android.util.Log.d("RatatoskVM", "country index refused: ${e.reason}")
            emptyList()
        } catch (e: Exception) {
            android.util.Log.w("RatatoskVM", "Failed to fetch country index", e)
            emptyList()
        }
        val country = when {
            guess == null -> null
            countries.isEmpty() -> guess
            else -> PeerOrigins.matchCountry(guess, countries)
        }
        if (country == null) return NearbyPeers.PickCountry(region, countries)
        return peersOf(region, country) ?: NearbyPeers.PickCountry(region, countries)
    }

    /**
     * Узлы страны, если они есть; `null` — страна не та, выбирать человеку.
     *
     * Отказ ядра отделён от «страны нет»: у причин разное продолжение —
     * одну исправит выбор другой страны, другую только повтор позже.
     */
    private fun peersOf(region: String, country: String): NearbyPeers? = try {
        val brought = session.core.client().fetchYggPeers(region, country)
        val picked = PeerOrigins.pickDistinctHosts(brought)
        if (picked.isEmpty()) null else NearbyPeers.Found(region, country, picked)
    } catch (e: org.ratatosk.core.RatatoskException.PeerList) {
        if (e.reason == org.ratatosk.core.FfiPeerListRefusal.NO_SUCH_LIST) {
            null
        } else {
            NearbyPeers.Refused(session.core.peerListRefusalText(e.reason))
        }
    } catch (e: Exception) {
        android.util.Log.w("RatatoskVM", "Failed to fetch nearby peers", e)
        NearbyPeers.Refused(session.string(R.string.error_ygg_settings_failed))
    }
}
