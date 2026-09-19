package chat.ratatosk.android.ui.model

import android.app.Application
import androidx.annotation.StringRes
import chat.ratatosk.android.core.RatatoskCore
import chat.ratatosk.android.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Общее для всех моделей: область корутин, настройки, приложение и ошибка,
 * которую видит человек.
 *
 * Появился при разрезке `RatatoskViewModel`: он оброс тремя тысячами строк,
 * и каждая новая правка требовала держать в голове весь файл. Модели по
 * назначению делят это состояние, а не копируют.
 */
class SessionContext(
    val app: Application,
    val scope: CoroutineScope,
    val settings: SettingsRepository,
) {
    internal val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Второй экран телефона, а не полный клиент. */
    val isCompanion: Boolean get() = RatatoskCore.isCompanionMode()

    /**
     * Есть ли связь с телефоном (у компаньона). Нужен нескольким моделям:
     * без связи команды уходят в пустоту, и звать их незачем.
     */
    /** Какой аккаунт открыт; `null` — никакой. Настройки у каждого свои. */
    internal val _activeAccountId = MutableStateFlow(RatatoskCore.getActiveAccountId())
    val activeAccountId: StateFlow<String?> = _activeAccountId.asStateFlow()

    internal val _isCompanionLinked = MutableStateFlow(false)
    val isCompanionLinked: StateFlow<Boolean> = _isCompanionLinked.asStateFlow()

    fun string(@StringRes id: Int): String = app.getString(id)

    fun clearError() {
        _error.value = null
    }

    /**
     * Команда в фоне.
     *
     * [logLabel] — только для журнала; человеку показываются **слова ядра**,
     * а если их нет — запасной текст [fallback]. Склеивать английскую
     * приставку с сообщением ядра нельзя: получается полфразы на чужом языке.
     */
    fun io(
        logLabel: String,
        @StringRes fallback: Int? = null,
        block: suspend () -> Unit,
    ): Job = scope.launch(Dispatchers.IO) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("RatatoskVM", logLabel, e)
            if (fallback != null) {
                val text = e.message?.takeIf { it.isNotBlank() } ?: app.getString(fallback)
                withContext(Dispatchers.Main) { _error.value = text }
            }
        }
    }
}
