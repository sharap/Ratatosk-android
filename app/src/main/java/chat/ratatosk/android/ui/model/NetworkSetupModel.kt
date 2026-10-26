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
 * Что помощник нашёл человеку из реле nostr (0.3, дополнение).
 *
 * Отдельно от [NearbyPeers], хотя состояния те же: источники разные,
 * отказы разные, и общий тип означал бы ветку «а это про что» в каждом
 * месте показа.
 */
sealed interface NearbyRelays {
    /** Ещё не искали. */
    data object Idle : NearbyRelays

    /** Идём к наблюдателю: через Tor это десятки секунд. */
    data object Looking : NearbyRelays

    /**
     * В своих краях годных реле не нашлось — ищем где угодно.
     *
     * Отдельное состояние, а не молчаливый второй заход: человек,
     * которому минуту показывают «ищем», вправе знать, что первая
     * попытка кончилась ничем.
     */
    data object LookingWider : NearbyRelays

    /**
     * Нашли и предлагаем.
     *
     * **Это ещё не настройка.** Реле видит, кто с кем переписывается,
     * и выбирает его человек (0.3.3).
     */
    data class Found(val relays: List<org.ratatosk.core.FfiRelayCandidate>) : NearbyRelays

    /** Страну телефон не называет — спросить «кто поближе» не у кого. */
    data object NoCountry : NearbyRelays

    /** Отказ похода — словами ядра. */
    data class Refused(val text: String) : NearbyRelays
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
    /**
     * Откуда человек, по мнению телефона.
     *
     * Швом, а не прямым обращением к системе: в проверках телефона нет,
     * а без страны обе ветки подбора — и узлы меша, и реле — остались бы
     * непроверенными вовсе.
     */
    private val countryCode: () -> String? = { PeerOrigins.countryCode(session.app) },
) {
    private val _nearby = MutableStateFlow<NearbyPeers>(NearbyPeers.Idle)
    val nearby: StateFlow<NearbyPeers> = _nearby.asStateFlow()

    private val _nearbyRelays = MutableStateFlow<NearbyRelays>(NearbyRelays.Idle)
    val nearbyRelays: StateFlow<NearbyRelays> = _nearbyRelays.asStateFlow()

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
        val code = countryCode()
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

    /**
     * Ищет реле поближе: страну берём у телефона, остальное знает ядро.
     *
     * Цену похода ([relaySourceNotice]) экран показывает **до** этого
     * вызова: адрес наблюдателя лежит в сборке, и он увидит, что с этого
     * адреса ищут реле.
     */
    fun lookForNearbyRelays() {
        if (session.isCompanion) return
        val code = countryCode()
        if (code == null) {
            _nearbyRelays.value = NearbyRelays.NoCountry
            return
        }
        _nearbyRelays.value = NearbyRelays.Looking
        session.scope.launch(Dispatchers.IO) {
            // Сначала свои края: своя страна и соседи по области одним
            // запросом. Порядок ответа — по замеру **телефона**, так что
            // ближнее окажется первым само, где бы оно ни стояло.
            var step = ask(PeerOrigins.nearbyCountries(code))
            // Одного реле мало не потому, что число красивое: несколько
            // реле человек называет, чтобы делить след (0.3.3), и одно
            // этого не делает. Страна без годных реле — обычное дело:
            // в Корее их пятнадцать, годится одно.
            if (step.poor()) {
                withContext(Dispatchers.Main) {
                    _nearbyRelays.value = NearbyRelays.LookingWider
                }
                val wider = ask(emptyList())
                // Широкий заход заменяет узкий, только если нашёл больше:
                // одно близкое реле лучше, чем отказ «нигде ничего».
                if (!wider.poor() || step !is NearbyRelays.Found) {
                    step = wider
                }
            }
            withContext(Dispatchers.Main) { _nearbyRelays.value = step }
        }
    }

    /** Нашлось ли столько, чтобы не искать шире. */
    private fun NearbyRelays.poor(): Boolean =
        this !is NearbyRelays.Found || relays.size < 2

    /** Один заход к наблюдателю со списком стран; пустой — где угодно. */
    private fun ask(countries: List<String>): NearbyRelays = try {
        val found = session.core.client().fetchNostrRelays(countries)
        if (found.isEmpty()) {
            NearbyRelays.Refused(
                session.core.relayListRefusalText(
                    org.ratatosk.core.FfiRelayListRefusal.NOTHING_USABLE,
                ),
            )
        } else {
            NearbyRelays.Found(found)
        }
    } catch (e: org.ratatosk.core.RatatoskException.RelayList) {
        NearbyRelays.Refused(session.core.relayListRefusalText(e.reason))
    } catch (e: Exception) {
        android.util.Log.w("RatatoskVM", "Failed to fetch nostr relays", e)
        NearbyRelays.Refused(session.string(R.string.error_nostr_settings_failed))
    }

    /**
     * Включает nostr с теми реле, которые человек подтвердил.
     *
     * Сначала реле, потом ступень, и одной корутиной: ступень без реле
     * честно не работает (FFI.md), и показывать человеку эту секунду
     * незачем.
     */
    fun turnOnNostr(relays: List<String>) {
        if (session.isCompanion || relays.isEmpty()) return
        session.io("Failed to turn nostr on", R.string.error_nostr_settings_failed) {
            val client = session.core.client()
            client.setNostrRelays(relays)
            client.setTransportEnabled(org.ratatosk.core.FfiTransport.NOSTR, true)
            client.networkChanged()
            transports.refreshTransportStatus()
        }
    }

    /** Цена похода за реле — слова ядра, показать до нажатия. */
    fun relaySourceNotice(): String =
        try { org.ratatosk.core.relaySourceNotice() } catch (e: Exception) { "" }

    /** Забыть принесённое: человек закрыл помощника, настройкой оно не стало. */
    fun forgetNearby() {
        _nearby.value = NearbyPeers.Idle
        _nearbyRelays.value = NearbyRelays.Idle
    }

    /** Сессия закрыта: узлы, подобранные прошлому аккаунту, — не наши. */
    fun reset() {
        _nearby.value = NearbyPeers.Idle
        _nearbyRelays.value = NearbyRelays.Idle
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
