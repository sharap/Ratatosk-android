package chat.ratatosk.android.ui.model

import chat.ratatosk.android.R
import chat.ratatosk.android.core.RatatoskCore
import chat.ratatosk.android.util.FileUtils
import chat.ratatosk.android.util.hexToByteArray
import chat.ratatosk.android.util.toHexString
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ratatosk.core.FfiFile
import org.ratatosk.core.FfiFileReader
import org.ratatosk.core.FfiFileWaitReason
import org.ratatosk.core.FfiSwept
import java.util.concurrent.ConcurrentHashMap

/**
 * Вложения: приём, приостановка, выкладывание сюда, превью и просмотр.
 *
 * Два разных хода передачи держатся врозь: [fileProgress] — сколько собрал
 * владелец файла (у компаньона это телефон, и у готового файла всегда сто
 * процентов), [saveProgress] — сколько уже легло на это устройство.
 */
interface FilesApi {
    val fileProgress: StateFlow<Map<String, Float>>
    val fileSending: StateFlow<Map<String, Float>>
    val saveProgress: StateFlow<Map<String, Float>>
    val fetchPaused: StateFlow<Boolean>
    val fileWaiting: StateFlow<Map<String, FfiFileWaitReason>>
    val filePreviews: StateFlow<Map<String, ByteArray>>
    val activeJobsFlow: StateFlow<Set<String>>
    val activeMediaFile: StateFlow<FfiFile?>
    val mediaExportedPath: StateFlow<String?>
    val autoAcceptLimit: StateFlow<ULong?>
    val downloadDirUri: StateFlow<String?>

    /** Почему передача стоит — словами ядра (§10.3). */
    fun fileWaitingText(reason: FfiFileWaitReason): String
    fun acceptFile(chatId: ByteArray, fileId: ByteArray)
    /** Останавливает приём, не отказываясь от него: продолжит `acceptFile`. */
    fun pauseFile(chatId: ByteArray, fileId: ByteArray)
    fun declineFile(chatId: ByteArray, fileId: ByteArray)
    fun requestFilePreview(fileId: ByteArray)
    fun saveFile(file: FfiFile, destination: java.io.File, onComplete: (java.io.File) -> Unit)
    fun downloadFile(file: FfiFile, onComplete: (String) -> Unit)
    fun cancelFileJob(fileId: ByteArray)
    fun sweepOrphanFiles(onResult: (FfiSwept) -> Unit)
    fun setDownloadDirUri(uri: android.net.Uri?)
    fun setAutoAcceptLimit(limit: ULong?)
    fun setActiveMediaFile(file: FfiFile?)
    fun openMedia(file: FfiFile, cacheDir: java.io.File)
    fun closeMedia()
}

/**
 * Исходник вложения не читается. `own` — наше отправленное: у него байты
 * берутся из файла по пути, и путь мог протухнуть.
 */
