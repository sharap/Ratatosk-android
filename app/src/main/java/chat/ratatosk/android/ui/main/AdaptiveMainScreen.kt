package chat.ratatosk.android.ui.main

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.*
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.ratatosk.android.R
import chat.ratatosk.android.ui.RatatoskViewModel
import chat.ratatosk.android.ui.chat.ChatScreen
import chat.ratatosk.android.ui.chatlist.ChatListScreen
import chat.ratatosk.android.ui.components.Avatar
import chat.ratatosk.android.ui.contacts.ContactDetailsScreen
import chat.ratatosk.android.ui.contacts.ContactsScreen
import chat.ratatosk.android.ui.profile.ProfileScreen
import chat.ratatosk.android.ui.settings.SettingsScreen
import chat.ratatosk.android.util.hexToByteArray
import chat.ratatosk.android.util.toHexString
import org.ratatosk.core.FfiAccount
import kotlinx.coroutines.launch
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import chat.ratatosk.android.ui.components.AddContactDialog
import androidx.window.core.layout.WindowWidthSizeClass

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun AdaptiveMainScreen(
    viewModel: RatatoskViewModel,
    onScanClick: () -> Unit,
    onCropAvatar: () -> Unit,
    onPairedDevicesClick: () -> Unit,
) {
    val navigator = rememberListDetailPaneScaffoldNavigator<String>()
    val focusManager = LocalFocusManager.current
    val activeChatId by viewModel.activeChatIdFlow.collectAsState()
    val activeContactId by viewModel.activeContactIdFlow.collectAsState()
    val contacts by viewModel.contacts.collectAsState()
    val contactAvatars by viewModel.contactAvatars.collectAsState()

    val selectedAccount by viewModel.selectedAccount.collectAsState()
    val totalUnreadCount by viewModel.totalUnreadCount.collectAsState()
    val isCompanionMode by viewModel.isCompanionMode.collectAsState()
    var showAddContactDialog by remember { mutableStateOf(false) }

    val visibleTabs = remember(isCompanionMode) {
        if (isCompanionMode) listOf(MainTab.CHATS, MainTab.SETTINGS, MainTab.PROFILE)
        else MainTab.entries
    }

    var selectedTab by remember { mutableStateOf(MainTab.CHATS) }
    
    LaunchedEffect(isCompanionMode) {
        if (isCompanionMode && selectedTab == MainTab.CONTACTS) {
            selectedTab = MainTab.CHATS
        }
    }

    val scope = rememberCoroutineScope()
    val adaptiveInfo = currentWindowAdaptiveInfo()
    val isCompact = adaptiveInfo.windowSizeClass.windowWidthSizeClass == WindowWidthSizeClass.COMPACT

    // Selection logic: UI only
    LaunchedEffect(activeChatId) {
        if (activeChatId != null) {
            val key = "chat_${activeChatId!!.toHexString()}"
            if (navigator.currentDestination?.contentKey != key) {
                navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, key)
            }
        }
    }

    LaunchedEffect(activeContactId) {
        if (activeContactId != null) {
            val key = "contact_${activeContactId!!.toHexString()}"
            if (navigator.currentDestination?.contentKey != key) {
                // Always navigate to Detail pane for simplicity and reliability
                navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, key)
            }
        }
    }

    // Sync back from Navigator to ViewModel to handle "Back" actions
    LaunchedEffect(navigator.currentDestination) {
        focusManager.clearFocus()
        val currentKey = navigator.currentDestination?.contentKey
        val currentPane = navigator.currentDestination?.pane
        
        if (currentKey == null) {
            viewModel.setActiveChat(null)
            viewModel.setActiveContact(null)
            return@LaunchedEffect
        }

        if (currentKey.startsWith("chat_")) {
            val id = try { currentKey.drop(5).hexToByteArray() } catch (e: Exception) { null }
            if (id != null) {
                viewModel.setActiveChat(id)
                // If we are in compact mode and navigated to a chat, clear contact
                if (isCompact) viewModel.setActiveContact(null)
            }
        } else if (currentKey.startsWith("contact_")) {
            val id = try { currentKey.drop(8).hexToByteArray() } catch (e: Exception) { null }
            if (id != null) {
                viewModel.setActiveContact(id)
                // If we are in compact mode and navigated to a contact, clear chat
                if (isCompact) viewModel.setActiveChat(null)
            }
        }
    }

    val chatGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val contactsGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    
    val isListVisible = navigator.scaffoldValue[ListDetailPaneScaffoldRole.List] != PaneAdaptedValue.Hidden
    val isDetailVisible = navigator.scaffoldValue[ListDetailPaneScaffoldRole.Detail] != PaneAdaptedValue.Hidden
    
    val showBottomBar = isCompact && isListVisible && !isDetailVisible
    val showBackButton = !isListVisible

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    visibleTabs.forEach { tab ->
                        NavigationBarItem(
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (tab == MainTab.CHATS && totalUnreadCount > 0) {
                                            Badge { Text(totalUnreadCount.toString()) }
                                        }
                                    }
                                ) { Icon(tab.icon, contentDescription = null) }
                            },
                            label = { Text(stringResource(tab.labelRes)) },
                            selected = selectedTab == tab,
                            onClick = { 
                                selectedTab = tab
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Row(Modifier.fillMaxSize()) {
            if (!isCompact) {
                UnifiedNavigationRail(
                    selectedAccount = selectedAccount,
                    contacts = contacts,
                    contactAvatars = contactAvatars,
                    currentTab = selectedTab,
                    visibleTabs = visibleTabs,
                    totalUnreadCount = totalUnreadCount,
                    hasChats = contacts.isNotEmpty(),
                    onAccountSelect = { account ->
                        viewModel.selectAccount(account)
                        viewModel.setActiveChat(null)
                        viewModel.setActiveContact(null)
                        selectedTab = MainTab.PROFILE
                        scope.launch { 
                            navigator.navigateBack()
                        }
                    },
                    onAddContactClick = { showAddContactDialog = true },
                    onChatSelect = { chatId ->
                        viewModel.setActiveChat(chatId)
                    },
                    onTabSelect = { tab ->
                        selectedTab = tab
                        if (tab != MainTab.CHATS && tab != MainTab.CONTACTS) {
                            viewModel.setActiveChat(null)
                            viewModel.setActiveContact(null)
                            scope.launch { 
                                navigator.navigateBack()
                            }
                        }
                    }
                )
            }
            
            NavigableListDetailPaneScaffold(
                modifier = Modifier.padding(innerPadding).weight(1f),
                navigator = navigator,
                listPane = {
                    AnimatedPane {
                        key(selectedTab) {
                            MainTabContent(
                                currentTab = selectedTab,
                                viewModel = viewModel,
                                onScanClick = onScanClick,
                                onCropAvatar = onCropAvatar,
                                onPairedDevicesClick = onPairedDevicesClick,
                                onChatClick = { chatId -> viewModel.setActiveChat(chatId) },
                                onContactClick = { contactId -> 
                                    viewModel.setActiveContact(contactId)
                                },
                                chatGridState = chatGridState,
                                contactsGridState = contactsGridState,
                                showFab = !isCompanionMode
                            )
                        }
                    }
                },
                detailPane = {
                    AnimatedPane {
                        DetailPaneContent(
                            navigator = navigator,
                            viewModel = viewModel,
                            isCompanionMode = isCompanionMode,
                            showBackButton = showBackButton,
                            isCompact = isCompact,
                            activeChatId = activeChatId,
                            activeContactId = activeContactId
                        )
                    }
                },
                extraPane = {
                    AnimatedPane {
                        ExtraPaneContent(
                            navigator = navigator,
                            viewModel = viewModel,
                            isCompact = isCompact,
                            activeContactId = activeContactId
                        )
                    }
                }
            )
        }
    }

    if (showAddContactDialog) {
        AddContactDialog(
            onDismiss = { showAddContactDialog = false },
            onAdd = { uri, inPerson ->
                viewModel.addContact(uri, inPerson)
                showAddContactDialog = false
            },
            onScan = {
                showAddContactDialog = false
                onScanClick()
            }
        )
    }
}

