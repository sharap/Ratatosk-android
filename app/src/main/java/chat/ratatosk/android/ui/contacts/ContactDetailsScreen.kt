package chat.ratatosk.android.ui.contacts

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import chat.ratatosk.android.R
import chat.ratatosk.android.core.RatatoskCore
import chat.ratatosk.android.ui.RatatoskViewModel
import chat.ratatosk.android.ui.components.Avatar
import chat.ratatosk.android.util.formatDateTime
import chat.ratatosk.android.util.toHexString
import kotlinx.coroutines.launch
import qrcode.QRCode
import org.ratatosk.core.*
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailsScreen(
    viewModel: RatatoskViewModel,
    chatId: ByteArray,
    onBack: () -> Unit,
    onChatClick: (ByteArray) -> Unit
) {
    val contacts by viewModel.contacts.collectAsState()
    val contactAvatars by viewModel.contactAvatars.collectAsState()
    val contact = remember(contacts, chatId) {
        contacts.find { it.chatId.contentEquals(chatId) }
    }
    
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val screenWidth = remember { context.resources.displayMetrics.widthPixels.toFloat() }
    val backOffset = remember(chatId.toHexString()) { Animatable(screenWidth) }

    val performBack = {
        scope.launch {
            backOffset.animateTo(screenWidth, animationSpec = tween(durationMillis = 200, easing = LinearOutSlowInEasing))
            onBack()
        }
    }

    LaunchedEffect(chatId.toHexString()) {
        backOffset.animateTo(0f, animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing))
    }

    BackHandler(enabled = true) {
        performBack()
    }

    var showEditNameDialog by remember { mutableStateOf(false) }
    var showRevokeDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showShareToChatDialog by remember { mutableStateOf(false) }
    
    var editNameText by remember { mutableStateOf("") }
    var purgeHistoryOnDelete by remember { mutableStateOf(true) }

    Box(modifier = Modifier.fillMaxSize()) {
        // Dimming layer
        if (backOffset.value > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = (0.25f * (1f - backOffset.value / screenWidth)).coerceAtLeast(0f)))
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(backOffset.value.roundToInt(), 0) }
                .shadow(elevation = if (backOffset.value > 0f) 16.dp else 0.dp)
                .background(MaterialTheme.colorScheme.background)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            scope.launch {
                                backOffset.snapTo((backOffset.value + dragAmount).coerceAtLeast(0f))
                            }
                            change.consume()
                        },
                        onDragEnd = {
                            if (backOffset.value > 250f) {
                                performBack()
                            } else {
                                scope.launch { backOffset.animateTo(0f) }
                            }
                        },
                        onDragCancel = {
                            scope.launch { backOffset.animateTo(0f) }
                        }
                    )
                }
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.contact_details)) },
                        navigationIcon = {
                            IconButton(onClick = { performBack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    )
                }
            ) { innerPadding ->
                if (contact == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Contact not found")
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .padding(innerPadding)
                            .padding(16.dp)
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Avatar
                        Avatar(
                            avatarBytes = contact.peerIk.toHexString().let { contactAvatars[it] } ?: viewModel.getAvatarOf(contact.peerIk),
                            name = contact.localName ?: contact.displayName,
                            size = 100.dp
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = contact.localName ?: contact.displayName,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(onClick = { 
                                editNameText = contact.localName ?: ""
                                showEditNameDialog = true 
                            }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit Name", modifier = Modifier.size(20.dp))
                            }
                        }
                        
                        if (contact.localName != null) {
                            Text(
                                text = "(${contact.displayName})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (contact.seenOnLan) {
                                Surface(
                                    modifier = Modifier.size(8.dp),
                                    shape = androidx.compose.foundation.shape.CircleShape,
                                    color = Color.Green
                                ) {}
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.online_lan),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Text(
                                    text = "Offline",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }

                        Text(
                            text = stringResource(R.string.contact_added, contact.addedMs.toLong().formatDateTime()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // Verification Status Card
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (contact.verified) 
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                else 
                                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (contact.verified) Icons.Default.VerifiedUser else Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = if (contact.verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (contact.verified) stringResource(R.string.identity_verified) else stringResource(R.string.unverified),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                
                                if (!contact.verified) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = stringResource(R.string.unverified_warning),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Button(
                                        onClick = { viewModel.markVerified(contact.peerIk) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(stringResource(R.string.verify_identity))
                                    }
                                } else {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    OutlinedButton(
                                        onClick = { showRevokeDialog = true },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                    ) {
                                        Icon(Icons.Default.GppBad, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(R.string.revoke_trust))
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        // Actions
                        OutlinedButton(
                            onClick = { onChatClick(chatId) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.chat_button))
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Fingerprint and Share
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = stringResource(R.string.fingerprint_title),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = contact.fingerprint,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = {
                                        clipboardManager.setText(AnnotatedString(contact.fingerprint))
                                    }) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                                    }
                                }

                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(stringResource(R.string.share_contact))
                                    IconButton(onClick = { showShareToChatDialog = true }) {
                                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Share to Chat")
                                    }
                                }

                                if (contact.onion != null || contact.chatmail != null) {
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                }

                                contact.onion?.let { onion ->
                                    Text(
                                        text = stringResource(R.string.onion_address),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(onion, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                        IconButton(onClick = { clipboardManager.setText(AnnotatedString(onion)) }) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                }

                                contact.chatmail?.let { chatmail ->
                                    Text(
                                        text = stringResource(R.string.mail_address),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(chatmail, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                        IconButton(onClick = { clipboardManager.setText(AnnotatedString(chatmail)) }) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Reachability
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = stringResource(R.string.reachability),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                contact.reachability.rungs.forEach { rung ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(rung.transport.name, style = MaterialTheme.typography.bodyMedium)
                                        Row {
                                            StatusChip(stringResource(R.string.status_enabled), rung.enabled)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            StatusChip(stringResource(R.string.status_ready), rung.ready)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            StatusChip(stringResource(R.string.status_addressable), rung.addressable)
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                }

                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                
                                Text(
                                    text = stringResource(R.string.route, contact.reachability.route?.name ?: "None"),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                contact.reachability.rising?.let { rising ->
                                    Text(
                                        text = stringResource(R.string.rising, rising.name),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                contact.directChannel?.let { direct ->
                                    Text(
                                        text = stringResource(R.string.direct_channel) + ": ${direct.name}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Green
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Technical Info
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Technical Details",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.card_version, contact.cardVersion.toLong()),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                
                                if (contact.anomalies.unknownSession > 0uL || contact.anomalies.badTag > 0uL || contact.anomalies.malformed > 0uL) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = stringResource(R.string.anomalies),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    if (contact.anomalies.unknownSession > 0uL) {
                                        Text(stringResource(R.string.unknown_sessions, contact.anomalies.unknownSession.toLong()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                    }
                                    if (contact.anomalies.badTag > 0uL) {
                                        Text(stringResource(R.string.bad_tags, contact.anomalies.badTag.toLong()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                    }
                                    if (contact.anomalies.malformed > 0uL) {
                                        Text(stringResource(R.string.malformed_frames, contact.anomalies.malformed.toLong()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        TextButton(
                            onClick = { showDeleteDialog = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.delete_contact))
                        }
                    }
                }
            }
        }
    }

    if (showEditNameDialog && contact != null) {
        AlertDialog(
            onDismissRequest = { showEditNameDialog = false },
            title = { Text(stringResource(R.string.edit_contact_name)) },
            text = {
                OutlinedTextField(
                    value = editNameText,
                    onValueChange = { editNameText = it },
                    label = { Text("Nickname") },
                    singleLine = true,
                    placeholder = { Text(contact.displayName) }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setLocalName(contact.peerIk, editNameText.ifBlank { null })
                    showEditNameDialog = false
                }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditNameDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showRevokeDialog && contact != null) {
        AlertDialog(
            onDismissRequest = { showRevokeDialog = false },
            title = { Text(stringResource(R.string.revoke_trust)) },
            text = { Text(revocationNotice()) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.revokeVerification(contact.peerIk)
                    showRevokeDialog = false
                }) {
                    Text(stringResource(R.string.revoke_trust))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showDeleteDialog && contact != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_contact)) },
            text = {
                Column {
                    Text(deletionNotice())
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = purgeHistoryOnDelete,
                            onCheckedChange = { purgeHistoryOnDelete = it }
                        )
                        Text(stringResource(R.string.purge_history))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteContact(contact.peerIk, purgeHistoryOnDelete)
                    showDeleteDialog = false
                    performBack()
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

    if (showShareToChatDialog && contact != null) {
        val allContacts by viewModel.contacts.collectAsState()
        AlertDialog(
            onDismissRequest = { showShareToChatDialog = false },
            title = { Text(stringResource(R.string.share_to)) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(allContacts.filter { it.chatId.toHexString() != contact.chatId.toHexString() }) { target ->
                        ListItem(
                            headlineContent = { Text(target.localName ?: target.displayName) },
                            leadingContent = {
                                Avatar(
                                    avatarBytes = contactAvatars[target.peerIk.toHexString()] ?: viewModel.getAvatarOf(target.peerIk),
                                    name = target.localName ?: target.displayName
                                )
                            },
                            modifier = Modifier.clickable {
                                viewModel.shareContact(target.chatId, contact.peerIk)
                                showShareToChatDialog = false
                            }
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showShareToChatDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
fun StatusChip(text: String, active: Boolean) {
    Surface(
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.outline
        )
    }
}
