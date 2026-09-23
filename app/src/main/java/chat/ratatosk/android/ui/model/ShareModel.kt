package chat.ratatosk.android.ui.model

import android.net.Uri
import chat.ratatosk.android.R
import chat.ratatosk.android.util.FileUtils
import chat.ratatosk.android.util.toHexString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ratatosk.core.maxPreviewBytes

/**
 * То, чем с нами поделились из другого приложения, уже сложенное
 * в файлы и ждущее своего чата.
 *
 * Черновиком, а не отправкой: человек выбрал получателя в чужом
 * окне «поделиться», и показать ему, что и куда уходит, надо до
 * отправки, а не после. Заодно он успеет добавить подпись.
 */
data class SharedDraft(
    val chatIdHex: String,
    val text: String,
    val files: List<java.io.File>,
)

/** Что окно знает про «поделиться». */
interface ShareApi {
    val sharedDraft: StateFlow<SharedDraft?>
    fun shareInto(chatId: ByteArray, text: String, uris: List<Uri>)
    fun clearSharedDraft()
}

/** Приём «поделиться» со стороны системы и превью исходящих картинок. */
class ShareModel(private val session: SessionContext) : ShareApi {

    private val _sharedDraft = MutableStateFlow<SharedDraft?>(null)
    override val sharedDraft: StateFlow<SharedDraft?> = _sharedDraft.asStateFlow()

    /**
     * Принимает «поделиться» в выбранный чат: копирует вложения к себе
     * и кладёт их черновиком.
     *
     * Копировать надо здесь и сразу: право на чужой `content:`-адрес
     * живёт, пока жива наша задача, и к моменту отправки может кончиться.
     */
    override fun shareInto(chatId: ByteArray, text: String, uris: List<Uri>) {
        val hex = chatId.toHexString()
        session.scope.launch(Dispatchers.IO) {
            val app = session.app
            val accepted = uris.filter { FileUtils.isSafeIncomingUri(app, it) }
            val files = accepted.mapNotNull { FileUtils.copyUriToInternalStorage(app, it) }
            val lost = uris.size - files.size
            withContext(Dispatchers.Main) {
                if (lost > 0) {
                    // Молчать нельзя: иначе человек отправит три файла
                    // из пяти и узнает об этом от собеседника.
                    session._error.value = app.resources.getQuantityString(
                        R.plurals.share_files_dropped, lost, lost
                    )
                }
                if (text.isNotBlank() || files.isNotEmpty()) {
                    _sharedDraft.value = SharedDraft(hex, text, files)
                }
            }
        }
    }

    override fun clearSharedDraft() {
        _sharedDraft.value = null
    }

    /**
     * Волны записей, снятые при записи, по пути файла.
     *
     * Превью вложения ядро просит у нас в момент отправки, а декодировать
     * Opus ради картинки нечем: громкость известна только записи. Поэтому
     * она кладёт готовую волну сюда, а [previewFor] её оттуда берёт.
     */
    private val recordedWaveforms = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    /** Запомнить волну записи до отправки. */
    fun rememberWaveform(path: String, png: ByteArray) {
        recordedWaveforms[path] = png
    }

    /**
     * Уменьшенная картинка к исходящему вложению.
     *
     * `null` — превью не будет: либо это не картинка, либо оно не влезает
     * в отведённые ядром байты, и лучше без него, чем с обрезанным.
     */
    fun previewFor(file: java.io.File): ByteArray? {
        // Голосовое: волна уже нарисована записью — декодировать Opus нечем.
        recordedWaveforms.remove(file.absolutePath)?.let { return it }
        // Остальное превью имеет смысл только у картинок: ядро ждёт
        // изображение, а не «что-нибудь про файл».
        if (file.extension.lowercase() !in listOf("jpg", "jpeg", "png", "webp")) return null
        try {
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return null
            val reqWidth = 320
            val reqHeight = 320
            val ratio = Math.min(reqWidth.toFloat() / bitmap.width, reqHeight.toFloat() / bitmap.height)

            val scaled = if (ratio >= 1.0f) bitmap else android.graphics.Bitmap.createScaledBitmap(
                bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true
            )
            val stream = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, stream)
            val bytes = stream.toByteArray()
            if (bytes.size > maxPreviewBytes().toInt()) return null
            return bytes
        } catch (e: Exception) {
            android.util.Log.e("RatatoskVM", "Failed to generate preview", e)
            return null
        }
    }

    /** Сессия закрыта: чужие файлы из прошлого аккаунта здесь не ждут. */
    fun reset() {
        _sharedDraft.value = null
        recordedWaveforms.clear()
    }
}
