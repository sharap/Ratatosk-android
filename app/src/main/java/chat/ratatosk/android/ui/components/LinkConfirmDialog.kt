package chat.ratatosk.android.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.ratatosk.android.R
import chat.ratatosk.android.util.inspectLink

/**
 * Куда ведёт ссылка — до перехода, а не после.
 *
 * Ссылку и её подпись пишет собеседник: `[сбербанк.рф](http://зло.example)`
 * выглядит как банк. Поэтому здесь показывается адрес, а не текст, и
 * названы странности пары — чужой хост в подписи, часть до `@`, punycode,
 * не-веб схема. Решает человек: перейти или скопировать и посмотреть
 * спокойно.
 *
 * @param shownText текст ссылки, каким его видно в сообщении.
 */
@Composable
fun LinkConfirmDialog(
    url: String,
    shownText: String,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onCopied: () -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    val looks = remember(url, shownText) { inspectLink(url, shownText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.link_open_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.link_leads_to),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = looks.host ?: stringResource(R.string.link_unknown_host),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                // Адрес целиком: в нём и прячут подмену.
                Text(
                    text = looks.url,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )

                val warnings = buildList {
                    looks.shownHost?.let { add(stringResource(R.string.link_warn_shown_host, it)) }
                    if (looks.hasUserInfo) {
                        add(stringResource(R.string.link_warn_userinfo, looks.host ?: ""))
                    }
                    if (looks.punycode) add(stringResource(R.string.link_warn_punycode))
                    if (!looks.web) add(stringResource(R.string.link_warn_scheme, looks.scheme ?: "—"))
                }
                if (warnings.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    warnings.forEach { warning ->
                        Text(
                            text = warning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onOpen(looks.url) }) {
                Text(stringResource(R.string.link_open))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(looks.url))
                onCopied()
                onDismiss()
            }) {
                Text(stringResource(R.string.link_copy))
            }
        },
    )
}
