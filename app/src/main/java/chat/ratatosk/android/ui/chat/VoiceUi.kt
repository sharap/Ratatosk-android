package chat.ratatosk.android.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.ratatosk.android.R
import chat.ratatosk.android.ui.RatatoskViewModel
import chat.ratatosk.android.util.VoiceFile
import chat.ratatosk.android.util.VoiceRecorder
import chat.ratatosk.android.util.Waveform
import kotlinx.coroutines.delay
import org.ratatosk.core.FfiFile

/** Выдано ли разрешение на микрофон прямо сейчас. */
fun hasAudioPermission(context: android.content.Context): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.RECORD_AUDIO,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

/** Длительность словами: 0:07, 1:23. */
fun formatVoiceDuration(ms: Long): String {
    val total = (ms / 1000).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

/**
 * Запись голосового: состояние, которое держит экран.
 *
 * Отдельной кнопки нет — запись начинается удержанием «отправить»,
 * поэтому состояние вынуто из кнопки: жест снаружи, а микрофон,
 * громкость и отправка здесь.
 */
class VoiceRecording(
    private val recorder: VoiceRecorder,
    private val onError: (String) -> Unit,
    private val onSend: (java.io.File, ByteArray?) -> Unit,
    private val texts: Texts,
) {
    class Texts(val unsupported: String, val tooShort: String)

    var isRecording by mutableStateOf(false)
        private set

    var elapsedMs by mutableStateOf(0L)
        private set

    /**
     * Палец увели достаточно далеко: отпускание **не** отправит.
     *
     * Компоуз про выход за границы кнопки молчит: `tryAwaitRelease`
     * возвращает «отпустили» и когда палец уехал на другой конец экрана.
     * Поэтому отмену считаем сами — по расстоянию, и человек видит её
     * до того, как отпустит.
     */
    var willCancel by mutableStateOf(false)
        private set

    fun dragged(distancePx: Float, thresholdPx: Float) {
        if (isRecording) willCancel = distancePx > thresholdPx
    }

    /** Начать запись; разрешение спрашивает экран — из него это виднее. */
    fun begin() {
        if (isRecording) return
        if (!VoiceRecorder.SUPPORTED || !recorder.start()) {
            onError(texts.unsupported)
            return
        }
        isRecording = true
        elapsedMs = 0
        willCancel = false
    }

    /** Снимает громкость и время; звать из цикла, пока идёт запись. */
    fun tick() {
        if (!isRecording) return
        recorder.sample()
        elapsedMs = recorder.elapsedMs()
    }

    /**
     * Закончить запись.
     *
     * @param send отправить записанное; `false` — человек передумал,
     *   и файл стирается, не долетев никуда.
     */
    fun finish(send: Boolean) {
        if (!isRecording) return
        val keep = send && !willCancel
        isRecording = false
        willCancel = false
        if (!keep) {
            recorder.cancel()
            return
        }
        val done = recorder.stop()
        if (done == null) onError(texts.tooShort) else onSend(done.file, Waveform.png(done.levels))
    }

    fun cancelIfRecording() {
        if (isRecording) {
            isRecording = false
            willCancel = false
            recorder.cancel()
        }
    }
}

/** Заводит запись для этого чата и следит за её временем. */
@Composable
fun rememberVoiceRecording(
    viewModel: RatatoskViewModel,
    chatId: ByteArray,
    onError: (String) -> Unit,
): VoiceRecording {
    val context = LocalContext.current
    val texts = VoiceRecording.Texts(
        unsupported = stringResource(R.string.voice_unsupported),
        tooShort = stringResource(R.string.voice_too_short),
    )
    val recording = remember(chatId.contentHashCode()) {
        VoiceRecording(
            recorder = VoiceRecorder(context),
            onError = onError,
            onSend = { file, waveform -> viewModel.sendVoice(chatId, file, waveform) },
            texts = texts,
        )
    }

    LaunchedEffect(recording.isRecording) {
        while (recording.isRecording) {
            recording.tick()
            delay(50)
        }
    }

    // Уход с экрана посреди записи не должен оставлять микрофон занятым.
    DisposableEffect(Unit) { onDispose { recording.cancelIfRecording() } }

    return recording
}

/** Полоса записи: сколько идёт и как её бросить. */
@Composable
fun VoiceRecordingBar(recording: VoiceRecording) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Icon(
                Icons.Default.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.voice_recording, formatVoiceDuration(recording.elapsedMs)),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (recording.willCancel) {
                    stringResource(R.string.voice_release_to_discard)
                } else {
                    stringResource(R.string.voice_release_to_send)
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (recording.willCancel) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                },
            )
        }
    }
}

