package chat.ratatosk.android.ui.model

import chat.ratatosk.android.R
import chat.ratatosk.android.util.VisibleChat
import chat.ratatosk.android.util.hexToByteArray
import chat.ratatosk.android.util.toHexString
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ratatosk.core.FfiDeliveryStatus
import org.ratatosk.core.FfiCompanionOutgoing
import org.ratatosk.core.FfiOutgoingFile
import org.ratatosk.core.FfiMessage
import java.util.concurrent.ConcurrentHashMap

/**
 * Переписка: история чатов, отправка, правка, поиск и тексты ядра,
 * которые показываются до действия, а не после.
 */
interface ChatsApi {
    val messages: StateFlow<Map<String, List<FfiMessage>>>
    val messageStatuses: StateFlow<Map<String, FfiDeliveryStatus>>
    val unreadCounts: StateFlow<Map<String, Int>>
    /** Сообщения, на которые отвечают: приезжают позже и дорисовывают цитату. */
    val repliedMessages: StateFlow<Map<String, FfiMessage?>>
    val searchResults: StateFlow<List<FfiMessage>>
    val isSearching: StateFlow<Boolean>
    val activeChatIdFlow: StateFlow<ByteArray?>
    /** Просьба открыть чат на определённом сообщении (уведомление о реакции). */
    val pendingScrollToMsgId: StateFlow<String?>

    fun loadMessages(chatId: ByteArray, limit: Int? = null)
    fun searchMessages(chatId: ByteArray?, query: String)
    fun clearSearch()
    fun sendMessage(chatId: ByteArray, text: String)
    fun sendText(chatId: ByteArray, text: String)
    fun sendFiles(chatId: ByteArray, files: List<java.io.File>, text: String)
    fun resendMessage(chatId: ByteArray, text: String)
    fun clearChat(chatId: ByteArray)
    fun deleteMessages(chatId: ByteArray, msgIds: List<ByteArray>)
    /** Удаляет у себя и **просит** собеседника сделать то же. */
    fun retractMessages(chatId: ByteArray, msgIds: List<ByteArray>)
    fun editMessage(chatId: ByteArray, msgId: ByteArray, text: String)
    fun reply(chatId: ByteArray, replyTo: ByteArray, text: String)
    fun forwardMessages(chatId: ByteArray, msgIds: List<ByteArray>)
    fun markRead(chatId: ByteArray, upTo: ByteArray)
    fun setReaction(chatId: ByteArray, msgId: ByteArray, emoji: String?)
    fun getMessage(msgId: ByteArray): FfiMessage?
    fun setActiveChat(chatId: ByteArray?)
    fun getDeletionNotice(): String
    fun getEditNotice(): String
    fun getForwardNotice(): String
    fun getRetractionNotice(): String
    fun getWaitingNotice(): String
    fun requestScrollToMessage(msgIdHex: String?)
    fun consumeScrollRequest()
}

