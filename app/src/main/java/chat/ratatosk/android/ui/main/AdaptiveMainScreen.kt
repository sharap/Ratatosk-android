package chat.ratatosk.android.ui.main

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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    val activeChatId by viewModel.activeChatIdFlow.collectAsState()
    val activeContactId by viewModel.activeContactIdFlow.collectAsState()
    val contacts by viewModel.contacts.collectAsState()
    val contactAvatars by viewModel.contactAvatars.collectAsState()

    val selectedAccount by viewModel.selectedAccount.collectAsState()
    val totalUnreadCount by viewModel.totalUnreadCount.collectAsState()
    var showAddContactDialog by remember { mutableStateOf(false) }

    val pagerState = rememberPagerState(pageCount = { MainTab.entries.size })
    val scope = rememberCoroutineScope()
    val currentTab by remember { derivedStateOf { MainTab.entries[pagerState.settledPage] } }

    val isDetailOpen by remember {
        derivedStateOf {
            when (currentTab) {
                MainTab.CHATS -> activeChatId != null
                MainTab.CONTACTS -> activeContactId != null
                else -> false
            }
        }
    }

    // Sync navigator with ViewModel state (for external events like notifications)
    LaunchedEffect(activeChatId, activeContactId) {
        if (activeChatId != null) {
            val key = "chat_${activeChatId!!.toHexString()}"
            if (navigator.currentDestination?.contentKey != key) {
                navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, key)
            }
        } else if (activeContactId != null) {
            val key = "contact_${activeContactId!!.toHexString()}"
            if (navigator.currentDestination?.contentKey != key) {
                navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, key)
            }
        }
    }

    // Sync ViewModel with navigator state (for system back button/gestures)
    LaunchedEffect(navigator.currentDestination) {
        if (navigator.currentDestination?.pane == ListDetailPaneScaffoldRole.List) {
            if (activeChatId != null) viewModel.setActiveChat(null)
            if (activeContactId != null) viewModel.setActiveContact(null)
        }
    }

    val chatGridState = rememberLazyGridState()
    val contactsGridState = rememberLazyGridState()

    val adaptiveInfo = currentWindowAdaptiveInfo()
    val isCompact = adaptiveInfo.windowSizeClass.windowWidthSizeClass == WindowWidthSizeClass.COMPACT

    val showBackButton = navigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Hidden

    if (isCompact) {
        // Mobile Layout
        Scaffold(
            bottomBar = {
                if (!isDetailOpen) {
                    NavigationBar {
                        MainTab.entries.forEach { tab ->
                            NavigationBarItem(
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
                                label = { Text(stringResource(tab.labelRes)) },
                                selected = currentTab == tab,
                                onClick = { 
                                    scope.launch { pagerState.animateScrollToPage(tab.ordinal) }
                                }
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.padding(innerPadding).fillMaxSize()) {
                    MainTabContent(
                        pagerState = pagerState,
                        viewModel = viewModel,
                        onScanClick = onScanClick,
                        onCropAvatar = onCropAvatar,
                        onPairedDevicesClick = onPairedDevicesClick,
                        onChatClick = { chatId ->
                            viewModel.setActiveChat(chatId)
                            val key = "chat_${chatId.toHexString()}"
                            scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, key) }
                        },
                        onContactClick = { contactId ->
                            viewModel.setActiveContact(contactId)
                            val key = "contact_${contactId.toHexString()}"
                            scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, key) }
                        },
                        isCompact = true,
                        chatGridState = chatGridState,
                        contactsGridState = contactsGridState,
                        showFab = true
                    )
                }

                if (isDetailOpen) {
                    DetailPaneContent(
                        navigator = navigator,
                        activeChatId = activeChatId,
                        activeContactId = activeContactId,
                        viewModel = viewModel,
                        showBackButton = true,
                        isCompact = true
                    )
                }
            }
        }
    } else {
        // Tablet/Desktop Layout
        Row(Modifier.fillMaxSize()) {
            UnifiedNavigationRail(
                selectedAccount = selectedAccount,
                contacts = contacts,
                contactAvatars = contactAvatars,
                currentTab = currentTab,
                totalUnreadCount = totalUnreadCount,
                hasChats = contacts.isNotEmpty(),
                onAccountSelect = { account ->
                    viewModel.selectAccount(account)
                    viewModel.setActiveChat(null)
                    viewModel.setActiveContact(null)
                    scope.launch { 
                        navigator.navigateBack()
                        pagerState.scrollToPage(MainTab.PROFILE.ordinal) 
                    }
                },
                onAddContactClick = { showAddContactDialog = true },
                onChatSelect = { chatId ->
                    viewModel.setActiveChat(chatId)
                    val key = "chat_${chatId.toHexString()}"
                    scope.launch { 
                        if (pagerState.currentPage != MainTab.CHATS.ordinal) {
                            pagerState.scrollToPage(MainTab.CHATS.ordinal)
                        }
                        navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, key)
                    }
                },
                onTabSelect = { tab ->
                    scope.launch { 
                        if (tab != MainTab.CHATS && tab != MainTab.CONTACTS) {
                            viewModel.setActiveChat(null)
                            viewModel.setActiveContact(null)
                            navigator.navigateBack()
                        }
                        pagerState.animateScrollToPage(tab.ordinal) 
                    }
                }
            )

            if (!isDetailOpen || (currentTab == MainTab.SETTINGS || currentTab == MainTab.PROFILE)) {
                Box(Modifier.weight(1f)) {
                    MainTabContent(
                        pagerState = pagerState,
                        viewModel = viewModel,
                        onScanClick = onScanClick,
                        onCropAvatar = onCropAvatar,
                        onPairedDevicesClick = onPairedDevicesClick,
                        onChatClick = { chatId ->
                            viewModel.setActiveChat(chatId)
                            val key = "chat_${chatId.toHexString()}"
                            scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, key) }
                        },
                        onContactClick = { contactId ->
                            viewModel.setActiveContact(contactId)
                            val key = "contact_${contactId.toHexString()}"
                            scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, key) }
                        },
                        isCompact = false,
                        isTwoColumn = true,
                        chatGridState = chatGridState,
                        contactsGridState = contactsGridState,
                        showFab = false
                    )
                }
            } else {
                NavigableListDetailPaneScaffold(
                    navigator = navigator,
                    listPane = {
                        AnimatedPane {
                            MainTabContent(
                                pagerState = pagerState,
                                viewModel = viewModel,
                                onScanClick = onScanClick,
                                onCropAvatar = onCropAvatar,
                                onPairedDevicesClick = onPairedDevicesClick,
                                onChatClick = { chatId ->
                                    viewModel.setActiveChat(chatId)
                                    val key = "chat_${chatId.toHexString()}"
                                    scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, key) }
                                },
                                onContactClick = { contactId ->
                                    viewModel.setActiveContact(contactId)
                                    val key = "contact_${contactId.toHexString()}"
                                    scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, key) }
                                },
                                isCompact = false,
                                chatGridState = chatGridState,
                                contactsGridState = contactsGridState,
                                showFab = false
                            )
                        }
                    },
                    detailPane = {
                        AnimatedPane {
                            DetailPaneContent(
                                navigator = navigator,
                                activeChatId = activeChatId,
                                activeContactId = activeContactId,
                                viewModel = viewModel,
                                showBackButton = showBackButton,
                                isCompact = false
                            )
                        }
                    }
                )
            }
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
    pagerState: androidx.compose.foundation.pager.PagerState,
    viewModel: RatatoskViewModel,
    onScanClick: () -> Unit,
    onCropAvatar: () -> Unit,
    onPairedDevicesClick: () -> Unit,
    onChatClick: (ByteArray) -> Unit,
    onContactClick: (ByteArray) -> Unit,
    isCompact: Boolean,
    isTwoColumn: Boolean = false,
    chatGridState: androidx.compose.foundation.lazy.grid.LazyGridState = rememberLazyGridState(),
    contactsGridState: androidx.compose.foundation.lazy.grid.LazyGridState = rememberLazyGridState(),
    showFab: Boolean = true
) {
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        userScrollEnabled = isCompact
    ) { page ->
        when (MainTab.entries[page]) {
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
    activeChatId: ByteArray?,
    activeContactId: ByteArray?,
    viewModel: RatatoskViewModel,
    showBackButton: Boolean,
    isCompact: Boolean
) {
    val scope = rememberCoroutineScope()
    val contentKey = navigator.currentDestination?.contentKey
    if (contentKey != null) {
        if (contentKey.startsWith("chat_")) {
            val effectiveChatId = activeChatId ?: remember(contentKey) {
                try { contentKey.removePrefix("chat_").hexToByteArray() } catch (e: Exception) { null }
            }
            if (effectiveChatId != null) {
                ChatScreen(
                    viewModel = viewModel,
                    chatId = effectiveChatId,
                    onBack = {
                        viewModel.setActiveChat(null)
                        scope.launch { navigator.navigateBack() }
                    },
                    onHeaderClick = { viewModel.setActiveContact(effectiveChatId) },
                    showBackButton = showBackButton,
                    isCompact = isCompact
                )
            }
        } else if (contentKey.startsWith("contact_")) {
            val effectiveContactId = activeContactId ?: remember(contentKey) {
                try { contentKey.removePrefix("contact_").hexToByteArray() } catch (e: Exception) { null }
            }
            if (effectiveContactId != null) {
                ContactDetailsScreen(
                    viewModel = viewModel,
                    chatId = effectiveContactId,
                    onBack = {
                        viewModel.setActiveContact(null)
                        scope.launch { navigator.navigateBack() }
                    },
                    onChatClick = {
                        viewModel.setActiveContact(null)
                        viewModel.setActiveChat(it)
                    },
                    showBackButton = showBackButton,
                    isCompact = isCompact
                )
            }
        }
    } else {
        // Empty state
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.choose_chat))
        }
    }
}

@Composable
fun UnifiedNavigationRail(
    selectedAccount: FfiAccount?,
    contacts: List<org.ratatosk.core.FfiContact>,
    contactAvatars: Map<String, ByteArray>,
    currentTab: MainTab,
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

                IconButton(onClick = onAddContactClick) {
                    Icon(Icons.Default.Add, contentDescription = "Add Contact")
                }
            }
        }
    ) {
        Spacer(Modifier.weight(1f))
        MainTab.entries.filter { tab ->
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
