package chat.ratatosk.android.ui.contacts

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.ratatosk.android.R
import chat.ratatosk.android.core.RatatoskCore
import chat.ratatosk.android.ui.RatatoskViewModel
import chat.ratatosk.android.ui.components.Avatar
import chat.ratatosk.android.util.toHexString
import qrcode.QRCode
import org.ratatosk.core.deletionNotice
import org.ratatosk.core.revocationNotice

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
    var showEditNameDialog by remember { mutableStateOf(false) }
    var showRevokeDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showShareToChatDialog by remember { mutableStateOf(false) }
    
    var editNameText by remember { mutableStateOf("") }
    var purgeHistoryOnDelete by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.contact_details)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
