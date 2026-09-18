package chat.ratatosk.android.util

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle

/**
 * Что нам прислали снаружи: «поделиться» из чужого приложения или
 * ссылку `ratatosk:`.
 */
sealed interface Incoming {
    /**
     * «Поделиться». Подпись и вложения; пустым не бывает ни то, ни другое
     * одновременно — такой разбор мы просто отбрасываем.
     */
    data class Share(val text: String, val uris: List<Uri>) : Incoming

    /** `ratatosk:v0:…` — «добавь меня в контакты» (§4.2). */
    data class AddContact(val uri: String) : Incoming

    /** `ratatosk:v0:pair:…` — «стань моим терминалом» (§13.3). */
    data class PairDevice(val uri: String) : Incoming
}

object IncomingIntents {
    private const val SCHEME = "ratatosk:"

    // Ссылка сопряжения начинается с того же `ratatosk:v0:`, что и
    // контактная, поэтому проверяется первой. Путать их нельзя: одна
    // значит «добавь меня в контакты», другая — «стань моим терминалом»
    // (FFI.md). Порядок проверок здесь и есть всё различие.
    private const val PAIR_PREFIX = "ratatosk:v0:pair:"

    fun parse(intent: Intent?): Incoming? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_SEND -> share(intent, streams(intent, multiple = false))
            Intent.ACTION_SEND_MULTIPLE -> share(intent, streams(intent, multiple = true))
            Intent.ACTION_VIEW -> link(intent.dataString)
            else -> null
        }
    }

    /**
     * Разбирает ссылку. Годится и для строки из буфера обмена — не только
     * для той, по которой щёлкнули.
     */
    fun link(raw: String?): Incoming? {
        val uri = raw?.trim().orEmpty()
        // Схему система приводит к нижнему регистру, а набранное руками
        // могло прийти как угодно; остальное — тело ссылки, и его регистр
        // трогать нельзя.
        if (!uri.startsWith(SCHEME, ignoreCase = true)) return null
        if (uri.length == SCHEME.length) return null
        return if (uri.startsWith(PAIR_PREFIX, ignoreCase = true)) {
            Incoming.PairDevice(uri)
        } else {
            Incoming.AddContact(uri)
        }
    }

    /**
     * Кладёт ещё не разобранное намерение в состояние активности.
     *
     * Поворот экрана пересоздаёт активность с тем же `Intent`, и разбери
     * мы его заново — только что закрытый диалог открылся бы снова, а
     * выбранный чат получил бы вложения дважды. Поэтому после поворота
     * восстанавливаем то, что осталось, а исходный `Intent` не трогаем.
     */
    fun save(state: Bundle, incoming: Incoming?) {
        when (incoming) {
            null -> state.remove(KEY_KIND)
            is Incoming.AddContact -> {
                state.putString(KEY_KIND, KIND_ADD)
                state.putString(KEY_URI, incoming.uri)
            }
            is Incoming.PairDevice -> {
                state.putString(KEY_KIND, KIND_PAIR)
                state.putString(KEY_URI, incoming.uri)
            }
            is Incoming.Share -> {
                state.putString(KEY_KIND, KIND_SHARE)
                state.putString(KEY_TEXT, incoming.text)
                state.putStringArrayList(
                    KEY_STREAMS,
                    ArrayList(incoming.uris.map { it.toString() })
                )
            }
        }
    }

    fun restore(state: Bundle?): Incoming? {
        state ?: return null
        return when (state.getString(KEY_KIND)) {
            KIND_ADD -> state.getString(KEY_URI)?.let { Incoming.AddContact(it) }
            KIND_PAIR -> state.getString(KEY_URI)?.let { Incoming.PairDevice(it) }
            KIND_SHARE -> Incoming.Share(
                text = state.getString(KEY_TEXT).orEmpty(),
                uris = state.getStringArrayList(KEY_STREAMS)?.map { Uri.parse(it) } ?: emptyList()
            )
            else -> null
        }
    }

    private const val KEY_KIND = "ratatosk.incoming.kind"
    private const val KEY_URI = "ratatosk.incoming.uri"
    private const val KEY_TEXT = "ratatosk.incoming.text"
    private const val KEY_STREAMS = "ratatosk.incoming.streams"
    private const val KIND_ADD = "add"
    private const val KIND_PAIR = "pair"
    private const val KIND_SHARE = "share"

    private fun share(intent: Intent, uris: List<Uri>): Incoming? {
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        // Делиться пустотой нечем: показывать выбор чата, чтобы потом
        // отправить ничего, — это тупик.
        if (text.isBlank() && uris.isEmpty()) return null
        return Incoming.Share(text, uris)
    }

    @Suppress("DEPRECATION")
    private fun streams(intent: Intent, multiple: Boolean): List<Uri> {
        val new = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        return if (multiple) {
            if (new) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        } else {
            val one =
                if (new) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            one?.let { listOf(it) }
        } ?: emptyList()
    }
}
