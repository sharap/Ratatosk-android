package chat.ratatosk.android.ui.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.ratatosk.android.R
import chat.ratatosk.android.ui.RatatoskViewModel
import chat.ratatosk.android.ui.components.AddContactDialog
import chat.ratatosk.android.ui.components.Avatar
import chat.ratatosk.android.util.toHexString

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    viewModel: RatatoskViewModel,
    onContactClick: (ByteArray) -> Unit,
    onScanClick: () -> Unit,
    isTwoColumn: Boolean = false,
    gridState: LazyGridState = rememberLazyGridState(),
    showFab: Boolean = true
) {
    val contacts by viewModel.contacts.collectAsState()
    val contactAvatars by viewModel.contactAvatars.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshContacts()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.contacts)) },
                actions = {
                    IconButton(onClick = { 
                        viewModel.refreshContacts()
                        // Force network changed to wake up discovery
                        try {
                            chat.ratatosk.android.core.RatatoskCore.getClient().networkChanged()
                        } catch (e: Exception) {}
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        },
        floatingActionButton = {
            if (showFab) {
                FloatingActionButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.PersonAdd, contentDescription = stringResource(R.string.add_contact))
                }
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            if (contacts.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text(
                        text = stringResource(R.string.no_contacts),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(if (isTwoColumn) 2 else 1),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(contacts) { contact ->
                        ListItem(
                            headlineContent = { 
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    val name = contact.localName ?: contact.displayName
                                    Text(name, modifier = Modifier.weight(1f))
                                    if (contact.seenOnLan) {
                                        Surface(
                                            modifier = Modifier.size(8.dp),
                                            shape = androidx.compose.foundation.shape.CircleShape,
                                            color = androidx.compose.ui.graphics.Color.Green
                                        ) {}
                                    }
                                }
                            },
                            supportingContent = { 
                                Column {
                                    if (contact.localName != null) {
                                        Text(contact.displayName, style = MaterialTheme.typography.labelSmall)
                                    }
                                    Text(contact.fingerprint)
                                }
                            },
                            leadingContent = {
                                val ikHex = contact.peerIk.toHexString()
                                val avatarBytes = contactAvatars[ikHex] ?: viewModel.getAvatarOf(contact.peerIk)
                                Avatar(
                                    avatarBytes = avatarBytes,
                                    name = contact.localName ?: contact.displayName
                                )
                            },
                            trailingContent = {
                                if (contact.verified) {
                                    Text("Verified", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            },
                            modifier = Modifier.clickable { onContactClick(contact.chatId) }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddContactDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { uri, inPerson ->
                viewModel.addContact(uri, inPerson)
                showAddDialog = false
            },
            onScan = {
                showAddDialog = false
                onScanClick()
            }
        )
    }
}