/**
 * Голосовое в пузыре: волна, длительность и проигрывание.
 *
 * Волна берётся из превью вложения — оно приезжает **до** самого файла,
 * так что запись видно ещё до приёма. Слушать можно только принятое:
 * расшифрованную копию готовит ядро, и до неё файл надо сохранить.
 */
@Composable
fun VoiceBubble(
    viewModel: RatatoskViewModel,
    file: FfiFile,
    durationMs: Long,
    preview: ByteArray?,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    // Сколько проиграно: по нему считается остаток и ползёт полоса.
    var positionMs by remember { mutableStateOf(0L) }
    val unavailable = stringResource(R.string.file_unavailable)

    DisposableEffect(Unit) {
        onDispose {
            player?.release()
            player = null
        }
    }

    // Пока играет — показываем, сколько осталось. Иначе полоса стоит
    // на месте, и непонятно, играет ли вообще.
    LaunchedEffect(playing) {
        while (playing) {
            positionMs = player?.currentPosition?.toLong() ?: positionMs
            delay(200)
        }
    }

    fun play(path: String) {
        // Формат проверяем по сигнатуре, а не по имени: имя выбирает
        // отправитель, и «голосовым» он может назвать что угодно.
        if (!VoiceFile.looksLikeOgg(java.io.File(path))) {
            onError(unavailable)
            return
        }
        try {
            val media = android.media.MediaPlayer()
            media.setDataSource(path)
            media.setOnCompletionListener {
                playing = false
                positionMs = 0
                it.release()
                player = null
            }
            media.prepare()
            media.start()
            player = media
            playing = true
        } catch (t: Throwable) {
            android.util.Log.w("RatatoskVM", "Failed to play a voice message", t)
            onError(unavailable)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        when {
            preparing -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            playing -> IconButton(onClick = {
                player?.pause()
                playing = false
            }) {
                Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.voice_pause))
            }
            else -> IconButton(onClick = {
                val ready = player
                if (ready != null) {
                    ready.start()
                    playing = true
                    return@IconButton
                }
                preparing = true
                val dest = java.io.File(
                    java.io.File(context.cacheDir, "voice").apply { mkdirs() },
                    file.fileId.let { id -> id.joinToString("") { "%02x".format(it) } } + ".ogg",
                )
                if (dest.exists() && dest.length() == file.sizeBytes.toLong()) {
                    preparing = false
                    play(dest.absolutePath)
                } else {
                    viewModel.saveFile(file, dest) { saved ->
                        preparing = false
                        play(saved.absolutePath)
                    }
                }
            }) {
                Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.voice_play))
            }
        }

        Spacer(Modifier.width(4.dp))

        val wave = remember(preview?.contentHashCode()) {
            preview?.let {
                runCatching { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
            }
        }
        if (wave != null) {
            androidx.compose.foundation.layout.Column {
                Image(
                    bitmap = wave.asImageBitmap(),
                    contentDescription = stringResource(R.string.voice_message),
                    modifier = Modifier.height(28.dp).width(120.dp),
                )
                if (playing || positionMs > 0) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { (positionMs.toFloat() / durationMs.coerceAtLeast(1)).coerceIn(0f, 1f) },
                        modifier = Modifier.width(120.dp).height(2.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
        }
        Text(
            // Пока играет — остаток: человеку важно, сколько ещё слушать,
            // а не сколько было всего.
            text = if (playing || positionMs > 0) {
                "−" + formatVoiceDuration((durationMs - positionMs).coerceAtLeast(0))
            } else {
                formatVoiceDuration(durationMs)
            },
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
