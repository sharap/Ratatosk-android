package chat.ratatosk.android.ui.groups

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
    onCropAvatar: () -> Unit = {},
    showBackButton: Boolean = true,
    isCompact: Boolean = true
) {
    val groups by viewModel.groups.collectAsState()
    val contacts by viewModel.contacts.collectAsState()
    val contactAvatars by viewModel.contactAvatars.collectAsState()
    val myAvatar by viewModel.myAvatar.collectAsState()
    
    val group = remember(groups, chatId) {
        groups.find { it.chatId.contentEquals(chatId) }
    }

    LaunchedEffect(chatId) {
        viewModel.loadCompanionMembers(chatId)
    }

    var showEditTitleDialog by remember { mutableStateOf(false) }
    var editTitleText by remember { mutableStateOf("") }
    var showInviteDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showLeaveDialog by remember { mutableStateOf(false) }
    var memberToEvict by remember { mutableStateOf<ByteArray?>(null) }
    var showChannelLink by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
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
                    val groupAvatarBytes = remember(contactAvatars, group.chatId) {
                        group.chatId.toHexString().let { contactAvatars[it] } ?: viewModel.getGroupAvatar(group.chatId)
                    }
                    val avatarLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.GetContent()
                    ) { uri ->
                        uri?.let {
                            viewModel.setPendingAvatarUri(it, group.chatId)
                            onCropAvatar()
                        }
                    }

                    Box(
                        contentAlignment = Alignment.BottomEnd,
                        modifier = if (isCompact) Modifier.fillMaxWidth().aspectRatio(1f) else Modifier.size(200.dp)
                    ) {
                        Avatar(
                            avatarBytes = groupAvatarBytes,
                            name = group.title,
                            modifier = Modifier.fillMaxSize(),
                            shape = androidx.compose.ui.graphics.RectangleShape,
                            icon = Icons.Default.Groups
                        )
                        if (group.mine && group.joined) {
                            var photoMenu by remember { mutableStateOf(false) }
                            Box {
                            SmallFloatingActionButton(
                                // Пока фото нет, выбирать не из чего.
                                onClick = { if (groupAvatarBytes != null) photoMenu = true else avatarLauncher.launch("image/*") },
                                modifier = Modifier.padding(16.dp).size(40.dp),
                                shape = CircleShape,
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Icon(Icons.Default.AddAPhoto, contentDescription = stringResource(R.string.avatar_change), modifier = Modifier.size(20.dp))
                            }
                            DropdownMenu(expanded = photoMenu, onDismissRequest = { photoMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.avatar_change)) },
                                    onClick = { photoMenu = false; avatarLauncher.launch("image/*") },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.avatar_remove)) },
                                    onClick = { photoMenu = false; viewModel.setGroupAvatar(group.chatId, null) },
                                )
                            }
                            }
                        }
                    }
                }

                item {
                    Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = group.title,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (group.mine && group.joined) {
                                IconButton(onClick = { 
                                    editTitleText = group.title
                                    showEditTitleDialog = true 
                                }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit Title", modifier = Modifier.size(20.dp))
                                }
                            }
                        }
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

                        // Ссылка на канал — здесь же, где всё о нём.
                        if (group.channel != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { showChannelLink = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Link, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.channel_link_show))
                            }
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
                
                items(group.members) { member ->
                    val memberContact = contacts.find { it.peerIk.contentEquals(member.ik) }
                    val name = memberContact?.let { it.localName ?: it.displayName } ?: member.name
                    val avatarBytes = if (member.mine) {
                        myAvatar ?: member.ik.toHexString().let { contactAvatars[it] } ?: viewModel.getAvatarOf(member.ik)
                    } else {
                        member.ik.toHexString().let { contactAvatars[it] } ?: viewModel.getAvatarOf(member.ik)
                    }
                    
                    ListItem(
                        modifier = Modifier.clickable {
                            if (!member.mine) {
                                if (viewModel.isCompanionMode.value) {
                                    viewModel.setActiveChat(member.ik)
                                    onChatClick(member.ik)
                                } else {
                                    val targetId = memberContact?.chatId ?: member.ik
                                    viewModel.setActiveContact(targetId)
                                }
                            }
                        },
                        headlineContent = { 
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(name)
                                if (member.mine) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.this_is_you),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        },
                        supportingContent = { Text(member.ik.toHexString()) },
                        leadingContent = {
                            Avatar(avatarBytes = avatarBytes, name = name)
                        },
                        trailingContent = {
                            if (group.mine && group.joined && !member.mine) {
                                IconButton(onClick = { memberToEvict = member.ik }) {
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
                    !group.members.any { it.ik.contentEquals(contact.peerIk) }
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
                    if (group.joined) {
                        viewModel.leaveGroup(chatId)
                    }
                    viewModel.clearChat(chatId)
                    showDeleteDialog = false
                    onBack()
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

    if (showEditTitleDialog && group != null) {
        val maxChars = viewModel.getMaxGroupTitleChars()
        AlertDialog(
            onDismissRequest = { showEditTitleDialog = false },
            title = { Text(stringResource(R.string.rename_group)) },
            text = {
                OutlinedTextField(
                    value = editTitleText,
                    onValueChange = { 
                        if (it.length <= maxChars.toInt()) {
                            editTitleText = it
                        }
                    },
                    label = { Text(stringResource(R.string.group_title)) },
                    singleLine = true,
                    supportingText = {
                        Text("${editTitleText.length}/$maxChars")
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (editTitleText.isNotBlank()) {
                            viewModel.renameGroup(chatId, editTitleText.trim())
                            showEditTitleDialog = false
                        }
                    },
                    enabled = editTitleText.isNotBlank()
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditTitleDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
