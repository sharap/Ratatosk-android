package chat.ratatosk.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Библиотека ядра лежит там, где её ищет система, и собрана с теми же
 * биндингами, что лежат рядом.
 *
 * Биндинги пересобираются при каждой сборке, а `.so` кладётся отдельной
 * задачей — стоит вернуть одно из двух старым (например, `git checkout`
 * одного файла), и приложение падает на первом же событии ядра, с одним
 * лишь `BufferUnderflowException` в отчёте.
 *
 * Что проверка ловит: пропавшую библиотеку и расхождение по составу
 * функций ядра. Чего не ловит: перестановку значений в перечислениях —
 * имена функций от неё не меняются. Поэтому правило остаётся правилом:
 * `jniLibs` и `ratatosk_ffi.kt` меняются только вместе.
 */
class NativeLibraryTest {
    private val abis = listOf("arm64-v8a", "x86_64")
    private val bindings = File("src/main/java/org/ratatosk/core/ratatosk_ffi.kt")
    private val checksumName = Regex("uniffi_ratatosk_ffi_checksum_[a-z_0-9]+")

    @Test
    fun libraryLiesWhereTheSystemLooksForIt() {
        for (abi in abis) {
            val lib = File("src/main/jniLibs/$abi/libratatosk_ffi.so")
            assertTrue("нет библиотеки ядра для $abi: ${lib.absolutePath}", lib.isFile)
            assertTrue("библиотека ядра для $abi пуста", lib.length() > 1_000_000)
            // ELF: 0x7F 'E' 'L' 'F'
            val head = lib.inputStream().use { it.readNBytes(4) }
            assertEquals("$abi: это не ELF", "7f454c46", head.joinToString("") { "%02x".format(it) })
        }
    }

    @Test
    fun libraryAndBindingsComeFromTheSameCore() {
        assertTrue("биндинги не найдены", bindings.isFile)
        val expected = checksumName.findAll(bindings.readText()).map { it.value }.toSet()
        assertTrue("в биндингах не нашлось ни одной функции ядра", expected.size > 50)

        for (abi in abis) {
            val lib = File("src/main/jniLibs/$abi/libratatosk_ffi.so")
            // Имена лежат в таблице строк открытым текстом, так что хватит
            // чтения байтов: разбирать ELF ради этого незачем.
            val inLib = checksumName
                .findAll(String(lib.readBytes(), Charsets.ISO_8859_1))
                .map { it.value }
                .toSet()

            val missing = expected - inLib
            assertTrue(
                "$abi: библиотека старше биндингов — в ней нет ${missing.size} функций, " +
                    "например ${missing.take(3)}",
                missing.isEmpty(),
            )
            val extra = inLib - expected
            assertTrue(
                "$abi: биндинги старше библиотеки — в них нет ${extra.size} функций, " +
                    "например ${extra.take(3)}",
                extra.isEmpty(),
            )
        }
    }
}
