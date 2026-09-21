package chat.ratatosk.android.ui.model

import chat.ratatosk.android.R
import chat.ratatosk.android.util.toHexString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ratatosk.core.FfiChannelAdmit
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

/** Что окно знает о каналах и что с ними делает. */
interface ChannelsApi {
    /** Заявки на впуск, по каналам; §10.4 обещает, что они ждут. */
    val channelRequests: StateFlow<Map<String, List<FfiChannelRequest>>>

    /** Кого уже впустили, по каналам: видно только владельцу. */
    val channelAdmits: StateFlow<Map<String, List<FfiChannelAdmit>>>

    /** Перечитать заявки и впущенных — при открытии карточки канала. */
    fun refreshChannelPeople(chatId: ByteArray)

    /**
     * Впускает просящего (§10.4): ему уедет ключ текущего поколения
     * и подписанная запись о впуске.
     *
     * Отказа как ответа не бывает — молчание владельца и есть отказ,
     * поэтому кнопка здесь одна.
     */
    fun admitToChannel(chatId: ByteArray, peerIk: ByteArray)

    /** Текст §15 — слова ядра, показываются **до** действия. */
    fun channelNotice(which: ChannelNotice): String

    /**
     * Заводит канал. Порода задаётся один раз и не меняется: «открытый»
     * и «по приглашению» — два разных обещания (§6.1).
     */
    fun createChannel(title: String, open: Boolean)

    /** Подписка по ссылке `ratatosk:v0:channel:…` (§10.3, §10.4). */
    fun subscribeToChannel(uri: String)

    /**
     * Отписка (§10.6). Уносит и архив: поколения ключа чтения хранятся
     * у читателя и больше нигде — сказать об этом надо **до**.
     */
    fun unsubscribeFromChannel(chatId: ByteArray)

    /**
     * Ссылка на канал — запросом, а не полем списка: в неё едут нынешняя
     * версия представления и наши адреса, и положенная в список она
     * устаревала бы молча (§10.2).
     */
    fun channelLink(chatId: ByteArray, onResult: (Result<String>) -> Unit)
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

    private val _channelAdmits = MutableStateFlow<Map<String, List<FfiChannelAdmit>>>(emptyMap())
    override val channelAdmits = _channelAdmits.asStateFlow()

    override fun refreshChannelPeople(chatId: ByteArray) {
        refreshRequests(chatId)
        refreshAdmits(chatId)
    }

    override fun admitToChannel(chatId: ByteArray, peerIk: ByteArray) {
        session.io("Failed to admit to channel", R.string.channel_admit_failed) {
            session.core.client().admitToChannel(chatId, peerIk)
            // Ядро ответит и событием, но списки обновляем сразу: человек
            // нажал и ждёт, что просящий перейдёт в впущенные.
            refreshChannelPeople(chatId)
        }
    }

    /** Впущенных читает только владелец: у остальных ядро их не держит. */
    private fun refreshAdmits(chatId: ByteArray) {
        if (session.isCompanion) return
        val hex = chatId.toHexString()
        session.scope.launch(Dispatchers.IO) {
            try {
                val list = session.core.client().channelAdmits(chatId)
                withContext(Dispatchers.Main) {
                    _channelAdmits.update { it + (hex to list) }
                }
            } catch (e: Exception) {
                android.util.Log.w("RatatoskVM", "Failed to read channel admits", e)
            }
        }
    }

    override fun channelNotice(which: ChannelNotice): String = session.core.channelNotice(which)

    override fun createChannel(title: String, open: Boolean) {
        session.io("Failed to create channel", R.string.channel_create_failed) {
            session.core.client().createChannel(title, open)
        }
    }

    override fun subscribeToChannel(uri: String) {
        session.io("Failed to subscribe to channel", R.string.channel_subscribe_failed) {
            session.core.client().subscribeToChannel(uri)
        }
    }

    override fun unsubscribeFromChannel(chatId: ByteArray) {
        session.io("Failed to unsubscribe from channel", R.string.channel_unsubscribe_failed) {
            session.core.client().unsubscribeFromChannel(chatId)
        }
    }

    override fun channelLink(chatId: ByteArray, onResult: (Result<String>) -> Unit) {
        session.scope.launch(Dispatchers.IO) {
            val result = runCatching { session.core.client().channelLink(chatId) }
            withContext(Dispatchers.Main) {
                result.onFailure { session._error.value = session.errorText(it) }
                onResult(result)
            }
        }
    }

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
                // Впустили — заявки этого человека больше нет, а в списке
                // впущенных он появился.
                refreshChannelPeople(event.chatId)
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
        _channelAdmits.value = emptyMap()
    }
}
