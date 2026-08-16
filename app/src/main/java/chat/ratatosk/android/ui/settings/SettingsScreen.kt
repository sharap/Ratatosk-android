package chat.ratatosk.android.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import uniffi.ratatosk_ffi.lanWarning

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: RatatoskViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val lanEnabled by viewModel.lanEnabled.collectAsState()
    var showLanWarning by remember { mutableStateOf(false) }
    var showPermissionRationale by remember { mutableStateOf(false) }
    val chatTheme by viewModel.chatTheme.collectAsState()

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
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            viewModel.updateChatTheme { it.copy(backgroundImageUri = it.toString()) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).padding(16.dp)) {
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
            
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            
            Text(
                text = stringResource(R.string.chat_theme),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(16.dp))

            Text(stringResource(R.string.bubble_color), style = MaterialTheme.typography.labelMedium)
            val colors = listOf(
                MaterialTheme.colorScheme.primaryContainer,
                Color(0xFFE1F5FE),
                Color(0xFFF1F8E9),
                Color(0xFFFFF3E0),
                Color(0xFFFCE4EC)
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(colors) { color ->
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(color)
                            .clickable {
                                viewModel.updateChatTheme { it.copy(outgoingBubbleColor = color) }
                            }
                            .then(if (chatTheme.outgoingBubbleColor == color) Modifier.background(Color.Black.copy(0.1f)) else Modifier)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = { imageLauncher.launch("image/*") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (chatTheme.backgroundImageUri != null) stringResource(R.string.change_background) else stringResource(R.string.set_background))
            }
            if (chatTheme.backgroundImageUri != null) {
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
