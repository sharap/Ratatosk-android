package chat.ratatosk.android.util

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

/** Где искать публичный список: каталог области и файл страны. */
data class PeerOrigin(val region: String, val country: String)

/**
 * Откуда человек — по мнению телефона, а не по его слову.
 *
 * Нужно это ровно для одного: предложить при первом запуске узлы меша
 * **поближе**, чтобы человеку не пришлось выбирать область и страну
 * из чужого репозитория, не зная ни того, ни другого.
 *
 * # Почему таблица стран здесь, а список узлов — нет
 *
 * Зашитого в сборку списка публичных пиров быть не должно (0.2.5): пир
 * видит источник и адресата пакетов в меше, и выбор, кто это будет,
 * принадлежит человеку. Здесь адресов нет вовсе — только география:
 * в какой каталог чужого репозитория смотреть. Узлы по-прежнему
 * приносятся во время работы, и включаются только те, что человек
 * увидел и подтвердил.
 *
 * География к тому же не протухает так, как список узлов: каталог
 * «europe» не выключают, а узел выключают каждый месяц.
 *
 * # Чего эта догадка не умеет
 *
 * Сказать, какой узел **быстрее**. Задержку видно только после
 * соединения (`ygg_peers_alive`), и до него мерить нечем. «Поближе»
 * здесь означает «в вашей стране или в вашей части света» — ровно тот
 * совет, который дают сами авторы сети, — а не «самый быстрый».
 */
object PeerOrigins {
    /**
     * Какая область чужого репозитория соответствует стране.
     *
     * Группировка — **их**, а не географическая: Армения у них в Европе,
     * Турция в `mena`. Поэтому таблица списана с репозитория, а не
     * выведена из частей света, и спорные случаи принадлежат ему.
     *
     * Область `other` сюда не входит нарочно: там лежат не страны,
     * а оверлейные сети (`tor`, `i2p`, `lokinet`). Подставить её
     * по умолчанию значило бы предложить человеку узлы, которых он
     * не выбирал и которые работают совсем иначе.
     */
    private val REGIONS: Map<String, String> = buildMap {
        // Имя помощника не `put`: локальная функция перекрыла бы метод
        // карты, и `put(it, region)` позвал бы саму себя. Проверка
        // поймала это переполнением стека на первом же обращении.
        fun region(region: String, codes: String) {
            codes.split(' ').forEach { put(it, region) }
        }
        region(
            "europe",
            "AL AD AM AT BY BE BA BG HR CY CZ DK EE FO FI FR GE DE GI GR GG HU IS IE IM IT JE " +
                "LV LI LT LU MT MD MC ME NL MK NO PL PT RO RU SM RS SK SI ES SJ SE CH UA GB VA XK",
        )
        region(
            "asia",
            "AF AZ BD BT BN KH CN HK IN ID JP KZ KP KR KG LA MO MY MV MN MM NP PK PH SG LK TW " +
                "TJ TH TL TM UZ VN",
        )
        region("mena", "DZ BH EG IR IQ IL JO KW LB LY MA OM PS QA SA SD SY TN TR AE EH YE")
        region(
            "africa",
            "AO BJ BW BF BI CM CV CF TD KM CD CG CI DJ GQ ER ET GA GM GH GN GW KE LS LR MG MW " +
                "ML MR MU MZ NA NE NG RE RW SH ST SN SC SL SO SS ZA SZ TZ TG UG ZM ZW YT",
        )
        region(
            "north-america",
            "AG AI AW BB BL BM BS BZ CA CR CU CW DM DO GD GL GP GT HN HT JM KN KY LC MF MQ MS " +
                "MX NI PA PR SV SX TC TT US VC VG VI",
        )
        region("south-america", "AR BO BR CL CO EC FK GF GY PE PY SR UY VE")
        region(
            "oceania",
            "AS AU CK FJ FM GU KI MH MP NC NF NR NU NZ PF PG PN PW SB TK TO TV VU WF WS",
        )
    }

