package chat.ratatosk.android.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest

@Composable
fun Avatar(
    avatarBytes: ByteArray?,
    name: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 40.dp,
    shape: androidx.compose.ui.graphics.Shape = CircleShape,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    Surface(
        modifier = modifier.size(size),
        shape = shape,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        if (avatarBytes != null) {
            // Ключ кэша задаём сами. У Coil для сырого ByteArray ключ
            // выводится из самого объекта, то есть на каждый новый массив
            // (и после пересборки экрана) картинка декодируется заново —
            // аватарки успевали моргнуть. По содержимому ключ стабилен:
            // тот же аватар берётся из памяти, изменившийся — перечитывается.
            val cacheKey = remember(avatarBytes) {
                "avatar-${avatarBytes.size}-${avatarBytes.contentHashCode()}"
            }
            Image(
                painter = rememberAsyncImagePainter(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(avatarBytes)
                        .memoryCacheKey(cacheKey)
                        .diskCacheKey(cacheKey)
                        .build()
                ),
                contentDescription = name,
                modifier = Modifier.fillMaxSize().clip(shape),
                contentScale = ContentScale.Crop
            )
        } else if (icon != null) {
            Box(contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(
                    imageVector = icon,
                    contentDescription = name,
                    modifier = Modifier.size(size * 0.6f),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        } else {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = name.take(1).uppercase(),
                    style = if (size > 60.dp) MaterialTheme.typography.displayMedium else MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}
