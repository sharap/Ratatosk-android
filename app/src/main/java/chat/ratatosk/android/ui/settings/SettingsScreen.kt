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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
fun SettingsScreen(viewModel: RatatoskViewModel) {
    val context = LocalContext.current
    val transportsEnabled by viewModel.transportsEnabled.collectAsState()
    val transportsReady by viewModel.transportsReady.collectAsState()
    val torStatus by viewModel.torStatus.collectAsState()
    val mailStatus by viewModel.mailStatus.collectAsState()
    val mailAccount by viewModel.mailAccount.collectAsState()
    
    val showName by viewModel.notificationsShowName.collectAsState()
    val showText by viewModel.notificationsShowText.collectAsState()
    var showLanWarning by remember { mutableStateOf(false) }
    var showPermissionRationale by remember { mutableStateOf(false) }
    var showMailSetup by remember { mutableStateOf(false) }
    var showMailCreate by remember { mutableStateOf(false) }
    
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
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(scrollState)
        ) {
            // --- Transports Section ---
            Text(text = "Transports", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(16.dp))

            // LAN
            TransportItem(
                title = stringResource(R.string.lan_transport),
                description = stringResource(R.string.lan_desc),
                enabled = transportsEnabled[FfiTransport.LAN] ?: false,
                ready = transportsReady[FfiTransport.LAN] ?: false,
                onToggle = { 
                    if (it) showLanWarning = true else viewModel.setTransportEnabled(FfiTransport.LAN, false)
                }
            )

            // Tor / Onion
            TransportItem(
                title = stringResource(R.string.tor_transport),
                description = stringResource(R.string.tor_desc),
                enabled = transportsEnabled[FfiTransport.ONION] ?: false,
                ready = transportsReady[FfiTransport.ONION] ?: false,
                onToggle = { viewModel.setTransportEnabled(FfiTransport.ONION, it) },
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
                onToggle = { viewModel.setTransportEnabled(FfiTransport.MAIL, it) },
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
                                OutlinedButton(onClick = { showMailSetup = true }, modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.setup_mail))
                                }
                                OutlinedButton(onClick = { showMailCreate = true }, modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.create_mail))
                                }
                            }
                            if (status.state != FfiMailState.NO_ACCOUNT) {
                                TextButton(onClick = { viewModel.clearMailAccount() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                                    Text("Clear Mail Account")
                                }
                            }
                        }
                    }
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // --- Notifications Section ---
            Text(text = stringResource(R.string.notification_privacy), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.privacy_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth().clickable { viewModel.setNotificationsShowName(!showName) }.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.show_sender_name))
                Switch(checked = showName, onCheckedChange = { viewModel.setNotificationsShowName(it) })
            }

            Row(
                modifier = Modifier.fillMaxWidth().clickable { viewModel.setNotificationsShowText(!showText) }.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.show_message_text))
                Switch(checked = showText, onCheckedChange = { viewModel.setNotificationsShowText(it) })
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // --- Storage Section ---
            Text(text = "Storage & Cleanup", style = MaterialTheme.typography.titleMedium)
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

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            
            // --- Theme Section ---
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
                    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Server URL") }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = viaT, onCheckedChange = { viaT = it })
                        Text(stringResource(R.string.via_tor))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.createMailAccount(url, viaT); showMailCreate = false }) { Text("Register") }
            },
            dismissButton = { TextButton(onClick = { showMailCreate = false }) { Text(stringResource(R.string.cancel)) } }
        )
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
