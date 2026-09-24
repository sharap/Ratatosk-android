package chat.ratatosk.android.ui.model

import chat.ratatosk.android.R
import chat.ratatosk.android.util.toHexString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Что человек выбрал для уведомлений чата (§14).
 *
 * [speaksNow] — не `!silent`: разница ровно в истёкшем сроке, и считает
 * его ядро. «Замолчать до утра» ставят чаще всего, а снять забывают.
 */
data class ChatNotify(
    val silent: Boolean,
    /** До какого момента молчать, мс; `0` — бессрочно. */
    val untilMs: ULong,
    val speaksNow: Boolean,
) {
    /** Незнакомое и неспрошенное читается как «говорить»: промолчать — потерять сообщение. */
    companion object {
        val SPEAKING = ChatNotify(silent = false, untilMs = 0UL, speaksNow = true)
    }
}

interface NotifyApi {
    /**
     * Выбор по чатам в hex. Чего здесь нет — не спрашивали, и это
     * «говорить»: ошибиться в сторону молчания значит потерять
     * сообщение молча.
     */
    val chatNotify: StateFlow<Map<String, ChatNotify>>

    /** Спрашивает ядро про этот чат; ответ ложится в [chatNotify]. */
    fun loadChatNotify(chatId: ByteArray)

    /**
     * Молчать или говорить (§14).
     *
     * [untilMs] — момент, когда молчание кончится само; `0` — «пока не
     * передумаю». У `silent = false` срок не читается: снятое молчание
     * не должно включаться обратно.
     */
    fun setChatNotify(chatId: ByteArray, silent: Boolean, untilMs: ULong)
}

/**
 * Уведомления чата: выбор живёт в ядре (§14).
 *
 * Здесь только выбор человека и ответ ядра «говорить ли сейчас». Звук,
 * вибрация и вид шторки — показ, и решает их клиент; решение «показывать
 * или молчать» принимается там, где уведомление рождается, — в службе.
 *
 * По сети настройка не ездит: ни собеседник, ни свой же десктоп о ней
 * не узнают, — поэтому у второго экрана её нет вовсе.
 */
class NotifyModel(private val session: SessionContext) : NotifyApi {
    private val _chatNotify = MutableStateFlow<Map<String, ChatNotify>>(emptyMap())
    override val chatNotify: StateFlow<Map<String, ChatNotify>> = _chatNotify.asStateFlow()

    override fun loadChatNotify(chatId: ByteArray) {
        if (session.isCompanion) return
        session.io("Failed to read chat notify") { load(chatId) }
    }

    override fun setChatNotify(chatId: ByteArray, silent: Boolean, untilMs: ULong) {
        if (session.isCompanion) return
        session.io("Failed to set chat notify", R.string.notify_failed) {
            session.core.client().setChatNotify(chatId, silent, untilMs)
            // Ядро ответит и событием, но экран не должен ждать шину:
            // выключатель обязан встать там, где его нажали.
            load(chatId)
        }
    }

    /** Ядро подтвердило правку (`ChatNotifyChanged`) — перечитать выбор. */
    fun onChatNotifyChanged(chatId: ByteArray) = loadChatNotify(chatId)

    private suspend fun load(chatId: ByteArray) {
        val notify = session.core.client().chatNotify(chatId)
        val hex = chatId.toHexString()
        withContext(Dispatchers.Main) {
            _chatNotify.update {
                it + (hex to ChatNotify(notify.silent, notify.untilMs, notify.speaksNow))
            }
        }
    }

    fun reset() {
        _chatNotify.value = emptyMap()
    }
}
