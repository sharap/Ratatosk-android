package chat.ratatosk.android.ui.model

import chat.ratatosk.android.R
import chat.ratatosk.android.core.RatatoskCore
import chat.ratatosk.android.data.CompanionLink
import chat.ratatosk.android.util.hexToByteArray
import chat.ratatosk.android.util.toHexString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ratatosk.core.FfiAnomalies
import org.ratatosk.core.FfiCompanionAttachment
import org.ratatosk.core.FfiCompanionChat
import org.ratatosk.core.FfiCompanionEvent
import org.ratatosk.core.FfiCompanionMessage
import org.ratatosk.core.FfiCompanionOutgoing
import org.ratatosk.core.FfiContact
import org.ratatosk.core.FfiFile
import org.ratatosk.core.FfiGroup
import org.ratatosk.core.FfiGroupMember
import org.ratatosk.core.FfiMessage
import org.ratatosk.core.FfiReachability
import org.ratatosk.core.FfiReaction
import org.ratatosk.core.FfiSharedContact

/** Что окно умеет со вторым экраном. */
interface CompanionApi {
    /** Показанное приехало с телефона, а не поднято из кэша. */
    val isCompanionFresh: StateFlow<Boolean>
    /** Хранится ли снимок переписки этого второго экрана на диске. */
    val companionCacheEnabled: StateFlow<Boolean>
    /** Порт и ключ этого устройства для ручного ввода на телефоне; `null` — связи нет. */
    fun companionEndpoint(): Pair<Int, String>?
    fun setCompanionCache(enabled: Boolean)
    fun initializeCompanion(
        inviteUri: String,
        port: Int,
        peerAddr: String?,
        cachePath: String?,
        label: String,
        torDir: String? = null,
    )
    fun unlockCompanion(link: CompanionLink)
    fun removeCompanionLink(inviteUri: String)
}

/**
 * Второй экран телефона: привязка, копия переписки и перевод событий
 * телефона в состояние моделей.
 *
 * Здесь же живут отображения типов компаньона в общие (`mapCompanion*`):
 * у второго экрана нет ни ключей, ни отпечатков, и то, чего он не знает,
 * не выдумывается, а остаётся пустым.
 */
