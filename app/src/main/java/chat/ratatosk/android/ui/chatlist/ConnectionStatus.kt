package chat.ratatosk.android.ui.chatlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.ratatosk.android.R
import chat.ratatosk.android.core.BuiltTransports
import chat.ratatosk.android.ui.theme.successColor
import org.ratatosk.core.FfiTransport

/**
 * Что со связью прямо сейчас — одной строкой над списком чатов.
 *
 * # Почему перечисление ступеней, а не «в сети»
 *
 * «В сети» — обещание, которого §14 не разрешает: включённая ступень
 * и работающая ступень это разные вещи, и человек, прочитавший «в сети»
 * при поднятом, но ещё не забутстрапленном Tor, решил бы, что сообщение
 * уехало. Поэтому здесь названо то, что **работает** (`transport_ready`),
 * и ничего больше.
 *
 * # Почему «ни одна не работает» — не ошибка
 *
 * Это обычное состояние только что заведённого аккаунта: локальная сеть
 * выключена по умолчанию (§5.1), Tor поднимается десятки секунд. Поэтому
 * строка зовёт настроить сеть, а не пугает красным: красным она
 * становится лишь тогда, когда человек что-то включил, а не поднялось
 * ничего.
 */
data class ConnectionState(
    /** Ступени, которые работают прямо сейчас. */
    val working: List<FfiTransport>,
    /** Ступени, которые человек включил. */
    val enabled: List<FfiTransport>,
    /** Tor ещё поднимается: его доля от нуля до единицы. */
    val torRising: Float?,
)

/** Короткое имя ступени — для строки, а не для экрана настроек. */
@Composable
private fun shortName(transport: FfiTransport): String = stringResource(
    when (transport) {
        FfiTransport.LAN -> R.string.status_rung_lan
        FfiTransport.BT -> R.string.status_rung_bt
        FfiTransport.YGG -> R.string.status_rung_ygg
        FfiTransport.ONION -> R.string.status_rung_tor
        FfiTransport.NOSTR -> R.string.status_rung_nostr
        FfiTransport.MAIL -> R.string.status_rung_mail
    }
)

/**
 * Полоска состояния связи.
 *
 * Нажатие ведёт в настройки ступеней: человек, увидевший «ничего
 * не работает», хочет не сообщение об этом, а место, где это чинится.
 */
@Composable
fun ConnectionStatusBar(
    state: ConnectionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Порядок §5.4 — тот, в каком ядро пробует ступени; перечислять их
    // в другом порядке значило бы рассказывать не про лестницу.
    val order = listOf(
        FfiTransport.LAN,
        FfiTransport.BT,
        FfiTransport.YGG,
        FfiTransport.ONION,
        FfiTransport.NOSTR,
        FfiTransport.MAIL,
    ).filter { it in BuiltTransports }
    val working = order.filter { it in state.working }
    val names = working.map { shortName(it) }

    val rising = state.torRising
    val (dot, text) = when {
        names.isNotEmpty() -> successColor to stringResource(
            R.string.status_working,
            names.joinToString(", "),
        )
        rising != null -> MaterialTheme.colorScheme.primary to stringResource(
            R.string.status_tor_rising,
            (rising * 100).toInt(),
        )
        // Человек ничего не включил: это не поломка, а несделанная
        // настройка, и звать надо её, а не чинить связь.
        state.enabled.isEmpty() -> MaterialTheme.colorScheme.outline to
            stringResource(R.string.status_nothing_enabled)
        else -> MaterialTheme.colorScheme.error to stringResource(R.string.status_nothing_works)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Surface(modifier = Modifier.size(8.dp), shape = CircleShape, color = dot) {}
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