    /**
     * Имена файлов, которые у Android называются иначе.
     *
     * Имя страны берётся у системы (`Locale.getDisplayCountry`), и почти
     * всегда оно сходится с именем файла: «Germany» — `germany.md`.
     * Но ICU зовёт Гонконг «Hong Kong SAR China», а файл называется
     * `hong-kong.md`. Таких случаев мало, и каждый здесь поимённо —
     * общего правила для них нет.
     *
     * Промах не беда: подобранное имя сверяется с настоящим перечнем
     * страны ещё до похода за списком, и не совпавшее просто уводит
     * человека к выбору руками.
     */
    private val FILE_NAMES: Map<String, String> = mapOf(
        "HK" to "hong-kong",
        "MO" to "macao",
        "KR" to "south-korea",
        "KP" to "north-korea",
        "CZ" to "czechia",
        "GB" to "united-kingdom",
        "US" to "united-states",
        "AE" to "united-arab-emirates",
    )

    /**
     * Код страны, который знает телефон.
     *
     * Сначала сеть оператора, потом SIM, потом язык системы. Порядок
     * такой потому, что сеть говорит, где человек **сейчас**, а язык —
     * где он когда-то выбрал язык: англичанин с русским интерфейсом
     * получил бы узлы не там, где сидит.
     *
     * Разрешений ни на что из этого не нужно: это не местоположение,
     * а код страны оператора.
     */
    fun countryCode(context: Context): String? {
        val telephony = try {
            context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        } catch (t: Throwable) {
            null
        }
        val fromNetwork = try {
            telephony?.networkCountryIso
        } catch (t: Throwable) {
            null
        }
        val fromSim = try {
            telephony?.simCountryIso
        } catch (t: Throwable) {
            null
        }
        val fromLocale = Locale.getDefault().country
        return listOf(fromNetwork, fromSim, fromLocale)
            .firstOrNull { !it.isNullOrBlank() && it.length == 2 }
            ?.uppercase(Locale.ROOT)
    }

    /** В какой области публичного списка искать страну; `null` — не знаем. */
    fun regionOf(code: String): String? = REGIONS[code.uppercase(Locale.ROOT)]

    /**
     * Как, по-нашему, называется файл страны.
     *
     * Имя берётся у системы и приводится к виду репозитория: строчные
     * латинские, пробелы дефисами. Проверять его не нужно — его сверят
     * с настоящим перечнем области.
     */
    fun countryFile(code: String): String? {
        val upper = code.uppercase(Locale.ROOT)
        FILE_NAMES[upper]?.let { return it }
        val name = Locale.Builder().setRegion(upper).build().getDisplayCountry(Locale.ENGLISH)
        if (name.isBlank() || name.equals(upper, ignoreCase = true)) return null
        val slug = name.lowercase(Locale.ROOT)
            .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
            .joinToString("")
            .trim('-')
            .replace(Regex("-+"), "-")
        return slug.takeIf { it.isNotEmpty() }
    }

    /**
     * Страны, которые стоит спросить про реле: своя и соседи по области.
     *
     * Соседи нужны потому, что страна без реле — обычное дело, а не
     * исключение. Принесено с устройства: в Корее наблюдатель знает
     * пятнадцать реле, проверку проходят три, и все три одного
     * владельца — после склейки остаётся одно. В соседних странах
     * их больше, а какое из них ближе **на самом деле**, скажет замер
     * с самого телефона.
     *
     * Своя страна стоит первой, но порядок здесь ничего не решает:
     * ядро всё равно расставит найденное по своему замеру.
     */
    fun nearbyCountries(code: String): List<String> {
        val upper = code.uppercase(Locale.ROOT)
        val region = regionOf(upper) ?: return listOf(upper)
        val mates = REGIONS.filterValues { it == region }.keys.sorted()
        return (listOf(upper) + mates).distinct()
    }

