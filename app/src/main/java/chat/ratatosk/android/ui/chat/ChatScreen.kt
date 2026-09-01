package chat.ratatosk.android.ui.chat

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.luminance
import chat.ratatosk.android.R
import chat.ratatosk.android.ui.RatatoskViewModel
import chat.ratatosk.android.ui.components.Avatar
import chat.ratatosk.android.util.MarkdownUtils
import chat.ratatosk.android.util.hexToByteArray
import chat.ratatosk.android.util.toHexString
import chat.ratatosk.android.util.FileUtils
import coil.compose.rememberAsyncImagePainter
import org.ratatosk.core.FfiDeliveryStatus
import org.ratatosk.core.FfiFile
import org.ratatosk.core.FfiMessage
import androidx.compose.ui.platform.LocalFocusManager
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: RatatoskViewModel,
    chatId: ByteArray,
    onBack: () -> Unit,
    onHeaderClick: () -> Unit,
    showBackButton: Boolean = true,
    isCompact: Boolean = true
) {
    var text by remember { mutableStateOf("") }
    var editingMessage by remember { mutableStateOf<FfiMessage?>(null) }
    var replyingTo by remember { mutableStateOf<FfiMessage?>(null) }
    var showForwardDialog by remember { mutableStateOf<List<ByteArray>?>(null) }
    var attachedFiles by remember { mutableStateOf<List<java.io.File>>(emptyList()) }
    
    val chatTheme by viewModel.chatTheme.collectAsState()
    val allMessages by viewModel.messages.collectAsState()
    val messageStatuses by viewModel.messageStatuses.collectAsState()
    val contacts by viewModel.contacts.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val contactAvatars by viewModel.contactAvatars.collectAsState()
    
    val chatIdHex = remember(chatId) { chatId.toHexString() }
    val messages = allMessages[chatIdHex] ?: emptyList()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    val contact = remember(contacts, chatIdHex) {
        contacts.find { it.chatId.toHexString() == chatIdHex }
    }
    
    val group = remember(groups, chatIdHex) {
        groups.find { it.chatId.toHexString() == chatIdHex }
    }

    val isMember = remember(group, contact) {
        contact != null || (group?.joined ?: false)
    }

    var highlightedMsgId by remember { mutableStateOf<String?>(null) }
    var pendingScrollToId by remember { mutableStateOf<String?>(null) }
    var showChatMenu by remember { mutableStateOf(false) }
    var showClearChatDialog by remember { mutableStateOf(false) }
    var showInviteDialog by remember { mutableStateOf(false) }
    
    var isSearchMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()

    val displayMessages = remember(messages) { messages.reversed() }

    val performBack = {
        onBack()
    }

    if (showBackButton) {
        BackHandler(enabled = true) {
            performBack()
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val copiedFiles = uris.mapNotNull { uri ->
                chat.ratatosk.android.util.FileUtils.copyUriToInternalStorage(context, uri)
            }
            attachedFiles = attachedFiles + copiedFiles
        }
    }

    var previousIndex by remember { mutableIntStateOf(0) }
    var previousOffset by remember { mutableStateOf(0) }
    var lastDirectionIsUp by remember { mutableStateOf(false) }

    val isAtBottom by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0
        }
    }
    
    val isAtTop by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems == 0) true
            else {
                val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
                lastVisibleItem != null && lastVisibleItem.index >= totalItems - 2
            }
        }
    }

    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
        val newIndex = listState.firstVisibleItemIndex
        val newOffset = listState.firstVisibleItemScrollOffset
        
        if (newIndex > previousIndex || (newIndex == previousIndex && newOffset > previousOffset)) {
            lastDirectionIsUp = true
        } else if (newIndex < previousIndex || (newIndex == previousIndex && newOffset < previousOffset)) {
            lastDirectionIsUp = false
        }
        
        previousIndex = newIndex
        previousOffset = newOffset
    }

    val showToTop by remember {
        derivedStateOf { !isAtTop && lastDirectionIsUp }
    }

    val showToBottom by remember {
        derivedStateOf { !isAtBottom && !lastDirectionIsUp }
    }

    // Handle pending scroll and search in history
    LaunchedEffect(pendingScrollToId) {
        val targetId = pendingScrollToId ?: return@LaunchedEffect
        
        var attempts = 0
        val maxAttempts = 10
        
        while (pendingScrollToId == targetId && attempts < maxAttempts) {
            val currentMessages = displayMessages
            val targetBytes = try { targetId.hexToByteArray() } catch (e: Exception) { null }
            val index = if (targetBytes != null) {
                currentMessages.indexOfFirst { it.msgId.contentEquals(targetBytes) }
            } else {
                currentMessages.indexOfFirst { it.msgId.toHexString() == targetId }
            }
            
            if (index != -1) {
                highlightedMsgId = targetId
                kotlinx.coroutines.yield()
                val vHeight = listState.layoutInfo.viewportSize.height
                val offset = if (vHeight > 0) (vHeight * (-0.5f)).toInt() else 0
                try {
                    listState.animateScrollToItem(index, offset)
                } catch (e: Exception) { }
                kotlinx.coroutines.delay(1500)
                highlightedMsgId = null
                pendingScrollToId = null
                break
            } else {
                val currentSize = currentMessages.size
                if (currentSize < 5000) {
                    viewModel.loadMessages(chatId, currentSize + 500)
                    attempts++
                    val loadSuccess = snapshotFlow { displayMessages.size }.filter { it > currentSize }.firstOrNull()
                    if (loadSuccess == null) break
                } else break
            }
        }
        if (pendingScrollToId == targetId) pendingScrollToId = null
    }

    LaunchedEffect(chatIdHex) {
        viewModel.setActiveChat(chatId)
        viewModel.loadMessages(chatId)
    }

    DisposableEffect(chatIdHex) {
        onDispose {
            viewModel.setActiveChat(null)
        }
    }

    // Auto-scroll and mark as read when new messages arrive
    LaunchedEffect(displayMessages.size) {
        if (displayMessages.isNotEmpty()) {
            val lastMsg = displayMessages.firstOrNull()
            val nearBottom = listState.firstVisibleItemIndex <= 1
            
            if (nearBottom || lastMsg?.mine == true) {
                listState.animateScrollToItem(0)
            }
            
            displayMessages.firstOrNull { !it.mine }?.let { lastPeerMsg ->
                if (group == null) {
                    viewModel.markRead(chatId, lastPeerMsg.msgId)
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Scaffold(
            containerColor = Color.Transparent, 
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = { 
                Column {
                    TopAppBar(
                        title = { 
                            if (isSearchMode) {
                                TextField(
                                    value = searchQuery,
                                    onValueChange = {
                                        searchQuery = it
                                        viewModel.searchMessages(chatId, it)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text(stringResource(R.string.search)) },
                                    singleLine = true,
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent
                                    )
                                )
                            } else {
                                Row(
                                    modifier = Modifier.clickable { 
                                        focusManager.clearFocus()
                                        onHeaderClick() 
                                    },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (group != null) {
                                        Avatar(
                                            avatarBytes = null,
                                            name = group.title,
                                            size = 32.dp,
                                            icon = Icons.Default.Groups
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(group.title)
                                            Text(
                                                text = stringResource(R.string.group_members_count, group.members.size),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.secondary
                                            )
                                        }
                                    } else {
                                        contact?.let {
                                            Avatar(
                                                avatarBytes = it.peerIk.toHexString().let { ik -> contactAvatars[ik] } ?: viewModel.getAvatarOf(it.peerIk),
                                                name = it.localName ?: it.displayName,
                                                size = 32.dp
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                        Column {
                                            Text(contact?.let { it.localName ?: it.displayName } ?: stringResource(R.string.chat))
                                            if (contact?.seenOnLan == true) {
                                                Text(
                                                    text = stringResource(R.string.online_lan),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        navigationIcon = {
                            if (isSearchMode) {
                                IconButton(onClick = { 
                                    isSearchMode = false
                                    searchQuery = ""
                                    viewModel.clearSearch()
                                }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel search")
                                }
                            } else if (showBackButton) {
                                IconButton(onClick = { performBack() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                            }
                        },
                        actions = {
                            if (!isSearchMode) {
                                IconButton(onClick = { isSearchMode = true }) {
                                    Icon(Icons.Default.Search, contentDescription = "Search")
                                }
                                IconButton(onClick = { showChatMenu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                                }
                            } else if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { 
                                    searchQuery = ""
                                    viewModel.clearSearch()
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear search")
                                }
                            }
                            
                            DropdownMenu(
                                expanded = showChatMenu,
                                onDismissRequest = { showChatMenu = false }
                            ) {
                                if (group != null) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.group_details)) },
                                        onClick = {
                                            showChatMenu = false
                                            onHeaderClick()
                                        },
                                        leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) }
                                    )
                                }
                                if (group != null && group.mine) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.invite_contact)) },
                                        onClick = {
                                            showChatMenu = false
                                            showInviteDialog = true
                                        },
                                        leadingIcon = { Icon(Icons.Default.PersonAdd, contentDescription = null) }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.clear_chat)) },
                                    onClick = {
                                        showChatMenu = false
                                        showClearChatDialog = true
                                    },
                                    leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) }
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        windowInsets = WindowInsets(0, 0, 0, 0)
                    )

                    val torStatus by viewModel.torStatus.collectAsState()
                    val torEnabled by viewModel.torEnabled.collectAsState()
                    val isCompanionMode by viewModel.isCompanionMode.collectAsState()
                    val isCompanionLinked by viewModel.isCompanionLinked.collectAsState()
                    
                    if (torEnabled && torStatus != null && torStatus!!.fraction < 1.0f) {
                        LinearProgressIndicator(
                            progress = { torStatus!!.fraction },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            trackColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    }

                    if (isCompanionMode && !isCompanionLinked) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.tertiary,
                            trackColor = MaterialTheme.colorScheme.tertiaryContainer
                        )
                    }
                }
            },
            bottomBar = {
                if (!isSearchMode) {
                    if (isMember) {
                        Surface(tonalElevation = 2.dp) {
                            Column {
                                replyingTo?.let { reply ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 8.dp)
                                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                            .padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            val allContacts by viewModel.contacts.collectAsState()
                                            val replyName = if (reply.mine) "You" else {
                                                val replyContactObj = allContacts.find { it.chatId.toHexString() == chatIdHex }
                                                replyContactObj?.localName ?: replyContactObj?.displayName ?: "User"
                                            }
                                            Text(
                                                text = stringResource(R.string.replying_to, replyName),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = reply.body.take(100) + if (reply.body.length > 100) "..." else "",
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                        }
                                        IconButton(onClick = { replyingTo = null }) {
                                            Icon(Icons.Default.Close, contentDescription = "Cancel")
                                        }
                                    }
                                }

                                if (attachedFiles.isNotEmpty()) {
                                    LazyRow(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        items(attachedFiles) { file ->
                                            Box(modifier = Modifier.size(60.dp)) {
                                                val isImage = file.extension.lowercase() in listOf("jpg", "jpeg", "png", "webp")
                                                if (isImage) {
                                                    Image(
                                                        painter = rememberAsyncImagePainter(file),
                                                        contentDescription = null,
                                                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)),
                                                        contentScale = ContentScale.Crop
                                                    )
                                                } else {
                                                    Surface(
                                                        modifier = Modifier.fillMaxSize(),
                                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                                        shape = RoundedCornerShape(4.dp)
                                                    ) {
                                                        Icon(Icons.Default.InsertDriveFile, contentDescription = null, modifier = Modifier.padding(16.dp))
                                                    }
                                                }
                                                IconButton(
                                                    onClick = { attachedFiles = attachedFiles - file },
                                                    modifier = Modifier.align(Alignment.TopEnd).size(20.dp).offset(x = 8.dp, y = (-8).dp)
                                                ) {
                                                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.error) {
                                                        Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.padding(2.dp))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .padding(8.dp)
                                        .fillMaxWidth()
                                        .navigationBarsPadding()
                                        .imePadding(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = text,
                                        onValueChange = { text = it },
                                        modifier = Modifier.weight(1f),
                                        placeholder = { Text(if (editingMessage != null) stringResource(R.string.edit) else stringResource(R.string.message)) },
                                        maxLines = 4,
                                        leadingIcon = if (editingMessage != null) {
                                            {
                                                IconButton(onClick = {
                                                    editingMessage = null
                                                    text = ""
                                                }) {
                                                    Icon(Icons.Default.Close, contentDescription = "Cancel")
                                                }
                                            }
                                        } else null,
                                        trailingIcon = {
                                            IconButton(onClick = { fileLauncher.launch("*/*") }) {
                                                Icon(Icons.Default.AttachFile, contentDescription = "Attach")
                                            }
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    IconButton(
                                        onClick = {
                                            if (text.isNotBlank() || attachedFiles.isNotEmpty()) {
                                                val currentEditing = editingMessage
                                                val currentReply = replyingTo
                                                if (attachedFiles.isNotEmpty()) {
                                                    viewModel.sendFiles(chatId, attachedFiles, text)
                                                    attachedFiles = emptyList()
                                                } else if (currentEditing != null) {
                                                    viewModel.editMessage(chatId, currentEditing.msgId, text)
                                                    editingMessage = null
                                                } else if (currentReply != null) {
                                                    viewModel.reply(chatId, currentReply.msgId, text)
                                                    replyingTo = null
                                                } else {
                                                    viewModel.sendText(chatId, text)
                                                }
                                                text = ""
                                            }
                                        },
                                        enabled = text.isNotBlank() || attachedFiles.isNotEmpty()
                                    ) {
                                        if (editingMessage != null) {
                                            Icon(Icons.Default.Check, contentDescription = stringResource(R.string.save))
                                        } else {
                                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send))
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surfaceVariant) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .navigationBarsPadding()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.you_left_group),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                if (isSearchMode && searchQuery.isNotEmpty()) {
                    if (isSearching) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else if (searchResults.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.no_results), style = MaterialTheme.typography.bodyLarge)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(searchResults) { msg ->
                                Box(modifier = Modifier.fillMaxWidth().clickable {
                                    isSearchMode = false
                                    searchQuery = ""
                                    viewModel.clearSearch()
                                    pendingScrollToId = msg.msgId.toHexString()
                                }) {
                                    MessageBubble(
                                        message = msg,
                                        viewModel = viewModel,
                                        chatId = chatId,
                                        snackbarHostState = snackbarHostState,
                                        outgoingColor = if (chatTheme.themeColor != Color.Unspecified) chatTheme.themeColor else MaterialTheme.colorScheme.primary,
                                        status = messageStatuses[msg.msgId.toHexString()] ?: msg.status,
                                        onReplyClick = {},
                                        isHighlighted = false,
                                        isCompact = isCompact,
                                        onRetry = {}, onDelete = {}, onRetract = {}, onEdit = {}, onReply = {}, onForward = {}, onReaction = { _ -> },
                                        retractionNotice = { "" }, getRepliedMessage = { null },
                                        showAuthor = group != null,
                                        isMember = isMember
                                    )
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        reverseLayout = true,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(displayMessages, key = { it.msgId.toHexString() }) { msg ->
                            val status = messageStatuses[msg.msgId.toHexString()] ?: msg.status
                            Box(modifier = Modifier.fillMaxWidth().animateItem()) {
                                MessageBubble(
                                    message = msg,
                                    viewModel = viewModel,
                                    chatId = chatId,
                                    snackbarHostState = snackbarHostState,
                                    outgoingColor = if (chatTheme.themeColor != Color.Unspecified) chatTheme.themeColor else MaterialTheme.colorScheme.primary,
                                    status = status,
                                    onRetry = { viewModel.resendMessage(chatId, msg.body) },
                                    onDelete = { viewModel.deleteMessages(chatId, listOf(msg.msgId)) },
                                    onRetract = { viewModel.retractMessages(chatId, listOf(msg.msgId)) },
                                    onEdit = { 
                                        editingMessage = msg
                                        text = msg.body
                                    },
                                    onReply = { replyingTo = msg },
                                    onForward = { showForwardDialog = listOf(msg.msgId) },
                                    onReaction = { emoji -> viewModel.setReaction(chatId, msg.msgId, emoji) },
                                    onReplyClick = { replyId ->
                                        val hex = replyId.toHexString()
                                        pendingScrollToId = hex
                                    },
                                    retractionNotice = { viewModel.getRetractionNotice() },
                                    getRepliedMessage = { id -> viewModel.getMessage(id) },
                                    isHighlighted = highlightedMsgId == msg.msgId.toHexString(),
                                    isCompact = isCompact,
                                    showAuthor = group != null,
                                    isMember = isMember
                                )
                            }
                        }

                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    text = stringResource(R.string.encrypted_connection),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }

                // Jump to Top/Bottom buttons
                AnimatedVisibility(
                    visible = showToTop,
                    modifier = Modifier.align(Alignment.TopCenter),
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut()
                ) {
                    SmallFloatingActionButton(
                        onClick = { scope.launch { if (displayMessages.isNotEmpty()) listState.animateScrollToItem(displayMessages.size) } },
                        modifier = Modifier.padding(top = 16.dp),
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        contentColor = MaterialTheme.colorScheme.primary,
                        shape = CircleShape
                    ) { Icon(Icons.Default.KeyboardDoubleArrowUp, contentDescription = null) }
                }

                AnimatedVisibility(
                    visible = showToBottom,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut()
                ) {
                    SmallFloatingActionButton(
                        onClick = { scope.launch { listState.animateScrollToItem(0) } },
                        modifier = Modifier.padding(bottom = 16.dp),
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        contentColor = MaterialTheme.colorScheme.primary,
                        shape = CircleShape
                    ) { Icon(Icons.Default.KeyboardDoubleArrowDown, contentDescription = null) }
                }
            }
        }
    }

    if (showClearChatDialog) {
        AlertDialog(
            onDismissRequest = { showClearChatDialog = false },
            title = { Text(stringResource(R.string.clear_chat)) },
            text = { Text(stringResource(R.string.clear_chat_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearChat(chatId)
                        showClearChatDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearChatDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showForwardDialog != null) {
        val allContacts by viewModel.contacts.collectAsState()
        AlertDialog(
            onDismissRequest = { showForwardDialog = null },
            title = { Text(stringResource(R.string.forward_to)) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(allContacts) { contact ->
                        ListItem(
                            headlineContent = { Text(contact.localName ?: contact.displayName) },
                            leadingContent = {
                                val contactAvatars by viewModel.contactAvatars.collectAsState()
                                Avatar(
                                    avatarBytes = contactAvatars[contact.peerIk.toHexString()] ?: viewModel.getAvatarOf(contact.peerIk),
                                    name = contact.localName ?: contact.displayName
                                )
                            },
                            modifier = Modifier.clickable {
                                viewModel.forwardMessages(contact.chatId, showForwardDialog!!)
                                showForwardDialog = null
                            }
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showForwardDialog = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showInviteDialog && group != null) {
        val allContacts by viewModel.contacts.collectAsState()
        AlertDialog(
            onDismissRequest = { showInviteDialog = false },
            title = { Text(stringResource(R.string.invite_contact)) },
            text = {
                val availableToInvite = allContacts.filter { contact ->
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
                                    val contactAvatars by viewModel.contactAvatars.collectAsState()
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
                TextButton(onClick = { showInviteDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageBubble(
    message: FfiMessage,
    viewModel: RatatoskViewModel,
    chatId: ByteArray,
    snackbarHostState: SnackbarHostState,
    outgoingColor: androidx.compose.ui.graphics.Color,
    status: FfiDeliveryStatus?,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onRetract: () -> Unit,
    onEdit: () -> Unit,
    onReply: () -> Unit,
    onForward: () -> Unit,
    onReaction: (String?) -> Unit,
    onReplyClick: (ByteArray) -> Unit,
    retractionNotice: () -> String,
    getRepliedMessage: (ByteArray) -> FfiMessage?,
    isHighlighted: Boolean,
    isCompact: Boolean = true,
    showAuthor: Boolean = false,
    isMember: Boolean = true
) {
    val alignment = if (message.mine) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor = if (message.mine) outgoingColor else MaterialTheme.colorScheme.surfaceVariant
    
    val authorIk = remember(message) {
        if (message.mine || !showAuthor) null
        else if (message.msgId.size >= 32) message.msgId.take(32).toByteArray()
        else null
    }

    val contacts by viewModel.contacts.collectAsState()
    val contactAvatars by viewModel.contactAvatars.collectAsState()
    val authorContact = remember(contacts, authorIk) {
        authorIk?.let { ik -> contacts.find { it.peerIk.contentEquals(ik) } }
    }
    val authorName = authorContact?.let { it.localName ?: it.displayName } ?: authorIk?.toHexString()?.take(8)

    val contentColor = if (message.mine) {
        if (bubbleColor.luminance() > 0.5f) Color.Black else Color.White
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    
    val linkColor = if (message.mine) contentColor else MaterialTheme.colorScheme.primary
    val fullAnnotatedBody = remember(message.body, linkColor) {
        MarkdownUtils.parseMarkdown(message.body, linkColor)
    }

    var isExpanded by remember { mutableStateOf(false) }
    var revealedSpoilers by remember { mutableStateOf(setOf<Int>()) }
    
    val threshold = 300
    val isLong = message.body.length > threshold
    val readMoreText = stringResource(R.string.read_more)

    val annotatedBody = remember(fullAnnotatedBody, isExpanded, isLong, readMoreText, linkColor, revealedSpoilers) {
        val base = if (isLong && !isExpanded) {
            val safeThreshold = if (fullAnnotatedBody.length > threshold) threshold else fullAnnotatedBody.length
            buildAnnotatedString {
                append(fullAnnotatedBody.subSequence(0, safeThreshold))
                append("... ")
                pushStringAnnotation(tag = "EXPAND", annotation = "expand")
                withStyle(style = SpanStyle(color = linkColor, fontWeight = FontWeight.Bold)) {
                    append(readMoreText)
                }
                pop()
            }
        } else {
            fullAnnotatedBody
        }
        
        val spoilerAnnotations = base.getStringAnnotations("SPOILER", 0, base.length)
        if (spoilerAnnotations.isEmpty()) {
            base
        } else {
            buildAnnotatedString {
                append(base)
                spoilerAnnotations.forEach { annotation ->
                    val isRevealed = revealedSpoilers.contains(annotation.start)
                    addStyle(
                        style = SpanStyle(
                            background = if (isRevealed) Color.Gray.copy(alpha = 0.2f) else Color.DarkGray,
                            color = if (isRevealed) contentColor else Color.Transparent
                        ),
                        start = annotation.start,
                        end = annotation.end
                    )
                }
            }
        }
    }

    var showMenu by remember { mutableStateOf(false) }
    var showRetractDialog by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    Box(
        modifier = Modifier.fillMaxWidth().background(if (isHighlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent),
        contentAlignment = alignment
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (message.mine) Arrangement.End else Arrangement.Start,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (message.mine && status == FfiDeliveryStatus.UNDELIVERABLE) {
                IconButton(onClick = onRetry) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Retry",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            Card(
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (message.mine) 16.dp else 0.dp,
                    bottomEnd = if (message.mine) 0.dp else 16.dp
                ),
                colors = CardDefaults.cardColors(
                    containerColor = bubbleColor,
                    contentColor = contentColor
                ),
                modifier = Modifier.widthIn(max = 280.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(12.dp)
                        .pointerInput(annotatedBody) {
                            detectTapGestures(
                                onTap = { offset ->
                                    textLayoutResult?.let { layout ->
                                        val characterIndex = layout.getOffsetForPosition(offset)
                                        annotatedBody.getStringAnnotations("URL", characterIndex, characterIndex)
                                            .firstOrNull()?.let { annotation ->
                                                uriHandler.openUri(annotation.item)
                                                return@detectTapGestures
                                            }
                                        annotatedBody.getStringAnnotations("EXPAND", characterIndex, characterIndex)
                                            .firstOrNull()?.let {
                                                isExpanded = true
                                                return@detectTapGestures
                                            }
                                        annotatedBody.getStringAnnotations("SPOILER", characterIndex, characterIndex)
                                            .firstOrNull()?.let { annotation ->
                                                if (!revealedSpoilers.contains(annotation.start)) {
                                                    revealedSpoilers = revealedSpoilers + annotation.start
                                                    return@detectTapGestures
                                                }
                                            }
                                    }
                                    if (isLong) { isExpanded = !isExpanded }
                                },
                                onLongPress = { showMenu = true }
                            )
                        }
                ) {
                    if (authorIk != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 4.dp)
                        ) {
                            val avatarBytes = authorIk.toHexString().let { contactAvatars[it] } ?: viewModel.getAvatarOf(authorIk)
                            Avatar(
                                avatarBytes = avatarBytes,
                                name = authorName ?: "",
                                size = 24.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = authorName ?: "",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = linkColor
                            )
                        }
                    }

                    message.replyTo?.let { replyId ->
                        val repliedMsg = getRepliedMessage(replyId)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(contentColor.copy(alpha = 0.1f))
                                .clickable { onReplyClick(replyId) }
                                .padding(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(2.dp)
                                    .height(IntrinsicSize.Max)
                                    .background(linkColor)
                                    .align(Alignment.CenterVertically)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = repliedMsg?.body ?: stringResource(R.string.message_unavailable),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                color = contentColor.copy(alpha = 0.8f)
                            )
                        }
                    }

                    message.sharedContact?.let { sharedContact ->
                        SharedContactCard(
                            sharedContact = sharedContact,
                            onAdd = { viewModel.addSharedContact(message.msgId) },
                            contentColor = contentColor,
                            linkColor = linkColor
                        )
                    }

                    FileAttachment(message.files, viewModel, chatId, snackbarHostState, contentColor, linkColor)

                    if (message.forwarded) {
                        Text(
                            text = stringResource(R.string.forwarded),
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.7f),
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                    }
                    Text(
                        text = annotatedBody,
                        style = MaterialTheme.typography.bodyMedium.copy(color = contentColor),
                        modifier = Modifier.padding(bottom = 4.dp),
                        onTextLayout = { textLayoutResult = it }
                    )
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (message.editedAtMs != null) {
                            Text(
                                text = stringResource(R.string.edited),
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(message.wallMs.toLong())),
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.8f)
                        )
                        
                        if (message.mine) {
                            Spacer(modifier = Modifier.width(2.dp))
                            MessageStatusIcon(status, contentColor, onWaitingClick = {
                                scope.launch {
                                    snackbarHostState.showSnackbar(viewModel.getWaitingNotice())
                                }
                            })
                        }
                    }
                }
            }
        }

        if (message.reactions.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .align(if (message.mine) Alignment.BottomEnd else Alignment.BottomStart)
                    .offset(y = 12.dp)
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val groups = message.reactions.groupBy { it.emoji }
                groups.forEach { (emoji, list) ->
                    val hasMine = list.any { it.mine }
                    Surface(
                        color = if (hasMine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.clickable { onReaction(if (hasMine) null else emoji) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(emoji, style = MaterialTheme.typography.labelSmall)
                            if (list.size > 1) {
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(list.size.toString(), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }

        if (showMenu) {
            ModalBottomSheet(
                onDismissRequest = { showMenu = false },
                sheetState = rememberModalBottomSheetState()
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
                    if (isMember) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            val emojis = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")
                            emojis.forEach { emoji ->
                                val isSelected = message.reactions.any { it.mine && it.emoji == emoji }
                                Surface(
                                    modifier = Modifier.size(44.dp).clickable {
                                        onReaction(if (isSelected) null else emoji)
                                        showMenu = false
                                    },
                                    shape = CircleShape,
                                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(emoji, style = MaterialTheme.typography.headlineSmall)
                                    }
                                }
                            }
                        }
                    }

                    if (message.mine && status == FfiDeliveryStatus.UNDELIVERABLE && isMember) {
                        ListItem(
                            headlineContent = { Text("Retry") },
                            leadingContent = { Icon(Icons.Default.Refresh, contentDescription = null) },
                            modifier = Modifier.clickable { onRetry(); showMenu = false }
                        )
                    }
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.copy)) },
                        leadingContent = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                        modifier = Modifier.clickable {
                            clipboardManager.setText(AnnotatedString(message.body))
                            showMenu = false
                        }
                    )
                    if (message.mine && isMember) {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.edit)) },
                            leadingContent = { Icon(Icons.Default.Edit, contentDescription = null) },
                            modifier = Modifier.clickable { showMenu = false; onEdit() }
                        )
                    }
                    if (isMember) {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.reply)) },
                            leadingContent = { Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null) },
                            modifier = Modifier.clickable { showMenu = false; onReply() }
                        )
                    }
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.forward)) },
                        leadingContent = { Icon(Icons.Default.ArrowForward, contentDescription = null) },
                        modifier = Modifier.clickable { showMenu = false; onForward() }
                    )
                    if (message.mine && isMember) {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.retract)) },
                            leadingContent = { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null) },
                            modifier = Modifier.clickable { showMenu = false; showRetractDialog = true }
                        )
                    }
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.delete_for_me)) },
                        leadingContent = { Icon(Icons.Default.Delete, contentDescription = null) },
                        colors = ListItemDefaults.colors(headlineColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.clickable { onDelete(); showMenu = false }
                    )
                }
            }
        }

        if (showRetractDialog) {
            AlertDialog(
                onDismissRequest = { showRetractDialog = false },
                title = { Text(stringResource(R.string.retract)) },
                text = { Text(retractionNotice()) },
                confirmButton = {
                    TextButton(
                        onClick = { onRetract(); showRetractDialog = false },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text(stringResource(R.string.delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { showRetractDialog = false }) { Text(stringResource(R.string.cancel)) }
                }
            )
        }
    }
}

