package chat.ratatosk.android.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import chat.ratatosk.android.R
import chat.ratatosk.android.core.BuiltTransports
import chat.ratatosk.android.ui.RatatoskViewModel
import chat.ratatosk.android.ui.model.NearbyPeers
import chat.ratatosk.android.ui.settings.YggPeerListDialog
import org.ratatosk.core.FfiTransport
import org.ratatosk.core.FfiYggMode
import org.ratatosk.core.lanWarning
import org.ratatosk.core.yggNodeNotice
import org.ratatosk.core.yggWarning

/**
 * Настройка сети для человека, которому слова «yggdrasil» и «реле»
 * не говорят ничего.
 *
 * # Почему это помощник, а не умолчания
 *
 * Зашитого в сборку списка публичных пиров быть не должно (0.2.5),
 * и зашитого списка реле nostr нет и не будет (FFI.md): выбор «кто видит,
 * кто с кем переписывается» принадлежит человеку, а не нам. Поэтому узлы
 * **приносятся** во время работы, показываются, и включает их человек —
 * одним нажатием, но увидев, что включает.
 *
 * Цену каждой ступени говорит ядро и **до** включения: §14 требует
 * этого прямо, и помощник ничего не включает молча. «Готово» внизу
 * не включает ничего само — оно только закрывает экран.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkSetupScreen(
    viewModel: RatatoskViewModel,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val setup = viewModel.networkSetup

    val lanEnabled by viewModel.lanEnabled.collectAsState()
    val btEnabled by viewModel.btEnabled.collectAsState()
    val btHasRadio by viewModel.btHasRadio.collectAsState()
    val torEnabled by viewModel.torEnabled.collectAsState()
    val torStatus by viewModel.torStatus.collectAsState()
    val transportsReady by viewModel.transportsReady.collectAsState()
    val yggMode by viewModel.yggMode.collectAsState()
    val yggPeers by viewModel.yggPeers.collectAsState()
    val nostrEnabled by viewModel.nostrEnabled.collectAsState()
    val nostrRelays by viewModel.nostrRelays.collectAsState()
    val contacts by viewModel.contacts.collectAsState()
    val nearby by setup.nearby.collectAsState()

    var showLanWarning by remember { mutableStateOf(false) }
    var showLanRationale by remember { mutableStateOf(false) }
    var showBtWarning by remember { mutableStateOf(false) }
    var showYggWarning by remember { mutableStateOf(false) }
    var showYggNodeNotice by remember { mutableStateOf(false) }
    var showNostrWarning by remember { mutableStateOf(false) }
    var showPeerPicker by remember { mutableStateOf(false) }
    // Узлы, которые человек подтвердил, но цена ещё не названа.
    var pendingPeers by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingRelays by remember { mutableStateOf<List<String>>(emptyList()) }

    val lanPermissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }.toTypedArray()
    }
    val lanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.any { it }) viewModel.setTransportEnabled(FfiTransport.LAN, true)
    }
    val enableLan: () -> Unit = {
        val missing = lanPermissions.any {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) showLanRationale = true else viewModel.setTransportEnabled(FfiTransport.LAN, true)
    }

    val btLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            viewModel.handBtRadio()
            viewModel.setTransportEnabled(FfiTransport.BT, true)
        }
    }
    val enableBt: () -> Unit = {
        val missing = viewModel.btMissingPermissions()
        if (missing.isEmpty()) {
            viewModel.handBtRadio()
            viewModel.setTransportEnabled(FfiTransport.BT, true)
        } else {
            btLauncher.launch(missing.toTypedArray())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.network_setup_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.network_setup_intro),
                style = MaterialTheme.typography.bodyMedium,
            )

            // --- Рядом: локальная сеть и эфир ---
            SetupCard(stringResource(R.string.network_setup_near)) {
                Text(
                    text = stringResource(R.string.network_setup_near_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SetupSwitch(
                    title = stringResource(R.string.lan_transport),
                    subtitle = stringResource(R.string.lan_desc),
                    checked = lanEnabled,
                    onChange = { on ->
                        if (on) showLanWarning = true
                        else viewModel.setTransportEnabled(FfiTransport.LAN, false)
                    },
                )
                if (BuiltTransports.bt) {
                    SetupSwitch(
                        title = stringResource(R.string.bt_transport),
                        subtitle = stringResource(R.string.bt_desc),
                        checked = btEnabled,
                        onChange = { on ->
                            if (on) showBtWarning = true
                            else viewModel.setTransportEnabled(FfiTransport.BT, false)
                        },
                    )
                    if (btEnabled && !btHasRadio) {
                        Text(
                            text = stringResource(R.string.bt_no_permissions),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            // --- Далеко: Tor ---
            if (BuiltTransports.tor) {
                SetupCard(stringResource(R.string.network_setup_far)) {
                    Text(
                        text = stringResource(R.string.network_setup_far_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val torReady = transportsReady[FfiTransport.ONION] == true
                    val state = when {
                        !torEnabled -> stringResource(R.string.network_setup_tor_off)
                        torReady -> stringResource(R.string.network_setup_tor_ready)
                        torStatus != null -> stringResource(
                            R.string.network_setup_tor_rising,
                            (torStatus!!.fraction * 100).toInt(),
                        )
                        else -> stringResource(R.string.network_setup_tor_rising, 0)
                    }
                    Text(text = state, style = MaterialTheme.typography.bodySmall)
                    if (!torEnabled) {
                        Button(
                            onClick = { viewModel.setTransportEnabled(FfiTransport.ONION, true) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.enable)) }
                    }
                }
            }

            // --- Меш ---
            if (BuiltTransports.ygg) {
                SetupCard(stringResource(R.string.network_setup_mesh)) {
                    Text(
                        text = stringResource(R.string.network_setup_mesh_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (yggMode != FfiYggMode.OFF) {
                        Text(
                            text = stringResource(R.string.network_setup_mesh_on, yggPeers.size),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(
                            onClick = { showPeerPicker = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.network_setup_mesh_change)) }
                    } else {
                        when (val found = nearby) {
                            NearbyPeers.Idle -> Button(
                                onClick = { setup.lookForNearbyPeers() },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.network_setup_mesh_look)) }

                            NearbyPeers.Looking -> Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp),
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.network_setup_mesh_looking),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }

                            is NearbyPeers.Found -> {
                                Text(
                                    text = stringResource(
                                        R.string.network_setup_mesh_found,
                                        found.country,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                found.peers.forEach { uri ->
                                    Text(
                                        text = uri,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Button(
                                    onClick = {
                                        pendingPeers = found.peers
                                        showYggWarning = true
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.network_setup_mesh_turn_on)) }
                                TextButton(
                                    onClick = { showPeerPicker = true },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.network_setup_mesh_pick_self)) }
                            }

                            is NearbyPeers.PickCountry -> {
                                Text(
                                    text = stringResource(R.string.network_setup_mesh_no_country),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                @OptIn(ExperimentalLayoutApi::class)
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    found.countries.forEach { name ->
                                        AssistChip(
                                            onClick = { setup.lookInCountry(found.region, name) },
                                            label = { Text(name) },
                                        )
                                    }
                                }
                                TextButton(
                                    onClick = { showPeerPicker = true },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.network_setup_mesh_pick_self)) }
                            }

                            NearbyPeers.NoRegion -> {
                                Text(
                                    text = stringResource(R.string.network_setup_mesh_no_region),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                OutlinedButton(
                                    onClick = { showPeerPicker = true },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.network_setup_mesh_pick_self)) }
                            }

                            is NearbyPeers.Refused -> {
                                Text(
                                    text = found.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { setup.lookForNearbyPeers() }) {
                                        Text(stringResource(R.string.retry))
                                    }
                                    TextButton(onClick = { showPeerPicker = true }) {
                                        Text(stringResource(R.string.network_setup_mesh_pick_self))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // --- Nostr ---
            if (BuiltTransports.nostr) {
                SetupCard(stringResource(R.string.network_setup_nostr)) {
                    Text(
                        text = stringResource(R.string.network_setup_nostr_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val donor = contacts.firstOrNull { it.nostrRelays.isNotEmpty() }
                    when {
                        nostrEnabled && nostrRelays.isNotEmpty() -> Text(
                            text = stringResource(R.string.network_setup_nostr_on, nostrRelays.size),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        donor != null -> {
                            Text(
                                text = stringResource(
                                    R.string.network_setup_nostr_from_contact,
                                    donor.localName ?: donor.displayName,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            donor.nostrRelays.forEach { relay ->
                                Text(
                                    text = relay,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button(
                                onClick = {
                                    pendingRelays = donor.nostrRelays
                                    showNostrWarning = true
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.network_setup_nostr_take)) }
                        }
                        else -> Text(
                            text = stringResource(R.string.network_setup_nostr_wait),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // --- Почта ---
            if (BuiltTransports.mail) {
                SetupCard(stringResource(R.string.network_setup_mail)) {
                    Text(
                        text = stringResource(R.string.network_setup_mail_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = {
                    setup.markSetupOffered()
                    setup.forgetNearby()
                    onDone()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.done)) }
            Text(
                text = stringResource(R.string.network_setup_later),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // --- Цена ступеней: слова ядра, и до включения ---

    if (showLanWarning) {
        AlertDialog(
            onDismissRequest = { showLanWarning = false },
            title = { Text(stringResource(R.string.lan_warning_title)) },
            text = { Text(lanWarning()) },
            confirmButton = {
                TextButton(onClick = { showLanWarning = false; enableLan() }) {
                    Text(stringResource(R.string.enable))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLanWarning = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showLanRationale) {
        AlertDialog(
            onDismissRequest = { showLanRationale = false },
            title = { Text(stringResource(R.string.lan_transport)) },
            text = { Text(stringResource(R.string.lan_permission_rationale)) },
            confirmButton = {
                TextButton(onClick = {
                    showLanRationale = false
                    lanLauncher.launch(lanPermissions)
                }) { Text(stringResource(R.string.bt_grant)) }
            },
            dismissButton = {
                TextButton(onClick = { showLanRationale = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showBtWarning) {
        AlertDialog(
            onDismissRequest = { showBtWarning = false },
            title = { Text(stringResource(R.string.bt_transport)) },
            text = { Text(stringResource(R.string.bt_warning)) },
            confirmButton = {
                TextButton(onClick = { showBtWarning = false; enableBt() }) {
                    Text(stringResource(R.string.enable))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBtWarning = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // Меш: сперва цена ступени, потом — что нового у своего узла.
    // Порядок требует FFI.md, и оба текста обязательны.
    if (showYggWarning) {
        AlertDialog(
            onDismissRequest = { showYggWarning = false; pendingPeers = emptyList() },
            title = { Text(stringResource(R.string.ygg_warning_title)) },
            text = { Text(yggWarning()) },
            confirmButton = {
                TextButton(onClick = { showYggWarning = false; showYggNodeNotice = true }) {
                    Text(stringResource(R.string.next))
                }
            },
            dismissButton = {
                TextButton(onClick = { showYggWarning = false; pendingPeers = emptyList() }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showYggNodeNotice) {
        AlertDialog(
            onDismissRequest = { showYggNodeNotice = false; pendingPeers = emptyList() },
            title = { Text(stringResource(R.string.ygg_node_notice_title)) },
            text = { Text(yggNodeNotice()) },
            confirmButton = {
                TextButton(onClick = {
                    showYggNodeNotice = false
                    setup.turnOnMesh(pendingPeers)
                    pendingPeers = emptyList()
                }) { Text(stringResource(R.string.enable)) }
            },
            dismissButton = {
                TextButton(onClick = { showYggNodeNotice = false; pendingPeers = emptyList() }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showNostrWarning) {
        AlertDialog(
            onDismissRequest = { showNostrWarning = false; pendingRelays = emptyList() },
            title = { Text(stringResource(R.string.nostr_warning_title)) },
            text = { Text(viewModel.getNostrWarning()) },
            confirmButton = {
                TextButton(onClick = {
                    showNostrWarning = false
                    viewModel.setNostrRelays(pendingRelays)
                    viewModel.setTransportEnabled(FfiTransport.NOSTR, true)
                    pendingRelays = emptyList()
                }) { Text(stringResource(R.string.enable)) }
            },
            dismissButton = {
                TextButton(onClick = { showNostrWarning = false; pendingRelays = emptyList() }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showPeerPicker) {
        val regions by viewModel.yggPeerRegions.collectAsState()
        val countries by viewModel.yggPeerCountries.collectAsState()
        val brought by viewModel.yggPeerList.collectAsState()
        YggPeerListDialog(
            regions = regions,
            countries = countries,
            peerList = brought,
            known = yggPeers,
            onFetchCountries = { viewModel.fetchYggPeerCountries(it) },
            onFetchList = { region, country -> viewModel.fetchYggPeerList(region, country) },
            onForget = { viewModel.forgetYggPeerList() },
            onAdd = { picked ->
                viewModel.forgetYggPeerList()
                showPeerPicker = false
                if (yggMode != FfiYggMode.OFF) {
                    // Меш уже включён: цена названа раньше, и спрашивать
                    // о ней второй раз незачем.
                    viewModel.setYggPeers(yggPeers + picked.filter { it !in yggPeers })
                } else {
                    pendingPeers = picked
                    showYggWarning = true
                }
            },
            onDismiss = {
                viewModel.forgetYggPeerList()
                showPeerPicker = false
            },
        )
    }
}

/** Карточка одного шага: заголовок и то, что в нём решается. */
@Composable
private fun SetupCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** Переключатель ступени с её ценой в одну строку. */
@Composable
private fun SetupSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