    /** Догадка целиком; `null` — страну телефон не называет или её нет в таблице. */
    fun guess(context: Context): PeerOrigin? {
        val code = countryCode(context) ?: return null
        val region = regionOf(code) ?: return null
        val country = countryFile(code) ?: return null
        return PeerOrigin(region, country)
    }

    /**
     * Какое из настоящих имён перечня похоже на нашу догадку.
     *
     * Сверка нужна потому, что имена ICU и имена файлов расходятся
     * («Hong Kong SAR China» против `hong-kong`), а промах по имени
     * выглядел бы как «узлов в вашей стране нет» — и человек поверил бы.
     *
     * Сначала точное совпадение, потом приставка в обе стороны: этого
     * хватает на расхождения вида «czechia» против «czech-republic»
     * без таблицы исключений на каждый случай.
     */
    fun matchCountry(guess: String, countries: List<String>): String? {
        countries.firstOrNull { it == guess }?.let { return it }
        countries.firstOrNull { it.startsWith(guess) || guess.startsWith(it) }?.let { return it }
        val head = guess.substringBefore('-')
        return countries.firstOrNull { it.substringBefore('-') == head }
    }

    /**
     * Берём до [limit] узлов **разных владельцев**, сохраняя порядок файла.
     *
     * Сами авторы сети советуют брать два-три узла, и именно разных: три
     * записи одного оператора — это один узел и ни одного запасного.
     *
     * # Почему владелец считается по домену, а не по хосту
     *
     * Проверено на настоящем списке: по хосту выходило `ygg1.mk16.de`
     * и `ygg2.mk16.de` — один владелец дважды, — а в списке США
     * `ip4.nerdvm.mywire.org` и `ip6.nerdvm.mywire.org`, то есть одна
     * и та же машина с двух сторон. Поэтому владелец считается по двум
     * последним частям имени.
     *
     * Правило приблизительное, и это сказано прямо: имена вида
     * `example.co.uk` сольются в `co.uk`, а два разных оператора под
     * одним хостингом в домене не различаются вовсе. Точного ответа тут
     * нет ни у кого — список ведут люди, и владельца в нём называет
     * заголовок, которого граница наружу не отдаёт.
     *
     * Адрес вместо имени (их в списке много) остаётся собой: по номеру
     * владельца не угадать, а гадать здесь хуже, чем не гадать.
     *
     * Порядок источника сохраняется: узлы там сгруппированы по владельцам,
     * и перемешивать их незачем.
     */
    fun pickDistinctHosts(peers: List<String>, limit: Int = 3): List<String> {
        val seen = mutableSetOf<String>()
        val picked = mutableListOf<String>()
        for (uri in peers) {
            val owner = ownerOf(uri) ?: continue
            if (!seen.add(owner)) continue
            picked += uri
            if (picked.size == limit) break
        }
        return picked
    }

    /** Кому, по-нашему, принадлежит узел: домен или сам адрес. */
    private fun ownerOf(uri: String): String? {
        val host = hostOf(uri) ?: return null
        // Адрес, а не имя: цифры с точками или двоеточия IPv6.
        val looksLikeAddress = host.all { it.isDigit() || it == '.' } || host.contains(':')
        if (looksLikeAddress) return host
        val parts = host.split('.')
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host
    }

    /** Хост из `tcp://узел:порт?key=…`; `null` — строка не похожа на адрес. */
    private fun hostOf(uri: String): String? {
        val afterScheme = uri.substringAfter("://", missingDelimiterValue = "")
        if (afterScheme.isEmpty()) return null
        val hostPort = afterScheme.substringBefore('/').substringBefore('?')
        // Адрес IPv6 в скобках: `[2001:db8::1]:9001` — порт отрезается
        // по последнему двоеточию только вне скобок.
        val host = if (hostPort.startsWith("[")) {
            hostPort.substringAfter('[').substringBefore(']')
        } else {
            hostPort.substringBefore(':')
        }
        return host.lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }
    }
}
