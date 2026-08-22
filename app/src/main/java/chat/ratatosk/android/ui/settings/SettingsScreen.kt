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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import chat.ratatosk.android.R
import chat.ratatosk.android.ui.RatatoskViewModel
import kotlinx.coroutines.launch
import org.ratatosk.core.lanWarning

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: RatatoskViewModel) {
    val context = LocalContext.current
    val lanEnabled by viewModel.lanEnabled.collectAsState()
    val torEnabled by viewModel.torEnabled.collectAsState()
    val showName by viewModel.notificationsShowName.collectAsState()
    val showText by viewModel.notificationsShowText.collectAsState()
    var showLanWarning by remember { mutableStateOf(false) }
    var showPermissionRationale by remember { mutableStateOf(false) }
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
            // At least some permissions granted, try to enable LAN
            viewModel.setLanEnabled(true)
        }
    }

    fun checkAndEnableLan() {
        val needsPermissions = permissionsToRequest.any {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needsPermissions) {
            showPermissionRationale = true
        } else {
            viewModel.setLanEnabled(true)
        }
    }

    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                android.util.Log.e("SettingsScreen", "Failed to take persistable permission", e)
            }
            viewModel.updateChatTheme { theme -> theme.copy(backgroundImageUri = it.toString()) }
        }
    }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.setDownloadDirUri(it)
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.lan_transport),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stringResource(R.string.lan_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = lanEnabled,
                    onCheckedChange = { 
                        if (it) showLanWarning = true else {
                            viewModel.setLanEnabled(false)
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Enable Tor",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Routes your traffic through the Tor network for improved anonymity",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = torEnabled,
                    onCheckedChange = { viewModel.setTorEnabled(it) }
                )
            }
            
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text(
                text = stringResource(R.string.notification_privacy),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.privacy_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setNotificationsShowName(!showName) }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.show_sender_name))
                Switch(checked = showName, onCheckedChange = { viewModel.setNotificationsShowName(it) })
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setNotificationsShowText(!showText) }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.show_message_text))
                Switch(checked = showText, onCheckedChange = { viewModel.setNotificationsShowText(it) })
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text(
                text = stringResource(R.string.file_attachments),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.auto_accept_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )

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
                OutlinedButton(
                    onClick = { showLimitMenu = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val currentLabel = limits.find { it.first == autoAcceptLimit }?.second ?: stringResource(R.string.limit_never)
                    Text("${stringResource(R.string.auto_accept_limit)}: $currentLabel")
                }
                DropdownMenu(
                    expanded = showLimitMenu,
                    onDismissRequest = { showLimitMenu = false }
                ) {
                    limits.forEach { (limit, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                viewModel.setAutoAcceptLimit(limit)
                                showLimitMenu = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val downloadDirUri by viewModel.downloadDirUri.collectAsState()
            OutlinedButton(
                onClick = { folderLauncher.launch(null) },
                modifier = Modifier.fillMaxWidth()
            ) {
                val folderName = if (downloadDirUri != null) {
                    val doc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, android.net.Uri.parse(downloadDirUri!!))
                    doc?.name ?: stringResource(R.string.settings) // Fallback label
                } else "Downloads/ratatosk"
                Text("${stringResource(R.string.save_folder)}: $folderName")
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = {
                    viewModel.sweepOrphanFiles { swept ->
                        val resultText = context.getString(
                            R.string.sweep_result,
                            swept.bytes.toString(),
                            swept.files.toString(),
                            swept.chunks.toString()
                        )
                        scope.launch {
                            snackbarHostState.showSnackbar(resultText)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.sweep_orphaned))
                    Text(
                        stringResource(R.string.sweep_orphaned_desc),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            
            Text(
                text = stringResource(R.string.chat_theme),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(16.dp))

            Text(stringResource(R.string.theme_color), style = MaterialTheme.typography.labelMedium)
            val themeColors = listOf(
                Color.Unspecified, // System Default (Dynamic)
                Color.Gray,        // Neutral/Monochrome
                Color(0xFF2196F3), // Blue
                Color(0xFF4CAF50), // Green
                Color(0xFFF44336), // Red
                Color(0xFFFF9800), // Orange
                Color(0xFF9C27B0), // Purple
                Color(0xFF00BCD4)  // Cyan
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(themeColors) { color ->
                    val isSelected = chatTheme.themeColor == color
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (color == Color.Unspecified) MaterialTheme.colorScheme.outline.copy(alpha = 0.3f) else color)
                            .clickable {
                                viewModel.updateChatTheme { it.copy(themeColor = color) }
                            }
                            .then(if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier),
                        contentAlignment = Alignment.Center
                    ) {
                        if (color == Color.Unspecified) {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.Settings,
                                contentDescription = "Auto",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = { imageLauncher.launch(arrayOf("image/*")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (chatTheme.backgroundImageUri != null) stringResource(R.string.change_background) else stringResource(R.string.set_background))
            }
            
            if (chatTheme.backgroundImageUri != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${stringResource(R.string.background_opacity)}: ${(chatTheme.backgroundOpacity * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium
                )
                Slider(
                    value = chatTheme.backgroundOpacity,
                    onValueChange = { viewModel.updateChatTheme { theme -> theme.copy(backgroundOpacity = it) } },
                    valueRange = 0f..1f
                )

                TextButton(onClick = { viewModel.updateChatTheme { it.copy(backgroundImageUri = null) } }) {
                    Text(stringResource(R.string.remove_background))
                }
            }
        }
    }

    if (showLanWarning) {
        AlertDialog(
            onDismissRequest = { showLanWarning = false },
            title = { Text(stringResource(R.string.lan_warning_title)) },
            text = { Text(lanWarning()) },
            confirmButton = {
                TextButton(onClick = { 
                    showLanWarning = false
                    checkAndEnableLan()
                }) {
                    Text(stringResource(R.string.enable))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLanWarning = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showPermissionRationale) {
        AlertDialog(
            onDismissRequest = { showPermissionRationale = false },
            title = { Text(stringResource(R.string.lan_transport)) },
            text = { Text(stringResource(R.string.lan_permission_rationale)) },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionRationale = false
                    permissionsLauncher.launch(permissionsToRequest)
                }) {
                    Text(stringResource(R.string.retry))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionRationale = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
