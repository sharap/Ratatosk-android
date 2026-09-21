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
                title = {
                    // Канал — группа со вторым профилем, но человеку он
                    // обещан каналом, и называть его группой нельзя.
                    Text(
                        stringResource(
                            if (group?.channel != null) R.string.channel_details else R.string.group_details
                        )
                    )
                },
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

                group.channel?.let { channel ->
                    item {
                        ChannelSection(
                            viewModel = viewModel,
                            chatId = chatId,
                            channel = channel,
                            isOwner = group.mine,
                        )
                    }
                }

                // Состава у читателя канала нет, и это свойство, а не пропуск
                // (§3.2): читатели друг друга не знают и карточками
                // не обмениваются. Показывать ему список из себя одного —
                // значит выдавать это за неполноту.
                val showMembers = group.channel == null || group.mine

                if (showMembers) {
                    item {
                        Text(
                            text = stringResource(R.string.members),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }

                items(if (showMembers) group.members else emptyList()) { member ->
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
                        if (group.joined && group.channel == null) {
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

    if (showChannelLink && group != null) {
        chat.ratatosk.android.ui.components.ChannelLinkDialog(
            viewModel = viewModel,
            chatId = chatId,
            open = group.channel?.open,
            onDismiss = { showChannelLink = false },
        )
    }
}

/**
 * Канальная часть карточки: порода, заявки и впущенные.
 *
 * Порода — только из подписанного представления: пока его нет, сказано
 * «неизвестна», а не обещание ссылки (§10.2). Заявки и впущенных держит
 * ядро **у владельца**; у читателя их нет, и показывать ему нечего.
 */
@Composable
private fun ChannelSection(
    viewModel: RatatoskViewModel,
    chatId: ByteArray,
    channel: org.ratatosk.core.FfiChannel,
    isOwner: Boolean,
) {
    val requests by viewModel.channelRequests.collectAsState()
    val admits by viewModel.channelAdmits.collectAsState()
    val grants by viewModel.channelGrants.collectAsState()
    val seeding by viewModel.seedingMode.collectAsState()
    val seeds by viewModel.channelSeeds.collectAsState()
    val sharing by viewModel.sharingLevel.collectAsState()
    val hex = remember(chatId) { chatId.toHexString() }

    // Кому правим права; `null` — окно закрыто.
    var editingRight by remember { mutableStateOf<Pair<ByteArray, String>?>(null) }
    var showRotate by remember { mutableStateOf(false) }
    var showPow by remember { mutableStateOf(false) }
    // Подтверждения: объявить себя сидом и сузить круг отдачи. Оба —
    // с текстом ядра, и оба до действия, а не после.
    var confirmAnnounce by remember { mutableStateOf(false) }
    var confirmNarrow by remember { mutableStateOf<org.ratatosk.core.FfiSharingLevel?>(null) }

    editingRight?.let { (who, name) ->
        chat.ratatosk.android.ui.components.ChannelRightDialog(
            viewModel = viewModel,
            chatId = chatId,
            who = who,
            name = name,
            current = grants[hex]?.firstOrNull { it.who.contentEquals(who) }?.rights,
            onDismiss = { editingRight = null },
        )
    }

    if (showRotate) {
        chat.ratatosk.android.ui.components.ChannelRotateDialog(
            notice = viewModel.channelNotice(chat.ratatosk.android.ui.model.ChannelNotice.KEY_ROTATION),
            onConfirm = { viewModel.rotateChannelKey(chatId) },
            onDismiss = { showRotate = false },
        )
    }

    if (confirmAnnounce) {
        chat.ratatosk.android.ui.components.ChannelNoticeDialog(
            title = stringResource(R.string.channel_seeding_announced),
            notice = viewModel.channelNotice(chat.ratatosk.android.ui.model.ChannelNotice.SEEDING),
            confirmLabel = stringResource(R.string.channel_seeding_announced),
            onConfirm = { viewModel.setSeeding(chatId, org.ratatosk.core.FfiSeeding.ANNOUNCED) },
            onDismiss = { confirmAnnounce = false },
        )
    }

    confirmNarrow?.let { level ->
        chat.ratatosk.android.ui.components.ChannelNoticeDialog(
            title = stringResource(R.string.channel_sharing_level),
            notice = viewModel.sharingLevelNotice(),
            confirmLabel = stringResource(R.string.save),
            onConfirm = { viewModel.setSharingLevel(chatId, level) },
            onDismiss = { confirmNarrow = null },
        )
    }

    if (showPow) {
        chat.ratatosk.android.ui.components.ChannelPowDialog(
            current = channel.powBits,
            onConfirm = { viewModel.setChannelPow(chatId, it) },
            onDismiss = { showPow = false },
        )
    }

    LaunchedEffect(hex, isOwner) {
        viewModel.refreshChannelSeeding(chatId)
        if (isOwner) viewModel.refreshChannelPeople(chatId)
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.channel_kind_label),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(
                when (channel.open) {
                    true -> R.string.channel_kind_open_short
                    false -> R.string.channel_kind_private_short
                    null -> R.string.channel_kind_unknown
                }
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Раздача — дело каждого читателя: канал держится на том, что
        // читатели раздают друг другу (§7.5).
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.channel_seeding),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        val mode = seeding[hex] ?: org.ratatosk.core.FfiSeeding.QUIET
        SeedingOption(
            selected = mode == org.ratatosk.core.FfiSeeding.QUIET,
            title = stringResource(R.string.channel_seeding_quiet),
            desc = stringResource(R.string.channel_seeding_quiet_desc),
            onClick = { viewModel.setSeeding(chatId, org.ratatosk.core.FfiSeeding.QUIET) },
        )
        SeedingOption(
            selected = mode == org.ratatosk.core.FfiSeeding.ANNOUNCED,
            title = stringResource(R.string.channel_seeding_announced),
            desc = stringResource(R.string.channel_seeding_announced_desc),
            // Объявление раскрывает адрес — спрашиваем до, а не после.
            onClick = { if (mode != org.ratatosk.core.FfiSeeding.ANNOUNCED) confirmAnnounce = true },
        )
        SeedingOption(
            selected = mode == org.ratatosk.core.FfiSeeding.OFF,
            title = stringResource(R.string.channel_seeding_off),
            desc = stringResource(R.string.channel_seeding_off_desc),
            onClick = { viewModel.setSeeding(chatId, org.ratatosk.core.FfiSeeding.OFF) },
        )

        if (mode != org.ratatosk.core.FfiSeeding.OFF) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.channel_sharing_level),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            val level = sharing[hex] ?: org.ratatosk.core.FfiSharingLevel.EVERYONE
            SeedingOption(
                selected = level == org.ratatosk.core.FfiSharingLevel.EVERYONE,
                title = stringResource(R.string.channel_sharing_everyone),
                desc = null,
                onClick = { viewModel.setSharingLevel(chatId, org.ratatosk.core.FfiSharingLevel.EVERYONE) },
            )
            SeedingOption(
                selected = level == org.ratatosk.core.FfiSharingLevel.CONTACTS,
                title = stringResource(R.string.channel_sharing_contacts),
                desc = null,
                // Сужение платится не только настраивающим (§12).
                onClick = {
                    if (level != org.ratatosk.core.FfiSharingLevel.CONTACTS) {
                        confirmNarrow = org.ratatosk.core.FfiSharingLevel.CONTACTS
                    }
                },
            )
            SeedingOption(
                selected = level == org.ratatosk.core.FfiSharingLevel.VERIFIED,
                title = stringResource(R.string.channel_sharing_verified),
                desc = null,
                onClick = {
                    if (level != org.ratatosk.core.FfiSharingLevel.VERIFIED) {
                        confirmNarrow = org.ratatosk.core.FfiSharingLevel.VERIFIED
                    }
                },
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.channel_seeds),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        val others = seeds[hex].orEmpty()
        if (others.isEmpty()) {
            Text(
                text = stringResource(R.string.channel_seeds_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        } else {
            others.forEach { seed ->
                Text(
                    text = seed.who.toHexString().take(16) + " — " +
                        stringResource(R.string.channel_seed_until, seed.validUntilMs.toLong().formatDateTime()) +
                        if (!seed.verified) ", " + stringResource(R.string.channel_seed_unverified) else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // Срок своего права виден заранее (§6.3): отказ по сроку не должен
        // наступать внезапно, и это единственный способ его предупредить.
        if (!isOwner && channel.rightsUntilMs > 0UL) {
            Text(
                text = stringResource(
                    R.string.channel_my_right_until,
                    channel.rightsUntilMs.toLong().formatDateTime(),
                ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        // «От владельца ничего не приходило» — про наш приём, а не про то,
        // где владелец: каталога пиров в ядре нет, и утверждать о нём нечего.
        if (channel.ownerUnseen) {
            Text(
                text = stringResource(R.string.channel_owner_quiet),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (!isOwner) return@Column

        // Ключ поворачивается сам раз в месяц (§6.4); кнопка — «повернуть
        // сейчас», то есть исключить читателя. Показывается по may_rotate:
        // там уже учтены порода, право и нижний предел в неделю.
        if (channel.mayRotate) {
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(onClick = { showRotate = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.channel_rotate))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.channel_pow_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (channel.powBits > 0u) {
                    stringResource(R.string.channel_pow_current, channel.powBits.toInt())
                } else {
                    stringResource(R.string.channel_pow_free)
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { showPow = true }) { Text(stringResource(R.string.edit)) }
        }

        // Выдачи: что кому выдано и до какого числа.
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.channel_grants),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        if (channel.grantsExpiring > 0u) {
            Text(
                text = stringResource(R.string.channel_grants_expiring, channel.grantsExpiring.toInt()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        val given = grants[hex].orEmpty()
        if (given.isEmpty()) {
            Text(
                text = stringResource(R.string.channel_grants_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            given.forEach { grant ->
                ListItem(
                    headlineContent = { Text(grant.name) },
                    supportingContent = {
                        Text(
                            rightsSummary(grant.rights) + ", " + if (grant.live) {
                                stringResource(R.string.channel_right_until, grant.untilMs.toLong().formatDateTime())
                            } else {
                                stringResource(R.string.channel_right_expired)
                            }
                        )
                    },
                    leadingContent = { Avatar(avatarBytes = null, name = grant.name) },
                    trailingContent = {
                        TextButton(onClick = { editingRight = grant.who to grant.name }) {
                            Text(stringResource(R.string.channel_grant_edit))
                        }
                    },
                )
            }
        }

        // Впуск — дело владельца канала по приглашению. В открытом впускать
        // некого: ключ чтения и так лежит в ссылке (§10.4).
        if (channel.open == true) return@Column

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.channel_requests),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.channel_no_refusal),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )

        val waiting = requests[hex].orEmpty()
        if (waiting.isEmpty()) {
            Text(
                text = stringResource(R.string.channel_requests_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            waiting.forEach { request ->
                ListItem(
                    headlineContent = { Text(request.name) },
                    supportingContent = { Text(request.who.toHexString().take(16)) },
                    leadingContent = { Avatar(avatarBytes = null, name = request.name) },
                    trailingContent = {
                        // Одна кнопка, а не пара: отказа как ответа §10.4
                        // не знает, и вторая обещала бы просящему ответ,
                        // которого он не получит.
                        Button(onClick = { viewModel.admitToChannel(chatId, request.who) }) {
                            Text(stringResource(R.string.channel_admit))
                        }
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.channel_admitted),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        val letIn = admits[hex].orEmpty()
        if (letIn.isEmpty()) {
            Text(
                text = stringResource(R.string.channel_admitted_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            letIn.forEach { admit ->
                ListItem(
                    headlineContent = { Text(admit.name) },
                    supportingContent = {
                        Text(stringResource(R.string.channel_admitted_by, admit.admittedByName))
                    },
                    leadingContent = { Avatar(avatarBytes = null, name = admit.name) },
                    trailingContent = {
                        TextButton(onClick = { editingRight = admit.who to admit.name }) {
                            Text(stringResource(R.string.channel_grant_edit))
                        }
                    },
                )
            }
        }
    }
}

/** Права одной строкой: что именно выдано. */
@Composable
private fun rightsSummary(rights: org.ratatosk.core.FfiChannelRights): String {
    val parts = buildList {
        if (rights.write) add(stringResource(R.string.channel_right_write))
        if (rights.admit) add(stringResource(R.string.channel_right_admit))
        if (rights.evict) add(stringResource(R.string.channel_right_evict))
        if (rights.edit) add(stringResource(R.string.channel_right_edit))
    }
    return parts.joinToString(", ").ifEmpty { stringResource(R.string.channel_right_none) }
}

/** Строка выбора: кружок, название и, если есть, что это значит. */
@Composable
private fun SeedingOption(
    selected: Boolean,
    title: String,
    desc: String?,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(top = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (desc != null) {
                Text(
                    text = desc,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}
