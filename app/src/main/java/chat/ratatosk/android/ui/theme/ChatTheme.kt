package chat.ratatosk.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class ChatThemeData(
    val backgroundImageUri: String? = null,
    val bubbleShape: Shape = RoundedCornerShape(16.dp),
    val incomingBubbleColor: Color = Color.Unspecified,
    val outgoingBubbleColor: Color = Color.Unspecified,
    val spacing: Dp = 8.dp
)

val LocalChatThemeData = staticCompositionLocalOf { ChatThemeData() }

@Composable
fun RatatoskChatTheme(
    data: ChatThemeData = ChatThemeData(
        incomingBubbleColor = MaterialTheme.colorScheme.surfaceVariant,
        outgoingBubbleColor = MaterialTheme.colorScheme.primaryContainer
    ),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalChatThemeData provides data) {
        content()
    }
}

object RatatoskChatTheme {
    val current: ChatThemeData
        @Composable
        @ReadOnlyComposable
        get() = LocalChatThemeData.current
}
