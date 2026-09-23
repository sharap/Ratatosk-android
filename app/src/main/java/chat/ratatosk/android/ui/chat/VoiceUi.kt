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

/** Длительность словами: 0:07, 1:23. */
fun formatVoiceDuration(ms: Long): String {
    val total = (ms / 1000).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

/**
 * Кнопка записи и сама запись.
 *
 * Запись идёт в Ogg/Opus прямо с микрофона, волна снимается по ходу
 * (декодировать Opus ради картинки нечем), и отправляется она обычным
 * вложением: голосовое узнаётся по имени файла ([VoiceFile]).
 *
 * @param onError сказать человеку словами: разрешения нет, писать нечем,
 *   записать не успели.
 */
@Composable
fun VoiceRecordButton(
    viewModel: RatatoskViewModel,
    chatId: ByteArray,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val recorder = remember { VoiceRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableStateOf(0L) }

    val unsupported = stringResource(R.string.voice_unsupported)
    val needPermission = stringResource(R.string.voice_permission)
    val tooShort = stringResource(R.string.voice_too_short)

    fun begin() {
        if (!VoiceRecorder.SUPPORTED) {
            onError(unsupported)
            return
        }
        if (recorder.start()) {
            recording = true
        } else {
            onError(unsupported)
        }
    }

    val askPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) begin() else onError(needPermission) }

    // Пока идёт запись — снимаем громкость и показываем время.
    LaunchedEffect(recording) {
        while (recording) {
            recorder.sample()
            elapsedMs = recorder.elapsedMs()
            delay(50)
        }
    }

    // Уход с экрана посреди записи не должен оставлять микрофон занятым.
    DisposableEffect(Unit) {
        onDispose { if (recorder.isRecording) recorder.cancel() }
    }

    if (!recording) {
        IconButton(onClick = {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (granted) begin() else askPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        }) {
            Icon(Icons.Default.Mic, contentDescription = stringResource(R.string.voice_record))
        }
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {
            recorder.cancel()
            recording = false
        }) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.voice_cancel),
                tint = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = stringResource(R.string.voice_recording, formatVoiceDuration(elapsedMs)),
            style = MaterialTheme.typography.labelMedium,
        )
        IconButton(onClick = {
            recording = false
            val done = recorder.stop()
            if (done == null) {
                onError(tooShort)
            } else {
                viewModel.sendVoice(chatId, done.file, Waveform.png(done.levels))
            }
        }) {
            Icon(Icons.Default.Send, contentDescription = stringResource(R.string.voice_send))
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
    val unavailable = stringResource(R.string.file_unavailable)

    DisposableEffect(Unit) {
        onDispose {
            player?.release()
            player = null
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
            Image(
                bitmap = wave.asImageBitmap(),
                contentDescription = stringResource(R.string.voice_message),
                modifier = Modifier.height(28.dp).width(120.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(formatVoiceDuration(durationMs), style = MaterialTheme.typography.labelMedium)
    }
}