class CompanionModel(
    private val session: SessionContext,
    private val chats: ChatsModel,
    private val contacts: ContactsModel,
    private val groups: GroupsModel,
    private val files: FilesModel,
    /** Компаньон открыт: сессию поднимает тот, кто ей владеет. */
    private val onCompanionOpened: () -> Unit,
    /** Поток событий полного клиента — он пока поднимается снаружи. */
    private val startClientEvents: () -> Unit,
) : CompanionApi {
    /** Показанное приехало с телефона, а не поднято из кэша. */
    private val _isCompanionFresh = MutableStateFlow(false)
    override val isCompanionFresh: StateFlow<Boolean> = _isCompanionFresh.asStateFlow()

    /** Хранится ли снимок переписки этого второго экрана на диске. */
    private val _companionCacheEnabled = MutableStateFlow(false)
    override val companionCacheEnabled: StateFlow<Boolean> = _companionCacheEnabled.asStateFlow()

    var currentCompanionLabel: String? = null
        private set

    private var companionEventsJob: Job? = null

    /** Поднимает второй экран: события телефона, отпечаток, первый снимок. */
    fun setupCompanionEngine() {
        android.util.Log.d("RatatoskVM", "Setting up companion engine...")
        
        // Start collecting events BEFORE making calls
        if (companionEventsJob == null) {
            companionEventsJob = session.scope.launch(Dispatchers.IO) {
                RatatoskCore.companionEvents.collect { event ->
                    handleCompanionEvent(event)
                }
            }
        }

        startClientEvents()

        session.scope.launch(Dispatchers.IO) {
            try {
                val companion = RatatoskCore.getCompanion()
                val fingerprint = companion.deviceId().toHexString()
                val phoneName = try { companion.phoneName() } catch (e: Exception) { "Companion" }
                
                withContext(Dispatchers.Main) {
                    contacts.setFingerprint(fingerprint)
                    if (currentCompanionLabel == null) currentCompanionLabel = phoneName
                    
                    session._isCompanionLinked.value = false
                }

                android.util.Log.d("RatatoskVM", "Initial companion chats() call for cache")
                companion.chats()
                // Своё лицо здесь не спрашиваем: телефона на линии ещё нет,
                // и запрос уходит в пустоту. Его место — в ветке `Linked`
                // («Свою — раз за подключение», FFI о `avatar`).
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to setup companion engine", e)
            }
        }
    }

    private fun handleCompanionEvent(event: FfiCompanionEvent) {
        when (event) {
            is FfiCompanionEvent.Chats -> {
                android.util.Log.d("RatatoskVM", "Companion received ${event.chats.size} chats, fresh=${event.fresh}")
                _isCompanionFresh.value = event.fresh
                val (groupChats, contactChats) = event.chats.partition { it.isGroup }

                val mappedContacts = contactChats.map { mapCompanionChat(it) }
                contacts.setContacts(mappedContacts)

                val existingGroupsMap = groups.currentGroups().associateBy { it.chatId.toHexString() }
                val mappedGroups = groupChats.map { chat ->
                    mapCompanionGroup(chat, existingGroupsMap[chat.chatId.toHexString()])
                }
                groups.setGroups(mappedGroups)

                event.chats.forEach { chat ->
                    val hex = chat.chatId.toHexString()
                    if (chat.isGroup && session._isCompanionLinked.value) {
                        session.scope.launch(Dispatchers.IO) {
                            try {
                                RatatoskCore.getCompanion().members(chat.chatId)
                            } catch (e: Exception) { /* ignore */ }
                        }
                    }
                    if (chats.currentMessages(hex).isNullOrEmpty() || hex == chats.activeChatIdHex) {
                        chats.loadMessages(chat.chatId)
                    }
                }
            }
            is FfiCompanionEvent.Members -> {
                val hex = event.chatId.toHexString()
                val groupMembers = event.members.map { member ->
                    FfiGroupMember(
                        ik = member.chatId,
                        name = member.name,
                        mine = member.mine
                    )
                }
                val isOwnerMine = event.members.any { it.mine && it.owner }
                groups.updateGroups { currentGroups ->
                    currentGroups.map { grp ->
                        if (grp.chatId.toHexString() == hex) {
                            grp.copy(
                                members = groupMembers,
                                mine = isOwnerMine
                            )
                        } else grp
                    }
                }

                val memberContacts = event.members.filter { member ->
                    !member.mine && contacts.currentContacts().none { it.chatId.contentEquals(member.chatId) }
                }.map { member ->
                    FfiContact(
                        peerIk = member.chatId,
                        chatId = member.chatId,
                        fingerprint = "",
                        displayName = member.name,
                        localName = null,
                        verified = false,
                        // Присутствие у компаньона не спрашивают: эфиры
                        // слушает телефон, а не пара. Оба признака ложны,
                        // и это правда, а не заглушка.
                        seenOnLan = false,
                        seenOnBt = false,
                        hasAvatar = false,
                        onion = null,
                        chatmail = null,
                        ygg = null,
                        nostrRelays = emptyList(),
                        cardVersion = 0UL,
                        addedMs = 0UL,
                        reachability = FfiReachability(emptyList(), null, null),
                        directChannel = null,
                        anomalies = FfiAnomalies(0UL, 0UL, 0UL, 0UL, 0UL)
                    )
                }
                if (memberContacts.isNotEmpty()) {
                    contacts.updateContacts { currentContacts ->
                        val existingHexes = currentContacts.map { it.chatId.toHexString() }.toSet()
                        currentContacts + memberContacts.filter { !existingHexes.contains(it.chatId.toHexString()) }
                    }
                }
                event.members.forEach { member ->
                    if (!member.mine) {
                        session.scope.launch(Dispatchers.IO) {
                            try {
                                RatatoskCore.getCompanion().avatar(member.chatId)
                            } catch (e: Exception) { /* ignore */ }
                        }
                    }
                }
            }
            is FfiCompanionEvent.GroupCreated -> {
                android.util.Log.i("RatatoskVM", "Companion group created: ${event.chatId.toHexString()}")
                session.scope.launch(Dispatchers.IO) {
                    try {
                        RatatoskCore.getCompanion().chats()
                        RatatoskCore.getCompanion().members(event.chatId)
                    } catch (e: Exception) { /* ignore */ }
                }
            }
            is FfiCompanionEvent.ChatsChanged -> {
                session.scope.launch(Dispatchers.IO) {
                    try {
                        RatatoskCore.getCompanion().chats()
                    } catch (e: Exception) { /* ignore */ }
                }
            }
            is FfiCompanionEvent.AvatarChanged -> {
                val hex = event.chatId?.toHexString() ?: "mine"
                if (event.avatarMs != 0UL) {
                    contacts.setAvatarStamp(hex, event.avatarMs)
                    session.scope.launch(Dispatchers.IO) {
                        try {
                            RatatoskCore.getCompanion().avatar(event.chatId)
                        } catch (e: Exception) { /* ignore */ }
                    }
                } else {
                    contacts.removeAvatarStamp(hex)
                    if (hex == "mine") contacts.onOwnAvatar(null)
                    else contacts.removeAvatar(hex)
                }
            }
            is FfiCompanionEvent.History -> {
                android.util.Log.d("RatatoskVM", "Companion received ${event.page.size} messages for chat ${event.chatId.toHexString()}, fresh=${event.fresh}")
                _isCompanionFresh.value = event.fresh
                val mappedMessages = event.page.map { mapCompanionMessage(it) }
                val chatIdHex = event.chatId.toHexString()
                chats.setMessages(chatIdHex, mappedMessages)
            }
            is FfiCompanionEvent.Arrived -> {
                chats.loadMessages(event.message.chatId)
            }
            is FfiCompanionEvent.Linked -> {
                android.util.Log.i("RatatoskVM", "Companion LINKED")
                session._isCompanionLinked.value = true
                RatatoskCore.getCompanion().chats()
                // Телефон на линии — самое время спросить своё лицо: метки для
                // сравнения у него нет, поэтому спрашиваем раз за подключение.
                try {
                    RatatoskCore.getCompanion().avatar(null)
                } catch (e: Exception) { /* ignore */ }
            }
            is FfiCompanionEvent.Unlinked -> {
                android.util.Log.w("RatatoskVM", "Companion UNLINKED")
                session._isCompanionLinked.value = false
                // Телефон ушёл со связи — `FileSaved` уже не придёт.
                files.failPendingSaves(session.string(R.string.companion_offline))
            }
            is FfiCompanionEvent.Revoked -> {
                // Сопряжение отозвано: объект жив, но на любую команду отвечает
                // отказом. Молчать об этом нельзя — окно иначе вечно «подключается».
                android.util.Log.w("RatatoskVM", "Companion REVOKED")
                session._isCompanionLinked.value = false
                files.failPendingSaves(session.string(R.string.companion_revoked))
            }
            is FfiCompanionEvent.FileSaved -> {
                files.finishSave(event.fileId.toHexString(), event.path, deliver = true)
            }
            // Приём сюда не сорвался, а ждёт: записанное лежит на диске
            // и допишется с того же места (FFI, FetchPaused).
            is FfiCompanionEvent.FetchPaused -> {
                android.util.Log.i("RatatoskVM", "Companion fetch paused")
                files.setFetchPaused(true)
            }
            is FfiCompanionEvent.FetchResumed -> {
                files.setFetchPaused(false)
                val hex = files.currentSaveFileId()
                if (hex != null && event.total > 0uL) {
                    val done = (event.done.toFloat() / event.total.toFloat()).coerceIn(0f, 1f)
                    files.onSaveProgress(hex, done)
                }
            }
            is FfiCompanionEvent.FilePreview -> {
                val hex = event.fileId.toHexString()
                files.onFilePreview(hex, event.bytes)
            }
            is FfiCompanionEvent.Avatar -> {
                val hex = event.chatId?.toHexString() ?: "mine"
                if (event.bytes != null) {
                    if (hex == "mine") {
                        contacts.onOwnAvatar(event.bytes)
                    } else {
                        contacts.putAvatar(hex, event.bytes)
                    }
                } else {
                    if (hex == "mine") contacts.onOwnAvatar(null)
                    else contacts.removeAvatar(hex)
                }
            }
            is FfiCompanionEvent.FileGone -> {
                files.forgetSave(event.fileId.toHexString())
            }
            is FfiCompanionEvent.FileProgress -> {
                val hex = event.fileId.toHexString()
                val progress = if (event.chunkTotal > 0uL) {
                    event.haveChunks.toFloat() / event.chunkTotal.toFloat()
                } else 0f
                files.onFileProgress(hex, progress)
            }
            is FfiCompanionEvent.FilesSent -> {
                android.util.Log.i("RatatoskVM", "Companion files sent: ${event.fileIds.size} files")
                val activeId = chats.activeChatIdHex
                if (activeId != null) {
                    chats.loadMessages(activeId.hexToByteArray())
                }
                RatatoskCore.getCompanion().chats()
            }
            is FfiCompanionEvent.Refused -> {
                android.util.Log.w("RatatoskVM", "Companion refused command: ${event.reason}")
                if (!event.reason.contains("прежние просьбы") && !event.reason.contains("не отвечает на прежние")) {
                    session._error.value = event.reason
                }
                // Отказ мог прийти и на просьбу забрать вложение: тогда ждать
                // `FileSaved` больше нечего.
                files.failPendingSaves(event.reason)
            }
            else -> {}
        }
    }

    private fun mapCompanionGroup(chat: FfiCompanionChat, existingGroup: FfiGroup?): FfiGroup {
        val chatIdHex = chat.chatId.toHexString()
        val oldMs = contacts.avatarStamp(chatIdHex) ?: 0UL
        if (chat.avatarMs != 0UL && chat.avatarMs != oldMs) {
            contacts.setAvatarStamp(chatIdHex, chat.avatarMs)
            session.scope.launch(Dispatchers.IO) {
                try {
                    RatatoskCore.getCompanion().avatar(chat.chatId)
                } catch (e: Exception) { /* ignore */ }
            }
        }

        return FfiGroup(
            chatId = chat.chatId,
            title = chat.title,
            createdMs = existingGroup?.createdMs ?: chat.lastMs,
            members = existingGroup?.members ?: emptyList(),
            mine = existingGroup?.mine ?: false,
            joined = chat.joined,
            avatarMs = chat.avatarMs
        )
    }

    private fun mapCompanionChat(chat: FfiCompanionChat): FfiContact {
        val chatIdHex = chat.chatId.toHexString()
        val oldMs = contacts.avatarStamp(chatIdHex) ?: 0UL
        if (chat.avatarMs != 0UL && chat.avatarMs != oldMs) {
            contacts.setAvatarStamp(chatIdHex, chat.avatarMs)
            session.scope.launch(Dispatchers.IO) {
                try {
                    RatatoskCore.getCompanion().avatar(chat.chatId)
                } catch (e: Exception) { /* ignore */ }
            }
        }

        return FfiContact(
            peerIk = chat.chatId,
            chatId = chat.chatId,
            fingerprint = "",
            displayName = chat.title,
            localName = null,
            verified = chat.verified,
            seenOnLan = false,
            seenOnBt = false,
            hasAvatar = chat.avatarMs != 0UL,
            onion = null,
            chatmail = null,
            ygg = null,
            nostrRelays = emptyList(),
            cardVersion = 0UL,
            addedMs = 0UL,
            reachability = FfiReachability(emptyList(), null, null),
            directChannel = null,
            anomalies = FfiAnomalies(0UL, 0UL, 0UL, 0UL, 0UL)
        )
    }

    private fun mapCompanionMessage(msg: FfiCompanionMessage): FfiMessage {
        val grp = groups.currentGroups().find { it.chatId.contentEquals(msg.chatId) }
        val member = if (grp != null && !msg.author.isNullOrBlank()) {
            grp.members.find { m ->
                m.name == msg.author ||
                contacts.currentContacts().find { c -> c.peerIk.contentEquals(m.ik) }?.let { (it.localName ?: it.displayName) == msg.author } == true
            }
        } else null

        val authorIk = member?.ik

        if (authorIk != null && contacts.contactAvatars.value[authorIk.toHexString()] == null && session.isCompanion) {
            session.scope.launch(Dispatchers.IO) {
                try {
                    RatatoskCore.getCompanion().avatar(authorIk)
                } catch (e: Exception) { /* ignore */ }
            }
        }

        // Ключа в карточке компаньона нет вовсе (§13.4 не пускает `IK` через
        // границу устройства), поэтому на его месте — известный `chatId`,
        // а отпечатка нет: показывать пустую строку вместо него нельзя.
        val mappedSharedContact = msg.shared?.let { shared ->
            FfiSharedContact(
                peerIk = shared.chatId ?: ByteArray(0),
                displayName = shared.name,
                fingerprint = "",
                alreadyKnown = shared.chatId != null,
                // Своя карточка, вернувшаяся из чата, — это «это вы»,
                // а не предложение добавить себя в контакты.
                mine = msg.mine
            )
        }

        return FfiMessage(
            msgId = msg.msgId,
            body = msg.body,
            mine = msg.mine,
            author = msg.author,
            authorIk = authorIk,
            wallMs = msg.wallMs,
            status = msg.status,
            editedAtMs = msg.editedAtMs,
            forwarded = msg.forwarded,
            reactions = msg.reactions.map { FfiReaction(it.emoji, if (it.mine) contacts.fingerprint.value?.hexToByteArray() ?: ByteArray(0) else ByteArray(0), it.mine) },
            files = msg.files.map { mapCompanionAttachment(it, msg.mine) },
            replyTo = msg.replyTo,
            sharedContact = mappedSharedContact
        )
    }

    private fun mapCompanionAttachment(att: FfiCompanionAttachment, mine: Boolean): FfiFile {
        return FfiFile(
            fileId = att.fileId,
            name = att.name,
            sizeBytes = att.sizeBytes,
            incoming = !mine,
            accepted = att.accepted,
            complete = att.haveChunks == att.chunkTotal,
            receivedChunks = att.haveChunks,
            chunkTotal = att.chunkTotal,
            // Нарезку компаньон теперь сообщает, и её обязательно вернуть
            // в save_file: у каждого файла она своя — эфирный кусок это
            // четыре килобайта, сетевой мебибайт (FFI.md, §10.2). Раньше
            // тут стоял ноль («неизвестно»), потому что взять было негде.
            //
            // u64 -> u32 здесь ничего не теряет: в FfiFile ядро объявляет
            // то же самое число как u32, то есть само ручается, что оно
            // туда влезает.
            chunkBytes = att.chunkBytes.toUInt(),
            hasPreview = att.hasPreview
        )
    }

    override fun companionEndpoint(): Pair<Int, String>? = try {
        if (!session.isCompanion) null
        else {
            val companion = RatatoskCore.getCompanion()
            companion.port().toInt() to companion.desktopIk().toHexString()
        }
    } catch (e: Exception) {
        android.util.Log.w("RatatoskVM", "Failed to read companion endpoint", e)
        null
    }

    override fun setCompanionCache(enabled: Boolean) {
        session.scope.launch(Dispatchers.IO) {
            try {
                val link = session.settings.companionLinks.first()
                    .firstOrNull { it.label == currentCompanionLabel }
                if (!enabled) {
                    RatatoskCore.getCompanion().setCachePath(null)
                    if (link != null) {
                        session.settings.saveCompanionLink(link.copy(cachePath = null))
                    }
                    withContext(Dispatchers.Main) { _companionCacheEnabled.value = false }
                    return@launch
                }
                if (link == null) {
                    session._error.value = session.string(R.string.companion_cache_needs_link)
                    return@launch
                }
                val path = java.io.File(
                    session.app.filesDir,
                    "companion_cache_${link.inviteUri.hashCode()}"
                ).absolutePath
                RatatoskCore.getCompanion().setCachePath(path)
                session.settings.saveCompanionLink(link.copy(cachePath = path))
                withContext(Dispatchers.Main) { _companionCacheEnabled.value = true }
            } catch (e: Exception) {
                android.util.Log.w("RatatoskVM", "Failed to switch companion cache", e)
                session._error.value = e.message?.takeIf { it.isNotBlank() }
                    ?: session.string(R.string.companion_cache_failed)
            }
        }
    }

    override fun initializeCompanion(
        inviteUri: String,
        port: Int,
        peerAddr: String?,
        cachePath: String?,
        label: String,
        torDir: String?,
    ) {
        session.scope.launch(Dispatchers.IO) {
            try {
                val resolvedCachePath = if (!cachePath.isNullOrBlank()) {
                    if (cachePath.startsWith("/")) cachePath
                    else java.io.File(session.app.filesDir, cachePath).absolutePath
                } else null

                RatatoskCore.initializeCompanion(inviteUri, port.toUShort(), peerAddr, resolvedCachePath, torDir)
                currentCompanionLabel = label
                _companionCacheEnabled.value = resolvedCachePath != null
                if (resolvedCachePath != null) {
                    session.settings.saveCompanionLink(
                        chat.ratatosk.android.data.CompanionLink(label, inviteUri, port, peerAddr, resolvedCachePath, torDir)
                    )
                }
                withContext(Dispatchers.Main) {
                    onCompanionOpened()
                    val id = "companion:${inviteUri.hashCode()}"
                    session._activeAccountId.value = id
                    session.settings.setLastAccountId(id)
                    setupCompanionEngine()
                    session._error.value = null
                }
            } catch (e: Exception) {
                android.util.Log.w("RatatoskVM", "Failed to link companion", e)
                session._error.value = e.message?.takeIf { it.isNotBlank() }
                    ?: session.string(R.string.error_pairing_failed)
            }
        }
    }

    override fun unlockCompanion(link: CompanionLink) {
        initializeCompanion(link.inviteUri, link.port, link.peerAddr, link.cachePath, link.label, link.torDir)
    }

    override fun removeCompanionLink(inviteUri: String) {
        session.scope.launch {
            session.settings.removeCompanionLink(inviteUri)
        }
    }

    /** Сессия закрыта: чужого телефона у нас больше нет. */
    fun reset() {
        companionEventsJob?.cancel()
        companionEventsJob = null
        _companionCacheEnabled.value = false
        _isCompanionFresh.value = false
        currentCompanionLabel = null
    }
}
