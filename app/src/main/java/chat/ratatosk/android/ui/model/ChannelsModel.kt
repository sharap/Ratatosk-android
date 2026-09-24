package chat.ratatosk.android.ui.model

import chat.ratatosk.android.R
import chat.ratatosk.android.util.toHexString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ratatosk.core.FfiChannelAdmit
import org.ratatosk.core.FfiChannelGrant
import org.ratatosk.core.FfiChannelRights
import org.ratatosk.core.FfiChannelSeed
import org.ratatosk.core.FfiSeeding
import org.ratatosk.core.FfiSharingLevel
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
 * Спрашивается `rights.write` — то же место, каким право спрашивает
 * отправка, и уже с учётом срока и правила «владельцу всё». Гасить поле
 * по составу нельзя: в канале состоять и мочь говорить — разные вещи
 * (§6.2).
 *
 * Раньше здесь гасилось всем, кроме владельца: доставки у делегата
 * не было — состав канала §3.2 оставляет владельцу, и развозить держателю
 * права было некому. Теперь своё слово уезжает владельцу и своим сидам,
 * а дальше расходится роем (§7.1), и отдельного «развозить некому»
 * больше не существует.
 */
fun channelInput(group: FfiGroup?): ChannelInput {
    val channel = group?.channel ?: return ChannelInput.ALLOWED
    return when {
        channel.awaiting -> ChannelInput.AWAITING
        !channel.readable -> ChannelInput.NOT_READABLE
        group.mine || channel.rights.write -> ChannelInput.ALLOWED
        else -> ChannelInput.NO_RIGHT
    }
}

/**
 * Что рассказал предпросмотр: название, порода и цена слова.
 *
 * Всё это — из документа, подписанного владельцем и проверенного ключом
 * из ссылки. Обещанию самой ссылки верить нельзя (§10.2), а этому можно.
 */
class ChannelPreview(
    val chatId: ByteArray,
    val title: String,
    val open: Boolean,
    val version: ULong,
    val powBits: UInt,
)

/** Что окно знает о каналах и что с ними делает. */
interface ChannelsApi {
    /** Заявки на впуск, по каналам; §10.4 обещает, что они ждут. */
    val channelRequests: StateFlow<Map<String, List<FfiChannelRequest>>>

    /** Кого уже впустили, по каналам: видно только владельцу. */
    val channelAdmits: StateFlow<Map<String, List<FfiChannelAdmit>>>

    /** Кому и что выдано, по каналам (§6.2, §6.3). */
    val channelGrants: StateFlow<Map<String, List<FfiChannelGrant>>>

    /**
     * Выдаёт или снимает право (§6.2, §6.3).
     *
     * Снятие — это выдача с пустым набором: список в новой версии
     * представления и есть всё, что действует.
     *
     * **Срок обязателен**: не продлил — истекло само. Право без срока
     * означало бы отзыв, а отзыв в рое не работает.
     */
    fun setChannelRight(chatId: ByteArray, who: ByteArray, rights: FfiChannelRights, untilMs: ULong)

    /** Поворот ключа чтения (§6.4): кнопка — по `may_rotate`. */
    fun rotateChannelKey(chatId: ByteArray)

    /** Цена слова (§11): фильтр первого уровня, а не защита. */
    fun setChannelPow(chatId: ByteArray, bits: UInt)

    /** Текст §12 — чем платит сужение круга отдачи. */
    fun sharingLevelNotice(): String

    /** Перечитать состояние раздачи: его видит каждый читатель. */
    fun refreshChannelSeeding(chatId: ByteArray)

    /** Как мы раздаём этот канал (§7.5.1); по каналам. */
    val seedingMode: StateFlow<Map<String, FfiSeeding>>

    /** Кто ещё вызвался раздавать (§7.5): каталог сидов. */
    val channelSeeds: StateFlow<Map<String, List<FfiChannelSeed>>>

