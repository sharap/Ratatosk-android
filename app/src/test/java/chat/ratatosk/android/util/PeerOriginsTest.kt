package chat.ratatosk.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Догадка «откуда человек» и выбор узлов из принесённого списка.
 *
 * Сеть здесь не зовётся: проверяется то, что считается на телефоне, —
 * область по стране, сверка имени и выбор разных владельцев.
 */
class PeerOriginsTest {

    /**
     * Три строки одного владельца — это один узел, а не три.
     *
     * В файле у оператора обычно несколько строк: разные схемы и порты
     * одной машины. Взяв три подряд, человек получил бы один узел и ни
     * одного запасного — а сами авторы сети советуют брать два-три
     * **разных**.
     */
    @Test
    fun threeLinesOfOneOwnerAreOneNode() {
        val brought = listOf(
            "tls://ygg.example.org:443",
            "tcp://ygg.example.org:80",
            "tcp://ygg.example.org:1337?key=00",
            "tcp://second.example.net:9001",
            "tls://third.example.com:443",
            "tcp://fourth.example.com:9001",
        )
        val picked = PeerOrigins.pickDistinctHosts(brought)
        assertEquals(
            listOf(
                "tls://ygg.example.org:443",
                "tcp://second.example.net:9001",
                "tls://third.example.com:443",
            ),
            picked,
        )
    }

    /**
     * Разные имена одного владельца — тоже один владелец.
     *
     * Это настоящие строки из списка: `ygg1.mk16.de` и `ygg2.mk16.de`
     * в Германии, `ip4.nerdvm.mywire.org` и `ip6.nerdvm.mywire.org`
     * в США — одна машина с двух сторон. По хосту они считались
     * разными, и помощник предлагал одного оператора дважды.
     */
    @Test
    fun twoNamesOfOneOwnerAreStillOneOwner() {
        val brought = listOf(
            "tcp://ygg1.mk16.de:1337",
            "tcp://ygg2.mk16.de:1337",
            "tcp://ip4.nerdvm.mywire.org:8080",
            "tcp://ip6.nerdvm.mywire.org:8080",
            "tls://ygg.mkg20001.io:443",
        )
        assertEquals(
            listOf(
                "tcp://ygg1.mk16.de:1337",
                "tcp://ip4.nerdvm.mywire.org:8080",
                "tls://ygg.mkg20001.io:443",
            ),
            PeerOrigins.pickDistinctHosts(brought),
        )
    }

    /**
     * Адрес остаётся собой: по номеру владельца не угадать.
     *
     * **Чего эта проверка не стережёт:** что два разных номера —
     * действительно два владельца. Они могут стоять в одной стойке,
     * и узнать это здесь нечем.
     */
    @Test
    fun anAddressIsItsOwnOwner() {
        val brought = listOf(
            "tls://133.18.201.69:54232",
            "tls://153.120.42.137:54232",
            "tls://133.18.201.69:443",
        )
        assertEquals(
            listOf("tls://133.18.201.69:54232", "tls://153.120.42.137:54232"),
            PeerOrigins.pickDistinctHosts(brought),
        )
    }

    /** Порядок источника сохраняется: он сгруппирован по владельцам. */
    @Test
    fun theOrderOfTheSourceSurvivesTheChoice() {
        val brought = listOf(
            "tcp://b.example:9001",
            "tcp://a.example:9001",
            "tcp://c.example:9001",
        )
        assertEquals(brought, PeerOrigins.pickDistinctHosts(brought))
        assertEquals(listOf("tcp://b.example:9001"), PeerOrigins.pickDistinctHosts(brought, limit = 1))
    }

    /** Адрес IPv6 в скобках — один хост, а не «2001» и порт. */
    @Test
    fun anIpv6AddressIsOneHost() {
        val brought = listOf(
            "tcp://[2001:db8::1]:9001",
            "tls://[2001:db8::1]:443",
            "tcp://[2001:db8::2]:9001",
        )
        assertEquals(
            listOf("tcp://[2001:db8::1]:9001", "tcp://[2001:db8::2]:9001"),
            PeerOrigins.pickDistinctHosts(brought),
        )
    }

