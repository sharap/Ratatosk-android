package chat.ratatosk.android.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.ratatosk.android.R
import chat.ratatosk.android.ui.RatatoskViewModel
import chat.ratatosk.android.ui.model.ChannelNotice

/**
 * Заведение канала.
 *
 * Порода выбирается один раз и не меняется: «открытый» и «по приглашению» —
 * два разных обещания (§6.1). Для открытого текст §15 обязателен и показан
 * до кнопки: ключ чтения лежит в самой ссылке, и закрыть доступ обратно
 * нельзя никогда. Заводящему канал по приглашению говорить нечего — такого
 * текста в §15 нет, и придумывать его клиенту нельзя.
 */
@Composable
fun CreateChannelDialog(
    viewModel: RatatoskViewModel,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(false) }
    val openNotice = remember { viewModel.channelNotice(ChannelNotice.OPEN) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_channel)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.channel_title_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.channel_kind), style = MaterialTheme.typography.labelLarge)
                Column(Modifier.selectableGroup()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !open, onClick = { open = false })
                        Text(stringResource(R.string.channel_kind_private))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = open, onClick = { open = true })
                        Text(stringResource(R.string.channel_kind_open))
                    }
                }
                if (open) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = openNotice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { viewModel.createChannel(title.trim(), open); onDismiss() },
                enabled = title.isNotBlank(),
            ) { Text(stringResource(R.string.create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * Подписка по ссылке — через предпросмотр.
 *
 * Сначала спрашиваем у владельца документ (§10.3, шаг 5): он приходит
 * подписанным, и из него видно название, породу и цену слова. До этого
 * порода — только обещание ссылки, которому верить нельзя (§10.2),
 * поэтому раньше здесь стояли оба текста §15 с условием.
 *
 * Предпросмотр не бесплатен, и об этом сказано **до** него: владелец
 * узнает, что кто-то интересуется каналом, даже если человек потом
 * откажется. Отменить это задним числом нечем.
 *
 * Ответа может и не быть — и это не отказ: ждём, а не объявляем тупик.
 * На такой случай остаётся «подписаться, не глядя» с обоими текстами.
 */
@Composable
fun SubscribeChannelDialog(
    viewModel: RatatoskViewModel,
    uri: String? = null,
    onDismiss: () -> Unit,
) {
    var link by remember { mutableStateOf(uri.orEmpty()) }
    var asked by remember { mutableStateOf(false) }
    var blindly by remember { mutableStateOf(false) }
    val preview by viewModel.channelPreview.collectAsState()

    val previewNotice = remember { viewModel.channelNotice(ChannelNotice.PREVIEW) }
    val openNotice = remember { viewModel.channelNotice(ChannelNotice.OPEN) }
    val privateNotice = remember { viewModel.channelNotice(ChannelNotice.PRIVATE) }
    val slowPathNotice = remember { viewModel.channelNotice(ChannelNotice.SLOW_PATH) }

    // Закрыли окно — забываем ответ: он про эту ссылку, а не про все.
    DisposableEffect(Unit) { onDispose { viewModel.clearChannelPreview() } }

    val shown = preview

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.channel_subscribe)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (uri == null && shown == null) {
                    OutlinedTextField(
                        value = link,
                        onValueChange = { link = it },
                        label = { Text(stringResource(R.string.channel_subscribe_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !asked,
                    )
                    Spacer(Modifier.height(12.dp))
                }

                when {
                    // Ответ пришёл: показываем то, что подписано владельцем,
                    // и ровно один текст §15 — по настоящей породе.
                    shown != null -> {
                        Text(shown.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = stringResource(
                                if (shown.open) R.string.channel_kind_open_short
                                else R.string.channel_kind_private_short
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (shown.powBits > 0u) {
                            Text(
                                text = stringResource(R.string.channel_preview_pow, shown.powBits.toInt()),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = if (shown.open) openNotice else privateNotice,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    // Спросили и ждём. Молчание — не тупик (§10.5).
                    asked -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.channel_opening),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(slowPathNotice, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                    // Ещё не спрашивали: цена предпросмотра — до кнопки.
                    blindly -> {
                        Text(stringResource(R.string.channel_subscribe_open_if), style = MaterialTheme.typography.labelMedium)
                        Text(openNotice, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.channel_subscribe_private_if), style = MaterialTheme.typography.labelMedium)
                        Text(privateNotice, style = MaterialTheme.typography.bodySmall)
                    }
                    else -> Text(previewNotice, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            when {
                shown != null || blindly -> Button(
                    onClick = { viewModel.subscribeToChannel(link.trim()); onDismiss() },
                    enabled = link.isNotBlank(),
                ) { Text(stringResource(R.string.channel_subscribe_action)) }
                asked -> Button(onClick = { blindly = true }) {
                    Text(stringResource(R.string.channel_subscribe_anyway))
                }
                else -> Button(
                    onClick = { asked = true; viewModel.previewChannel(link.trim()) },
                    enabled = link.isNotBlank(),
                ) { Text(stringResource(R.string.channel_preview)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Отписка: уносит и архив, и об этом сказано до кнопки (§10.6). */
@Composable
fun UnsubscribeChannelDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.channel_unsubscribe)) },
        text = { Text(stringResource(R.string.channel_unsubscribe_warning)) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(stringResource(R.string.channel_unsubscribe))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
