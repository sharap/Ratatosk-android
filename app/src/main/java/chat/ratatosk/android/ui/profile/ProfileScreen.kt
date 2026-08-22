package chat.ratatosk.android.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.ratatosk.android.R
import chat.ratatosk.android.ui.RatatoskViewModel
import chat.ratatosk.android.ui.components.Avatar
import qrcode.QRCode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: RatatoskViewModel,
    onCropAvatar: () -> Unit
) {
    val context = LocalContext.current
    val fingerprint by viewModel.fingerprint.collectAsState()
    val userName by viewModel.userName.collectAsState()
    val notices by viewModel.honestNotices.collectAsState()
    val myAvatar by viewModel.myAvatar.collectAsState()
    val torEnabled by viewModel.torEnabled.collectAsState()
    val onionAddress by viewModel.onionAddress.collectAsState()
    val cardVersion by viewModel.cardVersion.collectAsState()
    val myContactUri by viewModel.myContactUri.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    
    var showMyQr by remember { mutableStateOf(false) }
    var showEditName by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    val avatarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            viewModel.setPendingAvatarUri(it)
            onCropAvatar()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile)) },
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
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(contentAlignment = Alignment.BottomEnd) {
                Avatar(
                    avatarBytes = myAvatar,
                    name = userName ?: "U",
                    size = 100.dp,
                    modifier = Modifier.clickable { avatarLauncher.launch("image/*") }
                )
                SmallFloatingActionButton(
                    onClick = { avatarLauncher.launch("image/*") },
                    modifier = Modifier.size(32.dp),
                    shape = androidx.compose.foundation.shape.CircleShape,
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(Icons.Default.AddAPhoto, contentDescription = "Change Avatar", modifier = Modifier.size(16.dp))
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = userName ?: "User",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = { 
                    newName = userName ?: ""
                    showEditName = true 
                }) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit Name", modifier = Modifier.size(20.dp))
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))

            // Identity Card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.fingerprint_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = fingerprint ?: "Loading...",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        if (cardVersion != null) {
                            Text(
                                text = "v$cardVersion",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                        }
                        IconButton(onClick = {
                            fingerprint?.let { clipboardManager.setText(AnnotatedString(it)) }
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                        }
                    }
                }
            }

            if (torEnabled) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Onion Address",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = onionAddress ?: "Waiting for Tor...",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            if (onionAddress != null) {
                                IconButton(onClick = {
                                    clipboardManager.setText(AnnotatedString(onionAddress!!))
                                }) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { showMyQr = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.QrCode, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.my_qr_code))
                }

                OutlinedButton(
                    onClick = {
                        viewModel.getMyContactUri()
                        myContactUri?.let { uri ->
                            clipboardManager.setText(AnnotatedString(uri))
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.copy_link))
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            OutlinedButton(
                onClick = { viewModel.logout() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Logout, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Switch Account")
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Security Notices Section (§14)
            Text(
                text = stringResource(R.string.honest_notices_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(modifier = Modifier.height(8.dp))
            notices.forEach { notice ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Text(
                        text = notice,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    if (showEditName) {
        AlertDialog(
            onDismissRequest = { showEditName = false },
            title = { Text(stringResource(R.string.edit_name)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(stringResource(R.string.enter_display_name)) },
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.name_restart_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) {
                        viewModel.setDisplayName(newName)
                        showEditName = false
                    }
                }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditName = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showMyQr) {
        LaunchedEffect(Unit) {
            viewModel.getMyContactUri()
        }
        val myUri = myContactUri ?: ""
        AlertDialog(
            onDismissRequest = { showMyQr = false },
            title = { Text(stringResource(R.string.my_qr_code)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (myUri.isNotEmpty()) {
                        val qrCode = QRCode(myUri).render().nativeImage() as android.graphics.Bitmap
                        Image(
                            bitmap = qrCode.asImageBitmap(),
                            contentDescription = "My QR Code",
                            modifier = Modifier.size(200.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = myUri,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    } else {
                        Text("Identity not available")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (myUri.isNotEmpty()) {
                        clipboardManager.setText(AnnotatedString(myUri))
                    }
                }) {
                    Text(stringResource(R.string.copy))
                }
            },
            dismissButton = {
                TextButton(onClick = { showMyQr = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }
}
