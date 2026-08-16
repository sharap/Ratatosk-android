package chat.ratatosk.android.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
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
import chat.ratatosk.android.R
import chat.ratatosk.android.ui.RatatoskViewModel
import chat.ratatosk.android.util.MarkdownUtils
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
    val chatTheme by viewModel.chatTheme.collectAsState()
    val allMessages by viewModel.messages.collectAsState()
    val messageStatuses by viewModel.messageStatuses.collectAsState()
    val contacts by viewModel.contacts.collectAsState()
    
    val chatIdHex = remember(chatId) { chatId.toHexString() }
    val messages = allMessages[chatIdHex] ?: emptyList()
    val listState = rememberLazyListState()

    val contact = remember(contacts, chatIdHex) {
        contacts.find { it.chatId.toHexString() == chatIdHex }
    }

    // Reversed list for the UI because we use reverseLayout = true
    val displayMessages = remember(messages) { messages.reversed() }

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
        topBar = {
            TopAppBar(
                title = { 
                    Column(modifier = Modifier.clickable { onHeaderClick() }) {
                        Text(contact?.displayName ?: stringResource(R.string.chat))
                        if (contact?.seenOnLan == true) {
                            Text(
                                text = stringResource(R.string.online_lan),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
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
                        placeholder = { Text(stringResource(R.string.message)) },
                        maxLines = 4
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (text.isNotBlank()) {
                                viewModel.sendText(chatId, text)
                                text = ""
                            }
                        },
                        enabled = text.isNotBlank()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send))
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            chatTheme.backgroundImageUri?.let { uri ->
                Image(
                    painter = rememberAsyncImagePainter(uri),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alpha = 0.3f
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
                    val status = messageStatuses[msg.msgId.toHexString()]
                    Box(modifier = Modifier.fillMaxWidth().animateItem()) {
                        MessageBubble(
                            message = msg,
                            outgoingColor = chatTheme.outgoingBubbleColor,
                            status = status,
                            onRetry = { viewModel.resendMessage(chatId, msg.body) }
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
    }
}

@Composable
fun MessageBubble(
    message: FfiMessage,
    outgoingColor: androidx.compose.ui.graphics.Color,
    status: FfiDeliveryStatus?,
    onRetry: () -> Unit
) {
    val alignment = if (message.mine) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor = if (message.mine) outgoingColor else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (message.mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    
    val linkColor = MaterialTheme.colorScheme.primary
    val annotatedBody = remember(message.body, linkColor) {
        MarkdownUtils.parseMarkdown(message.body, linkColor)
    }

    var showMenu by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current

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
                modifier = Modifier.widthIn(max = 280.dp)
            ) {
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .padding(12.dp)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onLongPress = { showMenu = true }
                                )
                            }
                    ) {
                        ClickableText(
                            text = annotatedBody,
                            style = MaterialTheme.typography.bodyMedium.copy(color = contentColor),
                            modifier = Modifier.padding(bottom = 4.dp),
                            onClick = { offset ->
                                annotatedBody.getStringAnnotations(tag = "URL", start = offset, end = offset)
                                    .firstOrNull()?.let { annotation ->
                                        uriHandler.openUri(annotation.item)
                                    }
                            }
                        )
                        Row(
                            modifier = Modifier.align(Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                    .format(java.util.Date(message.wallMs.toLong())),
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor.copy(alpha = 0.6f)
                            )
                            
                            if (message.mine) {
                                MessageStatusIcon(status, contentColor.copy(alpha = 0.6f))
                            }
                        }
                    }
                }
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            properties = PopupProperties(focusable = false)
        ) {
            if (message.mine && status == FfiDeliveryStatus.UNDELIVERABLE) {
                DropdownMenuItem(
                    text = { Text("Retry") },
                    onClick = {
                        onRetry()
                        showMenu = false
                    },
                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) }
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.copy)) },
                onClick = {
                    clipboardManager.setText(AnnotatedString(message.body))
                    showMenu = false
                },
                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete)) },
                onClick = {
                    showMenu = false
                },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) }
            )
        }
    }
}

@Composable
fun MessageStatusIcon(status: FfiDeliveryStatus?, color: androidx.compose.ui.graphics.Color) {
    val icon: ImageVector? = when (status) {
        FfiDeliveryStatus.PENDING -> Icons.Default.Schedule
        FfiDeliveryStatus.SENT -> Icons.Default.Done
        FfiDeliveryStatus.DELIVERED, FfiDeliveryStatus.READ -> Icons.Default.DoneAll
        FfiDeliveryStatus.UNDELIVERABLE -> Icons.Default.ErrorOutline
        null -> null
    }
    
    val tint = if (status == FfiDeliveryStatus.READ) {
        MaterialTheme.colorScheme.primary // Use primary color for "Read"
    } else {
        color
    }

    icon?.let {
        Icon(
            imageVector = it,
            contentDescription = status?.name,
            modifier = Modifier.size(12.dp),
            tint = tint
        )
    }
}
