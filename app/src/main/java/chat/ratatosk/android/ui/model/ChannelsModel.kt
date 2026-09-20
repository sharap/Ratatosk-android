package chat.ratatosk.android.ui.model

import chat.ratatosk.android.util.toHexString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ratatosk.core.FfiChannelRequest
import org.ratatosk.core.FfiGroup

/**
 * Почему поле ввода в канале закрыто.
 *
 * Разные причины — разные слова, и путать их нельзя: «развозить некому»
 * не то же, что «вам не дали права», а «ждём впуска» не то же, что
 * «читать нечем».
 */
enum class ChannelInput {
    /** Не канал или писать можно. */
    ALLOWED,

    /**
     * Слово развозит сказавший, по своему составу, а состав канала §3.2
     * оставляет владельцу: читатели друг друга не знают. Значит держателю
     * права «писать» развозить некому (`OnlyOwnerPublishesYet`), и сказать
     * надо именно это — не «нет права» и не молчание с галочкой
     * «отправлено». Вернёт доставку делегату рой (§7).
     */
    OWNER_ONLY,

    /** Права писать нет (§6.2): состоять в канале и мочь говорить — разное. */
    NO_RIGHT,

    /** Ждём, пока владелец впустит (§10.4). Отказа как ответа не бывает. */
    AWAITING,

    /** Читать пока нечем: ни одного поколения ключа (§10.4). */
    NOT_READABLE,
}

/**
 * Что показывать вместо поля ввода.
 *
 * Считается по [FfiGroup.mine], а не сравнением ключей: правило
 * «кто здесь владелец» живёт в ядре и в одном месте (§13.3).
 */
fun channelInput(group: FfiGroup?): ChannelInput {
    val channel = group?.channel ?: return ChannelInput.ALLOWED
    return when {
        channel.awaiting -> ChannelInput.AWAITING
        !channel.readable -> ChannelInput.NOT_READABLE
        group.mine -> ChannelInput.ALLOWED
        channel.rights.write -> ChannelInput.OWNER_ONLY
        else -> ChannelInput.NO_RIGHT
    }
}

/** Что окно знает о каналах. */
interface ChannelsApi {
    /** Заявки на впуск, по каналам; §10.4 обещает, что они ждут. */
    val channelRequests: StateFlow<Map<String, List<FfiChannelRequest>>>
}

/**
 * Каналы (фаза 2, §6, §10).
 *
 * Канал — это группа со вторым профилем, а не третий вид чата: он приходит
 * тем же `groups()`, и всё канальное лежит в `FfiGroup.channel`. Поэтому
 * здесь нет ни списка каналов, ни их состояния — только то, чего в группе
 * нет: заявки на впуск и перевод канальных событий в обновление списков.
 *
 * @param refreshGroups перечитать список чатов: канальные события меняют
 *   и представление канала, и наши права в нём.
 */
class ChannelsModel(
    private val session: SessionContext,
    private val refreshGroups: () -> Unit,
    private val loadMessages: (ByteArray) -> Unit,
) : ChannelsApi {

    private val _channelRequests = MutableStateFlow<Map<String, List<FfiChannelRequest>>>(emptyMap())
    override val channelRequests = _channelRequests.asStateFlow()

    /** Заявки читает только владелец: у остальных ядро их не держит. */
    fun refreshRequests(chatId: ByteArray) {
        if (session.isCompanion) return
        val hex = chatId.toHexString()
        session.scope.launch(Dispatchers.IO) {
            try {
                val list = session.core.client().channelRequests(chatId)
                withContext(Dispatchers.Main) {
                    _channelRequests.update { it + (hex to list) }
                }
            } catch (e: Exception) {
                android.util.Log.w("RatatoskVM", "Failed to read channel requests", e)
            }
        }
    }

    /**
     * Канальные события ядра.
     *
     * Почти все они меняют представление канала или наши права в нём,
     * а то и другое приезжает внутри `FfiGroup` — поэтому ответ один
     * и тот же: перечитать список. Отдельно стоит заявка: её в группе нет.
     */
    fun onChannelEvent(event: org.ratatosk.core.FfiEvent) {
        when (event) {
            is org.ratatosk.core.FfiEvent.ChannelCreated -> refreshGroups()
            is org.ratatosk.core.FfiEvent.ChannelChanged -> refreshGroups()
            is org.ratatosk.core.FfiEvent.ChannelSubscribed -> refreshGroups()
            is org.ratatosk.core.FfiEvent.ChannelUnsubscribed -> {
                _channelRequests.update { it - event.chatId.toHexString() }
                refreshGroups()
            }
            is org.ratatosk.core.FfiEvent.ChannelAdmitted -> {
                // Впустили — заявки этого человека больше нет.
                refreshRequests(event.chatId)
                refreshGroups()
            }
            is org.ratatosk.core.FfiEvent.ChannelKeyRotated -> {
                // Новое поколение: старые записи читаются, новые — только
                // с новым ключом. Перечитываем чат, чтобы это стало видно.
                refreshGroups()
                loadMessages(event.chatId)
            }
            is org.ratatosk.core.FfiEvent.ChannelRequested -> refreshRequests(event.chatId)
            else -> {}
        }
    }

    /** Сессия закрыта: чужих заявок мы не храним. */
    fun reset() {
        _channelRequests.value = emptyMap()
    }
}