@Composable
fun SharedContactCard(
    sharedContact: org.ratatosk.core.FfiSharedContact,
    onAdd: () -> Unit,
    contentColor: Color,
    linkColor: Color
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(8.dp))
            .background(contentColor.copy(alpha = 0.1f)).padding(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AccountCircle, contentDescription = null, tint = linkColor, modifier = Modifier.size(40.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = sharedContact.displayName, style = MaterialTheme.typography.titleSmall, color = contentColor)
                Text(text = sharedContact.fingerprint, style = MaterialTheme.typography.labelSmall, color = contentColor.copy(alpha = 0.6f))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (sharedContact.mine) {
            Text(text = stringResource(R.string.this_is_you), style = MaterialTheme.typography.labelMedium, color = contentColor.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.End))
        } else if (sharedContact.alreadyKnown) {
            Text(text = stringResource(R.string.already_in_contacts), style = MaterialTheme.typography.labelMedium, color = contentColor.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.End))
        } else {
            Button(
                onClick = onAdd, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = linkColor, contentColor = if (linkColor.luminance() > 0.5f) Color.Black else Color.White)
            ) {
                Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.add_contact))
            }
        }
    }
}

@Composable
fun MessageStatusIcon(status: FfiDeliveryStatus?, color: Color, onWaitingClick: () -> Unit = {}) {
    val icon: ImageVector? = when (status) {
        FfiDeliveryStatus.PENDING -> Icons.Default.HourglassTop
        FfiDeliveryStatus.WAITING -> Icons.Default.Schedule
        FfiDeliveryStatus.SENT -> Icons.Default.Done
        FfiDeliveryStatus.DELIVERED, FfiDeliveryStatus.READ -> Icons.Default.DoneAll
        FfiDeliveryStatus.UNDELIVERABLE -> Icons.Default.ErrorOutline
        null -> null
    }
    val isLight = color.luminance() > 0.5f
    val tint = when (status) {
        FfiDeliveryStatus.READ -> if (isLight) Color(0xFF0288D1) else Color(0xFF40C4FF)
        FfiDeliveryStatus.UNDELIVERABLE -> MaterialTheme.colorScheme.error
        FfiDeliveryStatus.WAITING -> color.copy(alpha = 0.6f)
        else -> color
    }
    icon?.let {
        Icon(imageVector = it, contentDescription = status?.name, modifier = Modifier.size(16.dp).then(if (status == FfiDeliveryStatus.WAITING) Modifier.clickable { onWaitingClick() } else Modifier), tint = tint)
    }
}

