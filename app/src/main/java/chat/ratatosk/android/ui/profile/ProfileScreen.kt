package chat.ratatosk.android.ui.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import chat.ratatosk.android.R
import chat.ratatosk.android.ui.RatatoskViewModel
import qrcode.QRCode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(viewModel: RatatoskViewModel) {
    val fingerprint by viewModel.fingerprint.collectAsState()
    val userName by viewModel.userName.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    var showMyQr by remember { mutableStateOf(false) }

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
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                modifier = Modifier.size(100.dp),
                shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = userName?.take(1)?.uppercase() ?: "R",
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = userName ?: "User",
                style = MaterialTheme.typography.titleLarge
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = fingerprint ?: "Loading...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )

            Spacer(modifier = Modifier.height(32.dp))

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
                        viewModel.getMyContactUri()?.let { uri ->
                            clipboardManager.setText(AnnotatedString(uri))
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(androidx.compose.material.icons.Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.copy_link))
                }
            }
        }
    }

    if (showMyQr) {
        val myUri = viewModel.getMyContactUri() ?: ""
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
