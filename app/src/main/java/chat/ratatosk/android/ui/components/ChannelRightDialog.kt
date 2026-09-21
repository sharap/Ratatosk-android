package chat.ratatosk.android.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import chat.ratatosk.android.util.toHexString
import org.ratatosk.core.FfiChannelRights

/** Сроки выдачи: месяц, три, год. «Без срока» здесь быть не может (§6.3). */
private enum class Term(val days: Long, val label: Int) {
    MONTH(30, R.string.channel_right_term_month),
    QUARTER(90, R.string.channel_right_term_quarter),
    YEAR(365, R.string.channel_right_term_year),
}

/**
 * Выдача и снятие прав в канале (§6.2, §6.3).
 *
 * Одно окно на то и другое: снятие — это выдача с пустым набором, потому
 * что список в новой версии представления **и есть** всё, что действует.
 *
 * Срок спрашивается всегда и «бессрочно» не предлагается: непродлённое
 * право истекает само, а право без срока означало бы отзыв — в рое он
 * не работает. Перед выдачей права «впускать» показан
 * `admitter_grant_notice`: впущенные останутся, даже если право снять.
 */
@Composable
fun ChannelRightDialog(
    viewModel: RatatoskViewModel,
    chatId: ByteArray,
    who: ByteArray,
    name: String,
    current: FfiChannelRights?,
    onDismiss: () -> Unit,
) {
    var write by remember { mutableStateOf(current?.write == true) }
    var admit by remember { mutableStateOf(current?.admit == true) }
    var evict by remember { mutableStateOf(current?.evict == true) }
    var edit by remember { mutableStateOf(current?.edit == true) }
    var term by remember { mutableStateOf(Term.MONTH) }
    val admitterNotice = remember { viewModel.channelNotice(ChannelNotice.ADMITTER_GRANT) }

    val nothing = !write && !admit && !evict && !edit

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CheckRow(write, { write = it }, R.string.channel_right_write)
                CheckRow(admit, { admit = it }, R.string.channel_right_admit)
                CheckRow(evict, { evict = it }, R.string.channel_right_evict)
                CheckRow(edit, { edit = it }, R.string.channel_right_edit)

                if (admit) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = admitterNotice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(16.dp))
                if (nothing) {
                    Text(
                        text = stringResource(R.string.channel_right_none),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(stringResource(R.string.channel_right_term), style = MaterialTheme.typography.labelLarge)
                    Column(Modifier.selectableGroup()) {
                        Term.entries.forEach { option ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = term == option, onClick = { term = option })
                                Text(stringResource(option.label))
                            }
                        }
                    }
                    Text(
                        text = stringResource(R.string.channel_right_term_required),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val until = if (nothing) {
                    // Снятие: набор пуст, срок роли не играет.
                    0UL
                } else {
                    (System.currentTimeMillis() + term.days * 24L * 60 * 60 * 1000).toULong()
                }
                viewModel.setChannelRight(
                    chatId,
                    who,
                    FfiChannelRights(write = write, admit = admit, evict = evict, edit = edit),
                    until,
                )
                onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun CheckRow(checked: Boolean, onChange: (Boolean) -> Unit, label: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(stringResource(label))
    }
}

/** Поворот ключа: кнопка называется последствием (§6.4, §15). */
@Composable
fun ChannelRotateDialog(
    notice: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.channel_rotate)) },
        text = { Text(notice) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(stringResource(R.string.channel_rotate))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Цена слова (§11): фильтр первого уровня, и сказано это прямо. */
@Composable
fun ChannelPowDialog(
    current: UInt,
    onConfirm: (UInt) -> Unit,
    onDismiss: () -> Unit,
) {
    var bits by remember { mutableStateOf(current.toString()) }
    val parsed = bits.toUIntOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.channel_pow_title)) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = bits,
                    onValueChange = { bits = it.filter { ch -> ch.isDigit() }.take(3) },
                    label = { Text(stringResource(R.string.channel_pow_hint)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.channel_pow_explain),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { parsed?.let { onConfirm(it) }; onDismiss() },
                enabled = parsed != null,
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * Обязательный текст §15 перед действием, у которого есть цена.
 *
 * Одно окно на все такие случаи: объявление себя сидом раскрывает адрес,
 * сужение круга отдачи платится не только тем, кто настраивал. Слова —
 * ядра, кнопка называет действие.
 */
@Composable
fun ChannelNoticeDialog(
    title: String,
    notice: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(notice) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * Впустить контакт в канал, не дожидаясь заявки (§6.5, §10.4).
 *
 * Ключ чтения запечатывается на карточку впускаемого — поэтому впустить
 * так можно только контакт, и список здесь из контактов. Ссылка при этом
 * никому не нужна: ядро само отдаёт поколение ключа и подписанную запись
 * о впуске.
 *
 * @param alreadyIn кого уже впустили: им это не нужно второй раз.
 */
@Composable
fun AdmitContactDialog(
    viewModel: RatatoskViewModel,
    chatId: ByteArray,
    alreadyIn: List<ByteArray>,
    onDismiss: () -> Unit,
) {
    val contacts by viewModel.contacts.collectAsState()
    val avatars by viewModel.contactAvatars.collectAsState()

    val available = remember(contacts, alreadyIn) {
        contacts.filterNot { contact -> alreadyIn.any { it.contentEquals(contact.peerIk) } }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.channel_admit_contact)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.channel_admit_contact_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                when {
                    contacts.isEmpty() -> Text(stringResource(R.string.channel_admit_no_contacts))
                    available.isEmpty() -> Text(stringResource(R.string.channel_admit_none_left))
                    else -> androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp)
                    ) {
                        items(available.size) { index ->
                            val contact = available[index]
                            val name = contact.localName ?: contact.displayName
                            androidx.compose.material3.ListItem(
                                headlineContent = { Text(name) },
                                leadingContent = {
                                    Avatar(
                                        avatarBytes = avatars[contact.peerIk.toHexString()]
                                            ?: viewModel.getAvatarOf(contact.peerIk),
                                        name = name,
                                    )
                                },
                                modifier = Modifier.clickable {
                                    viewModel.admitToChannel(chatId, contact.peerIk)
                                    onDismiss()
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
