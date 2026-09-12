package chat.ratatosk.android.ui.profile

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import chat.ratatosk.android.R
import chat.ratatosk.android.ui.RatatoskViewModel
import chat.ratatosk.android.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvatarCropScreen(
    viewModel: RatatoskViewModel,
    onDone: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val uri by viewModel.pendingAvatarUri.collectAsState()
    val maxBytes by viewModel.maxAvatarBytes.collectAsState()
    
    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    // Отдельно от «ещё грузится»: без этого неудача выглядела бы тем же
    // бесконечным кружком, что и загрузка, — и выглядела, пока loadBitmap
    // отдавал null на каждой картинке.
    var loadFailed by remember { mutableStateOf(false) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var scale by remember { mutableStateOf(1f) }
    var containerSize by remember { mutableStateOf(Size.Zero) }

    LaunchedEffect(uri) {
        uri?.let {
            sourceBitmap = null
            loadFailed = false
            // Разбор снимка — не на главном потоке: даже прореженный он
            // занимает десятки миллисекунд, а на большом файле заметно
            // подвешивал бы экран.
            val loaded = withContext(Dispatchers.IO) { ImageUtils.loadBitmap(context, it) }
            sourceBitmap = loaded
            loadFailed = loaded == null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Crop Avatar") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            sourceBitmap?.let { bitmap ->
                                val drawWidth = containerSize.width * scale
                                val drawHeight = (containerSize.width / (bitmap.width.toFloat() / bitmap.height.toFloat())) * scale
                                val squareSize = containerSize.width * 0.8f
                                
                                val centerX = containerSize.width / 2
                                val centerY = containerSize.height / 2
                                
                                val imageTLX = centerX - drawWidth / 2 + offset.x
                                val imageTLY = centerY - drawHeight / 2 + offset.y
                                
                                val squareTLX = centerX - squareSize / 2
                                val squareTLY = centerY - squareSize / 2
                                
                                val relX = (squareTLX - imageTLX) / drawWidth
                                val relY = (squareTLY - imageTLY) / drawHeight
                                val relSizeX = squareSize / drawWidth
                                val relSizeY = squareSize / drawHeight
                                
                                val cropped = ImageUtils.cropAndResize(bitmap, relX, relY, relSizeX)
                                
                                val bytes = ImageUtils.compressToWebp(cropped, maxBytes)
                                val pendingChatId = viewModel.pendingAvatarChatId.value
                                if (pendingChatId != null) {
                                    viewModel.setGroupAvatar(pendingChatId, bytes)
                                } else {
                                    viewModel.setAvatar(bytes)
                                }
                                viewModel.setPendingAvatarUri(null, null)
                                onDone()
                            }
                        },
                        enabled = sourceBitmap != null
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "Done")
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(Color.Black)
                .onGloballyPositioned { containerSize = it.size.toSize() },
            contentAlignment = Alignment.Center
        ) {
            sourceBitmap?.let { bitmap ->
                val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
                
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale *= zoom
                                offset += pan
                            }
                        }
                ) {
                    val drawWidth = size.width * scale
                    val drawHeight = (size.width / (bitmap.width.toFloat() / bitmap.height.toFloat())) * scale
                    
                    drawImage(
                        image = imageBitmap,
                        dstOffset = androidx.compose.ui.unit.IntOffset(
                            (center.x - drawWidth / 2 + offset.x).toInt(),
                            (center.y - drawHeight / 2 + offset.y).toInt()
                        ),
                        dstSize = androidx.compose.ui.unit.IntSize(drawWidth.toInt(), drawHeight.toInt())
                    )

                    // Crop overlay
                    val squareSize = size.width * 0.8f
                    drawRect(
                        color = Color.White,
                        topLeft = Offset(center.x - squareSize / 2, center.y - squareSize / 2),
                        size = Size(squareSize, squareSize),
                        style = Stroke(width = 2.dp.toPx())
                    )
                    
                    // Dim outside
                    // (Omitted for simplicity, but good for UX)
                }
            } ?: if (loadFailed) {
                Text(
                    text = stringResource(R.string.avatar_load_failed),
                    color = Color.White
                )
            } else {
                CircularProgressIndicator()
            }
        }
    }
}
