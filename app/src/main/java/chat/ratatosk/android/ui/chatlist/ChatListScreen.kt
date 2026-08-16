package chat.ratatosk.android.ui.chatlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.ratatosk.android.R
import chat.ratatosk.android.ui.RatatoskViewModel
import chat.ratatosk.android.ui.components.AddContactDialog
import chat.ratatosk.android.util.toHexString

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    viewModel: RatatoskViewModel,
    onChatClick: (ByteArray) -> Unit,
    onSettingsClick: () -> Unit,
    onScanClick: () -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    val contacts by viewModel.contacts.collectAsState()
    val unreadCounts by viewModel.unreadCounts.collectAsState()
    val allMessages by viewModel.messages.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.setActiveChat(null)
        viewModel.refreshContacts()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.chats)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_contact))
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            if (contacts.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text(
                        text = stringResource(R.string.no_chats),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    items(contacts.size) { index ->
                        val contact = contacts[index]
                        val hexId = contact.chatId.toHexString()
                        val unreadCount = unreadCounts[hexId] ?: 0
                        val lastMessage = allMessages[hexId]?.lastOrNull()
                        
                        ListItem(
                            headlineContent = { 
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Text(contact.displayName, modifier = Modifier.weight(1f))
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
                                if (lastMessage != null) {
                                    val content = if (lastMessage.mine) {
                                        stringResource(R.string.you_prefix, lastMessage.body)
                                    } else {
                                        lastMessage.body
                                    }
                                    Text(
                                        text = content,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                } else {
                                    Text(contact.fingerprint)
                                }
                            },
                            leadingContent = {
                                Surface(
                                    modifier = Modifier.size(40.dp),
                                    shape = androidx.compose.foundation.shape.CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                                        Text(contact.displayName.take(1))
                                    }
                                }
                            },
                            trailingContent = {
                                if (unreadCount > 0) {
                                    Badge {
                                        Text(unreadCount.toString())
                                    }
                                }
                            },
                            modifier = Modifier.clickable { onChatClick(contact.chatId) }
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
