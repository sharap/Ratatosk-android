package chat.ratatosk.android

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Правила разрезки `RatatoskViewModel` по моделям (`ui/model`).
 *
 * Проверяется устройство, а не поведение: обе ошибки ниже уже случались
 * по ходу самой разрезки, тихо и без единого исключения, — модель забыли
 * позвать при выходе из аккаунта, и чужие данные всплывали у следующего;
 * окно завело свою ошибку, и всё, о чём сообщали модели, человек перестал
 * видеть вовсе. Ни то, ни другое не ловится ни компилятором, ни глазами
 * в ревью: правильный код выглядит точно так же, как забытый.
 */
class ModelsHygieneTest {
    private val modelsDir = File("src/main/java/chat/ratatosk/android/ui/model")
    private val models: List<File> =
        modelsDir.listFiles()?.filter { it.extension == "kt" }?.sortedBy { it.name } ?: emptyList()
    private val appModels = File(modelsDir, "AppModels.kt")

    @Test
    fun sourcesAreWhereWeThink() {
        assertTrue("модели не найдены — тест смотрит не туда", models.size > 10)
        assertTrue("нет AppModels.kt", appModels.isFile)
    }

    /** У кого есть что забывать, того зовут при выходе из аккаунта. */
    @Test
    fun everyModelWithStateIsClearedOnLogout() {
        val resetAll = appModels.readText()
            .substringAfter("fun resetAll()")
            .substringBefore("fun close()")
        val forgotten = models.mapNotNull { file ->
            val text = file.readText()
            if ("    fun reset()" !in text) return@mapNotNull null
            // `ChatsModel` → `chats`, `FilesModel` → `files` — так их зовёт AppModels.
            val name = file.nameWithoutExtension.removeSuffix("Model")
            val called = Regex("""\b\w+\.reset\(\)""").findAll(resetAll)
                .any { it.value.substringBefore(".").equals(name, ignoreCase = true) ||
                       it.value.substringBefore(".").equals(name + "s", ignoreCase = true) }
            if (called) null else file.name
        }
        assertTrue("не очищаются при выходе: $forgotten", forgotten.isEmpty())
    }

    /** Ошибка у окна и у моделей одна: `SessionContext._error`. */
    @Test
    fun thereIsOnlyOneErrorChannel() {
        val session = File(modelsDir, "SessionContext.kt")
        val own = (models + File("src/main/java/chat/ratatosk/android/ui/RatatoskViewModel.kt"))
            .filter { it.absolutePath != session.absolutePath }
            .filter { file ->
                file.readLines().any { line ->
                    Regex("""val _error\s*=\s*MutableStateFlow""").containsMatchIn(line)
                }
            }
        assertTrue("своя ошибка мимо SessionContext: ${own.map { it.name }}", own.isEmpty())
    }

    /** Корутины моделей живут в области сессии, а не сами по себе. */
    @Test
    fun modelsLaunchInTheSessionScope() {
        val offenders = models.mapNotNull { file ->
            val hits = file.readLines().withIndex().filter { (_, line) ->
                val code = line.substringBefore("//").trim()
                if (code.startsWith("*")) false
                else "GlobalScope" in code || "viewModelScope" in code
            }
            if (hits.isEmpty()) null else "${file.name}:${hits.map { it.index + 1 }}"
        }
        assertTrue("корутины мимо session.scope: $offenders", offenders.isEmpty())
    }
}
