package chat.ratatosk.android.ui.unlock

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.ratatosk.android.R
import kotlinx.coroutines.delay

/**
 * Пока реестр личностей не ответил.
 *
 * Этот экран стоит там, где раньше показывалось «Создать личность»:
 * до ответа реестра список пуст, и пустота значила не «личностей нет»,
 * а «мы ещё не спрашивали». Человеку с заведённой личностью предлагали
 * завести её заново — на быстром телефоне на полсекунды, на медленном
 * заметно дольше.
 *
 * Ожидание показывается не сразу: обычно реестр отвечает быстрее, чем
 * человек успеет что-то прочесть, и мелькнувшее колесо — такая же
 * неопрятность, как мелькнувшее приглашение.
 */
@Composable
fun StartupScreen() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(PATIENCE_MS)
        visible = true
    }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedVisibility(visible = visible, enter = fadeIn()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text(
                    text = stringResource(R.string.startup_looking),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}

/** Сколько ждать молча, прежде чем показать, что мы заняты. */
private const val PATIENCE_MS = 400L
