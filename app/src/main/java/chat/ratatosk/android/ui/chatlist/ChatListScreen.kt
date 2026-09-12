package chat.ratatosk.android.ui.chatlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.ratatosk.android.R
import chat.ratatosk.android.ui.RatatoskViewModel
import chat.ratatosk.android.ui.components.AddContactDialog
import chat.ratatosk.android.ui.components.Avatar
import chat.ratatosk.android.ui.components.CreateGroupDialog
import chat.ratatosk.android.util.toHexString

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    viewModel: RatatoskViewModel,
    onChatClick: (ByteArray) -> Unit,
    onScanClick: () -> Unit,
    isTwoColumn: Boolean = false,
    gridState: LazyGridState = rememberLazyGridState(),
    showFab: Boolean = true
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var showFabMenu by remember { mutableStateOf(false) }

    val contacts by viewModel.contacts.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val unreadCounts by viewModel.unreadCounts.collectAsState()
    val allMessages by viewModel.messages.collectAsState()
    val contactAvatars by viewModel.contactAvatars.collectAsState()
    val isCompanionMode by viewModel.isCompanionMode.collectAsState()
    val isCompanionLinked by viewModel.isCompanionLinked.collectAsState()
    val activeChatId by viewModel.activeChatIdFlow.collectAsState()

    val chats = remember(contacts, groups, allMessages, activeChatId) {
        (contacts.map { ChatItem.Contact(it) } + groups.map { ChatItem.Group(it) })
            .filter { chatItem ->
                when (chatItem) {
                    is ChatItem.Group -> true
                    is ChatItem.Contact -> {
                        val hexId = chatItem.chatId.toHexString()
                        val msgs = allMessages[hexId]
                        !msgs.isNullOrEmpty() || activeChatId?.contentEquals(chatItem.chatId) == true
                    }
                }
            }
            .sortedByDescending { chatItem ->
                allMessages[chatItem.chatId.toHexString()]?.lastOrNull()?.wallMs ?: 0UL
            }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshContacts()
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.chats)) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                    windowInsets = TopAppBarDefaults.windowInsets
                )
                
                val torStatus by viewModel.torStatus.collectAsState()
                val torEnabled by viewModel.torEnabled.collectAsState()
                
                if (torEnabled && torStatus != null && torStatus!!.fraction < 1.0f) {
                    LinearProgressIndicator(
                        progress = { torStatus!!.fraction },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = torStatus!!.note + (torStatus!!.blocked?.let { ": $it" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                if (isCompanionMode && !isCompanionLinked) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = stringResource(R.string.connecting_to_phone_cached),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (showFab) {
                if (isCompanionMode) {
                    FloatingActionButton(onClick = { showCreateGroupDialog = true }) {
                        Icon(Icons.Default.Groups, contentDescription = stringResource(R.string.create_group))
                    }
                } else {
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                        if (showFabMenu) {
                            SmallFloatingActionButton(
                                onClick = { 
                                    showCreateGroupDialog = true
                                    showFabMenu = false
                                },
                                modifier = Modifier.padding(bottom = 8.dp)
                            ) {
                                Icon(Icons.Default.Groups, contentDescription = stringResource(R.string.create_group))
                            }
                            SmallFloatingActionButton(
                                onClick = { 
                                    showAddDialog = true
                                    showFabMenu = false
                                },
                                modifier = Modifier.padding(bottom = 8.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_contact))
                            }
                        }
                        FloatingActionButton(onClick = { showFabMenu = !showFabMenu }) {
                            Icon(if (showFabMenu) Icons.Default.Close else Icons.Default.Add, contentDescription = null)
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            if (chats.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text(
                        text = stringResource(R.string.no_chats),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(if (isTwoColumn) 2 else 1),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    items(chats.size) { index ->
                        val chatItem = chats[index]
                        val hexId = chatItem.chatId.toHexString()
                        val unreadCount = unreadCounts[hexId] ?: 0
                        val lastMessage = allMessages[hexId]?.lastOrNull()
                        
                        val isSelected = activeChatId?.contentEquals(chatItem.chatId) == true

                        ListItem(
                            headlineContent = { 
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Text(chatItem.title, modifier = Modifier.weight(1f))
                                    if (chatItem is ChatItem.Contact && chatItem.contact.seenOnLan) {
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
                                    // Тем же помощником, что и уведомления: снимает
                                    // разметку и подписывает вложение, когда текста
                                    // нет — раньше такая строка была просто пустой.
                                    val ctx = androidx.compose.ui.platform.LocalContext.current
                                    val fileNames = lastMessage.files.map { it.name }
                                    val hasCard = lastMessage.sharedContact != null
                                    // Ключи — по значению. msgId сюда не годится:
                                    // это ByteArray, он сравнивается по ссылке,
                                    // и разбор шёл бы заново на каждую перезагрузку.
                                    val preview = remember(lastMessage.body, fileNames, hasCard) {
                                        chat.ratatosk.android.util.MessagePreview.of(
                                            context = ctx,
                                            body = lastMessage.body,
                                            fileNames = fileNames,
                                            hasSharedContact = hasCard
                                        )
                                    }
                                    val content = if (lastMessage.mine) {
                                        stringResource(R.string.you_prefix, preview)
                                    } else {
                                        preview
                                    }
                                    Text(
                                        text = content,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                } else {
                                    when (chatItem) {
                                        is ChatItem.Contact -> Text(chatItem.contact.fingerprint)
                                        is ChatItem.Group -> Text(stringResource(R.string.group_chat))
                                    }
                                }
                            },
                            leadingContent = {
                                when (chatItem) {
                                    is ChatItem.Contact -> {
                                        val ikHex = chatItem.contact.peerIk.toHexString()
                                        val avatarBytes = contactAvatars[ikHex] ?: viewModel.getAvatarOf(chatItem.contact.peerIk)
                                        Avatar(
                                            avatarBytes = avatarBytes,
                                            name = chatItem.title
                                        )
                                    }
                                    is ChatItem.Group -> {
                                        val avatarBytes = contactAvatars[hexId] ?: viewModel.getGroupAvatar(chatItem.chatId)
                                        Avatar(
                                            avatarBytes = avatarBytes,
                                            name = chatItem.title,
                                            icon = Icons.Default.Groups
                                        )
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
                            colors = ListItemDefaults.colors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onChatClick(chatItem.chatId) }
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

    if (showCreateGroupDialog) {
        CreateGroupDialog(
            onDismiss = { showCreateGroupDialog = false },
            onCreate = { title ->
                viewModel.createGroup(title)
                showCreateGroupDialog = false
            },
            notice = viewModel.getGroupJoinNotice(),
            maxChars = viewModel.getMaxGroupTitleChars()
        )
    }
}
