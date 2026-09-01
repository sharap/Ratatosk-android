package chat.ratatosk.android.ui.groups

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.ratatosk.android.R
import chat.ratatosk.android.ui.RatatoskViewModel
import chat.ratatosk.android.ui.components.Avatar
import chat.ratatosk.android.util.formatDateTime
import chat.ratatosk.android.util.hexToByteArray
import chat.ratatosk.android.util.toHexString
import org.ratatosk.core.FfiGroup

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailsScreen(
    viewModel: RatatoskViewModel,
    chatId: ByteArray,
    onBack: () -> Unit,
    onChatClick: (ByteArray) -> Unit,
    showBackButton: Boolean = true,
    isCompact: Boolean = true
) {
    val groups by viewModel.groups.collectAsState()
    val contacts by viewModel.contacts.collectAsState()
    val contactAvatars by viewModel.contactAvatars.collectAsState()
    val myIk by viewModel.myIk.collectAsState()
    
    val group = remember(groups, chatId) {
        groups.find { it.chatId.contentEquals(chatId) }
    }

    if (showBackButton) {
        BackHandler(enabled = true) {
            onBack()
        }
    }

    var showInviteDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showLeaveDialog by remember { mutableStateOf(false) }
    var memberToEvict by remember { mutableStateOf<ByteArray?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.group_details)) },
                navigationIcon = {
                    if (showBackButton) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        if (group == null) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.group_not_found))
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                item {
                    Avatar(
                        avatarBytes = null,
                        name = group.title,
                        modifier = if (isCompact) {
                            Modifier.fillMaxWidth().aspectRatio(1f)
                        } else {
                            Modifier.size(200.dp)
                        },
                        shape = androidx.compose.ui.graphics.RectangleShape,
                        icon = Icons.Default.Groups
                    )
                }

                item {
                    Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = group.title,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.group_created_at, group.createdMs.toLong().formatDateTime()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        OutlinedButton(
                            onClick = { onChatClick(chatId) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.chat_button))
                        }
                    }
                }

                item {
                    Text(
                        text = stringResource(R.string.members),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                
                items(group.members) { memberIk ->
                    val memberContact = contacts.find { it.peerIk.contentEquals(memberIk) }
                    val name = memberContact?.let { it.localName ?: it.displayName } ?: memberIk.toHexString().take(8)
                    val avatarBytes = memberIk.toHexString().let { contactAvatars[it] } ?: viewModel.getAvatarOf(memberIk)
                    
                    ListItem(
                        headlineContent = { Text(name) },
                        supportingContent = { Text(memberIk.toHexString()) },
                        leadingContent = {
                            Avatar(avatarBytes = avatarBytes, name = name)
                        },
                        trailingContent = {
                            if (group.mine && group.joined && myIk != null && !memberIk.contentEquals(myIk)) {
                                IconButton(onClick = { memberToEvict = memberIk }) {
                                    Icon(Icons.Default.PersonRemove, contentDescription = "Evict", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    )
                }

                item {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (group.joined) {
                            Button(
                                onClick = { showInviteDialog = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.PersonAdd, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.invite_contact))
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        TextButton(
                            onClick = { showClearDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.clear_chat))
                        }

                        if (group.joined) {
                            TextButton(
                                onClick = { showLeaveDialog = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Logout, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.leave_group))
                            }
                        }

                        TextButton(
                            onClick = { showDeleteDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.delete_group))
                        }
                    }
                }
            }
        }
    }

    if (showInviteDialog && group != null) {
        AlertDialog(
            onDismissRequest = { showInviteDialog = false },
            title = { Text(stringResource(R.string.invite_contact)) },
            text = {
                val availableToInvite = contacts.filter { contact ->
                    !group.members.any { it.contentEquals(contact.peerIk) }
                }
                if (availableToInvite.isEmpty()) {
                    Text(stringResource(R.string.no_contacts_to_invite))
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                        items(availableToInvite) { contact ->
                            ListItem(
                                headlineContent = { Text(contact.localName ?: contact.displayName) },
                                leadingContent = {
                                    Avatar(
                                        avatarBytes = contactAvatars[contact.peerIk.toHexString()] ?: viewModel.getAvatarOf(contact.peerIk),
                                        name = contact.localName ?: contact.displayName
                                    )
                                },
                                modifier = Modifier.clickable {
                                    viewModel.inviteToGroup(chatId, contact.peerIk)
                                    showInviteDialog = false
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showInviteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showClearDialog && group != null) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.clear_chat)) },
            text = { Text(stringResource(R.string.clear_chat_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearChat(chatId)
                    showClearDialog = false
                }) {
                    Text(stringResource(R.string.clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showDeleteDialog && group != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_group)) },
            text = { Text(stringResource(R.string.delete_group_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showLeaveDialog && group != null) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            title = { Text(stringResource(R.string.leave_group)) },
            text = {
                val notice = remember(group.mine) {
                    if (group.mine) {
                        viewModel.getLeaveNotice() + "\n\n" + viewModel.getOwnerLeaveNotice()
                    } else {
                        viewModel.getLeaveNotice()
                    }
                }
                Text(notice)
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.leaveGroup(chatId)
                    showLeaveDialog = false
                    onBack()
                }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.leave))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (memberToEvict != null) {
        AlertDialog(
            onDismissRequest = { memberToEvict = null },
            title = { Text(stringResource(R.string.evict_member)) },
            text = { Text(viewModel.getEvictionNotice()) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.evictFromGroup(chatId, memberToEvict!!)
                    memberToEvict = null
                }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.evict))
                }
            },
            dismissButton = {
                TextButton(onClick = { memberToEvict = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
