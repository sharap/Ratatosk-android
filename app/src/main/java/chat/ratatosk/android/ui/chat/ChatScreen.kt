package chat.ratatosk.android.ui.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import coil.compose.rememberAsyncImagePainter
import uniffi.ratatosk_ffi.FfiDeliveryStatus
import uniffi.ratatosk_ffi.FfiMessage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: RatatoskViewModel,
    chatId: ByteArray,
    onBack: () -> Unit,
    onHeaderClick: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    var editingMessage by remember { mutableStateOf<FfiMessage?>(null) }
    var replyingTo by remember { mutableStateOf<FfiMessage?>(null) }
    var showForwardDialog by remember { mutableStateOf<List<ByteArray>?>(null) }
    
    val chatTheme by viewModel.chatTheme.collectAsState()
    val allMessages by viewModel.messages.collectAsState()
    val messageStatuses by viewModel.messageStatuses.collectAsState()
    val contacts by viewModel.contacts.collectAsState()
    val contactAvatars by viewModel.contactAvatars.collectAsState()
    
    val chatIdHex = remember(chatId) { chatId.toHexString() }
    val messages = allMessages[chatIdHex] ?: emptyList()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val contact = remember(contacts, chatIdHex) {
        contacts.find { it.chatId.toHexString() == chatIdHex }
    }

    var highlightedMsgId by remember { mutableStateOf<String?>(null) }
    var pendingScrollToId by remember { mutableStateOf<String?>(null) }
    var showChatMenu by remember { mutableStateOf(false) }
    var showClearChatDialog by remember { mutableStateOf(false) }

    val displayMessages = remember(messages) { messages.reversed() }

    var previousIndex by remember { mutableStateOf(0) }
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
        android.util.Log.d("ChatScreen", "Jump-to started for: $targetId")
        
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
                android.util.Log.d("ChatScreen", "Found target message at index $index. Scrolling...")
                highlightedMsgId = targetId
                
                kotlinx.coroutines.yield()
                val vHeight = listState.layoutInfo.viewportSize.height
                // Идеальный офсет - 50% высоты экрана
                val offset = if (vHeight > 0) (vHeight * (-0.5f)).toInt() else 0
                
                try {
                    listState.animateScrollToItem(index, offset)
                } catch (e: Exception) {
                    android.util.Log.e("ChatScreen", "Scroll error: ${e.javaClass.simpleName}")
                }
                
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
            listState.animateScrollToItem(0)
            
            // Mark the newest incoming message as read
            displayMessages.firstOrNull { !it.mine }?.let { lastPeerMsg ->
                viewModel.markRead(chatId, lastPeerMsg.msgId)
            }
        }
    }

    Scaffold(
        containerColor = if (chatTheme.backgroundImageUri != null) Color.Transparent else MaterialTheme.colorScheme.background,
        topBar = { 
            TopAppBar(
                title = { 
                    Row(
                        modifier = Modifier.clickable { onHeaderClick() },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showChatMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(
                        expanded = showChatMenu,
                        onDismissRequest = { showChatMenu = false }
                    ) {
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
                )
            )
        },
        bottomBar = {
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
                            } else null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                if (text.isNotBlank()) {
                                    val currentEditing = editingMessage
                                    val currentReply = replyingTo
                                    if (currentEditing != null) {
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
                            enabled = text.isNotBlank()
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
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            chatTheme.backgroundImageUri?.let { uriString ->
                Image(
                    painter = rememberAsyncImagePainter(
                        model = coil.request.ImageRequest.Builder(LocalContext.current)
                            .data(Uri.parse(uriString))
                            .build()
                    ),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alpha = chatTheme.backgroundOpacity
                )
            }
            
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
                                android.util.Log.d("ChatScreen", "Reply clicked! Target hex: $hex")
                                pendingScrollToId = hex
                            },
                            retractionNotice = { viewModel.getRetractionNotice() },
                            getRepliedMessage = { id -> viewModel.getMessage(id) },
                            isHighlighted = highlightedMsgId == msg.msgId.toHexString()
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

            // Jump to Top (Oldest) button
            AnimatedVisibility(
                visible = showToTop,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut()
            ) {
                SmallFloatingActionButton(
                    onClick = {
                        scope.launch {
                            if (displayMessages.isNotEmpty()) {
                                listState.animateScrollToItem(displayMessages.size)
                            }
                        }
                    },
                    modifier = Modifier.padding(top = 16.dp),
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                    contentColor = MaterialTheme.colorScheme.primary,
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.KeyboardDoubleArrowUp, contentDescription = "To Beginning")
                }
            }

            // Jump to Bottom (Newest) button
            AnimatedVisibility(
                visible = showToBottom,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut()
            ) {
                SmallFloatingActionButton(
                    onClick = {
                        scope.launch {
                            listState.animateScrollToItem(0)
                        }
                    },
                    modifier = Modifier.padding(bottom = 16.dp),
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                    contentColor = MaterialTheme.colorScheme.primary,
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.KeyboardDoubleArrowDown, contentDescription = "To End")
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
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearChatDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
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
                TextButton(onClick = { showForwardDialog = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageBubble(
    message: FfiMessage,
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
    isHighlighted: Boolean
) {
    val alignment = if (message.mine) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor = if (message.mine) outgoingColor else MaterialTheme.colorScheme.surfaceVariant
    
    val highlightColor by animateColorAsState(
        targetValue = if (isHighlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent,
        animationSpec = tween(durationMillis = 500),
        label = "highlight"
    )

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
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    
    // Auto-scroll to top when expanded
    LaunchedEffect(isExpanded) {
        if (isExpanded) {
            // Wait for recomposition and layout pass to finish
            kotlinx.coroutines.yield()
            bringIntoViewRequester.bringIntoView(Rect(0f, 0f, 10f, 10f))
        }
    }
    
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
        
        // Apply spoiler revelation
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
    
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
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
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .background(highlightColor, RoundedCornerShape(16.dp))
                    .bringIntoViewRequester(bringIntoViewRequester)
            ) {
                Column(
                    modifier = Modifier
                        .padding(12.dp)
                        .pointerInput(annotatedBody) {
                            detectTapGestures(
                                onTap = { offset ->
                                    textLayoutResult?.let { layout ->
                                        val characterIndex = layout.getOffsetForPosition(offset)
                                        
                                        // Check for links
                                        annotatedBody.getStringAnnotations("URL", characterIndex, characterIndex)
                                            .firstOrNull()?.let { annotation ->
                                                uriHandler.openUri(annotation.item)
                                                return@detectTapGestures
                                            }
                                        
                                        // Check for expand link
                                        annotatedBody.getStringAnnotations("EXPAND", characterIndex, characterIndex)
                                            .firstOrNull()?.let {
                                                isExpanded = true
                                                return@detectTapGestures
                                            }

                                        // Check for spoilers
                                        annotatedBody.getStringAnnotations("SPOILER", characterIndex, characterIndex)
                                            .firstOrNull()?.let { annotation ->
                                                if (!revealedSpoilers.contains(annotation.start)) {
                                                    revealedSpoilers = revealedSpoilers + annotation.start
                                                    return@detectTapGestures
                                                }
                                            }
                                    }
                                    
                                    // Default toggle expansion if long message
                                    if (isLong) {
                                        isExpanded = !isExpanded
                                    }
                                },
                                onLongPress = { showMenu = true }
                            )
                        }
                ) {
                    message.replyTo?.let { replyId ->
                        val repliedMsg = getRepliedMessage(replyId)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(contentColor.copy(alpha = 0.1f))
                                .clickable { 
                                    android.util.Log.d("ChatScreen", "Reply bubble clicked! ID hex: ${replyId.toHexString()}")
                                    onReplyClick(replyId) 
                                }
                                .padding(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(2.dp)
                                    .fillMaxHeight()
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
                            MessageStatusIcon(status, contentColor)
                        }
                    }
                }
            }
        }

        // Reactions
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
                        modifier = Modifier.clickable {
                            onReaction(if (hasMine) null else emoji)
                        }
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp)
                ) {
                    // Reaction Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        val emojis = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")
                        emojis.forEach { emoji ->
                            val isSelected = message.reactions.any { it.mine && it.emoji == emoji }
                            Surface(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clickable {
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

                    if (message.mine && status == FfiDeliveryStatus.UNDELIVERABLE) {
                        ListItem(
                            headlineContent = { Text("Retry") },
                            leadingContent = { Icon(Icons.Default.Refresh, contentDescription = null) },
                            modifier = Modifier.clickable {
                                onRetry()
                                showMenu = false
                            }
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

                    if (message.mine) {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.edit)) },
                            leadingContent = { Icon(Icons.Default.Edit, contentDescription = null) },
                            modifier = Modifier.clickable {
                                showMenu = false
                                onEdit()
                            }
                        )
                    }

                    ListItem(
                        headlineContent = { Text(stringResource(R.string.reply)) },
                        leadingContent = { Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null) },
                        modifier = Modifier.clickable {
                            showMenu = false
                            onReply()
                        }
                    )

                    ListItem(
                        headlineContent = { Text(stringResource(R.string.forward)) },
                        leadingContent = { Icon(Icons.Default.ArrowForward, contentDescription = null) },
                        modifier = Modifier.clickable {
                            showMenu = false
                            onForward()
                        }
                    )

                    if (message.mine) {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.retract)) },
                            leadingContent = { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null) },
                            modifier = Modifier.clickable {
                                showMenu = false
                                showRetractDialog = true
                            }
                        )
                    }

                    ListItem(
                        headlineContent = { Text(stringResource(R.string.delete_for_me)) },
                        leadingContent = { Icon(Icons.Default.Delete, contentDescription = null) },
                        colors = ListItemDefaults.colors(headlineColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.clickable {
                            onDelete()
                            showMenu = false
                        }
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
                        onClick = {
                            onRetract()
                            showRetractDialog = false
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(stringResource(R.string.delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRetractDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
    }
}

@Composable
fun MessageStatusIcon(status: FfiDeliveryStatus?, color: Color) {
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
        FfiDeliveryStatus.WAITING -> color.copy(alpha = 0.6f) // Pale for waiting
        else -> color // Use full opacity for better visibility
    }

    icon?.let {
        Icon(
            imageVector = it,
            contentDescription = status?.name,
            modifier = Modifier.size(16.dp),
            tint = tint
        )
    }
}
