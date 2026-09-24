package chat.ratatosk.android.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.ratatosk.android.R
import chat.ratatosk.android.ui.model.ChatNotify

/** Час и восемь часов в миллисекундах: сроки, которые предлагает экран. */
private const val HOUR_MS = 60L * 60L * 1000L

/**
 * Уведомления чата (§14): молчать или говорить, и до какого момента.
 *
 * Выбор человека держит ядро, и сюда он приезжает целиком — вместе
 * с ответом «говорить ли сейчас». Истёкший срок выбора не стирает:
 * «молчал до такого-то» и «не трогал» — разные вещи, и показываем мы
 * первое, а не молчание, которого уже нет.
 */
@Composable
fun ChatNotifyDialog(
    notify: ChatNotify,
    onChoose: (silent: Boolean, untilMs: ULong) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notify_settings)) },
        text = {
            Column {
                // Срок виден: «молчу» и «молчу до утра» — разные вещи,
                // и вторая кончится сама.
                if (notify.silent && notify.untilMs > 0UL) {
                    val until = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(notify.untilMs.toLong()))
                    Text(
                        text = if (notify.speaksNow) {
                            stringResource(R.string.notify_mute_expired)
                        } else {
                            stringResource(R.string.notify_muted_until, until)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }

                val now = System.currentTimeMillis()
                Choice(
                    text = stringResource(R.string.notify_speak),
                    selected = !notify.silent,
                    onClick = { onChoose(false, 0UL) },
                )
                Choice(
                    text = stringResource(R.string.notify_mute_hour),
                    selected = false,
                    onClick = { onChoose(true, (now + HOUR_MS).toULong()) },
                )
                Choice(
                    text = stringResource(R.string.notify_mute_8h),
                    selected = false,
                    onClick = { onChoose(true, (now + 8L * HOUR_MS).toULong()) },
                )
                Choice(
                    text = stringResource(R.string.notify_mute_always),
                    selected = notify.silent && notify.untilMs == 0UL,
                    onClick = { onChoose(true, 0UL) },
                )

                // Честно и до нажатия: §14 никому не обещал сообщать,
                // что его приглушили, — и не сообщает.
                Text(
                    text = stringResource(R.string.notify_local_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
