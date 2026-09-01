package chat.ratatosk.android.ui.unlock

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import chat.ratatosk.android.data.CompanionLink
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.ratatosk.android.R
import chat.ratatosk.android.ui.RatatoskViewModel
import chat.ratatosk.android.util.toHexString
import kotlinx.coroutines.launch
import org.ratatosk.core.FfiAccount

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSelectionScreen(
    viewModel: RatatoskViewModel,
    onSelect: (FfiAccount) -> Unit,
    onSelectCompanion: (CompanionLink) -> Unit,
    onCreateNew: () -> Unit,
    onLinkCompanion: () -> Unit
) {
    val accounts by viewModel.availableAccounts.collectAsState()
    val companionLinks by viewModel.companionLinks.collectAsState()
    val isFindingHidden by viewModel.isFindingHidden.collectAsState()
    var showHiddenDialog by remember { mutableStateOf(false) }
    var hiddenPin by remember { mutableStateOf("") }
    
    var showDeleteConfirm by remember { mutableStateOf<FfiAccount?>(null) }
    var showImportDialog by remember { mutableStateOf<String?>(null) }
    
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val file = chat.ratatosk.android.util.FileUtils.copyUriToInternalStorage(context, it)
            if (file != null) {
                showImportDialog = file.absolutePath
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.select_account)) },
                actions = {
                    IconButton(onClick = { importLauncher.launch("*/*") }) {
                        Icon(Icons.Default.Restore, contentDescription = stringResource(R.string.restore_from_backup))
                    }
                    TextButton(onClick = { showHiddenDialog = true }) {
                        Text(stringResource(R.string.find_hidden))
                    }
                }
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                SmallFloatingActionButton(
                    onClick = onLinkCompanion,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ) {
                    Icon(Icons.Default.AddLink, contentDescription = "Link Companion")
                }
                Spacer(modifier = Modifier.height(16.dp))
                FloatingActionButton(onClick = onCreateNew) {
                    Icon(Icons.Default.Add, contentDescription = "Add Account")
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (accounts.isEmpty() && companionLinks.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.no_accounts))
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (accounts.isNotEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.local_accounts),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(16.dp, 8.dp)
                            )
                        }
                        items(accounts) { account ->
                            ListItem(
                                headlineContent = { Text(account.label) },
                                supportingContent = { Text("ID: ${account.id.toHexString().take(8)}...") },
                                leadingContent = { Icon(Icons.Default.Person, contentDescription = null) },
                                trailingContent = {
                                    IconButton(onClick = { showDeleteConfirm = account }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                                    }
                                },
                                modifier = Modifier.clickable { onSelect(account) }
                            )
                        }
                    }
                    
                    if (companionLinks.isNotEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.companion_devices),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(16.dp, 8.dp, 16.dp, 0.dp)
                            )
                        }
                        items(companionLinks) { link ->
                            ListItem(
                                headlineContent = { Text(link.label) },
                                supportingContent = { Text("URI: ${link.inviteUri.take(20)}...") },
                                leadingContent = { Icon(Icons.Default.Devices, contentDescription = null) },
                                trailingContent = {
                                    IconButton(onClick = { viewModel.removeCompanionLink(link.inviteUri) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
                                    }
                                },
                                modifier = Modifier.clickable { onSelectCompanion(link) }
                            )
                        }
                    }
                }
            }

            if (isFindingHidden) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.finding_hidden_desc))
                    }
                }
            }
        }
    }

    if (showDeleteConfirm != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text(stringResource(R.string.delete_account_title)) },
            text = { Text(stringResource(R.string.delete_account_desc, showDeleteConfirm?.label ?: "")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.wipeAccount(showDeleteConfirm!!.id)
                        showDeleteConfirm = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showImportDialog != null) {
        ImportArchiveDialog(
            path = showImportDialog!!,
            viewModel = viewModel,
            onDismiss = { showImportDialog = null }
        )
    }

    if (showHiddenDialog) {
        AlertDialog(
            onDismissRequest = { if (!isFindingHidden) showHiddenDialog = false },
            title = { Text(stringResource(R.string.find_hidden)) },
            text = {
                Column {
                    Text(stringResource(R.string.hidden_pin_desc))
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = hiddenPin,
                        onValueChange = { hiddenPin = it },
                        label = { Text(stringResource(R.string.pin)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.findHiddenAccount(
                            pin = hiddenPin,
                            onFound = { id ->
                                showHiddenDialog = false
                                onSelect(FfiAccount(id, "Hidden Account", System.currentTimeMillis().toULong()))
                            },
                            onNotFound = {
                                scope.launch {
                                    snackbarHostState.showSnackbar("No hidden account found with this PIN")
                                }
                            }
                        )
                    },
                    enabled = hiddenPin.isNotEmpty() && !isFindingHidden
                ) {
                    Text(stringResource(R.string.find))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showHiddenDialog = false },
                    enabled = !isFindingHidden
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
fun ImportArchiveDialog(
    path: String,
    viewModel: RatatoskViewModel,
    onDismiss: () -> Unit
) {
    var label by remember { mutableStateOf("Restored Account") }
    var keyText by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var usePassphrase by remember { mutableStateOf(false) }
    var isPeeking by remember { mutableStateOf(true) }
    var isImporting by remember { mutableStateOf(false) }
    var peekResult by remember { mutableStateOf<org.ratatosk.core.FfiArchivePeek?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(path) {
        viewModel.peekArchive(path) { result ->
            if (result != null) {
                peekResult = result
                usePassphrase = result.takesPassphrase
                error = null
            } else {
                error = "Failed to read archive. Is it a valid backup?"
            }
            isPeeking = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isImporting) onDismiss() },
        title = { Text(stringResource(R.string.restore_from_backup)) },
        text = {
            Column {
                if (isPeeking) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(stringResource(R.string.peeking_archive), modifier = Modifier.align(Alignment.CenterHorizontally))
                } else {
                    peekResult?.let { peek ->
                        Text(stringResource(R.string.archive_type, peek.scope.name))
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        OutlinedTextField(
                            value = label,
                            onValueChange = { label = it; error = null },
                            label = { Text(stringResource(R.string.account_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isImporting
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        if (peek.takesPassphrase) {
                            OutlinedTextField(
                                value = passphrase,
                                onValueChange = { passphrase = it; error = null },
                                label = { Text(stringResource(R.string.passphrase)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                enabled = !isImporting
                            )
                        } else {
                            OutlinedTextField(
                                value = keyText,
                                onValueChange = { keyText = it; error = null },
                                label = { Text(stringResource(R.string.recovery_key)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                enabled = !isImporting
                            )
                        }
                        
                        if (isImporting) {
                            Spacer(modifier = Modifier.height(16.dp))
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                            Text(stringResource(R.string.restoring_account), modifier = Modifier.align(Alignment.CenterHorizontally))
                        }

                        error?.let {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                        }
                    } ?: Text(error ?: "Failed to read archive.", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    isImporting = true
                    error = null
                    val unlock = if (usePassphrase) {
                        org.ratatosk.core.FfiArchiveUnlock.Passphrase(passphrase)
                    } else {
                        org.ratatosk.core.FfiArchiveUnlock.Key(keyText)
                    }
                    viewModel.importArchive(path, unlock, label) { result ->
                        isImporting = false
                        result.onSuccess {
                            onDismiss()
                        }.onFailure { e ->
                            error = e.message ?: "Import failed"
                        }
                    }
                },
                enabled = !isPeeking && !isImporting && peekResult != null && (passphrase.isNotEmpty() || keyText.isNotEmpty())
            ) {
                Text(stringResource(R.string.restore))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isImporting) { Text(stringResource(R.string.cancel)) }
        }
    )
}