    /** Кому отдаём блоки этого канала (§12); `null` — умолчание аккаунта. */
    val sharingLevel: StateFlow<Map<String, FfiSharingLevel>>

    /**
     * Тянем ли сейчас более раннюю историю канала (§7.4).
     *
     * `true` — попросили и ждём; `false` после `ChannelHistoryEnd` —
     * у тех, кого спросили, глубже ничего нет. Ответ окончателен ровно
     * настолько, насколько полон каталог: появится сид с более длинным
     * архивом — и попросить стоит снова.
     */
    val historyPulling: StateFlow<Map<String, Boolean>>

    /** Признак «глубже ничего нет» по последней попытке. */
    val historyEnded: StateFlow<Map<String, Boolean>>

    /**
     * Просит более раннюю историю канала (§7.4).
     *
     * Вступление историю не тянет, и это решение: иначе подписавшийся
     * оплачивал бы год чужой переписки, которого не просил. Лента
     * начинается с первого живого слова, а дальше — по просьбе.
     */
    fun pullOlderHistory(chatId: ByteArray)

    /**
     * Меняет участие в раздаче (§7.5.1).
     *
     * `OFF` — выключатель раздачи, а не отписка: канал продолжает
     * читаться. Перед `ANNOUNCED` клиент обязан показать `seeding_notice`.
     */
    fun setSeeding(chatId: ByteArray, mode: FfiSeeding)

    /**
     * Кому отдавать блоки этого канала (§12).
     *
     * Перед сужением обязателен `sharing_level_notice`: платит за него
     * не только тот, кто настраивал.
     */
    fun setSharingLevel(chatId: ByteArray, level: FfiSharingLevel)

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
     * Чем объяснить тишину в канале — **одним** признаком (§15).
     *
     * Выбор главного из шести фактов делает ядро (`ChannelFacts::signal`):
     * повтори мы его здесь, правило зажило бы в двух местах и разошлось
     * бы на первой правке.
     */
    fun channelSignalText(signal: org.ratatosk.core.FfiChannelSignal): String

    /** Слова к метке «до владельца канала не доехало» (§15). */
    fun messageNotInTheChannelText(): String

    /** Что показывать, пока канал не открылся (§10.5). */
    fun channelWaitingText(waiting: org.ratatosk.core.FfiWaiting): String

    /** Предлагать ли кнопку «сообщить, когда откроется». */
    fun channelWaitingOffersNotification(waiting: org.ratatosk.core.FfiWaiting): Boolean

    /** Каналы, о которых просили сообщить, когда откроются (§10.5). */
    val channelsToAnnounce: StateFlow<Set<String>>

    /**
     * Просит сообщить, когда канал откроется.
     *
     * Просьба живёт на диске: ожидание тянется часами, и человек за это
     * время закроет приложение. Сказать ему потом — дело службы, она
     * переживает закрытое окно.
     */
    fun announceChannelWhenOpen(chatId: ByteArray, announce: Boolean)

    /**
     * Предпросмотр канала по ссылке (§10.3, шаг 5).
     *
     * Спрашивает документ у владельца, проверяет подпись ключом
     * **из ссылки** и отдаёт название, породу и цену слова событием.
     * В базе не заводится ничего: согласие — это подписка той же
     * ссылкой.
     *
     * Перед вызовом обязателен текст §15: владелец узнает, что кто-то
     * интересуется каналом, даже если человек потом откажется. Отменить
     * это задним числом нечем.
     */
    fun previewChannel(uri: String)

    /**
     * Что рассказал предпросмотр; `null` — ещё не спрашивали или ответа
     * нет. Ответа может и не быть, и это не отказ (§10.5).
     */
    val channelPreview: StateFlow<ChannelPreview?>

    /** Забыть предпросмотр: окно закрыли. */
    fun clearChannelPreview()

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

