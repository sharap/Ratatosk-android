package chat.ratatosk.android.ui.media

import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import chat.ratatosk.android.ui.RatatoskViewModel
import chat.ratatosk.android.util.FileUtils
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaViewerScreen(
    viewModel: RatatoskViewModel,
    onClose: () -> Unit
) {
    val activeFile by viewModel.activeMediaFile.collectAsState()
    val exportedPath by viewModel.mediaExportedPath.collectAsState()
    val context = LocalContext.current
    
    if (activeFile == null) return

    BackHandler {
        onClose()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (exportedPath == null) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = Color.White)
                Spacer(modifier = Modifier.height(16.dp))
                Text("Decrypting ${activeFile?.name}...", color = Color.White)
            }
        } else {
            val path = exportedPath!!
            val isImage = FileUtils.isImage(activeFile!!.name)
            val isVideo = FileUtils.isVideo(activeFile!!.name)
            val isAudio = FileUtils.isAudio(activeFile!!.name)

            if (isImage) {
                ImageViewer(path)
            } else if (isVideo) {
                VideoViewer(path)
            } else if (isAudio) {
                AudioViewer(path, activeFile!!.name)
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Unsupported media type", color = Color.White)
                }
            }
        }

        // Top Bar
        TopAppBar(
            title = {
                Text(
                    text = activeFile?.name ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
            },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            },
            actions = {
                IconButton(onClick = {
                    exportedPath?.let { path ->
                        val file = java.io.File(path)
                        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = FileUtils.getMimeType(activeFile!!.name)
                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(android.content.Intent.createChooser(intent, "Share Media"))
                    }
                }) {
                    Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Black.copy(alpha = 0.5f),
                titleContentColor = Color.White
            )
        )
    }
}

@Composable
fun ImageViewer(path: String) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset += pan
                }
            }
    ) {
        AsyncImage(
            model = path,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                ),
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
fun VideoViewer(path: String) {
    AndroidView(
        factory = { context ->
            VideoView(context).apply {
                val mc = MediaController(context)
                mc.setAnchorView(this)
                setMediaController(mc)
                setVideoPath(path)
                start()
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
fun AudioViewer(path: String, name: String) {
    // Basic audio player UI
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Audiotrack,
            contentDescription = null,
            modifier = Modifier.size(128.dp),
            tint = Color.White
        )
        Spacer(modifier = Modifier.height(32.dp))
        Text(name, color = Color.White, style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(32.dp))
        
        AndroidView(
            factory = { context ->
                VideoView(context).apply {
                    val mc = MediaController(context)
                    mc.setAnchorView(this)
                    setMediaController(mc)
                    setVideoPath(path)
                    start()
                }
            },
            modifier = Modifier.height(64.dp).fillMaxWidth()
        )
    }
}