    /**
     * Имя страны сверяется с настоящим перечнем, а не угадывается.
     *
     * ICU зовёт Гонконг «Hong Kong SAR China», а файл называется
     * `hong-kong.md`; у Чехии наоборот — старое имя длиннее нового.
     * Промах по имени выглядел бы как «узлов в вашей стране нет».
     */
    @Test
    fun aGuessedNameIsMatchedAgainstTheRealIndex() {
        val real = listOf("czechia", "germany", "hong-kong", "united-kingdom")
        assertEquals("germany", PeerOrigins.matchCountry("germany", real))
        assertEquals("hong-kong", PeerOrigins.matchCountry("hong-kong-sar-china", real))
        assertEquals("united-kingdom", PeerOrigins.matchCountry("united-kingdom", real))
        assertNull("страны в перечне нет — и выдумывать её нельзя", PeerOrigins.matchCountry("latvia", real))

        // **Чего сверка не делает нарочно:** не ищет похожее по началу
        // слова. «Czech Republic» со старых ICU в «czechia» так
        // не превратится — для него есть имя в таблице исключений,
        // и проверяется оно ниже. Включи мы догадку по приставке, и
        // «india» нашла бы «indonesia»: человек получил бы узлы
        // не в своей стране и об этом не узнал.
        assertNull(PeerOrigins.matchCountry("czech-republic", real))
        assertNull(PeerOrigins.matchCountry("india", listOf("indonesia")))
        assertEquals("czechia", PeerOrigins.countryFile("CZ"))
        assertEquals("hong-kong", PeerOrigins.countryFile("HK"))
    }

    /**
     * Группировка — **их**, а не географическая.
     *
     * Армения у них в Европе, Турция в `mena`. Проверка называет
     * значения сама: возьми она их из таблицы, подмена Армении на Азию
     * оставила бы проверку зелёной.
     */
    @Test
    fun theRegionsFollowTheSourceAndNotGeography() {
        assertEquals("europe", PeerOrigins.regionOf("AM"))
        assertEquals("mena", PeerOrigins.regionOf("TR"))
        assertEquals("asia", PeerOrigins.regionOf("HK"))
        assertEquals("north-america", PeerOrigins.regionOf("US"))
        assertEquals("south-america", PeerOrigins.regionOf("BR"))
        assertEquals("oceania", PeerOrigins.regionOf("NZ"))
        assertEquals("africa", PeerOrigins.regionOf("ZA"))
        // Код страны приходит и строчным — от SIM он бывает таким.
        assertEquals("europe", PeerOrigins.regionOf("ru"))
    }

    /**
     * Про реле спрашивают не только свою страну.
     *
     * Страна без годных реле — обычное дело: в Корее их пятнадцать,
     * проверку проходят три, и все три одного владельца. Поэтому
     * спрашивают свою и соседей по области, а что из этого ближе
     * на самом деле, меряет уже телефон.
     */
    @Test
    fun relaysAreAskedAboutTheWholeNeighbourhood() {
        val korea = PeerOrigins.nearbyCountries("KR")
        assertEquals("своя страна первая", "KR", korea.first())
        assertTrue("соседи по области добавлены: $korea", korea.size > 10)
        assertTrue("Япония рядом", korea.contains("JP"))
        assertTrue("Сингапур рядом", korea.contains("SG"))
        assertTrue("Германия не сосед Кореи", !korea.contains("DE"))
        assertEquals("дважды одну страну не спрашиваем", korea.distinct(), korea)

        // Код, которого нет в таблице: спросим хотя бы про него самого,
        // а дальше расширит клиент — пустым списком «где угодно».
        assertEquals(listOf("ZZ"), PeerOrigins.nearbyCountries("ZZ"))
    }

    /**
     * Область `other` не предлагается никому.
     *
     * Там лежат не страны, а оверлейные сети (`tor`, `i2p`, `lokinet`):
     * подставив её по умолчанию, помощник предложил бы человеку узлы,
     * которых он не выбирал и которые работают совсем иначе.
     */
    @Test
    fun theOtherFolderIsNeverProposed() {
        val codes = listOf("US", "RU", "DE", "ZA", "BR", "NZ", "TR", "HK", "ZZ", "QQ")
        for (code in codes) {
            assertTrue("область `other` предлагать нельзя: $code", PeerOrigins.regionOf(code) != "other")
        }
        assertNull("несуществующий код не область", PeerOrigins.regionOf("ZZ"))
    }
}