class ChatsModel(
    private val session: SessionContext,
    /** Превью исходящей картинки делает тот, кто умеет читать файлы. */
    private val previewFor: (java.io.File) -> ByteArray?,
    /** Открытый чат закрывает карточку человека: показываем что-то одно. */
    private val onChatOpened: () -> Unit,
) : ChatsApi {
    private val _messages = MutableStateFlow<Map<String, List<FfiMessage>>>(emptyMap())
    override val messages = _messages.asStateFlow()
    private val _messageStatuses = MutableStateFlow<Map<String, FfiDeliveryStatus>>(emptyMap())
    override val messageStatuses = _messageStatuses.asStateFlow()
    private val _unreadCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    override val unreadCounts = _unreadCounts.asStateFlow()
    private val _repliedMessages = MutableStateFlow<Map<String, FfiMessage?>>(emptyMap())
    override val repliedMessages = _repliedMessages.asStateFlow()
    private val _searchResults = MutableStateFlow<List<FfiMessage>>(emptyList())
    override val searchResults = _searchResults.asStateFlow()
    private val _isSearching = MutableStateFlow(false)
    override val isSearching = _isSearching.asStateFlow()
    private val _activeChatIdFlow = MutableStateFlow<ByteArray?>(null)
    override val activeChatIdFlow = _activeChatIdFlow.asStateFlow()
    private val _pendingScrollToMsgId = MutableStateFlow<String?>(null)
    override val pendingScrollToMsgId = _pendingScrollToMsgId.asStateFlow()
    private val messageLoads = ConcurrentHashMap<String, Job>()
    private var searchJob: Job? = null

    /** Тот же чат строкой: им пользуются события, где имени чата нет. */
    var activeChatIdHex: String? = null
        private set

    override fun loadMessages(chatId: ByteArray, limit: Int?) {
        if (session.isCompanion) {
            session.scope.launch(Dispatchers.IO) {
                try {
                    session.core.companion().history(chatId, limit?.toUInt() ?: 100u, null)
                } catch (e: Exception) {
                    android.util.Log.e("RatatoskVM", "Failed to load companion messages", e)
                }
            }
            return
        }
        val chatIdHex = chatId.toHexString()
        val currentSize = _messages.value[chatIdHex]?.size ?: 0
        
        val targetLimit = when {
            limit != null -> limit
            currentSize > 0 -> maxOf(currentSize, 100)
            else -> 100
        }
        
        android.util.Log.d("RatatoskVM", "loadMessages for $chatIdHex, current: $currentSize, target: $targetLimit")
        
        // Одна загрузка на чат. Прежде каждый вызов запускал свою корутину,
        // и две наложившиеся приходили в произвольном порядке: медленная
        // выборка на 100 сообщений перезаписывала уже приехавшие 500,
        // и список на экране схлопывался. Зовут её часто — из событий,
        // из открытия чата, из «загрузить ещё».
        val load = session.scope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                if (!session.core.isInitialized) {
                    android.util.Log.w("RatatoskVM", "loadMessages called but core not initialized")
                    return@launch
                }
                val msgs = session.core.client().messages(chatId, maxOf(targetLimit, 1).toUInt())
                android.util.Log.d("RatatoskVM", "Fetched ${msgs.size} messages for $chatIdHex")

                _messages.update { currentMap ->
                    currentMap + (chatIdHex to msgs)
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    android.util.Log.e("RatatoskVM", "Failed to load messages for $chatIdHex", e)
                }
            } finally {
                messageLoads.remove(chatIdHex, coroutineContext[Job])
            }
        }
        // Регистрируем до запуска и снимаем прежнюю — по тем же причинам,
        // что и у файловых задач.
        messageLoads.put(chatIdHex, load)?.cancel()
        load.start()
    }

    override fun searchMessages(chatId: ByteArray?, query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _isSearching.value = false
            return
        }

        _isSearching.value = true
        searchJob = session.scope.launch(Dispatchers.IO) {
            try {
                if (session.isCompanion) {
                    // Search not supported in companion mode yet
                    _searchResults.value = emptyList()
                } else {
                    val results = session.core.client().search(chatId, query, 50u)
                    _searchResults.value = results
                }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Search failed", e)
            } finally {
                _isSearching.value = false
            }
        }
    }

    override fun clearSearch() {
        searchJob?.cancel()
        _searchResults.value = emptyList()
        _isSearching.value = false
    }

    override fun sendMessage(chatId: ByteArray, text: String) {
        session.scope.launch(Dispatchers.IO) {
            try {
                if (session.isCompanion) {
                    if (!session.isCompanionLinked.value) {
                        withContext(Dispatchers.Main) {
                            session._error.value = session.string(R.string.connecting_to_phone_cached)
                        }
                        return@launch
                    }
                    session.core.companion().sendText(chatId, text)
                    loadMessages(chatId)
                } else {
                    session.core.client().sendText(chatId, text)
                    loadMessages(chatId)
                    delay(150)
                    loadMessages(chatId)
                }
            } catch (e: Exception) {
                android.util.Log.w("RatatoskVM", "Failed to send text", e)
                withContext(Dispatchers.Main) {
                    session._error.value = e.message?.takeIf { it.isNotBlank() }
                        ?: session.string(R.string.error_send_failed)
                }
            }
        }
    }

    override fun sendText(chatId: ByteArray, text: String) {
        sendMessage(chatId, text)
    }

    override fun sendFiles(chatId: ByteArray, files: List<java.io.File>, text: String) {
        session.scope.launch(Dispatchers.IO) {
            try {
                if (session.isCompanion) {
                    if (!session.isCompanionLinked.value) {
                        withContext(Dispatchers.Main) {
                            session._error.value = session.string(R.string.connecting_to_phone_cached)
                        }
                        return@launch
                    }
                    val companionFiles = files.map { file ->
                        val preview = if (file.extension.lowercase() in listOf("jpg", "jpeg", "png", "webp")) {
                            previewFor(file)
                        } else null
                        FfiCompanionOutgoing(file.absolutePath, preview)
                    }
                    session.core.companion().sendFiles(chatId, companionFiles, text)
                    loadMessages(chatId)
                } else {
                    val outgoingFiles = files.map { file ->
                        val preview = if (file.extension.lowercase() in listOf("jpg", "jpeg", "png", "webp")) {
                            previewFor(file)
                        } else null
                        FfiOutgoingFile(file.absolutePath, preview)
                    }
                    session.core.client().sendFiles(chatId, outgoingFiles, text)
                    loadMessages(chatId)
                    delay(150)
                    loadMessages(chatId)
                }
            } catch (e: Exception) {
                android.util.Log.w("RatatoskVM", "Failed to send files", e)
                withContext(Dispatchers.Main) {
                    session._error.value = e.message?.takeIf { it.isNotBlank() }
                        ?: session.string(R.string.error_send_files_failed)
                }
            }
        }
    }

    override fun resendMessage(chatId: ByteArray, body: String) {
        sendMessage(chatId, body)
    }

    override fun clearChat(chatId: ByteArray) {
        session.scope.launch(Dispatchers.IO) {
            try {
                if (session.isCompanion) {
                    session.core.companion().clearChat(chatId)
                } else {
                    session.core.client().clearChat(chatId)
                }
                _messages.update { it + (chatId.toHexString() to emptyList()) }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to clear chat", e)
            }
        }
    }

    override fun deleteMessages(chatId: ByteArray, msgIds: List<ByteArray>) {
        session.scope.launch(Dispatchers.IO) {
            try {
                if (session.isCompanion) {
                    session.core.companion().deleteMessages(chatId, msgIds)
                } else {
                    session.core.client().deleteMessages(chatId, msgIds)
                    loadMessages(chatId)
                }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to delete messages", e)
            }
        }
    }

    override fun retractMessages(chatId: ByteArray, msgIds: List<ByteArray>) {
        session.scope.launch(Dispatchers.IO) {
            try {
                if (session.isCompanion) {
                    session.core.companion().retractMessages(chatId, msgIds)
                } else {
                    session.core.client().retractMessages(chatId, msgIds)
                    loadMessages(chatId)
                }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to retract messages", e)
            }
        }
    }

    override fun editMessage(chatId: ByteArray, msgId: ByteArray, text: String) {
        session.scope.launch(Dispatchers.IO) {
            try {
                if (session.isCompanion) {
                    session.core.companion().editMessage(chatId, msgId, text)
                } else {
                    session.core.client().editMessage(chatId, msgId, text)
                    loadMessages(chatId)
                }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to edit message", e)
            }
        }
    }

    override fun reply(chatId: ByteArray, replyTo: ByteArray, text: String) {
        session.scope.launch(Dispatchers.IO) {
            try {
                if (session.isCompanion) {
                    if (!session.isCompanionLinked.value) {
                        withContext(Dispatchers.Main) {
                            session._error.value = session.string(R.string.connecting_to_phone_cached)
                        }
                        return@launch
                    }
                    session.core.companion().sendReply(chatId, replyTo, text)
                    loadMessages(chatId)
                } else {
                    session.core.client().reply(chatId, replyTo, text)
                    loadMessages(chatId)
                }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to reply", e)
            }
        }
    }

    override fun forwardMessages(chatId: ByteArray, msgIds: List<ByteArray>) {
        session.scope.launch(Dispatchers.IO) {
            try {
                if (session.isCompanion) {
                    if (!session.isCompanionLinked.value) {
                        withContext(Dispatchers.Main) {
                            session._error.value = session.string(R.string.connecting_to_phone_cached)
                        }
                        return@launch
                    }
                    session.core.companion().forwardMessages(chatId, msgIds)
                    loadMessages(chatId)
                } else {
                    session.core.client().forwardMessages(chatId, msgIds)
                    loadMessages(chatId)
                }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to forward", e)
                withContext(Dispatchers.Main) {
                    session._error.value = e.message?.takeIf { it.isNotBlank() }
                        ?: session.string(R.string.error_forward_failed)
                }
            }
        }
    }

    override fun markRead(chatId: ByteArray, upTo: ByteArray) {
        session.scope.launch(Dispatchers.IO) {
            try {
                if (session.isCompanion) {
                    session.core.companion().markRead(chatId, upTo)
                } else {
                    session.core.client().markRead(chatId, upTo)
                }
                _unreadCounts.update { it + (chatId.toHexString() to 0) }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to mark read", e)
            }
        }
    }

    override fun setReaction(chatId: ByteArray, msgId: ByteArray, emoji: String?) {
        session.scope.launch(Dispatchers.IO) {
            try {
                if (session.isCompanion) {
                    session.core.companion().setReaction(chatId, msgId, emoji ?: "")
                } else {
                    session.core.client().setReaction(chatId, msgId, emoji)
                    loadMessages(chatId)
                }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to set reaction", e)
            }
        }
    }

    override fun getMessage(msgId: ByteArray): FfiMessage? {
        val hex = msgId.toHexString()
        _repliedMessages.value[hex]?.let { return it }
        
        if (session.isCompanion) return null

        session.scope.launch(Dispatchers.IO) {
            try {
                val msg = session.core.client().message(msgId)
                _repliedMessages.update { it + (hex to msg) }
            } catch (e: Exception) { /* ignore */ }
        }
        return null
    }

    override fun setActiveChat(chatId: ByteArray?) {
        _activeChatIdFlow.value = chatId
        // Сервису уведомлений это нужно, а общей модели у них с экраном нет.
        chat.ratatosk.android.util.VisibleChat.setOpenChat(chatId?.toHexString())
        if (chatId != null) {
            onChatOpened()
            activeChatIdHex = chatId.toHexString()
            _unreadCounts.update { it + (chatId.toHexString() to 0) }
            loadMessages(chatId)
        } else {
            activeChatIdHex = null
        }
    }

    override fun getDeletionNotice(): String {
        return try {
            org.ratatosk.core.deletionNotice()
        } catch (e: Exception) {
            ""
        }
    }

    override fun getEditNotice(): String {
        return try {
            org.ratatosk.core.editNotice()
        } catch (e: Exception) {
            ""
        }
    }

    override fun getForwardNotice(): String {
        return try {
            org.ratatosk.core.forwardNotice()
        } catch (e: Exception) {
            ""
        }
    }

    override fun getRetractionNotice(): String {
        return try {
            org.ratatosk.core.retractionNotice()
        } catch (e: Exception) {
            "This message will be deleted for everyone."
        }
    }

    override fun getWaitingNotice(): String {
        return try {
            org.ratatosk.core.waitingNotice()
        } catch (e: Exception) {
            "Waiting for peer to come online..."
        }
    }

    override fun requestScrollToMessage(msgIdHex: String?) {
        _pendingScrollToMsgId.value = msgIdHex
    }

    override fun consumeScrollRequest() {
        _pendingScrollToMsgId.value = null
    }

    /** Сколько непрочитанного всего — для значка на вкладке. */
    val totalUnreadCount: StateFlow<Int> = _unreadCounts
        .map { it.values.sum() }
        .stateIn(session.scope, SharingStarted.WhileSubscribed(5000), 0)

    /** Чат, в котором лежит это сообщение: статус приходит без имени чата. */
    fun chatIdHexOf(msgId: ByteArray): String? =
        _messages.value.entries.find { entry ->
            entry.value.any { it.msgId.contentEquals(msgId) }
        }?.key

    fun clearActiveChat() {
        _activeChatIdFlow.value = null
        activeChatIdHex = null
        VisibleChat.setOpenChat(null)
    }

    // --- Входы для событий ядра -------------------------------------------

    fun setMessages(chatIdHex: String, list: List<FfiMessage>) {
        _messages.update { it + (chatIdHex to list) }
    }

    fun currentMessages(chatIdHex: String): List<FfiMessage>? = _messages.value[chatIdHex]

    fun onStatusChanged(msgIdHex: String, status: FfiDeliveryStatus) {
        _messageStatuses.update { it + (msgIdHex to status) }
    }

    fun setUnread(chatIdHex: String, count: Int) {
        _unreadCounts.update { it + (chatIdHex to count) }
    }

    fun bumpUnread(chatIdHex: String) {
        _unreadCounts.update { it + (chatIdHex to (it[chatIdHex] ?: 0) + 1) }
    }

    fun putRepliedMessage(msgIdHex: String, message: FfiMessage?) {
        _repliedMessages.update { it + (msgIdHex to message) }
    }

    /** Сессия закрыта: переписка прошлого аккаунта — не наша. */
    fun reset() {
        searchJob?.cancel()
        searchJob = null
        messageLoads.values.forEach { it.cancel() }
        messageLoads.clear()
        _messages.value = emptyMap()
        _messageStatuses.value = emptyMap()
        _unreadCounts.value = emptyMap()
        _repliedMessages.value = emptyMap()
        _searchResults.value = emptyList()
        _isSearching.value = false
        _activeChatIdFlow.value = null
        _pendingScrollToMsgId.value = null
        VisibleChat.clear()
    }
}
