package chat.ratatosk.android.ui.model

import chat.ratatosk.android.R
import chat.ratatosk.android.util.hexToByteArray
import chat.ratatosk.android.util.toHexString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ratatosk.core.FfiEvent
import org.ratatosk.core.FfiTorStatus
import org.ratatosk.core.FfiTransport
import org.ratatosk.core.maxAvatarBytes

/** Что окно знает о своём адресе в сети. */
interface ClientApi {
    /** Onion этого устройства; `null` — Tor ещё не поднялся. */
    val onionAddress: StateFlow<String?>
    /** Версия визитки: по ней собеседник понимает, что адреса сменились. */
    val cardVersion: StateFlow<ULong?>
}

/**
 * Полный клиент: подъём сессии и перевод событий ядра в состояние моделей.
 *
 * Событий много, и каждое касается своей модели — поэтому маршрутизация
 * живёт отдельно от них: моделям незачем знать про `FfiEvent`, а окну —
 * про то, какое событие какую из них трогает.
 *
 * @param openCompanion второй экран поднимает [CompanionModel]; сюда
 *   приходит тот же `setupEngine`, и режим выбирается здесь.
 * @param onCompanionMode режим сессии — его показывает окно.
 */
class ClientModel(
    private val session: SessionContext,
    private val chats: ChatsModel,
    private val contacts: ContactsModel,
    private val groups: GroupsModel,
    private val files: FilesModel,
    private val transports: TransportsModel,
    private val pairing: PairingModel,
    private val openCompanion: () -> Unit,
    private val onCompanionMode: (Boolean) -> Unit,
) : ClientApi {

    private val _onionAddress = MutableStateFlow<String?>(null)
    override val onionAddress: StateFlow<String?> = _onionAddress.asStateFlow()

    private val _cardVersion = MutableStateFlow<ULong?>(null)
    override val cardVersion: StateFlow<ULong?> = _cardVersion.asStateFlow()

    /** Одна на сессию: второй сбор тех же событий раздваивал бы работу. */
    private var eventsJob: Job? = null

    /** Ответ Tor приходит не один раз; объявляемся по первому. */
    private var isAnnouncingTor = false

    fun ensureClientEvents() {
        if (eventsJob != null) return
        android.util.Log.d("RatatoskVM", "Starting event collection job")
        eventsJob = session.scope.launch(Dispatchers.IO) {
            session.core.events.collect { event ->
                handleEvent(event)
            }
        }
    }

    fun setupEngine() {
        android.util.Log.d("RatatoskVM", "Setting up engine components... Companion mode: ${session.core.isCompanion}")
        onCompanionMode(session.core.isCompanion)

        session.scope.launch(Dispatchers.IO) {
            try {
                if (session.core.isCompanion) {
                    openCompanion()
                    return@launch
                }

                // Радио эфира — сразу после открытия аккаунта.
                //
                // Раньше его вручала только служба, при своём старте.
                // Этого хватало там, где аккаунт открывается сам
                // (без PIN, восстановлением сессии): служба сама его
                // и открывала, а следом вручала радио. Но если аккаунт
                // открывает человек — ввёл PIN, выбрал другой, поставил
                // приложение заново, — служба к тому моменту уже
                // отработала вхолостую: клиента ещё не было, вручать
                // было нечего, а второй раз её никто не позовёт.
                //
                // Снаружи это выглядело так: на одном телефоне эфир
                // поднимается с запуском, на другом не поднимается вовсе.
                // Вызов идемпотентен и под замком, так что лишним он
                // не будет даже там, где служба успела раньше.
                session.core.ensureBtRadio(session.app)

                android.util.Log.d("RatatoskVM", "Engine setup: getting fingerprint and limits")
                val client = session.core.client()
                val fingerprint = client.fingerprint()
                val maxAvatar = try { maxAvatarBytes().toInt() } catch (e: Exception) { 32768 }
                
                withContext(Dispatchers.Main) {
                    contacts.setFingerprint(fingerprint)
                    contacts.setMaxAvatarBytes(maxAvatar)
                }

                ensureClientEvents()

                // Load own avatar (non-critical, separate launch)
                launch(Dispatchers.IO) {
                    try {
                        android.util.Log.d("RatatoskVM", "Engine setup: loading own avatar")
                        val avatar = client.myAvatar()
                        withContext(Dispatchers.Main) { contacts.onOwnAvatar(avatar) }
                    } catch (e: Exception) {
                        android.util.Log.e("RatatoskVM", "Failed to load my avatar", e)
                    }
                }

                // Load auto-accept limit
                launch(Dispatchers.IO) {
                    try {
                        android.util.Log.d("RatatoskVM", "Engine setup: getting auto accept bytes")
                        val limit = client.autoAcceptBytes()
                        withContext(Dispatchers.Main) { files.setAutoAcceptLimitValue(limit) }
                    } catch (e: Exception) {
                        android.util.Log.e("RatatoskVM", "Failed to load auto-accept limit", e)
                    }
                }
                
                // Initial load of contacts, groups and messages (instant local DB queries)
                launch(Dispatchers.IO) {
                    try {
                        android.util.Log.d("RatatoskVM", "Engine setup: preloading contacts and groups from local DB")
                        val currentContacts = client.contacts()
                        val currentGroups = client.groups()
                        withContext(Dispatchers.Main) {
                            contacts.setContacts(currentContacts)
                            groups.setGroups(currentGroups)
                        }
                        
                        (currentContacts.map { it.chatId } + currentGroups.map { it.chatId }).forEach { chatId ->
                            launch(Dispatchers.IO) {
                                chats.loadMessages(chatId)
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("RatatoskVM", "Failed to preload contacts/messages", e)
                    }
                }

                // Refresh transport status and paired devices in background without blocking UI
                launch(Dispatchers.IO) {
                    try {
                        transports.refreshTransportStatus()
                        pairing.loadPairedDevices()
                    } catch (e: Exception) {
                        android.util.Log.e("RatatoskVM", "Failed background transport refresh", e)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Critical failure during setupEngine", e)
                withContext(Dispatchers.Main) {
                    session._error.value = e.message?.takeIf { it.isNotBlank() }
                        ?: session.string(R.string.error_identity_load_failed)
                }
            }
        }
    }

    private fun handleEvent(event: FfiEvent) {
        when (event) {
            is FfiEvent.MessageReceived -> {
                chats.loadMessages(event.chatId)
                if (chats.activeChatIdHex != event.chatId.toHexString()) {
                    chats.bumpUnread(event.chatId.toHexString())
                }
            }
            is FfiEvent.StatusChanged -> {
                val hex = event.msgId.toHexString()
                chats.onStatusChanged(hex, event.status)
                
                val targetChatHex = chats.chatIdHexOf(event.msgId) ?: chats.activeChatIdHex

                if (targetChatHex != null) {
                    chats.loadMessages(targetChatHex.hexToByteArray())
                }
            }
            is FfiEvent.ContactAdded, is FfiEvent.ContactChanged, is FfiEvent.ContactRemoved -> {
                contacts.refreshContacts()
            }
            is FfiEvent.MessagesDeleted -> {
                chats.loadMessages(event.chatId)
            }
            is FfiEvent.MessageEdited -> {
                chats.loadMessages(event.chatId)
            }
            is FfiEvent.ReactionChanged -> {
                chats.loadMessages(event.chatId)
            }
            is FfiEvent.AvatarChanged -> {
                val hex = event.peerIk.toHexString()
                session.scope.launch(Dispatchers.IO) {
                    try {
                        val bytes = session.core.client().avatarOf(event.peerIk)
                        withContext(Dispatchers.Main) {
                            if (bytes != null) {
                                contacts.putAvatar(hex, bytes)
                            } else {
                                contacts.removeAvatar(hex)
                            }
                        }
                    } catch (t: Throwable) {
                        android.util.Log.w("RatatoskVM", "Failed to refresh avatar for ${event.peerIk.toHexString()}: ${t.message}")
                    }
                }
            }
            is FfiEvent.FileWaitsForChannel -> {
                files.onFileWaiting(event.fileId.toHexString(), event.reason)
            }
            is FfiEvent.FileGone -> {
                val hex = event.fileId.toHexString()
                // Вложения больше нет — и ожидания вместе с ним.
                files.forgetFile(hex)
                // Строку вложения надо **убрать**, а не обнулить в ней
                // числа: само сообщение остаётся, текст к отвергнутой
                // картинке никуда не делся. Список сообщений об этом
                // не знает, поэтому перечитываем открытый чат.
                chats.activeChatIdHex?.let { chats.loadMessages(it.hexToByteArray()) }
            }
            is FfiEvent.FileSending -> {
                // «Отдано транспорту», а не «доставлено»: говорить про
                // доставку этими числами нельзя (§14, FFI.md).
                val hex = event.fileId.toHexString()
                val progress = if (event.total > 0UL) {
                    event.sent.toFloat() / event.total.toFloat()
                } else 0f
                files.onFileSending(hex, progress)
                // Ядро называет снимающим ожидание только FileProgress,
                // но он про приём. У отдачи движение видно отсюда, и
                // держать «стоит» поверх идущей отправки было бы враньём.
                files.onFileWaiting(hex, null)
            }
            is FfiEvent.FileProgress -> {
                val hex = event.fileId.toHexString()
                // Ход передачи и означает, что она пошла: ядро прямо
                // говорит, что это событие снимает ожидание.
                files.onFileWaiting(hex, null)
                val progress = if (event.total > 0UL) event.received.toFloat() / event.total.toFloat() else 0f
                files.onFileProgress(hex, progress)
                
                if (event.received == event.total) {
                    // Перечитываем чат, чтобы у FfiFile обновился `complete`.
                    //
                    // FileProgress — единственный сигнал о завершении: ядро
                    // шлёт его на каждый чанк «и на завершение», отдельного
                    // события «файл собран» нет. Сам объект FfiFile приехал
                    // вместе со списком сообщений и об этом не знает.
                    //
                    // Раньше здесь стояло ещё и `&& isCompanionMode()`, то
                    // есть в обычном режиме — основном — статус не обновлялся
                    // вовсе: вложение до перезахода в чат висело с крутилкой
                    // и кнопками «принять/отклонить», а «открыть» и
                    // «сохранить» не появлялись.
                    //
                    // Условие `received == total` нарочно без `total > 0`:
                    // у пустого файла оба нуля, и это тоже завершение.
                    chats.activeChatIdHex?.let { chats.loadMessages(it.hexToByteArray()) }
                }
            }
            is FfiEvent.TorStatus -> {
                android.util.Log.i("RatatoskVM", "TorStatus event: fraction=${event.fraction}, note=${event.note}, blocked=${event.blocked}")
                transports.onTorStatus(FfiTorStatus(event.fraction, event.note, event.blocked))
                if (event.fraction >= 1.0f && _onionAddress.value == null && !isAnnouncingTor) {
                    // Tor is up, announce addresses if Tor is enabled
                    isAnnouncingTor = true
                    session.scope.launch(Dispatchers.IO) {
                        try {
                            val client = session.core.client()
                            val card = client.myAddresses()
                            
                            withContext(Dispatchers.Main) {
                                _onionAddress.value = card.onion.takeIf { it.isNotEmpty() }
                                _cardVersion.value = card.version
                            }
                            
                            if (client.transportEnabled(FfiTransport.ONION) && card.onion.isNotEmpty()) {
                                android.util.Log.i("RatatoskVM", "Tor is up, announcing onion address: ${card.onion}")
                                client.announceAddresses(card.onion, card.chatmail)
                                // Refresh again to get new version if it changed
                                val updatedCard = client.myAddresses()
                                withContext(Dispatchers.Main) {
                                    _cardVersion.value = updatedCard.version
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("RatatoskVM", "Failed to announce addresses", e)
                        } finally {
                            isAnnouncingTor = false
                        }
                    }
                }
                transports.refreshTransportStatus()
            }
            is FfiEvent.CommandRefused -> {
                session._error.value = event.reason
            }
            is FfiEvent.MailAccountReady -> {
                android.util.Log.i("RatatoskVM", "Mail account ready")
                transports.refreshTransportStatus()
            }
            is FfiEvent.MailAccountFailed -> {
                session._error.value = event.reason.takeIf { it.isNotBlank() }
                    ?: session.string(R.string.error_mail_setup_failed)
                transports.refreshTransportStatus()
            }
            is FfiEvent.MailLoginFailed -> {
                android.util.Log.w("RatatoskVM", "Mail login failed: ${event.reason}")
                transports.refreshTransportStatus()
            }
            is FfiEvent.MailLimits -> {
                android.util.Log.i("RatatoskVM", "Mail limits updated: usage=${event.mailboxUsed}, limit=${event.mailboxLimit}")
                transports.refreshTransportStatus()
            }
            is FfiEvent.HonestNotice -> {
                // Could show as a global notice or snackbar
            }
            is FfiEvent.PairingReady -> {
                // Ссылка сопряжения — это и есть секрет: у кого она, тот второй
                // экран этого телефона до отзыва. В журнал она не идёт.
                android.util.Log.i("RatatoskVM", "Pairing ready event received")
                pairing.onPairingReady(event.uri)
                pairing.loadPairedDevices()
            }
            is FfiEvent.PairingRevoked -> {
                android.util.Log.i("RatatoskVM", "Pairing revoked event for device: ${event.deviceId.toHexString()}")
                pairing.loadPairedDevices()
            }
            is FfiEvent.DeviceLink -> {
                android.util.Log.i("RatatoskVM", "Device ${event.deviceId.toHexString()} link status: ${event.connected}")
                pairing.loadPairedDevices()
            }
            is FfiEvent.GroupCreated -> {
                android.util.Log.i("RatatoskVM", "Group created: ${event.chatId.toHexString()} (${event.title})")
                contacts.refreshContacts()
                chats.loadMessages(event.chatId)
            }
            is FfiEvent.GroupRenamed -> {
                android.util.Log.i("RatatoskVM", "Group renamed: chat=${event.chatId.toHexString()} title=${event.title}")
                contacts.refreshContacts()
                chats.loadMessages(event.chatId)
            }
            is FfiEvent.GroupMembershipChanged -> {
                android.util.Log.i("RatatoskVM", "Group membership changed: chat=${event.chatId.toHexString()}")
                contacts.refreshContacts()
                chats.loadMessages(event.chatId)
            }
            is FfiEvent.GroupAvatarChanged -> {
                val hex = event.chatId.toHexString()
                session.scope.launch(Dispatchers.IO) {
                    try {
                        val bytes = session.core.client().groupAvatar(event.chatId)
                        withContext(Dispatchers.Main) {
                            if (bytes != null) {
                                contacts.putAvatar(hex, bytes)
                            } else {
                                contacts.removeAvatar(hex)
                            }
                        }
                    } catch (t: Throwable) {
                        android.util.Log.w("RatatoskVM", "Failed to refresh group avatar for ${event.chatId.toHexString()}: ${t.message}")
                    }
                }
            }
            else -> {}
        }
    }

    /** Сессия закрыта: ни событий, ни адресов прошлого аккаунта. */
    fun reset() {
        eventsJob?.cancel()
        eventsJob = null
        _onionAddress.value = null
        _cardVersion.value = null
        isAnnouncingTor = false
    }
}