@Composable
fun FileAttachment(
    files: List<FfiFile>,
    viewModel: RatatoskViewModel,
    chatId: ByteArray,
    snackbarHostState: SnackbarHostState,
    contentColor: Color,
    linkColor: Color
) {
    files.forEach { file ->
        val progress by viewModel.fileProgress.collectAsState()
        val activeJobs by viewModel.activeJobsFlow.collectAsState()
        val fileIdHex = file.fileId.toHexString()
        val isExportingActive = activeJobs.contains(fileIdHex)
        val currentProgress = progress[fileIdHex] ?: (if (file.complete) 1f else if (file.receivedChunks > 0UL) file.receivedChunks.toFloat() / file.chunkTotal.toFloat() else 0f)
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val isMedia = FileUtils.isImage(file.name) || FileUtils.isVideo(file.name) || FileUtils.isAudio(file.name)
        
        Column(modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(contentColor.copy(alpha = 0.1f))
            .clickable(enabled = file.complete || !file.incoming) {
                if (isMedia) {
                    viewModel.openMedia(file, context.cacheDir)
                }
            }
            .padding(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (FileUtils.isImage(file.name)) Icons.Default.Image 
                                 else if (FileUtils.isVideo(file.name)) Icons.Default.Movie
                                 else if (FileUtils.isAudio(file.name)) Icons.Default.Audiotrack
                                 else Icons.Default.InsertDriveFile, 
                    contentDescription = null, 
                    tint = linkColor, 
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = contentColor)
                    Text(text = formatFileSize(file.sizeBytes), style = MaterialTheme.typography.labelSmall, color = contentColor.copy(alpha = 0.6f))
                    if (!file.incoming && !file.complete) {
                        Text(text = if (currentProgress > 0) stringResource(R.string.peer_downloading, (currentProgress * 100).toInt()) else stringResource(R.string.waiting_for_peer), style = MaterialTheme.typography.labelSmall, color = linkColor)
                    }
                }
                if (file.incoming && !file.accepted && !file.complete) {
                    Row {
                        IconButton(onClick = { viewModel.declineFile(chatId, file.fileId) }) { Icon(Icons.Default.Close, contentDescription = "Decline", tint = MaterialTheme.colorScheme.error) }
                        IconButton(onClick = { viewModel.acceptFile(chatId, file.fileId) }) { Icon(Icons.Default.Download, contentDescription = "Accept", tint = linkColor) }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isExportingActive) {
                            Box(contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(progress = { currentProgress }, modifier = Modifier.size(32.dp), strokeWidth = 2.dp, color = linkColor)
                                IconButton(onClick = { viewModel.cancelFileJob(file.fileId) }, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Close, contentDescription = "Cancel", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp)) }
                            }
                        } else {
                            if (!file.complete) { CircularProgressIndicator(progress = { currentProgress }, modifier = Modifier.size(24.dp).padding(4.dp), strokeWidth = 2.dp, color = linkColor) }
                            if (file.complete || !file.incoming) {
                                IconButton(onClick = {
                                    val tempDir = java.io.File(context.cacheDir, "temp_open"); tempDir.mkdirs()
                                    val dest = java.io.File(tempDir, file.name)
                                    viewModel.saveFile(file, dest) { savedFile ->
                                        try {
                                            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", savedFile)
                                            val mimeType = if (file.name.endsWith(".apk", ignoreCase = true)) "application/vnd.android.package-archive" else context.contentResolver.getType(uri) ?: "*/*"
                                            val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, mimeType); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); if (mimeType == "application/vnd.android.package-archive") addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                            context.startActivity(intent)
                                        } catch (e: Exception) { scope.launch { snackbarHostState.showSnackbar("Failed to open file: ${e.message}") } }
                                    }
                                }) { Icon(Icons.Default.OpenInNew, contentDescription = "Open", tint = linkColor) }
                                val savedMsgTemplate = stringResource(R.string.file_saved)
                                IconButton(onClick = { viewModel.downloadFile(file) { path -> scope.launch { snackbarHostState.showSnackbar(savedMsgTemplate.format(path)) } } }) { Icon(Icons.Default.Save, contentDescription = "Save", tint = linkColor) }
                            }
                        }
                    }
                }
            }
            if (file.hasPreview) {
                 val previewBytes = viewModel.getFilePreview(file.fileId)
                 Box(modifier = Modifier
                     .fillMaxWidth()
                     .heightIn(min = 100.dp, max = 300.dp)
                     .padding(top = 8.dp)
                     .clip(RoundedCornerShape(4.dp))
                     .background(contentColor.copy(alpha = 0.05f))
                     .clickable(enabled = file.complete || !file.incoming) {
                         if (isMedia) {
                             viewModel.openMedia(file, context.cacheDir)
                         }
                     }, 
                     contentAlignment = Alignment.Center
                 ) {
                     if (previewBytes != null) { Image(painter = rememberAsyncImagePainter(previewBytes), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                     else { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = linkColor.copy(alpha = 0.5f)) }
                 }
            }
        }
    }
}

fun formatFileSize(bytes: ULong): String {
    val b = bytes.toDouble()
    return when {
        b < 1024 -> "%.0f B".format(java.util.Locale.US, b)
        b < 1024 * 1024 -> "%.1f KB".format(java.util.Locale.US, b / 1024)
        b < 1024 * 1024 * 1024 -> "%.1f MB".format(java.util.Locale.US, b / (1024 * 1024))
        else -> "%.1f GB".format(java.util.Locale.US, b / (1024 * 1024 * 1024))
    }
}
