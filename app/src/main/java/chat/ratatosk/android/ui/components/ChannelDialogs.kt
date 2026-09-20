package chat.ratatosk.android.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
 * Подписка по ссылке.
 *
 * Оба текста §15 — и с условием: порода канала до подписки неизвестна.
 * Ссылка ничем не подписана (§10.2), и выдать её обещание за установленное
 * нельзя; разбирать её тело у себя — значит повторять формат ядра
 * в клиенте. Поэтому человеку показывают оба последствия, а какое из них
 * сбудется, скажет уже ядро.
 *
 * @param uri заранее известная ссылка (пришли по ней снаружи); `null` —
 *   человек вставит её сам.
 */
@Composable
fun SubscribeChannelDialog(
    viewModel: RatatoskViewModel,
    uri: String? = null,
    onDismiss: () -> Unit,
) {
    var link by remember { mutableStateOf(uri.orEmpty()) }
    val openNotice = remember { viewModel.channelNotice(ChannelNotice.OPEN) }
    val privateNotice = remember { viewModel.channelNotice(ChannelNotice.PRIVATE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.channel_subscribe)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (uri == null) {
                    OutlinedTextField(
                        value = link,
                        onValueChange = { link = it },
                        label = { Text(stringResource(R.string.channel_subscribe_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                }
                Text(
                    text = stringResource(R.string.channel_subscribe_open_if),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(text = openNotice, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.channel_subscribe_private_if),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(text = privateNotice, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(
                onClick = { viewModel.subscribeToChannel(link.trim()); onDismiss() },
                enabled = link.isNotBlank(),
            ) { Text(stringResource(R.string.channel_subscribe_action)) }
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
