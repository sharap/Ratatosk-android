package chat.ratatosk.android.ui.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import chat.ratatosk.android.R
import chat.ratatosk.android.ui.RatatoskViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkCompanionScreen(
    viewModel: RatatoskViewModel,
    onScan: () -> Unit,
    onBack: () -> Unit,
    initialUri: String? = null
) {
    var inviteUri by remember { mutableStateOf(initialUri ?: "") }
    var label by remember { mutableStateOf("My Companion") }
    var port by remember { mutableStateOf("29862") }
    var peerAddr by remember { mutableStateOf("") }
    var useCache by remember { mutableStateOf(true) }
    var useTor by remember { mutableStateOf(false) }

    LaunchedEffect(initialUri) {
        if (initialUri != null) {
            inviteUri = initialUri
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.link_companion_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OutlinedTextField(
                value = inviteUri,
                onValueChange = { inviteUri = it },
                label = { Text(stringResource(R.string.invite_uri)) },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(onClick = onScan) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = stringResource(R.string.scan_qr))
                    }
                }
            )
            
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text(stringResource(R.string.device_label)) },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text(stringResource(R.string.port)) },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedTextField(
                    value = peerAddr,
                    onValueChange = { peerAddr = it },
                    label = { Text(stringResource(R.string.peer_address_optional)) },
                    modifier = Modifier.weight(2f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(checked = useCache, onCheckedChange = { useCache = it })
                Text(stringResource(R.string.store_cache_desc))
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(checked = useTor, onCheckedChange = { useTor = it })
                Text(stringResource(R.string.via_tor))
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    val context = viewModel.getApplication<android.app.Application>()
                    viewModel.initializeCompanion(
                        inviteUri = inviteUri,
                        port = port.toIntOrNull() ?: 29862,
                        peerAddr = peerAddr.takeIf { it.isNotBlank() },
                        cachePath = if (useCache) "companion_cache_${inviteUri.hashCode()}" else null,
                        label = label,
                        torDir = if (useTor) java.io.File(context.filesDir, "tor_companion").absolutePath else null
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = inviteUri.isNotBlank() && port.isNotBlank()
            ) {
                Text(stringResource(R.string.link_device_btn))
            }
        }
    }
}
