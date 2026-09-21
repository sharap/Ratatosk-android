package chat.ratatosk.android.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import chat.ratatosk.android.R
import chat.ratatosk.android.ui.RatatoskViewModel
import chat.ratatosk.android.ui.model.ChannelNotice
import qrcode.QRCode

/**
 * Ссылка на канал: текстом и кодом.
 *
 * Собирается **в момент показа** и запросом, а не берётся из списка:
 * в неё едут нынешняя версия представления и наши адреса, и лежащая
 * в поле она устаревала бы молча (§10.2). Перед показом — `sharing_notice`:
 * в ссылку попадает наш адрес, и узнает его всякий, к кому она попадёт
 * дальше, даже если сам подписываться не станет.
 *
 * У открытого канала сказано и второе: в ссылке едет ключ чтения (§10.1).
 * Сокращать такую ссылку сторонним сервисом нельзя — ключ уедет
 * сокращателю; поэтому «отправить» отдаёт её как есть.
 *
 * @param open порода канала; `null` — представление ещё не приехало.
 */
@Composable
fun ChannelLinkDialog(
    viewModel: RatatoskViewModel,
    chatId: ByteArray,
    open: Boolean?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var link by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val notice = remember { viewModel.channelNotice(ChannelNotice.SHARING) }

    LaunchedEffect(chatId.contentHashCode()) {
        viewModel.channelLink(chatId) { result ->
            result.onSuccess { link = it }.onFailure { failed = true }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.channel_link)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(notice, style = MaterialTheme.typography.bodySmall)
                if (open == true) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.channel_link_open_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(16.dp))

                val shown = link
                when {
                    failed -> Text(
                        text = stringResource(R.string.channel_link_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                    shown == null -> CircularProgressIndicator()
                    else -> {
                        val qr = remember(shown) {
                            runCatching { QRCode(shown).render().nativeImage() as? android.graphics.Bitmap }
                                .getOrNull()
                        }
                        if (qr != null) {
                            Image(
                                bitmap = qr.asImageBitmap(),
                                contentDescription = stringResource(R.string.channel_link),
                                modifier = Modifier.size(220.dp).background(Color.White).padding(8.dp),
                            )
                        } else {
                            Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.error_qr), color = MaterialTheme.colorScheme.error)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        SelectionContainer {
                            Text(
                                text = shown,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            val shown = link
            if (shown != null) {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(shown))
                    onDismiss()
                }) { Text(stringResource(R.string.copy)) }
            }
        },
        dismissButton = {
            val shown = link
            if (shown != null) {
                TextButton(onClick = {
                    // Отдаём как есть: сокращать ссылку открытого канала
                    // сторонним сервисом нельзя — ключ уедет сокращателю.
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, shown)
                    }
                    runCatching {
                        context.startActivity(
                            android.content.Intent.createChooser(send, context.getString(R.string.channel_link_share))
                        )
                    }
                    onDismiss()
                }) { Text(stringResource(R.string.channel_link_share)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
