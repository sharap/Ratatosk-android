package chat.ratatosk.android.ui.chat

import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import chat.ratatosk.android.R
import chat.ratatosk.android.util.VideoFile
import chat.ratatosk.android.util.VideoPoster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Запись видеосообщения: круглое окно, камера и одна кнопка.
 *
 * Отдельным окном, а не жестом на «отправить», как у голосового:
 * в кружок надо смотреть, и держать при этом палец на кнопке —
 * значит снимать себе палец. Здесь нажали — пишем, нажали ещё раз —
 * ушло.
 *
 * Круглым кадр делает показ, а не файл: внутри обычный MP4, который
 * откроется где угодно. Обрезать его по-настоящему значило бы отнять
 * у получателя то, чего он не просил лишаться.
 */
@Composable
fun VideoMessageDialog(
    onSend: (File, ByteArray?) -> Unit,
    onDismiss: () -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { ContextCompat.getMainExecutor(context) }

    var front by remember { mutableStateOf(true) }
    var capture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var startedAtMs by remember { mutableStateOf(0L) }
    var elapsedMs by remember { mutableStateOf(0L) }
    val failed = stringResource(R.string.video_failed)

    // Пока пишем — считаем время: у кружка есть потолок, и человек
    // должен видеть, сколько осталось, а не упираться в него молча.
    LaunchedEffect(recording != null) {
        while (recording != null) {
            elapsedMs = System.currentTimeMillis() - startedAtMs
            if (elapsedMs >= VideoFile.RECORD_LIMIT_MS) {
                recording?.stop()
                break
            }
            delay(100)
        }
    }

    // Камера, кадр и запись живут столько же, сколько окно: пересобирать
    // их на перерисовку значит переподключать камеру по кругу — раньше
    // это и делало кнопку «другая камера» бесполезной, а привязку заново
    // рвало саму запись.
    val previewView = remember {
        PreviewView(context).also { it.scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val preview = remember { Preview.Builder().build() }
    // SD, а не «как получится»: кружок смотрят размером с ладонь,
    // а ехать ему по сети, где каждый килобайт — чей-то трафик.
    val videoCapture = remember {
        VideoCapture.withOutput(
            Recorder.Builder()
                .setQualitySelector(QualitySelector.fromOrderedList(listOf(Quality.SD, Quality.HD)))
                .build()
        )
    }

    LaunchedEffect(front) {
        preview.setSurfaceProvider(previewView.surfaceProvider)
        val provider = try {
            withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(context).get() }
        } catch (t: Throwable) {
            android.util.Log.w("RatatoskVM", "Failed to get the camera provider", t)
            onError(failed)
            return@LaunchedEffect
        }
        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                videoCapture,
            )
            capture = videoCapture
        } catch (t: Throwable) {
            android.util.Log.w("RatatoskVM", "Failed to open the camera", t)
            onError(failed)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Окно закрыли посреди записи: файл дописывать некуда.
            recording?.close()
            recording = null
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }

    fun begin() {
        val videoCapture = capture ?: return
        val dir = File(context.cacheDir, "video").apply { mkdirs() }
        val file = File(dir, "recording-${System.currentTimeMillis()}.mp4")
        val started = try {
            videoCapture.output
                .prepareRecording(context, FileOutputOptions.Builder(file).build())
                .withAudioEnabled()
                .start(executor) { event ->
                    if (event is VideoRecordEvent.Finalize) {
                        recording = null
                        val durationMs = System.currentTimeMillis() - startedAtMs
                        // Слишком короткое — это промах по кнопке, а не запись.
                        if (event.hasError() || !file.exists() || file.length() == 0L || durationMs < 700) {
                            file.delete()
                            if (event.hasError()) onError(failed)
                            onDismiss()
                            return@start
                        }
                        // Длину берём из самого файла: часы и камера
                        // считают по-разному, а показывать будут её.
                        val exact = VideoPoster.durationMsOf(file) ?: durationMs
                        val named = File(file.parentFile, VideoFile.name(exact))
                        file.renameTo(named)
                        onSend(named, VideoPoster.of(named))
                        onDismiss()
                    }
                }
        } catch (t: Throwable) {
            android.util.Log.w("RatatoskVM", "Failed to record a video message", t)
            onError(failed)
            null
        }
        if (started != null) {
            startedAtMs = System.currentTimeMillis()
            elapsedMs = 0
            recording = started
        }
    }

    Dialog(
        onDismissRequest = { if (recording == null) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(color = MaterialTheme.colorScheme.scrim) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier.size(300.dp).clip(CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { previewView },
                    )
                }

                Text(
                    text = if (recording != null) {
                        formatVoiceDuration(elapsedMs) + " / " + formatVoiceDuration(VideoFile.RECORD_LIMIT_MS)
                    } else {
                        stringResource(R.string.video_message_hint)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(top = 16.dp),
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { recording?.close(); recording = null; onDismiss() }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.cancel),
                            tint = MaterialTheme.colorScheme.inverseOnSurface,
                        )
                    }
                    IconButton(
                        onClick = { if (recording == null) begin() else recording?.stop() },
                        modifier = Modifier.size(72.dp),
                    ) {
                        Icon(
                            if (recording == null) Icons.Default.FiberManualRecord else Icons.Default.Stop,
                            contentDescription = stringResource(
                                if (recording == null) R.string.video_record else R.string.video_stop
                            ),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(56.dp),
                        )
                    }
                    // Камеру меняем только до записи, и во время неё кнопки
                    // просто нет: CameraX держит запись на привязанной
                    // камере, и переключение обрывает файл. Мёртвая кнопка
                    // на экране выглядит поломкой, поэтому её не рисуем.
                    if (recording == null) {
                        IconButton(onClick = { front = !front }) {
                            Icon(
                                Icons.Default.Cameraswitch,
                                contentDescription = stringResource(R.string.video_flip),
                                tint = MaterialTheme.colorScheme.inverseOnSurface,
                            )
                        }
                    } else {
                        Spacer(Modifier.size(48.dp))
                    }
                }
            }
        }
    }
}
