package chat.ratatosk.android.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.ratatosk.android.R
import chat.ratatosk.android.util.Incoming
import chat.ratatosk.android.util.IncomingIntents

@Composable
fun AddContactDialog(onDismiss: () -> Unit, onAdd: (String, Boolean) -> Unit, onScan: () -> Unit) {
    var uri by remember { mutableStateOf("") }
    var inPerson by remember { mutableStateOf(true) }

    // Обе ссылки начинаются одинаково, и вставить сюда можно любую.
    // Ссылка сопряжения значит «стань моим терминалом», а не «добавь
    // меня»; отдать её в add_contact — получить невнятный отказ ядра
    // вместо понятного слова (FFI.md).
    val parsed = remember(uri) { IncomingIntents.link(uri) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_contact)) },
        text = {
            Column {
                OutlinedTextField(
                    value = uri,
                    onValueChange = { uri = it },
                    label = { Text(stringResource(R.string.ratatosk_uri)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("ratatosk:v0:...") }
                )
                if (parsed is Incoming.PairDevice) {
                    Text(
                        text = stringResource(R.string.link_pair_not_contact),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = onScan,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.QrCode, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.scan_qr))
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = inPerson, onCheckedChange = { inPerson = it })
                    Text(stringResource(R.string.met_in_person), style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    text = stringResource(R.string.met_in_person_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(uri, inPerson) },
                enabled = parsed is Incoming.AddContact
            ) {
                Text(stringResource(R.string.add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
