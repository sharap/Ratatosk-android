package chat.ratatosk.android.ui.chat

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import chat.ratatosk.android.R
import chat.ratatosk.android.ui.RatatoskViewModel
import chat.ratatosk.android.util.VideoFile
import chat.ratatosk.android.util.VideoPoster
import kotlinx.coroutines.delay
import org.ratatosk.core.FfiFile

/** Выданы ли прямо сейчас камера и микрофон: кружку нужны оба. */
fun hasVideoPermissions(context: android.content.Context): Boolean =
    listOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO).all {
        androidx.core.content.ContextCompat.checkSelfPermission(context, it) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

/**
 * Видеосообщение: кружок, который играет по нажатию.
 *
 * Круглым его делает показ, а не файл: внутри обычный MP4. Обложка —
 * превью вложения, то самое, что ядро везёт вместе с предложением
 * файла: благодаря ей кружок виден **до** приёма, а не серым пятном.
 *
 * Формат проверяется по сигнатуре, а не по имени: «видеосообщением»
 * отправитель волен назвать что угодно, а показываем мы это в своём
 * окне.
 */
@Composable
fun VideoBubble(
    viewModel: RatatoskViewModel,
    file: FfiFile,
    durationMs: Long,
    preview: ByteArray?,
    /** Сколько кружка уже приехало; `null` — смотреть можно, приём ни при чём. */
    receiveFraction: Float? = null,
    /** Сколько ушло, если кружок наш и ещё едет. */
    sendFraction: Float? = null,
    /** Почему передача стоит — словами ядра; `null` — не стоит. */
    waitingText: String? = null,
    onAccept: () -> Unit = {},
    onError: (String) -> Unit,
) {
    // Смотреть можно принятое: до этого файла на диске нет.
    val ready = receiveFraction == null
    val context = LocalContext.current
    var player by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var surface by remember { mutableStateOf<Surface?>(null) }
    var playing by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) }
    // Длину знает сам файл; имя — только обещание отправителя.
    var actualMs by remember { mutableStateOf(durationMs) }
    val unavailable = stringResource(R.string.file_unavailable)

    DisposableEffect(Unit) {
        onDispose {
            player?.release()
            player = null
            surface?.release()
            surface = null
        }
    }

    LaunchedEffect(playing) {
        while (playing) {
            positionMs = player?.currentPosition?.toLong() ?: positionMs
            delay(100)
        }
    }

    fun start() {
        val ready = player
        if (ready != null) {
            // Доигравшее начинаем сначала: иначе кнопка «смотреть»
            // после конца записи не делает ничего.
            if (positionMs >= actualMs - 150) {
                ready.seekTo(0)
                positionMs = 0
            }
            ready.start()
            playing = true
            return
        }

        preparing = true
        val dest = java.io.File(
            java.io.File(context.cacheDir, "video").apply { mkdirs() },
            file.fileId.joinToString("") { "%02x".format(it) } + ".mp4",
        )

        fun open(path: String) {
            preparing = false
            if (!VideoFile.looksLikeMp4(java.io.File(path))) {
                onError(unavailable)
                return
            }
            try {
                val media = android.media.MediaPlayer()
                media.setDataSource(path)
                surface?.let { media.setSurface(it) }
                media.setOnCompletionListener {
                    playing = false
                    positionMs = actualMs
                }
                media.prepare()
                media.duration.takeIf { it > 0 }?.let { actualMs = it.toLong() }
                media.start()
                playing = true
                player = media
            } catch (t: Throwable) {
                android.util.Log.w("RatatoskVM", "Failed to play a video message", t)
                onError(unavailable)
            }
        }

        if (dest.exists() && dest.length() == file.sizeBytes.toLong()) {
            open(dest.absolutePath)
        } else {
            viewModel.saveFile(file, dest) { saved -> open(saved.absolutePath) }
        }
    }

    val poster = remember(preview?.contentHashCode()) { VideoPoster.bitmapOf(preview) }
    val fraction = if (actualMs > 0) (positionMs.toFloat() / actualMs).coerceIn(0f, 1f) else 0f

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 4.dp)) {
        Box(
            modifier = Modifier
                .size(220.dp)
                .clip(CircleShape)
                .background(Color.Black)
                .clickable {
                    // Нечего смотреть — значит нажатие про приём: кружок
                    // сам и есть кнопка «принять», второй рядом не нужно.
                    if (!ready) {
                        if (!file.accepted) onAccept()
                    } else if (playing) {
                        player?.pause()
                        playing = false
                    } else {
                        start()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    TextureView(ctx).also { view ->
                        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                                val created = Surface(texture)
                                surface = created
                                // Кадр мог приехать позже проигрывателя:
                                // без этого первое видео играло бы звуком
                                // по чёрному кружку.
                                player?.setSurface(created)
                            }

                            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}

                            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                surface = null
                                return true
                            }

                            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
                        }
                    }
                },
            )

            // Пока не играет — обложка: чёрный кружок не говорит ничего.
            if (!playing && poster != null) {
                Image(
                    bitmap = poster.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            when {
                preparing -> CircularProgressIndicator(modifier = Modifier.size(48.dp), color = Color.White)
                // Ещё не принято: видно, что делать — нажать.
                !ready && !file.accepted -> Icon(
                    Icons.Default.Download,
                    contentDescription = stringResource(R.string.accept),
                    tint = Color.White,
                    modifier = Modifier.size(56.dp),
                )
                // Принято и едет: доля словами, потому что кольцо на
                // четверти круга на глаз от половины не отличить.
                !ready -> Text(
                    text = "${((receiveFraction ?: 0f) * 100).toInt()}%",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                !playing -> Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.video_play),
                    tint = Color.White,
                    modifier = Modifier.size(56.dp),
                )
            }

            // Кольцо по краю кружка — одно на всё: сколько приехало, сколько
            // ушло, сколько проиграно. Полоса под круглым кадром выглядела бы
            // приделанной сбоку, а три полосы — тем более.
            val ring = when {
                !ready -> receiveFraction
                sendFraction != null -> sendFraction
                playing || positionMs > 0 -> fraction
                else -> null
            }
            if (ring != null) {
                CircularProgressIndicator(
                    progress = { ring.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.White.copy(alpha = 0.25f),
                    strokeWidth = 3.dp,
                )
            }
        }

        Text(
            text = if (ready) {
                formatVoiceDuration(
                    if (playing || positionMs > 0) (actualMs - positionMs).coerceAtLeast(0) else actualMs
                )
            } else {
                // Длительность известна из имени ещё до файла — она и
                // говорит, сколько ждать, а не только «сколько байт».
                stringResource(R.string.video_message) + ", " + formatVoiceDuration(durationMs)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        // Стоит не «просто так»: причину знает ядро, и словами её говорит оно.
        waitingText?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