@Composable
fun MainTabContent(
    currentTab: MainTab,
    viewModel: RatatoskViewModel,
    onScanClick: () -> Unit,
    onCropAvatar: () -> Unit,
    onPairedDevicesClick: () -> Unit,
    onChatClick: (ByteArray) -> Unit,
    onContactClick: (ByteArray) -> Unit,
    isTwoColumn: Boolean = false,
    chatGridState: androidx.compose.foundation.lazy.grid.LazyGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(),
    contactsGridState: androidx.compose.foundation.lazy.grid.LazyGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(),
    showFab: Boolean = true
) {
    androidx.compose.animation.Crossfade(targetState = currentTab, label = "tab_fade") { tab ->
        when (tab) {
            MainTab.CHATS -> ChatListScreen(
                viewModel = viewModel,
                onChatClick = onChatClick,
                onScanClick = onScanClick,
                isTwoColumn = isTwoColumn,
                gridState = chatGridState,
                showFab = showFab
            )
            MainTab.CONTACTS -> ContactsScreen(
                viewModel = viewModel,
                onContactClick = onContactClick,
                onScanClick = onScanClick,
                isTwoColumn = isTwoColumn,
                gridState = contactsGridState,
                showFab = showFab
            )
            MainTab.SETTINGS -> SettingsScreen(
                viewModel = viewModel,
                onPairedDevicesClick = onPairedDevicesClick,
                isTwoColumn = isTwoColumn
            )
            MainTab.PROFILE -> ProfileScreen(
                viewModel = viewModel,
                onCropAvatar = onCropAvatar,
                isTwoColumn = isTwoColumn
            )
        }
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun DetailPaneContent(
    navigator: androidx.compose.material3.adaptive.navigation.ThreePaneScaffoldNavigator<String>,
    viewModel: RatatoskViewModel,
    isCompanionMode: Boolean,
    showBackButton: Boolean,
    isCompact: Boolean,
    activeChatId: ByteArray?,
    activeContactId: ByteArray?
) {
    val scope = rememberCoroutineScope()
    val currentDestination = navigator.currentDestination
    val currentPane = currentDestination?.pane
    val currentKey = currentDestination?.contentKey
    val groups by viewModel.groups.collectAsState()

    when {
        activeChatId != null -> {
            ChatScreen(
                viewModel = viewModel,
                chatId = activeChatId,
                onBack = { scope.launch { navigator.navigateBack() } },
                onHeaderClick = { 
                    if (!isCompanionMode) {
                        viewModel.setActiveContact(activeChatId)
                    }
                },
                showBackButton = showBackButton,
                isCompact = isCompact
            )
        }
        activeContactId != null && (currentKey?.startsWith("contact_") == true && currentPane == ListDetailPaneScaffoldRole.Detail) -> {
            val isGroup = groups.any { it.chatId.contentEquals(activeContactId) }
            if (isGroup) {
                chat.ratatosk.android.ui.groups.GroupDetailsScreen(
                    viewModel = viewModel,
                    chatId = activeContactId,
                    onBack = { scope.launch { navigator.navigateBack() } },
                    onChatClick = { chatId ->
                        viewModel.setActiveChat(chatId)
                    },
                    showBackButton = showBackButton,
                    isCompact = isCompact
                )
            } else {
                ContactDetailsScreen(
                    viewModel = viewModel,
                    chatId = activeContactId,
                    onBack = { scope.launch { navigator.navigateBack() } },
                    onChatClick = { chatId ->
                        viewModel.setActiveChat(chatId)
                    },
                    showBackButton = showBackButton,
                    isCompact = isCompact
                )
            }
        }
        else -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.choose_chat))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun ExtraPaneContent(
    navigator: androidx.compose.material3.adaptive.navigation.ThreePaneScaffoldNavigator<String>,
    viewModel: RatatoskViewModel,
    isCompact: Boolean,
    activeContactId: ByteArray?
) {
    val scope = rememberCoroutineScope()
    val currentDestination = navigator.currentDestination
    val currentPane = currentDestination?.pane
    val currentKey = currentDestination?.contentKey
    val groups by viewModel.groups.collectAsState()

    if (activeContactId != null && currentKey?.startsWith("contact_") == true && currentPane == ListDetailPaneScaffoldRole.Extra) {
        val isGroup = groups.any { it.chatId.contentEquals(activeContactId) }
        if (isGroup) {
            chat.ratatosk.android.ui.groups.GroupDetailsScreen(
                viewModel = viewModel,
                chatId = activeContactId,
                onBack = {
                    scope.launch { navigator.navigateBack() }
                },
                onChatClick = { chatId ->
                    viewModel.setActiveChat(chatId)
                },
                showBackButton = true,
                isCompact = isCompact
            )
        } else {
            ContactDetailsScreen(
                viewModel = viewModel,
                chatId = activeContactId,
                onBack = {
                    scope.launch { navigator.navigateBack() }
                },
                onChatClick = { chatId ->
                    viewModel.setActiveChat(chatId)
                },
                showBackButton = true,
                isCompact = isCompact
            )
        }
    }
}

@Composable
fun UnifiedNavigationRail(
    selectedAccount: FfiAccount?,
    contacts: List<org.ratatosk.core.FfiContact>,
    contactAvatars: Map<String, ByteArray>,
    currentTab: MainTab,
    visibleTabs: List<MainTab>,
    totalUnreadCount: Int,
    hasChats: Boolean,
    onAccountSelect: (FfiAccount) -> Unit,
    onAddContactClick: () -> Unit,
    onChatSelect: (ByteArray) -> Unit,
    onTabSelect: (MainTab) -> Unit
) {
    NavigationRail(
        modifier = Modifier.width(72.dp),
        header = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(vertical = 12.dp)
            ) {
                // User Avatar (Profile)
                selectedAccount?.let { account ->
                    AccountAvatar(
                        account = account,
                        isSelected = currentTab == MainTab.PROFILE,
                        onClick = { onAccountSelect(account) }
                    )
                }
                
                // Recent Chats
                contacts.take(5).forEach { contact ->
                    val ikHex = contact.peerIk.toHexString()
                    val avatarBytes = contactAvatars[ikHex]
                    
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable { onChatSelect(contact.chatId) },
                        contentAlignment = Alignment.Center
                    ) {
                        Avatar(
                            avatarBytes = avatarBytes,
                            name = contact.localName ?: contact.displayName,
                            size = 40.dp
                        )
                    }
                }

                if (visibleTabs.contains(MainTab.CONTACTS)) {
                    IconButton(onClick = onAddContactClick) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_contact))
                    }
                }
            }
        }
    ) {
        Spacer(Modifier.weight(1f))
        visibleTabs.filter { tab ->
            when (tab) {
                MainTab.CHATS -> false // Recent chats in header
                MainTab.PROFILE -> false // Accessible via avatar
                else -> true
            }
        }.forEach { tab ->
            NavigationRailItem(
                selected = currentTab == tab,
                onClick = { onTabSelect(tab) },
                icon = {
                    BadgedBox(
                        badge = {
                            if (tab == MainTab.CHATS && totalUnreadCount > 0) {
                                Badge {
                                    Text(totalUnreadCount.toString())
                                }
                            }
                        }
                    ) {
                        Icon(tab.icon, contentDescription = null)
                    }
                },
                label = { Text(stringResource(tab.labelRes)) }
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun AccountAvatar(
    account: FfiAccount,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Avatar(
            avatarBytes = null,
            name = account.label,
            size = 40.dp
        )
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
    }
}
