package chat.ratatosk.android.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.ratatosk.android.R

/**
 * Подтверждение ссылки `ratatosk:v0:…`.
 *
 * Добавлять по щелчку молча нельзя: ссылку человеку мог прислать кто
 * угодно и откуда угодно, а добавление контакта — это согласие завести
 * с ним канал.
 */
@Composable
fun AddContactByLinkDialog(onAdd: (Boolean) -> Unit, onDismiss: () -> Unit) {
    // Выключено по умолчанию — и это не осторожность ради осторожности.
    // Пришедшая ссылка личной встречей не является, а `met_in_person`
    // сразу засчитывает контакт сверенным (§4.2). Поставить галочку
    // человек волен, если ссылку ему дали в руки.
    var inPerson by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.link_add_contact_title)) },
        text = {
            Column {
                Text(stringResource(R.string.link_add_contact_body))
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = inPerson, onCheckedChange = { inPerson = it })
                    Text(
                        text = stringResource(R.string.met_in_person),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    text = stringResource(R.string.met_in_person_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        confirmButton = {
            Button(onClick = { onAdd(inPerson) }) { Text(stringResource(R.string.add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/**
 * Ссылка сопряжения пришла, когда аккаунт уже открыт.
 *
 * Терминалом становится устройство **вместо** своего аккаунта, а не
 * вдобавок к нему, поэтому предложить тут нечего — можно только честно
 * сказать, чего не хватает.
 */
@Composable
fun PairLinkBusyDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.link_pair_title)) },
        text = { Text(stringResource(R.string.link_pair_busy_body)) },
        confirmButton = {
            Button(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        }
    )
}