    private val _channelGrants = MutableStateFlow<Map<String, List<FfiChannelGrant>>>(emptyMap())
    override val channelGrants = _channelGrants.asStateFlow()

    override fun refreshChannelPeople(chatId: ByteArray) {
        refreshRequests(chatId)
        refreshAdmits(chatId)
        refreshGrants(chatId)
    }

    override fun setChannelRight(
        chatId: ByteArray,
        who: ByteArray,
        rights: FfiChannelRights,
        untilMs: ULong,
    ) {
        session.io("Failed to set channel right", R.string.channel_right_failed) {
            session.core.client().setChannelRight(chatId, who, rights, untilMs)
            refreshChannelPeople(chatId)
        }
    }

    override fun rotateChannelKey(chatId: ByteArray) {
        session.io("Failed to rotate channel key", R.string.channel_rotate_failed) {
            session.core.client().rotateChannelKey(chatId)
        }
    }

    override fun setChannelPow(chatId: ByteArray, bits: UInt) {
        session.io("Failed to set channel pow", R.string.channel_pow_failed) {
            session.core.client().setChannelPow(chatId, bits)
        }
    }

    private val _seedingMode = MutableStateFlow<Map<String, FfiSeeding>>(emptyMap())
    override val seedingMode = _seedingMode.asStateFlow()

    private val _channelSeeds = MutableStateFlow<Map<String, List<FfiChannelSeed>>>(emptyMap())
    override val channelSeeds = _channelSeeds.asStateFlow()

    private val _sharingLevel = MutableStateFlow<Map<String, FfiSharingLevel>>(emptyMap())
    override val sharingLevel = _sharingLevel.asStateFlow()

    private val _historyPulling = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    override val historyPulling = _historyPulling.asStateFlow()

    private val _historyEnded = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    override val historyEnded = _historyEnded.asStateFlow()

    override fun pullOlderHistory(chatId: ByteArray) {
        val hex = chatId.toHexString()
        _historyPulling.update { it + (hex to true) }
        _historyEnded.update { it - hex }
        session.io("Failed to pull older history", R.string.channel_history_failed) {
            session.core.client().pullOlderHistory(chatId)
        }
    }

    override fun setSeeding(chatId: ByteArray, mode: FfiSeeding) {
        session.io("Failed to change seeding", R.string.channel_seeding_failed) {
            session.core.client().setSeeding(chatId, mode)
            refreshSeeding(chatId)
        }
    }

    override fun setSharingLevel(chatId: ByteArray, level: FfiSharingLevel) {
        session.io("Failed to set sharing level", R.string.channel_sharing_failed) {
            session.core.client().setSharingLevel(chatId, level)
            refreshSeeding(chatId)
        }
    }

    override fun sharingLevelNotice(): String = session.core.sharingLevelNotice()

    override fun refreshChannelSeeding(chatId: ByteArray) = refreshSeeding(chatId)

    /** Раздача — дело каждого читателя, не только владельца. */
    private fun refreshSeeding(chatId: ByteArray) {
        if (session.isCompanion) return
        val hex = chatId.toHexString()
        session.scope.launch(Dispatchers.IO) {
            try {
                val client = session.core.client()
                val mode = client.seedingMode(chatId)
                val seeds = client.channelSeeds(chatId)
                val level = client.sharingLevel(chatId)
                withContext(Dispatchers.Main) {
                    _seedingMode.update { it + (hex to mode) }
                    _channelSeeds.update { it + (hex to seeds) }
                    _sharingLevel.update { it + (hex to level) }
                }
            } catch (e: Exception) {
                android.util.Log.w("RatatoskVM", "Failed to read seeding state", e)
            }
        }
    }