private class FileSourceGone(val own: Boolean) : Exception()

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FilesModel(
    private val session: SessionContext,
    /** Историю чата держит модель переписки: приём файла её меняет. */
    private val loadMessages: (ByteArray) -> Unit,
) : FilesApi {
    private val _fileProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    override val fileProgress = _fileProgress.asStateFlow()
    private val _fileSending = MutableStateFlow<Map<String, Float>>(emptyMap())
    override val fileSending = _fileSending.asStateFlow()
    private val _saveProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    override val saveProgress = _saveProgress.asStateFlow()
    private val _fileWaiting = MutableStateFlow<Map<String, FfiFileWaitReason>>(emptyMap())
    override val fileWaiting = _fileWaiting.asStateFlow()
    private val _filePreviews = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    override val filePreviews = _filePreviews.asStateFlow()
    private val _activeJobsFlow = MutableStateFlow<Set<String>>(emptySet())
    override val activeJobsFlow = _activeJobsFlow.asStateFlow()
    private val _activeMediaFile = MutableStateFlow<FfiFile?>(null)
    override val activeMediaFile = _activeMediaFile.asStateFlow()
    private val _mediaExportedPath = MutableStateFlow<String?>(null)
    override val mediaExportedPath = _mediaExportedPath.asStateFlow()
    private val _autoAcceptLimit = MutableStateFlow<ULong?>(null)
    override val autoAcceptLimit = _autoAcceptLimit.asStateFlow()
    private val _fetchPaused = MutableStateFlow(false)
    private val fetchWatchers = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val pendingCompanionSaves = ConcurrentHashMap<String, (java.io.File) -> Unit>()
    private val pendingCompanionPaths = ConcurrentHashMap<String, String>()
    override val fetchPaused: StateFlow<Boolean> = _fetchPaused.asStateFlow()
    private var currentCompanionSaveFileId: String? = null

    /** Идущие сохранения: по одной задаче на файл. */
    private val activeJobs = ConcurrentHashMap<String, Job>()

    // Превью, которые уже заказаны. Держит от лавины запросов: заказ идёт
    // из composable, то есть на каждую перерисовку строки.
    private val previewRequests: MutableSet<String> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    override val downloadDirUri = session.activeAccountId.flatMapLatest { id ->
        if (id == null) flowOf(null) else session.settings.getDownloadDirUri(id)
    }.stateIn(session.scope, SharingStarted.WhileSubscribed(5000), null)


    /**
     * Текст для стоящей передачи. Берётся у ядра и переписыванию
     * не подлежит: он обещает ровно то, что протокол делает. «Ошибка
     * отправки» и «загрузка…» здесь одинаково неправда.
     */
    override fun fileWaitingText(reason: FfiFileWaitReason): String =
        try { org.ratatosk.core.fileWaitingText(reason) } catch (e: Exception) { "" }

    override fun acceptFile(chatId: ByteArray, fileId: ByteArray) {
        session.scope.launch(Dispatchers.IO) {
            try {
                if (session.isCompanion) {
                    RatatoskCore.getCompanion().acceptFile(fileId)
                } else {
                    RatatoskCore.getClient().acceptFile(fileId)
                    loadMessages(chatId)
                }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to accept file", e)
            }
        }
    }

    override fun pauseFile(chatId: ByteArray, fileId: ByteArray) {
        session.scope.launch(Dispatchers.IO) {
            try {
                if (session.isCompanion) {
                    RatatoskCore.getCompanion().pauseFile(fileId)
                } else {
                    RatatoskCore.getClient().pauseFile(fileId)
                    loadMessages(chatId)
                }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to pause file", e)
            }
        }
    }

    override fun declineFile(chatId: ByteArray, fileId: ByteArray) {
        session.scope.launch(Dispatchers.IO) {
            try {
                if (session.isCompanion) {
                    RatatoskCore.getCompanion().declineFile(fileId)
                } else {
                    RatatoskCore.getClient().declineFile(fileId)
                    loadMessages(chatId)
                }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to decline file", e)
            }
        }
    }

    override fun requestFilePreview(fileId: ByteArray) {
        val hex = fileId.toHexString()
        if (_filePreviews.value.containsKey(hex)) return
        // Заказ уже в пути — второй ни к чему: composable зовёт нас
        // на каждую перерисовку.
        if (!previewRequests.add(hex)) return

        session.scope.launch(Dispatchers.IO) {
            try {
                if (session.isCompanion) {
                    // Это запрос, а не чтение: байты приедут событием
                    // FfiCompanionEvent.FilePreview и лягут в _filePreviews.
                    RatatoskCore.getCompanion().preview(fileId)
                } else {
                    val bytes = RatatoskCore.getClient().previewOf(fileId)
                    if (bytes != null) {
                        _filePreviews.update { it + (hex to bytes) }
                    }
                    // bytes == null означает «превью у этого файла нет».
                    // Отметку заказа не снимаем: спрашивать снова незачем.
                }
            } catch (e: Exception) {
                // Отметку снимаем, чтобы следующая попытка состоялась:
                // ядро могло быть ещё не поднято.
                previewRequests.remove(hex)
                android.util.Log.w("RatatoskVM", "Failed to fetch preview for $hex: ${e.message}")
            }
        }
    }

    private fun watchFetchProgress(fileIdHex: String, destination: java.io.File, sizeBytes: ULong) {
        val total = sizeBytes.toLong()
        if (total <= 0L) return
        val part = java.io.File(destination.parentFile, destination.name + ".part")
        fetchWatchers.remove(fileIdHex)?.cancel()
        fetchWatchers[fileIdHex] = session.scope.launch(Dispatchers.IO) {
            while (isActive && pendingCompanionSaves.containsKey(fileIdHex)) {
                val written = when {
                    destination.isFile -> destination.length()
                    part.isFile -> part.length()
                    else -> 0L
                }
                _saveProgress.update { it + (fileIdHex to (written.toFloat() / total).coerceIn(0f, 1f)) }
                kotlinx.coroutines.delay(400)
            }
        }
    }

    private fun stopFetchWatchers(fileIds: Collection<String>) {
        fileIds.forEach { fetchWatchers.remove(it)?.cancel() }
    }

    private fun failPendingCompanionSaves(reason: String?) {
        if (pendingCompanionSaves.isEmpty() && currentCompanionSaveFileId == null) return
        val waiting = pendingCompanionSaves.keys.toList()
        stopFetchWatchers(waiting)
        pendingCompanionSaves.clear()
        pendingCompanionPaths.clear()
        currentCompanionSaveFileId = null
        _activeJobsFlow.update { it - waiting.toSet() }
        val text = reason?.takeIf { it.isNotBlank() }
            ?: session.string(R.string.file_unavailable)
        session._error.value = text
    }

    override fun saveFile(file: FfiFile, destination: java.io.File, onComplete: (java.io.File) -> Unit) {
        val fileIdHex = file.fileId.toHexString()
        
        if (session.isCompanion) {
            if (currentCompanionSaveFileId != null && currentCompanionSaveFileId != fileIdHex) {
                // Automatically cancel previous save if a new one is requested
                cancelFileJob(currentCompanionSaveFileId!!.hexToByteArray())
            }

            // Без нарезки куски лягут врастопырку, и это не отказ:
            // файл сохранится, будет выглядеть сохранённым и окажется
            // битым, раздувшись в сотни раз (FFI.md к save_file). Лучше
            // честно не начать, чем отдать человеку такое.
            if (file.chunkBytes == 0u) {
                android.util.Log.e("RatatoskVM", "No chunk_bytes for $fileIdHex, refusing save")
                session._error.value = session.string(R.string.file_unavailable)
                return
            }

            currentCompanionSaveFileId = fileIdHex
            pendingCompanionSaves[fileIdHex] = onComplete
            pendingCompanionPaths[fileIdHex] = destination.absolutePath
            _activeJobsFlow.update { it + fileIdHex }
            // Прошлая доля того же файла ввела бы в заблуждение.
            _saveProgress.update { it - fileIdHex }
            _fetchPaused.value = false
            watchFetchProgress(fileIdHex, destination, file.sizeBytes)

            session.scope.launch(Dispatchers.IO) {
                try {
                    destination.parentFile?.mkdirs()
                    RatatoskCore.getCompanion().saveFile(
                        file.fileId,
                        file.chunkTotal,
                        file.chunkBytes.toULong(),
                        destination.absolutePath
                    )
                } catch (e: Exception) {
                    android.util.Log.e("RatatoskVM", "Failed companion save", e)
                    pendingCompanionSaves.remove(fileIdHex)
                    pendingCompanionPaths.remove(fileIdHex)
                    _activeJobsFlow.update { it - fileIdHex }
                    if (currentCompanionSaveFileId == fileIdHex) currentCompanionSaveFileId = null
                }
            }
            return
        }

        // start = LAZY, потому что регистрация обязана произойти раньше, чем
        // задача успеет закончиться. При DEFAULT корутина уже бежала, и на
        // быстром отказе её finally снимал отметку до того, как строки ниже
        // её поставят: вложение навсегда оставалось с крутилкой «отменить».
        val job = session.scope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            var reader: FfiFileReader? = null
            try {
                destination.parentFile?.mkdirs()
                
                reader = RatatoskCore.getClient().openFile(file.fileId)
                if (reader == null) {
                    withContext(Dispatchers.Main) {
                        session._error.value = session.string(R.string.file_unavailable)
                    }
                    return@launch
                }

                destination.outputStream().use { output ->
                    val total = reader.chunkTotal()
                    for (i in 0UL until total) {
                        ensureActive()
                        val chunk = reader.chunk(i)
                        if (chunk != null) {
                            output.write(chunk)
                            output.flush()
                            _saveProgress.update { it + (fileIdHex to (i.toFloat() / total.toFloat())) }
                        } else {
                            // Своё вложение ядро читает из исходника по
                            // пути, а не из принятого: отправитель ничего
                            // у себя не запечатывал. Человек удалил или
                            // перенёс файл — и это не обрыв загрузки,
                            // а исчезнувший исходник.
                            throw FileSourceGone(reader.own())
                        }
                    }
                }
                _fileProgress.update { it + (fileIdHex to 1f) }
                session.scope.launch { onComplete(destination) }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    // Имя файла приходит от собеседника — в журнал его класть нельзя.
                    android.util.Log.e("RatatoskVM", "Failed to save file", e)
                    withContext(Dispatchers.Main) { session._error.value = fileFailureText(e) }
                }
            } finally {
                reader?.destroy()
                // По ключу **и значению**: по одному ключу нас могла уже
                // сменить следующая задача, и remove(key) снёс бы её
                // регистрацию — она качала бы дальше, но без прогресса
                // и без возможности отмены.
                if (activeJobs.remove(fileIdHex, coroutineContext[Job])) {
                    _activeJobsFlow.update { it - fileIdHex }
                }
            }
        }
        
        // Прежнюю задачу снимаем и **ждать её finally не нужно**: удаление
        // идёт по совпадению значения (см. ниже), так что её уборка нашу
        // регистрацию не затрёт.
        activeJobs.put(fileIdHex, job)?.cancel()
        _activeJobsFlow.update { it + fileIdHex }
        job.start()
    }

    override fun downloadFile(file: FfiFile, onComplete: (String) -> Unit) {
        val fileIdHex = file.fileId.toHexString()

        if (session.isCompanion) {
            val tempFile = java.io.File(session.app.cacheDir, "downloads/${file.fileId.toHexString()}_${file.name}")
            saveFile(file, tempFile) { savedFile ->
                session.scope.launch(Dispatchers.IO) {
                    try {
                        val dirUriString = downloadDirUri.value
                        val dirUri = dirUriString?.let { android.net.Uri.parse(it) }
                        
                        if (dirUri != null) {
                            val root = DocumentFile.fromTreeUri(session.app, dirUri)
                            if (root != null && root.canWrite()) {
                                val target = root.createFile("*/*", file.name)
                                if (target != null) {
                                    session.app.contentResolver.openOutputStream(target.uri)?.use { output ->
                                        savedFile.inputStream().use { input ->
                                            input.copyTo(output)
                                        }
                                    }
                                    session.scope.launch { onComplete(file.name) }
                                    savedFile.delete()
                                    return@launch
                                }
                            }
                        }
                        
                        // Запасной путь, когда каталог через SAF не выбран.
                        // Через MediaStore: прямая запись в публичные
                        // «Загрузки» на Android 10+ запрещена без разрешений.
                        val sink = FileUtils.openDownloadSink(session.app, file.name)
                        if (sink == null) {
                            withContext(Dispatchers.Main) {
                                session._error.value = session.string(R.string.error_save_to_downloads)
                            }
                            savedFile.delete()
                            return@launch
                        }
                        sink.stream.use { output ->
                            savedFile.inputStream().use { input -> input.copyTo(output) }
                        }
                        savedFile.delete()
                        session.scope.launch { onComplete(sink.displayPath) }
                    } catch (e: Exception) {
                        android.util.Log.e("RatatoskVM", "Failed companion download copy", e)
                    }
                }
            }
            return
        }

        // start = LAZY, потому что регистрация обязана произойти раньше, чем
        // задача успеет закончиться. При DEFAULT корутина уже бежала, и на
        // быстром отказе её finally снимал отметку до того, как строки ниже
        // её поставят: вложение навсегда оставалось с крутилкой «отменить».
        val job = session.scope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            var reader: FfiFileReader? = null
            try {
                reader = RatatoskCore.getClient().openFile(file.fileId)
                if (reader == null) {
                    withContext(Dispatchers.Main) {
                        session._error.value = session.string(R.string.file_unavailable)
                    }
                    return@launch
                }

                val dirUriString = downloadDirUri.value
                val dirUri = dirUriString?.let { android.net.Uri.parse(it) }
                
                if (dirUri != null) {
                    val root = DocumentFile.fromTreeUri(session.app, dirUri)
                    if (root != null && root.canWrite()) {
                        val target = root.createFile("*/*", file.name)
                        if (target != null) {
                            session.app.contentResolver.openOutputStream(target.uri)?.use { output ->
                                val total = reader.chunkTotal()
                                for (i in 0UL until total) {
                                    ensureActive()
                                    val chunk = reader.chunk(i)
                                    // Пропустить кусок молча значило бы
                                    // записать в «Загрузки» обрезанный файл
                                    // и назвать это успехом.
                                    if (chunk == null) throw FileSourceGone(reader.own())
                                    output.write(chunk)
                                    output.flush()
                                    _fileProgress.update { it + (fileIdHex to (i.toFloat() / total.toFloat())) }
                                }
                            }
                            _fileProgress.update { it + (fileIdHex to 1f) }
                            session.scope.launch { onComplete(file.name) }
                            return@launch
                        }
                    }
                }
                
                // См. пояснение выше: только через MediaStore.
                val sink = FileUtils.openDownloadSink(session.app, file.name)
                if (sink == null) {
                    withContext(Dispatchers.Main) {
                        session._error.value = session.string(R.string.error_save_to_downloads)
                    }
                    return@launch
                }

                sink.stream.use { output ->
                    val total = reader.chunkTotal()
                    for (i in 0UL until total) {
                        ensureActive()
                        val chunk = reader.chunk(i)
                        if (chunk == null) throw FileSourceGone(reader.own())
                        output.write(chunk)
                        output.flush()
                        _fileProgress.update { it + (fileIdHex to (i.toFloat() / total.toFloat())) }
                    }
                }
                _fileProgress.update { it + (fileIdHex to 1f) }
                session.scope.launch { onComplete(sink.displayPath) }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    android.util.Log.e("RatatoskVM", "Failed to download file", e)
                    withContext(Dispatchers.Main) { session._error.value = fileFailureText(e) }
                }
            } finally {
                reader?.destroy()
                // По ключу **и значению**: по одному ключу нас могла уже
                // сменить следующая задача, и remove(key) снёс бы её
                // регистрацию — она качала бы дальше, но без прогресса
                // и без возможности отмены.
                if (activeJobs.remove(fileIdHex, coroutineContext[Job])) {
                    _activeJobsFlow.update { it - fileIdHex }
                }
            }
        }
        
        // Прежнюю задачу снимаем и **ждать её finally не нужно**: удаление
        // идёт по совпадению значения (см. ниже), так что её уборка нашу
        // регистрацию не затрёт.
        activeJobs.put(fileIdHex, job)?.cancel()
        _activeJobsFlow.update { it + fileIdHex }
        job.start()
    }

    override fun cancelFileJob(fileId: ByteArray) {
        val hex = fileId.toHexString()
        if (session.isCompanion) {
            if (currentCompanionSaveFileId == hex) {
                session.scope.launch(Dispatchers.IO) {
                    try {
                        RatatoskCore.getCompanion().cancelSave()
                    } catch (e: Exception) {
                        android.util.Log.e("RatatoskVM", "Failed to cancel companion save", e)
                    }
                }
                currentCompanionSaveFileId = null
                pendingCompanionSaves.remove(hex)
                pendingCompanionPaths.remove(hex)
                _activeJobsFlow.update { it - hex }
            }
            return
        }
        activeJobs[hex]?.cancel()
        activeJobs.remove(hex)
        _activeJobsFlow.update { it - hex }
    }

    override fun sweepOrphanFiles(onResult: (FfiSwept) -> Unit) {
        if (session.isCompanion) return
        session.scope.launch(Dispatchers.IO) {
            try {
                val result = RatatoskCore.getClient().sweepOrphanFiles()
                session.scope.launch { onResult(result) }
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to sweep orphan files", e)
            }
        }
    }

    override fun setDownloadDirUri(uri: android.net.Uri?) {
        val id = session.activeAccountId.value ?: return
        session.scope.launch {
            session.settings.setDownloadDirUri(id, uri?.toString())
        }
    }

    override fun setAutoAcceptLimit(limit: ULong?) {
        if (session.isCompanion) return
        session.scope.launch(Dispatchers.IO) {
            try {
                RatatoskCore.getClient().setAutoAcceptBytes(limit)
                _autoAcceptLimit.value = limit
            } catch (e: Exception) {
                android.util.Log.e("RatatoskVM", "Failed to set auto-accept limit", e)
            }
        }
    }

    override fun setActiveMediaFile(file: FfiFile?) {
        _activeMediaFile.value = file
    }

    override fun openMedia(file: FfiFile, cacheDir: java.io.File) {
        _activeMediaFile.value = file
        _mediaExportedPath.value = null
        
        val mediaDir = java.io.File(cacheDir, "media_viewer")
        mediaDir.mkdirs()
        val dest = java.io.File(mediaDir, "${file.fileId.toHexString()}_${file.name}")
        
        if (dest.exists() && dest.length() == file.sizeBytes.toLong()) {
            _mediaExportedPath.value = dest.absolutePath
            return
        }
        
        saveFile(file, dest) { savedFile ->
            if (_activeMediaFile.value?.fileId?.contentEquals(file.fileId) == true) {
                _mediaExportedPath.value = savedFile.absolutePath
            }
        }
    }

    override fun closeMedia() {
        val fileId = _activeMediaFile.value?.fileId
        if (fileId != null && session.isCompanion) {
            cancelFileJob(fileId)
        }
        _activeMediaFile.value = null
        _mediaExportedPath.value = null
    }

    private fun fileFailureText(e: Throwable): String = when {
        // Формулировку последствий даёт ядро (§14) — там она честная и
        // переведённая, а мы бы сочинили своё.
        e is FileSourceGone && e.own -> org.ratatosk.core.fileSourceGoneNotice()
        else -> session.string(R.string.file_unavailable)
    }

    // --- Входы для событий ядра -------------------------------------------

    fun onFileProgress(hex: String, fraction: Float) {
        _fileProgress.update { it + (hex to fraction) }
    }

    fun onFileSending(hex: String, fraction: Float) {
        _fileSending.update { it + (hex to fraction) }
    }

    fun onSaveProgress(hex: String, fraction: Float) {
        _saveProgress.update { it + (hex to fraction) }
    }

    fun onFileWaiting(hex: String, reason: FfiFileWaitReason?) {
        _fileWaiting.update { if (reason != null) it + (hex to reason) else it - hex }
    }

    fun onFilePreview(hex: String, bytes: ByteArray?) {
        if (bytes != null) _filePreviews.update { it + (hex to bytes) }
    }

    /** Файла больше нет: ни хода, ни причины ожидания. */
    fun forgetFile(hex: String) {
        _fileWaiting.update { it - hex }
        _fileProgress.update { it - hex }
        _fileSending.update { it - hex }
    }

    /** Лимит автоприёма прочитан у ядра при открытии аккаунта. */
    fun setAutoAcceptLimitValue(limit: ULong?) {
        _autoAcceptLimit.value = limit
    }

    fun setFetchPaused(paused: Boolean) {
        _fetchPaused.value = paused
    }

    /** Какое вложение забираем сейчас: ядро берёт по одному за раз. */
    fun currentSaveFileId(): String? = currentCompanionSaveFileId

    /** Выкладывание кончилось — успехом или ничем. */
    fun finishSave(hex: String, path: String?, deliver: Boolean) {
        _saveProgress.update { it + (hex to 1f) }
        _fetchPaused.value = false
        stopFetchWatchers(listOf(hex))
        _activeJobsFlow.update { it - hex }
        if (currentCompanionSaveFileId == hex) currentCompanionSaveFileId = null
        val callback = pendingCompanionSaves.remove(hex)
        val saved = pendingCompanionPaths.remove(hex) ?: path
        if (deliver && callback != null && saved != null) {
            session.scope.launch(Dispatchers.Main) { callback(java.io.File(saved)) }
        }
    }

    fun forgetSave(hex: String) {
        stopFetchWatchers(listOf(hex))
        _activeJobsFlow.update { it - hex }
        if (currentCompanionSaveFileId == hex) currentCompanionSaveFileId = null
        pendingCompanionSaves.remove(hex)
        pendingCompanionPaths.remove(hex)
    }

    /** Отказ ядра или уход телефона: ждать `FileSaved` больше нечего. */
    fun failPendingSaves(reason: String?) {
        failPendingCompanionSaves(reason)
    }

    /** Сессия закрыта: ход чужих передач и превью нам не нужны. */
    fun reset() {
        stopFetchWatchers(fetchWatchers.keys.toList())
        _fileProgress.value = emptyMap()
        _fileSending.value = emptyMap()
        _saveProgress.value = emptyMap()
        _fetchPaused.value = false
        _fileWaiting.value = emptyMap()
        _filePreviews.value = emptyMap()
        _activeJobsFlow.value = emptySet()
        _activeMediaFile.value = null
        _mediaExportedPath.value = null
        _autoAcceptLimit.value = null
        pendingCompanionSaves.clear()
        pendingCompanionPaths.clear()
        currentCompanionSaveFileId = null
    }
}
