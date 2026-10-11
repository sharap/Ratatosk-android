package chat.ratatosk.android.core

import chat.ratatosk.android.BuildConfig
import org.ratatosk.core.FfiTransport

/**
 * Какие ступени доставки вошли в эту сборку.
 *
 * Набор задаётся при сборке — `-Pratatosk.without=tor,nostr` — и одним
 * списком управляет обоими концами сразу: признаками, с которыми собрано
 * ядро, и тем, что видно в настройках.
 *
 * Разделять эти два конца нельзя, и причина не в стройности. Ядро, собранное
 * без признака, ступень не поднимет, но настройку сохранит и честно откажет
 * (FFI.md). Покажи мы при этом переключатель — экран выглядел бы рабочим,
 * а ступень молчала бы; этот разбор у ядра уже был на стенде. Поэтому
 * убранной ступени в интерфейсе нет вовсе: человек, собравший сборку без
 * неё, не ищет, куда она делась, — он сам её и убрал.
 *
 * Локальной сети в списке нет: это опора лестницы §5.4, ей не нужно ни
 * адреса, ни настройки, и признака сборки у неё тоже нет.
 */
object BuiltTransports {
    val tor = BuildConfig.TRANSPORT_TOR
    val mail = BuildConfig.TRANSPORT_MAIL
    val ygg = BuildConfig.TRANSPORT_YGG
    val nostr = BuildConfig.TRANSPORT_NOSTR

    /**
     * Эфир убирается не признаком ядра, а тем, что радио ему не вручают:
     * признак `bt` в ядре включает BlueZ, то есть Linux, а на Android
     * эфир целиком даёт мост из приложения.
     */
    val bt = BuildConfig.TRANSPORT_BT

    /** `FfiTransport.ONION in BuiltTransports` — читается как вопрос. */
    operator fun contains(transport: FfiTransport): Boolean = when (transport) {
        FfiTransport.LAN -> true
        FfiTransport.ONION -> tor
        FfiTransport.MAIL -> mail
        FfiTransport.YGG -> ygg
        FfiTransport.NOSTR -> nostr
        FfiTransport.BT -> bt
    }

    /** Все ли ступени на месте — для диагностики и журнала. */
    val complete: Boolean get() = tor && mail && ygg && nostr && bt

    /** Чего в сборке нет, словами для журнала. */
    val missing: List<String>
        get() = buildList {
            if (!tor) add("tor")
            if (!mail) add("mail")
            if (!ygg) add("ygg")
            if (!nostr) add("nostr")
            if (!bt) add("bt")
        }
}