    private fun refreshGrants(chatId: ByteArray) {
        if (session.isCompanion) return
        val hex = chatId.toHexString()
        session.scope.launch(Dispatchers.IO) {
            try {
                val list = session.core.client().channelGrants(chatId)
                withContext(Dispatchers.Main) {
                    _channelGrants.update { it + (hex to list) }
                }
            } catch (e: Exception) {
                android.util.Log.w("RatatoskVM", "Failed to read channel grants", e)
            }
        }
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

    override fun channelSignalText(signal: org.ratatosk.core.FfiChannelSignal): String =
        session.core.channelSignalText(signal)

    override fun messageNotInTheChannelText(): String = session.core.messageNotInTheChannelText()

    override fun channelWaitingText(waiting: org.ratatosk.core.FfiWaiting): String =
        session.core.channelWaitingText(waiting)

    override fun channelWaitingOffersNotification(waiting: org.ratatosk.core.FfiWaiting): Boolean =
        session.core.channelWaitingOffersNotification(waiting)

    override val channelsToAnnounce: StateFlow<Set<String>> =
        session.settings.channelsToAnnounce.stateIn(
            session.scope,
            kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
            emptySet(),
        )

    override fun announceChannelWhenOpen(chatId: ByteArray, announce: Boolean) {
        session.scope.launch {
            session.settings.announceChannelWhenOpen(chatId.toHexString(), announce)
        }
    }

    private val _channelPreview = MutableStateFlow<ChannelPreview?>(null)
    override val channelPreview = _channelPreview.asStateFlow()

    /**
     * Ждём ли ответа прямо сейчас.
     *
     * Ответа может и не быть вовремя: человек закрыл окно, а владелец
     * ответил через минуту. Без этого признака ответ ложился в состояние
     * и всплывал в следующем окне — с чужой ссылкой и чужим названием.
     */
    @Volatile
    private var awaitingPreview = false

    override fun previewChannel(uri: String) {
        _channelPreview.value = null
        awaitingPreview = true
        session.io("Failed to preview a channel", R.string.channel_preview_failed) {
            session.core.client().previewChannel(uri)
        }
    }

    override fun clearChannelPreview() {
        awaitingPreview = false
        _channelPreview.value = null
    }

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
                refreshChannelPeople(event.chatId)
                // Новое поколение: старые записи читаются, новые — только
                // с новым ключом. Перечитываем чат, чтобы это стало видно.
                refreshGroups()
                loadMessages(event.chatId)
            }
            is org.ratatosk.core.FfiEvent.ChannelPreviewed -> {
                // Ответ на предпросмотр: теперь породу можно не угадывать.
                // Но только если его ещё ждут: опоздавший ответ всплыл бы
                // в следующем окне, рассказывая про чужую ссылку.
                if (!awaitingPreview) return
                awaitingPreview = false
                _channelPreview.value = ChannelPreview(
                    chatId = event.chatId,
                    title = event.title,
                    open = event.open,
                    version = event.version,
                    powBits = event.powBits,
                )
            }
            is org.ratatosk.core.FfiEvent.ChannelRequested -> refreshRequests(event.chatId)
            is org.ratatosk.core.FfiEvent.ChannelHistoryEnd -> {
                // Полоску пора убрать: у тех, кого спросили, глубже ничего нет.
                val hex = event.chatId.toHexString()
                _historyPulling.update { it - hex }
                _historyEnded.update { it + (hex to true) }
            }
            is org.ratatosk.core.FfiEvent.SeedingChanged -> refreshSeeding(event.chatId)
            is org.ratatosk.core.FfiEvent.SeedAnnounced -> refreshSeeding(event.chatId)
            else -> {}
        }
    }

    /** Сессия закрыта: чужих заявок мы не храним. */
    fun reset() {
        _channelRequests.value = emptyMap()
        _channelAdmits.value = emptyMap()
        _channelGrants.value = emptyMap()
        _seedingMode.value = emptyMap()
        _channelSeeds.value = emptyMap()
        _sharingLevel.value = emptyMap()
        _historyPulling.value = emptyMap()
        _historyEnded.value = emptyMap()
        _channelPreview.value = null
        awaitingPreview = false
    }
}
