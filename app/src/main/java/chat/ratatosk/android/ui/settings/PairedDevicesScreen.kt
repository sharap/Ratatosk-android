package chat.ratatosk.android.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import chat.ratatosk.android.R
import chat.ratatosk.android.ui.RatatoskViewModel
import chat.ratatosk.android.util.toHexString
import qrcode.QRCode
import org.ratatosk.core.FfiPairedDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairedDevicesScreen(
    viewModel: RatatoskViewModel,
    onBack: () -> Unit
) {
    val pairedDevices by viewModel.pairedDevices.collectAsState()
    val pairingUri by viewModel.pairingUri.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    LaunchedEffect(pairingUri) {
        android.util.Log.d("PairedDevices", "pairingUri changed: $pairingUri")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.paired_devices)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_device))
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            if (pairedDevices.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.no_devices_found), color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn {
                    items(pairedDevices) { device ->
                        DeviceItem(
                            device = device,
                            onRevoke = { viewModel.revokePairing(device.deviceId) }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var deviceName by remember { mutableStateOf("") }
        
        AlertDialog(
            onDismissRequest = { 
                showAddDialog = false
                viewModel.stopPairing()
            },
            title = { Text(stringResource(R.string.add_companion)) },
            text = {
                Column {
                    if (pairingUri == null) {
                        Text(stringResource(R.string.pairing_qr_desc))
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = deviceName,
                            onValueChange = { deviceName = it },
                            label = { Text(stringResource(R.string.device_name)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.pairing_instructions))
                            Spacer(modifier = Modifier.height(16.dp))
                            if (pairingUri.isNullOrBlank()) {
                                CircularProgressIndicator()
                                Text(stringResource(R.string.waiting_for_core), style = MaterialTheme.typography.labelSmall)
                            } else {
                                QRCodeImage(pairingUri!!)
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                val clipboardManager = LocalClipboardManager.current
                                OutlinedCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.outlinedCardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = pairingUri!!,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 3
                                        )
                                        IconButton(onClick = {
                                            clipboardManager.setText(AnnotatedString(pairingUri!!))
                                        }) {
                                            Icon(
                                                Icons.Default.ContentCopy,
                                                contentDescription = "Copy",
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(deviceName, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            confirmButton = {
                if (pairingUri == null) {
                    Button(
                        onClick = { viewModel.startPairing(deviceName) },
                        enabled = deviceName.isNotBlank()
                    ) {
                        Text(stringResource(R.string.generate_qr))
                    }
                } else {
                    TextButton(onClick = { 
                        showAddDialog = false
                        viewModel.stopPairing()
                    }) {
                        Text(stringResource(R.string.done))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showAddDialog = false
                    viewModel.stopPairing()
                }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
fun DeviceItem(
    device: FfiPairedDevice,
    onRevoke: () -> Unit
) {
    var showRevokeConfirm by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = { 
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(device.label)
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    modifier = Modifier.size(8.dp),
                    shape = CircleShape,
                    color = if (device.connected) Color.Green else Color.Gray
                ) {}
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (device.connected) stringResource(R.string.connected) else stringResource(R.string.offline),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (device.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
            }
        },
        supportingContent = { 
            Column {
                val pairedDate = java.util.Date(device.pairedMs.toLong())
                Text(stringResource(R.string.paired_at, java.text.DateFormat.getDateTimeInstance().format(pairedDate)))
                
                if (device.lastSeenMs > 0UL) {
                    val lastSeenDate = java.util.Date(device.lastSeenMs.toLong())
                    Text(stringResource(R.string.last_seen, java.text.DateFormat.getDateTimeInstance().format(lastSeenDate)))
                } else {
                    Text(stringResource(R.string.never_connected))
                }
                
                if (device.cacheExpired) {
                    Text(
                        stringResource(R.string.cache_expired_desc),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        },
        leadingContent = {
            Icon(
                imageVector = Icons.Default.Computer,
                contentDescription = null,
                tint = if (device.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            IconButton(onClick = { showRevokeConfirm = true }) {
                Icon(Icons.Default.Delete, contentDescription = "Revoke", tint = MaterialTheme.colorScheme.error)
            }
        }
    )

    if (showRevokeConfirm) {
        AlertDialog(
            onDismissRequest = { showRevokeConfirm = false },
            title = { Text(stringResource(R.string.revoke_device_title)) },
            text = { Text(stringResource(R.string.revoke_device_desc)) },
            confirmButton = {
                TextButton(onClick = { 
                    onRevoke()
                    showRevokeConfirm = false
                }) {
                    Text(stringResource(R.string.revoke), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
fun QRCodeImage(content: String) {
    val bitmap = remember(content) {
        try {
            QRCode(content).render().nativeImage() as? android.graphics.Bitmap
        } catch (e: Exception) {
            android.util.Log.e("PairedDevices", "Failed to render QR", e)
            null
        }
    }
    
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Pairing QR Code",
            modifier = Modifier.size(200.dp).background(Color.White).padding(8.dp)
        )
    } else {
        Box(modifier = Modifier.size(200.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.error_qr), color = MaterialTheme.colorScheme.error)
        }
    }
}
