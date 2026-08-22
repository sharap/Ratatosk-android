package chat.ratatosk.android.ui.unlock

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
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
    onCreateNew: () -> Unit
) {
    val accounts by viewModel.availableAccounts.collectAsState()
    val isFindingHidden by viewModel.isFindingHidden.collectAsState()
    var showHiddenDialog by remember { mutableStateOf(false) }
    var hiddenPin by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.select_account)) },
                actions = {
                    TextButton(onClick = { showHiddenDialog = true }) {
                        Text(stringResource(R.string.find_hidden))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateNew) {
                Icon(Icons.Default.Add, contentDescription = "Add Account")
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (accounts.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.no_accounts))
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(accounts) { account ->
                        ListItem(
                            headlineContent = { Text(account.label) },
                            supportingContent = { Text("ID: ${account.id.toHexString().take(8)}...") },
                            leadingContent = { Icon(Icons.Default.Person, contentDescription = null) },
                            modifier = Modifier.clickable { onSelect(account) }
                        )
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
