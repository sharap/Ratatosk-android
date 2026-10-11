package chat.ratatosk.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.ratatosk.android.R
import chat.ratatosk.android.ui.model.PeerListStep

/**
 * Публичный список пиров меша: принести и выбрать (0.2.7).
 *
 * **Принесённое ничего не включает.** Пир видит, от кого и кому идут
 * пакеты в меше, поэтому выбирает его человек — окно только приносит
 * предложение, а в ядро уезжает то, что он отметил.
 *
 * Порядок источника сохранён и здесь: узлы в файле сгруппированы по
 * владельцам, и перемешав их, экран лишил бы человека возможности
 * выбрать двух **разных** операторов.
 *
 * Отказ показан как ветка, а не как текст: перечень стран —
 * необязательный поход (у источника предел запросов), и поле «страна»
 * открыто всегда, чтобы знающий имя не зависел от перечня вовсе.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun YggPeerListDialog(
    regions: List<String>,
    countries: PeerListStep,
    peerList: PeerListStep,
    /** Уже назначенные пиры: предлагать их второй раз незачем. */
    known: List<String>,
    onFetchCountries: (String) -> Unit,
    onFetchList: (region: String, country: String) -> Unit,
    /** Забыть принесённое: сменили область — прошлый список не о ней. */
    onForget: () -> Unit,
    onAdd: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var region by remember { mutableStateOf<String?>(null) }
    var country by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(emptySet<String>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ygg_peer_list_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.ygg_peer_list_advice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.ygg_peer_list_region),
                    style = MaterialTheme.typography.labelLarge
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    regions.forEach { name ->
                        FilterChip(
                            selected = region == name,
                            onClick = {
                                region = name
                                country = ""
                                chosen = emptySet()
                                // Принесённое принадлежало прошлой области:
                                // оставь его на экране, и человек выбирал бы
                                // пиров одной страны под именем другой.
                                onForget()
                                onFetchCountries(name)
                            },
                            label = { Text(name) }
                        )
                    }
                }

                val picked = region
                if (picked != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.ygg_peer_list_country),
                        style = MaterialTheme.typography.labelLarge
                    )

                    when (countries) {
                        PeerListStep.Going -> Going()
                        is PeerListStep.Brought -> FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            countries.items.forEach { name ->
                                FilterChip(
                                    selected = country == name,
                                    onClick = {
                                        country = name
                                        chosen = emptySet()
                                        onFetchList(picked, name)
                                    },
                                    label = { Text(name) }
                                )
                            }
                        }
                        // Отказ перечня не мешает походу за списком: он
                        // здесь удобство, а не основа. Поэтому — слова
                        // ядра и открытое поле рядом, а не пустой экран.
                        is PeerListStep.Refused -> Text(
                            text = countries.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        PeerListStep.Idle -> Unit
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = country,
                            onValueChange = { country = it },
                            label = { Text(stringResource(R.string.ygg_peer_list_country)) },
                            placeholder = { Text(stringResource(R.string.ygg_peer_list_country_hint)) },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                chosen = emptySet()
                                // Имена стран в источнике строчные, и ядро
                                // иных не берёт: «Germany», набранное
                                // с заглавной, иначе получило бы отказ
                                // «такого списка нет».
                                onFetchList(picked, country.trim().lowercase())
                            },
                            enabled = country.isNotBlank() && peerList != PeerListStep.Going
                        ) {
                            Text(stringResource(R.string.ygg_peer_list_fetch))
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    when (peerList) {
                        PeerListStep.Going -> Going()
                        is PeerListStep.Refused -> Text(
                            text = peerList.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        is PeerListStep.Brought -> {
                            if (peerList.items.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.ygg_peer_list_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                peerList.items.forEach { uri ->
                                    val already = uri in known
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = already || uri in chosen,
                                            enabled = !already,
                                            onCheckedChange = { on ->
                                                chosen = if (on) chosen + uri else chosen - uri
                                            }
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = uri,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Medium
                                            )
                                            if (already) {
                                                Text(
                                                    text = stringResource(R.string.ygg_peer_list_already),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.outline
                                                )
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.ygg_peer_list_latency_note),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                        PeerListStep.Idle -> Unit
                    }
                }
            }
        },
        confirmButton = {
            Button(
                // Порядок источника: отмеченное уезжает так, как лежало
                // в файле, а не так, как его нажимали.
                onClick = {
                    val brought = (peerList as? PeerListStep.Brought)?.items ?: emptyList()
                    onAdd(brought.filter { it in chosen })
                },
                enabled = chosen.isNotEmpty()
            ) {
                Text(stringResource(R.string.ygg_peer_list_add, chosen.size))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    )
}

/** Поход идёт, и быстрым он не бывает: молчать об этом нельзя. */
@Composable
private fun Going() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(modifier = Modifier.height(16.dp).width(16.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.ygg_peer_list_going),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
