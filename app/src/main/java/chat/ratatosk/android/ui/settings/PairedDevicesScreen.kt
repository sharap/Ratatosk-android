package chat.ratatosk.android.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
                title = { Text("Paired Devices") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Device")
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            if (pairedDevices.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No paired devices found", color = MaterialTheme.colorScheme.outline)
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
            title = { Text("Add Companion Device") },
            text = {
                Column {
                    if (pairingUri == null) {
                        Text("Enter a name for the new device to generate a pairing QR code.")
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = deviceName,
                            onValueChange = { deviceName = it },
                            label = { Text("Device Name") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Scan this QR code with your Ratatosk PC client:")
                            Spacer(modifier = Modifier.height(16.dp))
                            if (pairingUri.isNullOrBlank()) {
                                CircularProgressIndicator()
                                Text("Waiting for core...", style = MaterialTheme.typography.labelSmall)
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
                        Text("Generate QR")
                    }
                } else {
                    TextButton(onClick = { 
                        showAddDialog = false
                        viewModel.stopPairing()
                    }) {
                        Text("Done")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showAddDialog = false
                    viewModel.stopPairing()
                }) {
                    Text("Cancel")
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
        headlineContent = { Text("Device ID: ${device.deviceId.toHexString().take(8)}...") },
        supportingContent = { 
            val date = java.util.Date(device.pairedMs.toLong())
            Text("Paired on: ${java.text.DateFormat.getDateTimeInstance().format(date)}")
        },
        leadingContent = {
            Icon(Icons.Default.Computer, contentDescription = null)
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
            title = { Text("Revoke Device") },
            text = { Text("Are you sure you want to revoke access for this device? It will no longer be able to sync messages.") },
            confirmButton = {
                TextButton(onClick = { 
                    onRevoke()
                    showRevokeConfirm = false
                }) {
                    Text("Revoke", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeConfirm = false }) {
                    Text("Cancel")
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
            Text("QR Error", color = MaterialTheme.colorScheme.error)
        }
    }
}
