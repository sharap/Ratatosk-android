package chat.ratatosk.android.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import chat.ratatosk.android.R
import chat.ratatosk.android.ui.RatatoskViewModel
import kotlinx.coroutines.launch
import org.ratatosk.core.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: RatatoskViewModel,
    onPairedDevicesClick: () -> Unit,
    isTwoColumn: Boolean = false
) {
    val context = LocalContext.current
    val transportsEnabled by viewModel.transportsEnabled.collectAsState()
    val transportsReady by viewModel.transportsReady.collectAsState()
    val torStatus by viewModel.torStatus.collectAsState()
    val mailStatus by viewModel.mailStatus.collectAsState()
    val mailAccount by viewModel.mailAccount.collectAsState()
    val yggMode by viewModel.yggMode.collectAsState()
    val yggKey by viewModel.yggKey.collectAsState()
    val yggAddress by viewModel.yggAddress.collectAsState()
    val yggPeers by viewModel.yggPeers.collectAsState()
    val yggPeersAlive by viewModel.yggPeersAlive.collectAsState()
    
    val btHasRadio by viewModel.btHasRadio.collectAsState()
    val btContext = LocalContext.current

    // Запрос разрешений живёт здесь: из ViewModel системный диалог
    // не показать. Радио вручается **после** выдачи — розданное без
    // разрешений уходит в onLost, и человек видит сломанную ступень
    // вместо запроса.
    val btPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) viewModel.handBtRadio()
    }

    val requestBtPermissions: () -> Unit = {
        val missing = viewModel.btMissingPermissions()
        if (missing.isEmpty()) viewModel.handBtRadio() else btPermissionLauncher.launch(missing.toTypedArray())
    }

    // Цена ступени называется **до** включения, как у локальной сети
    // и меша (FFI.md: «сказать человеку надо два раза и до включения»).
    // Своей функции §14 у эфира в ядре нет, поэтому текст наш — но говорит
    // он ровно про то, о чём предупреждает документация: батарея и
    // отдельное разрешение.
    var showBtWarning by remember { mutableStateOf(false) }

    // Причину считаем здесь: секция транспортов ViewModel не видит,
    // а обе возможные причины знает приложение, а не ядро.
    val btNotReadyReason = when {
        !viewModel.btAdapterEnabled() -> stringResource(R.string.bt_adapter_off)
        viewModel.btMissingPermissions().isNotEmpty() -> stringResource(R.string.bt_no_permissions)
        else -> stringResource(R.string.bt_not_up)
    }

    val onToggleBt: (Boolean) -> Unit = { wanted ->
        if (wanted) showBtWarning = true else viewModel.setTransportEnabled(FfiTransport.BT, false)
    }

    val nostrEnabled by viewModel.nostrEnabled.collectAsState()
    val nostrRelays by viewModel.nostrRelays.collectAsState()
    val nostrRelaysAlive by viewModel.nostrRelaysAlive.collectAsState()
    val nostrNpub by viewModel.nostrNpub.collectAsState()
    val nostrDirect by viewModel.nostrDirect.collectAsState()
    var showNostrWarning by remember { mutableStateOf(false) }
    var showNostrDirectWarning by remember { mutableStateOf(false) }
    var showNostrRelaysSetup by remember { mutableStateOf(false) }
    
    val showName by viewModel.notificationsShowName.collectAsState()
    val showText by viewModel.notificationsShowText.collectAsState()
    var showLanWarning by remember { mutableStateOf(false) }
    var showYggWarning by remember { mutableStateOf(false) }
    var showYggNodeNotice by remember { mutableStateOf(false) }
    var pendingYggMode by remember { mutableStateOf<FfiYggMode?>(null) }
    var showPermissionRationale by remember { mutableStateOf(false) }
    var showMailSetup by remember { mutableStateOf(false) }
    var showMailCreate by remember { mutableStateOf(false) }
    var showYggSetup by remember { mutableStateOf(false) }
    var showYggPeersSetup by remember { mutableStateOf(false) }
    
    val chatTheme by viewModel.chatTheme.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val permissionsToRequest = remember {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        list.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        list.toTypedArray()
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.any { it }) {
            viewModel.setTransportEnabled(FfiTransport.LAN, true)
        }
    }

    fun checkAndEnableLan() {
        val needsPermissions = permissionsToRequest.any {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needsPermissions) {
            showPermissionRationale = true
        } else {
            viewModel.setTransportEnabled(FfiTransport.LAN, true)
        }
    }

    val isCompanionMode by viewModel.isCompanionMode.collectAsState()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            )
        }
    ) { innerPadding ->
        if (isTwoColumn) {
            Row(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                // Column 1: Transports & Companion
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    if (!isCompanionMode) {
                        SettingsTransportsSection(
                            transportsEnabled = transportsEnabled,
                            transportsReady = transportsReady,
                            torStatus = torStatus,
                            mailStatus = mailStatus,
                            yggMode = yggMode,
                            yggKey = yggKey,
                            yggAddress = yggAddress,
                            yggPeers = yggPeers,
                            nostrRelays = nostrRelays,
                            nostrRelaysAlive = nostrRelaysAlive,
                            nostrNpub = nostrNpub,
                            nostrDirect = nostrDirect,
                            onToggleLan = { if (it) showLanWarning = true else viewModel.setTransportEnabled(FfiTransport.LAN, false) },
                            btHasRadio = btHasRadio,
                            btNotReadyReason = btNotReadyReason,
                            onToggleBt = onToggleBt,
                            onGrantBtPermissions = requestBtPermissions,
                            onSelectYggMode = { mode ->
                                if (mode == yggMode) return@SettingsTransportsSection
                                if (mode == FfiYggMode.OFF) {
                                    viewModel.setYggMode(FfiYggMode.OFF)
                                } else {
                                    pendingYggMode = mode
                                    showYggWarning = true
                                }
                            },
                            onToggleTor = { viewModel.setTransportEnabled(FfiTransport.ONION, it) },
                            onToggleMail = { viewModel.setTransportEnabled(FfiTransport.MAIL, it) },
                            onToggleNostr = { enabled ->
                                if (enabled) {
                                    showNostrWarning = true
                                } else {
                                    viewModel.setTransportEnabled(FfiTransport.NOSTR, false)
                                }
                            },
                            onToggleNostrDirect = { direct ->
                                if (direct) {
                                    showNostrDirectWarning = true
                                } else {
                                    viewModel.setNostrDirect(false)
                                }
                            },
                            onShowMailSetup = { showMailSetup = true },
                            onShowMailCreate = { showMailCreate = true },
                            onClearMailAccount = { viewModel.clearMailAccount() },
                            onShowYggSetup = { showYggSetup = true },
                            onShowYggPeersSetup = { showYggPeersSetup = true },
                            onShowNostrRelaysSetup = { showNostrRelaysSetup = true }
                        )

                        Spacer(modifier = Modifier.height(24.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(24.dp))

                        SettingsCompanionSection(onPairedDevicesClick = onPairedDevicesClick)
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(24.dp))
                    }

                    SettingsPrivacySection(
                        showName = showName,
                        showText = showText,
                        onToggleShowName = { viewModel.setNotificationsShowName(it) },
                        onToggleShowText = { viewModel.setNotificationsShowText(it) }
                    )
                }

                // Column 2: Storage, Theme
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    if (!isCompanionMode) {
                        SettingsStorageSection(
                            viewModel = viewModel,
                            snackbarHostState = snackbarHostState,
                            scope = scope
                        )

                        Spacer(modifier = Modifier.height(24.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        SettingsBackupSection(viewModel = viewModel, snackbarHostState = snackbarHostState)

                        Spacer(modifier = Modifier.height(24.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(24.dp))
                    } else {
                        SettingsDownloadPathSection(viewModel = viewModel)

                        Spacer(modifier = Modifier.height(24.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(24.dp))
                    }

                    SettingsThemeSection(
                        viewModel = viewModel,
                        chatTheme = chatTheme
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .padding(16.dp)
                    .verticalScroll(scrollState)
            ) {
                if (!isCompanionMode) {
                    SettingsTransportsSection(
                        transportsEnabled = transportsEnabled,
                        transportsReady = transportsReady,
                        torStatus = torStatus,
                        mailStatus = mailStatus,
                        yggMode = yggMode,
                        yggKey = yggKey,
                        yggAddress = yggAddress,
                        yggPeers = yggPeers,
                        nostrRelays = nostrRelays,
                        nostrRelaysAlive = nostrRelaysAlive,
                        nostrNpub = nostrNpub,
                        nostrDirect = nostrDirect,
                        onToggleLan = { if (it) showLanWarning = true else viewModel.setTransportEnabled(FfiTransport.LAN, false) },
                        btHasRadio = btHasRadio,
                        btNotReadyReason = btNotReadyReason,
                        onToggleBt = onToggleBt,
                        onGrantBtPermissions = requestBtPermissions,
                        onSelectYggMode = { mode ->
                            if (mode == yggMode) return@SettingsTransportsSection
                            if (mode == FfiYggMode.OFF) {
                                viewModel.setYggMode(FfiYggMode.OFF)
                            } else {
                                pendingYggMode = mode
                                showYggWarning = true
                            }
                        },
                        onToggleTor = { viewModel.setTransportEnabled(FfiTransport.ONION, it) },
                        onToggleMail = { viewModel.setTransportEnabled(FfiTransport.MAIL, it) },
                        onToggleNostr = { enabled ->
                            if (enabled) {
                                showNostrWarning = true
                            } else {
                                viewModel.setTransportEnabled(FfiTransport.NOSTR, false)
                            }
                        },
                        onToggleNostrDirect = { direct ->
                            if (direct) {
                                showNostrDirectWarning = true
                            } else {
                                viewModel.setNostrDirect(false)
                            }
                        },
                        onShowMailSetup = { showMailSetup = true },
                        onShowMailCreate = { showMailCreate = true },
                        onClearMailAccount = { viewModel.clearMailAccount() },
                        onShowYggSetup = { showYggSetup = true },
                        onShowYggPeersSetup = { showYggPeersSetup = true },
                        onShowNostrRelaysSetup = { showNostrRelaysSetup = true }
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                    SettingsCompanionSection(onPairedDevicesClick = onPairedDevicesClick)

                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                }

                SettingsPrivacySection(
                    showName = showName,
                    showText = showText,
                    onToggleShowName = { viewModel.setNotificationsShowName(it) },
                    onToggleShowText = { viewModel.setNotificationsShowText(it) }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                if (!isCompanionMode) {
                    SettingsStorageSection(
                        viewModel = viewModel,
                        snackbarHostState = snackbarHostState,
                        scope = scope
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                    SettingsBackupSection(viewModel = viewModel, snackbarHostState = snackbarHostState)

                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                } else {
                    // In companion mode, we only show Download Path from Storage section
                    SettingsDownloadPathSection(viewModel = viewModel)
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                }

                SettingsThemeSection(
                    viewModel = viewModel,
                    chatTheme = chatTheme
                )
            }
        }
    }

    // --- Dialogs ---

    if (showLanWarning) {
        AlertDialog(
            onDismissRequest = { showLanWarning = false },
            title = { Text(stringResource(R.string.lan_warning_title)) },
            text = { Text(lanWarning()) },
            confirmButton = { TextButton(onClick = { showLanWarning = false; checkAndEnableLan() }) { Text(stringResource(R.string.enable)) } },
            dismissButton = { TextButton(onClick = { showLanWarning = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    if (showYggWarning) {
        AlertDialog(
            onDismissRequest = { showYggWarning = false; pendingYggMode = null },
            title = { Text(stringResource(R.string.ygg_warning_title)) },
            text = { Text(yggWarning()) },
            confirmButton = {
                TextButton(onClick = {
                    showYggWarning = false
                    val mode = pendingYggMode
                    if (mode == FfiYggMode.EMBEDDED) {
                        showYggNodeNotice = true
                    } else if (mode == FfiYggMode.EXTERNAL) {
                        viewModel.setYggMode(FfiYggMode.EXTERNAL)
                        pendingYggMode = null
                    }
                }) { Text(stringResource(R.string.enable)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showYggWarning = false
                    pendingYggMode = null
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showYggNodeNotice) {
        AlertDialog(
            onDismissRequest = { showYggNodeNotice = false; pendingYggMode = null },
            title = { Text(stringResource(R.string.ygg_node_notice_title)) },
            text = { Text(yggNodeNotice()) },
            confirmButton = {
                TextButton(onClick = {
                    showYggNodeNotice = false
                    viewModel.setYggMode(FfiYggMode.EMBEDDED)
                    pendingYggMode = null
                }) { Text(stringResource(R.string.enable)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showYggNodeNotice = false
                    pendingYggMode = null
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showPermissionRationale) {
        AlertDialog(
            onDismissRequest = { showPermissionRationale = false },
            title = { Text(stringResource(R.string.lan_transport)) },
            text = { Text(stringResource(R.string.lan_permission_rationale)) },
            confirmButton = { TextButton(onClick = { showPermissionRationale = false; permissionsLauncher.launch(permissionsToRequest) }) { Text(stringResource(R.string.retry)) } },
            dismissButton = { TextButton(onClick = { showPermissionRationale = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    if (showMailSetup) {
        var addr by remember { mutableStateOf(mailAccount?.address ?: "") }
        var pass by remember { mutableStateOf(mailAccount?.password ?: "") }
        var imapH by remember { mutableStateOf(mailAccount?.imapHost ?: "") }
        var imapP by remember { mutableStateOf(mailAccount?.imapPort?.toString() ?: "0") }
        var smtpH by remember { mutableStateOf(mailAccount?.smtpHost ?: "") }
        var smtpP by remember { mutableStateOf(mailAccount?.smtpPort?.toString() ?: "0") }
        var viaT by remember { mutableStateOf(mailAccount?.viaTor ?: true) }

        AlertDialog(
            onDismissRequest = { showMailSetup = false },
            title = { Text(stringResource(R.string.setup_mail)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(value = addr, onValueChange = { addr = it }, label = { Text(stringResource(R.string.address)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = pass, onValueChange = { pass = it }, label = { Text(stringResource(R.string.password)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = imapH, onValueChange = { imapH = it }, label = { Text(stringResource(R.string.imap_host)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = imapP, onValueChange = { imapP = it }, label = { Text(stringResource(R.string.imap_port)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = smtpH, onValueChange = { smtpH = it }, label = { Text(stringResource(R.string.smtp_host)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = smtpP, onValueChange = { smtpP = it }, label = { Text(stringResource(R.string.smtp_port)) }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = viaT, onCheckedChange = { viaT = it })
                        Text(stringResource(R.string.via_tor))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setMailAccount(addr, pass, imapH, imapP.toIntOrNull() ?: 0, smtpH, smtpP.toIntOrNull() ?: 0, viaT)
                    showMailSetup = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { showMailSetup = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    if (showMailCreate) {
        var url by remember { mutableStateOf("https://chatmail.example/new") }
        var viaT by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { showMailCreate = false },
            title = { Text(stringResource(R.string.create_mail)) },
            text = {
                Column {
                    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text(stringResource(R.string.server_url)) }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = viaT, onCheckedChange = { viaT = it })
                        Text(stringResource(R.string.via_tor))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.createMailAccount(url, viaT); showMailCreate = false }) { Text(stringResource(R.string.register)) }
            },
            dismissButton = { TextButton(onClick = { showMailCreate = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    if (showYggSetup) {
        var keyInput by remember { mutableStateOf(yggKey ?: "") }

        AlertDialog(
            onDismissRequest = { showYggSetup = false },
            title = { Text(stringResource(R.string.setup_ygg)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        label = { Text(stringResource(R.string.ygg_key_label)) },
                        placeholder = { Text(stringResource(R.string.ygg_key_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                    if (!yggAddress.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.ygg_address_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            text = yggAddress!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setYggKey(keyInput)
                    showYggSetup = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { showYggSetup = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    if (showYggPeersSetup) {
        var showAddPeerDialog by remember { mutableStateOf(false) }
        var peerToEdit by remember { mutableStateOf<String?>(null) }
        var newPeerInput by remember { mutableStateOf("") }
        var editPeerInput by remember { mutableStateOf("") }

        // Refresh transport/peer status while dialog is open
        LaunchedEffect(Unit) {
            while (true) {
                viewModel.refreshTransportStatus()
                kotlinx.coroutines.delay(2000)
            }
        }

        AlertDialog(
            onDismissRequest = { showYggPeersSetup = false },
            title = { Text(stringResource(R.string.setup_ygg_peers)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (yggPeers.isEmpty()) {
                        Text(
                            text = stringResource(R.string.ygg_no_peers_warning),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        yggPeers.forEach { peerUri ->
                            val aliveInfo = yggPeersAlive?.find { 
                                it.uri == peerUri || it.uri.trim() == peerUri.trim() 
                            }
                            
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        peerToEdit = peerUri
                                        editPeerInput = peerUri
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = peerUri,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (yggPeersAlive == null) {
                                                Surface(
                                                    modifier = Modifier.size(8.dp),
                                                    shape = CircleShape,
                                                    color = Color.Gray
                                                ) {}
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = stringResource(R.string.peer_node_stopped),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.outline
                                                )
                                            } else if (aliveInfo != null && aliveInfo.up) {
                                                Surface(
                                                    modifier = Modifier.size(8.dp),
                                                    shape = CircleShape,
                                                    color = Color(0xFF4CAF50)
                                                ) {}
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = stringResource(R.string.peer_connected),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color(0xFF4CAF50),
                                                    fontWeight = FontWeight.Bold
                                                )
                                                if (aliveInfo.latencyMs > 0) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = "• ${aliveInfo.latencyMs.toInt()} мс",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            } else {
                                                Surface(
                                                    modifier = Modifier.size(8.dp),
                                                    shape = CircleShape,
                                                    color = MaterialTheme.colorScheme.error
                                                ) {}
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = stringResource(R.string.peer_disconnected),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                    }
                                    
                                    IconButton(
                                        onClick = {
                                            peerToEdit = peerUri
                                            editPeerInput = peerUri
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = "Edit Peer",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            viewModel.setYggPeers(yggPeers - peerUri)
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Remove Peer",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Inbound Peers section if any
                    val inboundPeers = remember(yggPeersAlive) {
                        yggPeersAlive?.filter { it.inbound } ?: emptyList()
                    }
                    if (inboundPeers.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.inbound_peers_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        inboundPeers.forEach { inboundPeer ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = inboundPeer.uri,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Surface(
                                                modifier = Modifier.size(8.dp),
                                                shape = CircleShape,
                                                color = Color(0xFF4CAF50)
                                            ) {}
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = stringResource(R.string.peer_connected) + " (входящий)",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFF4CAF50)
                                            )
                                            if (inboundPeer.latencyMs > 0) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "• ${inboundPeer.latencyMs.toInt()} мс",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = { showAddPeerDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.add_peer))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showYggPeersSetup = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )

        if (showAddPeerDialog) {
            AlertDialog(
                onDismissRequest = {
                    newPeerInput = ""
                    showAddPeerDialog = false
                },
                title = { Text(stringResource(R.string.add_peer_title)) },
                text = {
                    OutlinedTextField(
                        value = newPeerInput,
                        onValueChange = { newPeerInput = it },
                        label = { Text(stringResource(R.string.ygg_peers_label)) },
                        placeholder = { Text(stringResource(R.string.add_peer_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val trimmed = newPeerInput.trim()
                            if (trimmed.isNotEmpty() && !yggPeers.contains(trimmed)) {
                                viewModel.setYggPeers(yggPeers + trimmed)
                            }
                            newPeerInput = ""
                            showAddPeerDialog = false
                        },
                        enabled = newPeerInput.isNotBlank()
                    ) {
                        Text(stringResource(R.string.add_peer))
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        newPeerInput = ""
                        showAddPeerDialog = false
                    }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        if (peerToEdit != null) {
            AlertDialog(
                onDismissRequest = {
                    peerToEdit = null
                    editPeerInput = ""
                },
                title = { Text(stringResource(R.string.edit_peer_title)) },
                text = {
                    OutlinedTextField(
                        value = editPeerInput,
                        onValueChange = { editPeerInput = it },
                        label = { Text(stringResource(R.string.ygg_peers_label)) },
                        placeholder = { Text(stringResource(R.string.add_peer_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val trimmed = editPeerInput.trim()
                            val oldPeer = peerToEdit!!
                            if (trimmed.isNotEmpty()) {
                                val updatedList = yggPeers.map { if (it == oldPeer) trimmed else it }
                                viewModel.setYggPeers(updatedList)
                            }
                            peerToEdit = null
                            editPeerInput = ""
                        },
                        enabled = editPeerInput.isNotBlank()
                    ) {
                        Text(stringResource(R.string.save))
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        peerToEdit = null
                        editPeerInput = ""
                    }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
    }

    if (showBtWarning) {
        AlertDialog(
            onDismissRequest = { showBtWarning = false },
            title = { Text(stringResource(R.string.bt_transport)) },
            text = { Text(stringResource(R.string.bt_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    showBtWarning = false
                    // Разрешения — до включения: иначе ступень встанет
                    // включённой и неработающей.
                    requestBtPermissions()
                    viewModel.setTransportEnabled(FfiTransport.BT, true)
                }) { Text(stringResource(R.string.enable)) }
            },
            dismissButton = {
                TextButton(onClick = { showBtWarning = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showNostrWarning) {
        AlertDialog(
            onDismissRequest = { showNostrWarning = false },
            title = { Text(stringResource(R.string.nostr_warning_title)) },
            text = {
                val warningText = viewModel.getNostrWarning() + "\n\n" + viewModel.getNostrNoFilesNotice()
                Text(warningText)
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.setTransportEnabled(FfiTransport.NOSTR, true)
                    showNostrWarning = false
                }) {
                    Text(stringResource(R.string.enable))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNostrWarning = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showNostrDirectWarning) {
        AlertDialog(
            onDismissRequest = { showNostrDirectWarning = false },
            title = { Text(stringResource(R.string.nostr_direct_warning_title)) },
            text = { Text(viewModel.getNostrDirectWarning()) },
            confirmButton = {
                Button(onClick = {
                    viewModel.setNostrDirect(true)
                    showNostrDirectWarning = false
                }) {
                    Text(stringResource(R.string.enable))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNostrDirectWarning = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showNostrRelaysSetup) {
        var showAddRelayDialog by remember { mutableStateOf(false) }
        var relayToEdit by remember { mutableStateOf<String?>(null) }
        var newRelayInput by remember { mutableStateOf("") }
        var editRelayInput by remember { mutableStateOf("") }

        LaunchedEffect(Unit) {
            while (true) {
                viewModel.refreshTransportStatus()
                kotlinx.coroutines.delay(2000)
            }
        }

        AlertDialog(
            onDismissRequest = { showNostrRelaysSetup = false },
            title = { Text(stringResource(R.string.setup_nostr_relays)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (nostrRelays.isEmpty()) {
                        Text(
                            text = stringResource(R.string.nostr_no_relays_warning),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        nostrRelays.forEach { relayUrl ->
                            val aliveInfo = nostrRelaysAlive?.find { 
                                it.url == relayUrl || it.url.trim() == relayUrl.trim() 
                            }

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        relayToEdit = relayUrl
                                        editRelayInput = relayUrl
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = relayUrl,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (nostrRelaysAlive == null) {
                                                Surface(
                                                    modifier = Modifier.size(8.dp),
                                                    shape = CircleShape,
                                                    color = Color.Gray
                                                ) {}
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = stringResource(R.string.peer_node_stopped),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.outline
                                                )
                                            } else if (aliveInfo != null && aliveInfo.up) {
                                                Surface(
                                                    modifier = Modifier.size(8.dp),
                                                    shape = CircleShape,
                                                    color = Color(0xFF4CAF50)
                                                ) {}
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = stringResource(R.string.peer_connected),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color(0xFF4CAF50),
                                                    fontWeight = FontWeight.Bold
                                                )
                                            } else {
                                                Surface(
                                                    modifier = Modifier.size(8.dp),
                                                    shape = CircleShape,
                                                    color = MaterialTheme.colorScheme.error
                                                ) {}
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = if (aliveInfo?.note?.isNotBlank() == true) aliveInfo.note else stringResource(R.string.peer_disconnected),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                    }

                                    IconButton(
                                        onClick = {
                                            relayToEdit = relayUrl
                                            editRelayInput = relayUrl
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = "Edit Relay",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            viewModel.setNostrRelays(nostrRelays - relayUrl)
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Remove Relay",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = { showAddRelayDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.add_nostr_relay))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showNostrRelaysSetup = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )

        if (showAddRelayDialog) {
            AlertDialog(
                onDismissRequest = {
                    newRelayInput = ""
                    showAddRelayDialog = false
                },
                title = { Text(stringResource(R.string.add_nostr_relay_title)) },
                text = {
                    OutlinedTextField(
                        value = newRelayInput,
                        onValueChange = { newRelayInput = it },
                        label = { Text(stringResource(R.string.nostr_relays_label)) },
                        placeholder = { Text("wss://relay.damus.io") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val raw = newRelayInput.trim()
                            val trimmed = if (raw.startsWith("wss://") || raw.startsWith("ws://")) raw else if (raw.isNotEmpty()) "wss://$raw" else ""
                            if (trimmed.isNotEmpty() && !nostrRelays.contains(trimmed)) {
                                viewModel.setNostrRelays(nostrRelays + trimmed)
                            }
                            newRelayInput = ""
                            showAddRelayDialog = false
                        },
                        enabled = newRelayInput.isNotBlank()
                    ) {
                        Text(stringResource(R.string.add_nostr_relay))
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        newRelayInput = ""
                        showAddRelayDialog = false
                    }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        if (relayToEdit != null) {
            AlertDialog(
                onDismissRequest = {
                    relayToEdit = null
                    editRelayInput = ""
                },
                title = { Text(stringResource(R.string.add_nostr_relay_title)) },
                text = {
                    OutlinedTextField(
                        value = editRelayInput,
                        onValueChange = { editRelayInput = it },
                        label = { Text(stringResource(R.string.nostr_relays_label)) },
                        placeholder = { Text("wss://relay.damus.io") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val raw = editRelayInput.trim()
                            val trimmed = if (raw.startsWith("wss://") || raw.startsWith("ws://")) raw else if (raw.isNotEmpty()) "wss://$raw" else ""
                            val oldRelay = relayToEdit!!
                            if (trimmed.isNotEmpty()) {
                                val updatedList = nostrRelays.map { if (it == oldRelay) trimmed else it }
                                viewModel.setNostrRelays(updatedList)
                            }
                            relayToEdit = null
                            editRelayInput = ""
                        },
                        enabled = editRelayInput.isNotBlank()
                    ) {
                        Text(stringResource(R.string.save))
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        relayToEdit = null
                        editRelayInput = ""
                    }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
    }
}

@Composable
fun SettingsTransportsSection(
    transportsEnabled: Map<FfiTransport, Boolean>,
    transportsReady: Map<FfiTransport, Boolean>,
    torStatus: FfiTorStatus?,
    mailStatus: FfiMailStatus?,
    yggMode: FfiYggMode,
    yggKey: String?,
    yggAddress: String?,
    yggPeers: List<String>,
    nostrRelays: List<String>,
    nostrRelaysAlive: List<FfiNostrRelay>?,
    nostrNpub: String?,
    nostrDirect: Boolean,
    onToggleLan: (Boolean) -> Unit,
    btHasRadio: Boolean,
    btNotReadyReason: String,
    onToggleBt: (Boolean) -> Unit,
    onGrantBtPermissions: () -> Unit,
    onSelectYggMode: (FfiYggMode) -> Unit,
    onToggleTor: (Boolean) -> Unit,
    onToggleMail: (Boolean) -> Unit,
    onToggleNostr: (Boolean) -> Unit,
    onToggleNostrDirect: (Boolean) -> Unit,
    onShowMailSetup: () -> Unit,
    onShowMailCreate: () -> Unit,
    onClearMailAccount: () -> Unit,
    onShowYggSetup: () -> Unit,
    onShowYggPeersSetup: () -> Unit,
    onShowNostrRelaysSetup: () -> Unit
) {
    Text(text = stringResource(R.string.transports), style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(16.dp))

    // LAN
    TransportItem(
        title = stringResource(R.string.lan_transport),
        description = stringResource(R.string.lan_desc),
        enabled = transportsEnabled[FfiTransport.LAN] ?: false,
        ready = transportsReady[FfiTransport.LAN] ?: false,
        onToggle = onToggleLan
    )

    // Bluetooth
    TransportItem(
        title = stringResource(R.string.bt_transport),
        description = stringResource(R.string.bt_desc),
        enabled = transportsEnabled[FfiTransport.BT] ?: false,
        ready = transportsReady[FfiTransport.BT] ?: false,
        onToggle = onToggleBt,
        statusContent = {
            // Состояний три, а не два (FFI.md): выключено человеком;
            // включено, но радио не вручено — беда приложения, не сети;
            // включено, радио есть, а ступень не поднялась — и тогда надо
            // назвать причину. Причину ядро словами наружу не отдаёт,
            // но обе возможные приложение знает про себя само.
            if (transportsEnabled[FfiTransport.BT] == true) {
                val ready = transportsReady[FfiTransport.BT] ?: false
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    when {
                        !btHasRadio -> {
                            Text(
                                text = stringResource(R.string.bt_no_radio),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedButton(
                                onClick = onGrantBtPermissions,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.bt_grant))
                            }
                        }
                        !ready -> {
                            Text(
                                text = btNotReadyReason,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    )

    // Yggdrasil
    TransportItem(
        title = stringResource(R.string.ygg_transport),
        description = stringResource(R.string.ygg_desc),
        enabled = yggMode != FfiYggMode.OFF,
        ready = transportsReady[FfiTransport.YGG] ?: false,
        onToggle = { enabled -> if (!enabled) onSelectYggMode(FfiYggMode.OFF) else onSelectYggMode(FfiYggMode.EMBEDDED) },
        statusContent = {
            Column(modifier = Modifier.padding(top = 4.dp)) {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = yggMode == FfiYggMode.OFF,
                        onClick = { onSelectYggMode(FfiYggMode.OFF) },
                        label = { Text(stringResource(R.string.ygg_mode_off)) }
                    )
                    FilterChip(
                        selected = yggMode == FfiYggMode.EMBEDDED,
                        onClick = { onSelectYggMode(FfiYggMode.EMBEDDED) },
                        label = { Text(stringResource(R.string.ygg_mode_embedded)) }
                    )
                    FilterChip(
                        selected = yggMode == FfiYggMode.EXTERNAL,
                        onClick = { onSelectYggMode(FfiYggMode.EXTERNAL) },
                        label = { Text(stringResource(R.string.ygg_mode_external)) }
                    )
                }

                if (yggMode == FfiYggMode.EMBEDDED) {
                    Spacer(modifier = Modifier.height(4.dp))
                    val keyText = if (!yggKey.isNullOrBlank()) yggKey else stringResource(R.string.ygg_key_not_configured)
                    Text(
                        text = stringResource(R.string.ygg_key_status, keyText),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF4CAF50)
                    )
                    if (!yggAddress.isNullOrBlank()) {
                        Text(
                            text = stringResource(R.string.ygg_address_status, yggAddress),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Text(
                        text = stringResource(R.string.ygg_peers_status, yggPeers.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (yggPeers.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    if (yggPeers.isEmpty()) {
                        Text(
                            text = stringResource(R.string.ygg_no_peers_warning),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Row(modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedButton(onClick = onShowYggPeersSetup, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.setup_ygg_peers))
                        }
                    }
                } else if (yggMode == FfiYggMode.EXTERNAL) {
                    Spacer(modifier = Modifier.height(4.dp))
                    val keyText = if (!yggKey.isNullOrBlank()) yggKey else stringResource(R.string.ygg_key_not_configured)
                    Text(
                        text = stringResource(R.string.ygg_key_status, keyText),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (yggKey.isNullOrBlank()) MaterialTheme.colorScheme.outline else Color(0xFF4CAF50)
                    )
                    if (!yggAddress.isNullOrBlank()) {
                        Text(
                            text = stringResource(R.string.ygg_address_status, yggAddress),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Row(modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedButton(onClick = onShowYggSetup, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.setup_ygg))
                        }
                    }
                }
            }
        }
    )

    // Tor / Onion
    TransportItem(
        title = stringResource(R.string.tor_transport),
        description = stringResource(R.string.tor_desc),
        enabled = transportsEnabled[FfiTransport.ONION] ?: false,
        ready = transportsReady[FfiTransport.ONION] ?: false,
        onToggle = onToggleTor,
        statusContent = {
            if (transportsEnabled[FfiTransport.ONION] == true && !(transportsReady[FfiTransport.ONION] ?: false)) {
                torStatus?.let {
                    Column(modifier = Modifier.padding(top = 4.dp)) {
                        LinearProgressIndicator(
                            progress = { it.fraction },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                        )
                        Text(
                            text = if (it.blocked != null) stringResource(R.string.tor_blocked, it.blocked!!) else stringResource(R.string.tor_bootstrap, (it.fraction * 100).toInt()),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (it.blocked != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                        Text(text = it.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    )

    // Mail
    TransportItem(
        title = stringResource(R.string.mail_transport),
        description = stringResource(R.string.mail_desc),
        enabled = transportsEnabled[FfiTransport.MAIL] ?: false,
        ready = transportsReady[FfiTransport.MAIL] ?: false,
        onToggle = onToggleMail,
        statusContent = {
            mailStatus?.let { status ->
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    val statusText = when (status.state) {
                        FfiMailState.OFF -> stringResource(R.string.limit_never)
                        FfiMailState.NO_ACCOUNT -> stringResource(R.string.mail_not_configured)
                        FfiMailState.CONNECTING -> stringResource(R.string.mail_connecting)
                        FfiMailState.READY -> if (status.viaTor) stringResource(R.string.mail_ready_tor, status.address ?: "") else stringResource(R.string.mail_ready, status.address ?: "")
                        FfiMailState.FAILED -> stringResource(R.string.mail_failed, status.detail ?: "Unknown error")
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (status.state == FfiMailState.FAILED) MaterialTheme.colorScheme.error else if (status.state == FfiMailState.READY) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline
                    )
                    
                    if (status.state == FfiMailState.READY) {
                        status.mailboxUsedBytes?.let { used ->
                            val limit = status.mailboxLimitBytes
                            val storageText = if (limit != null) {
                                stringResource(R.string.mailbox_storage, chat.ratatosk.android.util.FileUtils.formatFileSize(used), chat.ratatosk.android.util.FileUtils.formatFileSize(limit))
                            } else {
                                stringResource(R.string.mailbox_usage, chat.ratatosk.android.util.FileUtils.formatFileSize(used))
                            }
                            Text(
                                text = storageText,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (status.mailboxCrowded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    
                    Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onShowMailSetup, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.setup_mail))
                        }
                        OutlinedButton(onClick = onShowMailCreate, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.create_mail))
                        }
                    }
                    if (status.state != FfiMailState.NO_ACCOUNT) {
                        TextButton(onClick = onClearMailAccount, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                            Text(stringResource(R.string.remove))
                        }
                    }
                }
            }
        }
    )

    // Nostr
    TransportItem(
        title = stringResource(R.string.nostr_transport),
        description = stringResource(R.string.nostr_desc),
        enabled = transportsEnabled[FfiTransport.NOSTR] ?: false,
        ready = transportsReady[FfiTransport.NOSTR] ?: false,
        onToggle = onToggleNostr,
        statusContent = {
            if (transportsEnabled[FfiTransport.NOSTR] == true) {
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    if (!nostrNpub.isNullOrBlank()) {
                        val clipboard = LocalClipboardManager.current
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    clipboard.setText(AnnotatedString(nostrNpub))
                                }
                                .padding(vertical = 2.dp)
                        ) {
                            val npubShort = if (nostrNpub.length > 24) "${nostrNpub.take(12)}...${nostrNpub.takeLast(8)}" else nostrNpub
                            Text(
                                text = stringResource(R.string.nostr_npub_status, npubShort),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy Npub",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.nostr_direct_label),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Switch(
                            checked = nostrDirect,
                            onCheckedChange = onToggleNostrDirect
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    if (nostrRelays.isEmpty()) {
                        Text(
                            text = stringResource(R.string.nostr_no_relays_warning),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        val activeRelays = nostrRelaysAlive?.count { it.up } ?: 0
                        Text(
                            text = stringResource(R.string.ygg_peers_status, nostrRelays.size) + if (activeRelays > 0) " (активно: $activeRelays)" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (activeRelays > 0) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = onShowNostrRelaysSetup,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.setup_nostr_relays))
                    }
                }
            }
        }
    )
}

@Composable
fun SettingsCompanionSection(onPairedDevicesClick: () -> Unit) {
    Text(text = stringResource(R.string.companion_devices), style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedButton(
        onClick = onPairedDevicesClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.Computer, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(stringResource(R.string.paired_devices))
    }
}

@Composable
fun SettingsPrivacySection(
    showName: Boolean,
    showText: Boolean,
    onToggleShowName: (Boolean) -> Unit,
    onToggleShowText: (Boolean) -> Unit
) {
    Text(text = stringResource(R.string.notification_privacy), style = MaterialTheme.typography.titleMedium)
    Text(
        text = stringResource(R.string.privacy_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp)
    )

    Row(
        modifier = Modifier.fillMaxWidth().clickable { onToggleShowName(!showName) }.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.show_sender_name))
        Switch(checked = showName, onCheckedChange = onToggleShowName)
    }

    Row(
        modifier = Modifier.fillMaxWidth().clickable { onToggleShowText(!showText) }.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.show_message_text))
        Switch(checked = showText, onCheckedChange = onToggleShowText)
    }
}

@Composable
fun SettingsDownloadPathSection(viewModel: RatatoskViewModel) {
    val context = LocalContext.current
    Text(text = stringResource(R.string.storage), style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(16.dp))

    val downloadDirUri by viewModel.downloadDirUri.collectAsState()
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            viewModel.setDownloadDirUri(it)
        }
    }
    OutlinedButton(onClick = { folderLauncher.launch(null) }, modifier = Modifier.fillMaxWidth()) {
        val folderName = if (downloadDirUri != null) {
            val doc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, android.net.Uri.parse(downloadDirUri!!))
            doc?.name ?: stringResource(R.string.settings)
        } else "Downloads/ratatosk"
        Text("${stringResource(R.string.save_folder)}: $folderName")
    }
}

@Composable
fun SettingsBackupSection(
    viewModel: RatatoskViewModel,
    snackbarHostState: SnackbarHostState
) {
    var showExportDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Text(text = stringResource(R.string.backup_recovery), style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(16.dp))

    OutlinedButton(
        onClick = { showExportDialog = true },
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.Backup, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(stringResource(R.string.create_backup_archive))
    }

    if (showExportDialog) {
        ExportArchiveDialog(
            viewModel = viewModel,
            onDismiss = { showExportDialog = false },
            onSuccess = { path, _ ->
                scope.launch {
                    snackbarHostState.showSnackbar("Backup saved to $path")
                }
            }
        )
    }
}

@Composable
fun ExportArchiveDialog(
    viewModel: RatatoskViewModel,
    onDismiss: () -> Unit,
    onSuccess: (String, String) -> Unit
) {
    var passphrase by remember { mutableStateOf("") }
    var selectedScope by remember { mutableStateOf(FfiExportScope.EVERYTHING) }
    var isExporting by remember { mutableStateOf(false) }
    var exportResult by remember { mutableStateOf<org.ratatosk.core.FfiExported?>(null) }
    
    val clipboardManager = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = { if (!isExporting) onDismiss() },
        title = { Text(stringResource(R.string.create_backup)) },
        text = {
            Column {
                if (exportResult == null) {
                    Text(stringResource(R.string.export_scope_desc))
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(stringResource(R.string.scope), style = MaterialTheme.typography.labelMedium)
                    FfiExportScope.entries.forEach { scope ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { selectedScope = scope }
                        ) {
                            RadioButton(selected = selectedScope == scope, onClick = { selectedScope = scope })
                            val label = when(scope) {
                                FfiExportScope.EVERYTHING -> stringResource(R.string.scope_everything)
                                FfiExportScope.WITHOUT_ATTACHMENTS -> stringResource(R.string.scope_without_attachments)
                                FfiExportScope.SOCIAL_GRAPH -> stringResource(R.string.scope_social_graph)
                            }
                            Text(label)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text(stringResource(R.string.passphrase_optional)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    if (isExporting) {
                        Spacer(modifier = Modifier.height(16.dp))
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                        Text(stringResource(R.string.creating_archive), modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                } else {
                    Text(stringResource(R.string.backup_success), fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.backup_path, exportResult!!.path), style = MaterialTheme.typography.labelSmall)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(stringResource(R.string.recovery_key_critical), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = exportResult!!.keyText,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { clipboardManager.setText(AnnotatedString(exportResult!!.keyText)) }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (exportResult == null) {
                Button(
                    onClick = {
                        isExporting = true
                        viewModel.exportHistory(selectedScope, passphrase.takeIf { it.isNotBlank() }) { result ->
                            exportResult = result
                            isExporting = false
                            onSuccess(result.path, result.keyText)
                        }
                    },
                    enabled = !isExporting
                ) {
                    Text(stringResource(R.string.export))
                }
            } else {
                Button(onClick = onDismiss) {
                    Text(stringResource(R.string.close))
                }
            }
        },
        dismissButton = {
            if (exportResult == null) {
                TextButton(onClick = onDismiss, enabled = !isExporting) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    )
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, widthDp = 360)
@Composable
fun NostrTransportPreview() {
    MaterialTheme {
        Surface(modifier = Modifier.padding(16.dp)) {
            TransportItem(
                title = "Реле Nostr (через Tor)",
                description = "Передает сообщения через реле Nostr поверх сети Tor.",
                enabled = true,
                ready = true,
                onToggle = {},
                statusContent = {
                    Column(modifier = Modifier.padding(top = 4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        ) {
                            Text(
                                text = "Npub: npub1abc123...xyz890",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy Npub",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Прямое соединение (мимо Tor)",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Switch(checked = false, onCheckedChange = {})
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "Реле настроено: 2 (активно: 2)",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF4CAF50)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = {},
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Настроить реле Nostr")
                        }
                    }
                }
            )
        }
    }
}

@Composable
fun SettingsStorageSection(
    viewModel: RatatoskViewModel,
    snackbarHostState: SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val context = LocalContext.current
    Text(text = stringResource(R.string.storage), style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(16.dp))

    val autoAcceptLimit by viewModel.autoAcceptLimit.collectAsState()
    val limits = listOf(
        0UL to stringResource(R.string.limit_never),
        1024UL * 1024UL to stringResource(R.string.limit_1mb),
        1024UL * 1024UL * 10UL to stringResource(R.string.limit_10mb),
        1024UL * 1024UL * 100UL to stringResource(R.string.limit_100mb),
        null to stringResource(R.string.limit_always)
    )
    var showLimitMenu by remember { mutableStateOf(false) }

    Box {
        OutlinedButton(onClick = { showLimitMenu = true }, modifier = Modifier.fillMaxWidth()) {
            val currentLabel = limits.find { it.first == autoAcceptLimit }?.second ?: stringResource(R.string.limit_never)
            Text("${stringResource(R.string.auto_accept_limit)}: $currentLabel")
        }
        DropdownMenu(expanded = showLimitMenu, onDismissRequest = { showLimitMenu = false }) {
            limits.forEach { (limit, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { viewModel.setAutoAcceptLimit(limit); showLimitMenu = false })
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    val downloadDirUri by viewModel.downloadDirUri.collectAsState()
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            viewModel.setDownloadDirUri(it)
        }
    }
    OutlinedButton(onClick = { folderLauncher.launch(null) }, modifier = Modifier.fillMaxWidth()) {
        val folderName = if (downloadDirUri != null) {
            val doc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, android.net.Uri.parse(downloadDirUri!!))
            doc?.name ?: stringResource(R.string.settings)
        } else "Downloads/ratatosk"
        Text("${stringResource(R.string.save_folder)}: $folderName")
    }

    Spacer(modifier = Modifier.height(16.dp))

    OutlinedButton(
        onClick = {
            viewModel.sweepOrphanFiles { swept ->
                val resultText = context.getString(R.string.sweep_result, swept.bytes.toString(), swept.files.toString(), swept.chunks.toString())
                scope.launch { snackbarHostState.showSnackbar(resultText) }
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.sweep_orphaned))
            Text(stringResource(R.string.sweep_orphaned_desc), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun SettingsThemeSection(
    viewModel: RatatoskViewModel,
    chatTheme: chat.ratatosk.android.ui.theme.ChatThemeData
) {
    val context = LocalContext.current
    Text(text = stringResource(R.string.chat_theme), style = MaterialTheme.typography.titleMedium)
    val themeColors = listOf(Color.Unspecified, Color.Gray, Color(0xFF2196F3), Color(0xFF4CAF50), Color(0xFFF44336), Color(0xFFFF9800), Color(0xFF9C27B0), Color(0xFF00BCD4))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(themeColors) { color ->
            val isSelected = chatTheme.themeColor == color
            Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(if (color == Color.Unspecified) MaterialTheme.colorScheme.outline.copy(alpha = 0.3f) else color).clickable { viewModel.updateChatTheme { it.copy(themeColor = color) } }.then(if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier), contentAlignment = Alignment.Center) {
                if (color == Color.Unspecified) Icon(Icons.Default.Settings, contentDescription = "Auto", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) {}
            viewModel.updateChatTheme { theme -> theme.copy(backgroundImageUri = it.toString()) }
        }
    }
    OutlinedButton(onClick = { imageLauncher.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) {
        Text(if (chatTheme.backgroundImageUri != null) stringResource(R.string.change_background) else stringResource(R.string.set_background))
    }
    if (chatTheme.backgroundImageUri != null) {
        Slider(value = chatTheme.backgroundOpacity, onValueChange = { viewModel.updateChatTheme { theme -> theme.copy(backgroundOpacity = it) } }, valueRange = 0f..1f)
        TextButton(onClick = { viewModel.updateChatTheme { it.copy(backgroundImageUri = null) } }) { Text(stringResource(R.string.remove_background)) }
    }
}

@Composable
fun TransportItem(
    title: String,
    description: String,
    enabled: Boolean,
    ready: Boolean,
    onToggle: (Boolean) -> Unit,
    statusContent: @Composable () -> Unit = {}
) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(if (ready) Color(0xFF4CAF50) else Color.Gray))
                    }
                    Text(text = description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            statusContent()
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, widthDp = 360)
@Composable
fun YggdrasilTransportPreview() {
    MaterialTheme {
        Surface(modifier = Modifier.padding(16.dp)) {
            TransportItem(
                title = "Сеть Yggdrasil (Mesh)",
                description = "Маршрутизирует трафик через меш-сеть Yggdrasil.",
                enabled = true,
                ready = true,
                onToggle = {},
                statusContent = {
                    Column(modifier = Modifier.padding(top = 4.dp)) {
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            FilterChip(
                                selected = false,
                                onClick = {},
                                label = { Text("Выключен") }
                            )
                            FilterChip(
                                selected = true,
                                onClick = {},
                                label = { Text("Встроенный узел") }
                            )
                            FilterChip(
                                selected = false,
                                onClick = {},
                                label = { Text("Внешний узел") }
                            )
                        }
                    }
                }
            )
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, widthDp = 360)
@Composable
fun YggPeersDialogPreview() {
    MaterialTheme {
        Surface(modifier = Modifier.padding(16.dp)) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Настроить пиры", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
                
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("tcp://peer1.yggdrasil.net:65535", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(modifier = Modifier.size(8.dp), shape = CircleShape, color = Color(0xFF4CAF50)) {}
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Подключен", style = MaterialTheme.typography.labelSmall, color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("• 42 мс", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        IconButton(onClick = {}) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = {}) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("tls://peer2.yggdrasil.net:65535", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(modifier = Modifier.size(8.dp), shape = CircleShape, color = MaterialTheme.colorScheme.error) {}
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Не подключен", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        IconButton(onClick = {}) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = {}) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Добавить пир")
                }
            }
        }
    }
}


