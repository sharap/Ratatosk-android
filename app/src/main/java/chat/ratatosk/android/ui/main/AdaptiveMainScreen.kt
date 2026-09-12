package chat.ratatosk.android.ui.main

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.ratatosk.android.R
import chat.ratatosk.android.ui.RatatoskViewModel
import chat.ratatosk.android.ui.chat.ChatScreen
import chat.ratatosk.android.ui.chatlist.ChatItem
import chat.ratatosk.android.ui.chatlist.ChatListScreen
import chat.ratatosk.android.ui.components.AddContactDialog
import chat.ratatosk.android.ui.components.SwipeBackLayer
import chat.ratatosk.android.ui.components.rememberSwipeBackState
import chat.ratatosk.android.ui.components.swipeBackUnderlay
import chat.ratatosk.android.ui.components.Avatar
import chat.ratatosk.android.ui.contacts.ContactDetailsScreen
import chat.ratatosk.android.ui.contacts.ContactsScreen
import chat.ratatosk.android.ui.profile.ProfileScreen
import chat.ratatosk.android.ui.settings.SettingsScreen
import chat.ratatosk.android.util.hexToByteArray
import chat.ratatosk.android.util.toHexString
import kotlinx.coroutines.launch
import org.ratatosk.core.FfiAccount

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun AdaptiveMainScreen(
    viewModel: RatatoskViewModel,
    onScanClick: () -> Unit,
    onCropAvatar: () -> Unit,
    onPairedDevicesClick: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val adaptiveInfo = currentWindowAdaptiveInfo()
    val configuration = LocalConfiguration.current
    
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isWideScreen = adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(600)
    
    // Tablet/multi-pane UI enabled only in landscape screen orientation on wide screens
    val isTabletMode = isLandscape && isWideScreen
    val isCompact = !isTabletMode

    val scaffoldDirective = remember(adaptiveInfo, isTabletMode) {
        val baseDirective = androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective(adaptiveInfo)
        baseDirective.copy(
            maxHorizontalPartitions = if (isTabletMode) 2 else 1,
            horizontalPartitionSpacerSize = 0.dp
        )
    }

    val navigator = rememberListDetailPaneScaffoldNavigator<String>(
        scaffoldDirective = scaffoldDirective
    )

    val activeChatId by viewModel.activeChatIdFlow.collectAsState()
    val activeContactId by viewModel.activeContactIdFlow.collectAsState()

    val totalUnreadCount by viewModel.totalUnreadCount.collectAsState()
    val isCompanionMode by viewModel.isCompanionMode.collectAsState()
    var showAddContactDialog by remember { mutableStateOf(false) }
    var isPairedDevicesOpen by remember { mutableStateOf(false) }

    val contacts by viewModel.contacts.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val allMessages by viewModel.messages.collectAsState()
    val contactAvatars by viewModel.contactAvatars.collectAsState()
    val unreadCounts by viewModel.unreadCounts.collectAsState()
    val selectedAccount by viewModel.selectedAccount.collectAsState()
    val myAvatar by viewModel.myAvatar.collectAsState()

    val sortedChats = remember(contacts, groups, allMessages, activeChatId) {
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

    val visibleTabs = remember(isCompanionMode) {
        if (isCompanionMode) listOf(MainTab.CHATS, MainTab.SETTINGS, MainTab.PROFILE)
        else MainTab.entries
    }

    var selectedTab by rememberSaveable { mutableStateOf(MainTab.CHATS) }
    
    LaunchedEffect(isCompanionMode) {
        if (isCompanionMode && selectedTab == MainTab.CONTACTS) {
            selectedTab = MainTab.CHATS
        }
    }

    // Selection logic: UI only
    LaunchedEffect(activeChatId) {
        if (activeChatId != null) {
            isPairedDevicesOpen = false
            val key = "chat_${activeChatId!!.toHexString()}"
            if (navigator.currentDestination?.contentKey != key) {
                navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, key)
            }
        }
    }

    LaunchedEffect(activeContactId) {
        if (activeContactId != null) {
            isPairedDevicesOpen = false
            val key = "contact_${activeContactId!!.toHexString()}"
            if (navigator.currentDestination?.contentKey != key) {
                navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, key)
            }
        }
    }

    // Sync back from Navigator to ViewModel to handle "Back" actions
    LaunchedEffect(navigator.currentDestination) {
        focusManager.clearFocus()
        val currentKey = navigator.currentDestination?.contentKey
        
        if (currentKey == null) {
            if (viewModel.activeChatIdFlow.value != null) viewModel.setActiveChat(null)
            if (viewModel.activeContactIdFlow.value != null) viewModel.setActiveContact(null)
            return@LaunchedEffect
        }

        if (currentKey.startsWith("chat_")) {
            val id = try { currentKey.drop(5).hexToByteArray() } catch (e: Exception) { null }
            if (id != null && viewModel.activeChatIdFlow.value?.contentEquals(id) != true) {
                viewModel.setActiveChat(id)
            }
        } else if (currentKey.startsWith("contact_")) {
            val id = try { currentKey.drop(8).hexToByteArray() } catch (e: Exception) { null }
            if (id != null && viewModel.activeContactIdFlow.value?.contentEquals(id) != true) {
                viewModel.setActiveContact(id)
            }
        }
    }

    val chatGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val contactsGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val showBackButton = navigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Hidden

    val handlePairedDevicesClick = {
        isPairedDevicesOpen = true
    }
    
    // «Назад» обрабатывает SwipeBackHost ниже: он берёт на себя и кнопку,
    // и системный жест — через PredictiveBackHandler, потому что рисовать
    // уход экрана и отдельно слушать back двумя разными местами значит
    // однажды их рассогласовать. Отдельные BackHandler здесь стояли раньше.

    // В компактном режиме движение панелей рисует SwipeBackHost. Своя
    // растворялка панели накладывалась бы на него второй анимацией —
    // сразу после слайда экран проявлялся бы ещё раз, и это читается
    // как рывок в конце жеста. На планшете она к месту: там панели
    // сменяются без жеста.
    val paneEnter = if (isCompact) EnterTransition.None else fadeIn(animationSpec = tween(150))
    val paneExit = if (isCompact) ExitTransition.None else fadeOut(animationSpec = tween(150))

    // Содержимое списка вынесено отдельно: в компактном режиме его надо
    // показать **под** уходящей деталью, а внутри ListDetailPaneScaffold
    // неактивная панель не компонуется вовсе.
    val listPaneBody: @Composable () -> Unit = {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (!isTabletMode) {
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
                                    isPairedDevicesOpen = false
                                    selectedTab = tab
                                    viewModel.setActiveChat(null)
                                    viewModel.setActiveContact(null)
                                    scope.launch {
                                        while (navigator.canNavigateBack()) {
                                            navigator.navigateBack()
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                key(selectedTab) {
                    MainTabContent(
                        currentTab = selectedTab,
                        viewModel = viewModel,
                        onScanClick = onScanClick,
                        onCropAvatar = onCropAvatar,
                        onPairedDevicesClick = handlePairedDevicesClick,
                        onChatClick = { chatId -> viewModel.setActiveChat(chatId) },
                        onContactClick = { contactId -> 
                            viewModel.setActiveContact(contactId)
                        },
                        chatGridState = chatGridState,
                        contactsGridState = contactsGridState,
                        showFab = true
                    )
                }
            }
        }
    }

    // Панели как они есть. В компактном режиме это то, что едет за пальцем.
    val paneArea: @Composable () -> Unit = {
        if (isTabletMode && (selectedTab == MainTab.SETTINGS || selectedTab == MainTab.PROFILE)) {
            androidx.compose.animation.Crossfade(targetState = selectedTab, label = "full_pane_fade") { tab ->
                when (tab) {
                    MainTab.SETTINGS -> SettingsScreen(
                        viewModel = viewModel,
                        onPairedDevicesClick = handlePairedDevicesClick,
                        isTwoColumn = true
                    )
                    MainTab.PROFILE -> ProfileScreen(
                        viewModel = viewModel,
                        onCropAvatar = onCropAvatar,
                        isTwoColumn = true
                    )
                    else -> {}
            }
                }

        } else {
            ListDetailPaneScaffold(
                modifier = Modifier.fillMaxSize(),
                directive = navigator.scaffoldDirective,
                scaffoldState = navigator.scaffoldState,
                listPane = {
                    AnimatedPane(
                        modifier = if (isTabletMode) {
                            Modifier
                                .preferredWidth(340.dp)
                                .border(
                                    width = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                        } else Modifier,
                        boundsAnimationSpec = snap(),
                        enterTransition = paneEnter,
                        exitTransition = paneExit
                    ) {
                        listPaneBody()
                    }
                },
                detailPane = {
                    AnimatedPane(
                        modifier = Modifier.background(MaterialTheme.colorScheme.background),
                        boundsAnimationSpec = snap(),
                        enterTransition = paneEnter,
                        exitTransition = paneExit
                    ) {
                        DetailPaneContent(
                            navigator = navigator,
                            viewModel = viewModel,
                            isCompanionMode = isCompanionMode,
                            showBackButton = showBackButton,
                            isCompact = isCompact,
                            activeChatId = activeChatId,
                            activeContactId = activeContactId,
                            onCropAvatar = onCropAvatar
                        )
                    }
                },
                extraPane = {
                    AnimatedPane(
                        modifier = Modifier.background(MaterialTheme.colorScheme.background),
                        boundsAnimationSpec = snap(),
                        enterTransition = paneEnter,
                        exitTransition = paneExit
                    ) {
                        ExtraPaneContent(
                            navigator = navigator,
                            viewModel = viewModel,
                            isCompact = isCompact,
                            activeContactId = activeContactId,
                            onCropAvatar = onCropAvatar
                        )
                    }
                }
            )

        }
    }

    Row(Modifier.fillMaxSize()) {
        if (isTabletMode) {
            UnifiedNavigationRail(
                viewModel = viewModel,
                selectedAccount = selectedAccount,
                myAvatar = myAvatar,
                chats = sortedChats,
                contactAvatars = contactAvatars,
                unreadCounts = unreadCounts,
                activeChatId = activeChatId,
                currentTab = selectedTab,
                visibleTabs = visibleTabs,
                totalUnreadCount = totalUnreadCount,
                onChatSelect = { chatId ->
                    isPairedDevicesOpen = false
                    selectedTab = MainTab.CHATS
                    viewModel.setActiveChat(chatId)
                },
                onTabSelect = { tab ->
                    isPairedDevicesOpen = false
                    selectedTab = tab
                    viewModel.setActiveChat(null)
                    viewModel.setActiveContact(null)
                    scope.launch {
                        while (navigator.canNavigateBack()) {
                            navigator.navigateBack()
                        }
                    }
                }
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            if (isCompact) {
                // Компактный режим — два слоя, а не подмена панелей.
                //
                // Список лежит снизу **всегда** и из дерева не уходит.
                // Раньше он компоновался заново в начале каждого жеста:
                // лента пересобиралась, Coil перезапрашивал аватарки,
                // и они моргали. Теперь он просто уезжает с параллаксом.
                //
                // ListDetailPaneScaffold здесь не нужен вовсе: панель
                // одна за раз, а Extra в этом приложении не открывается
                // ниоткуда. Он остаётся для планшета, где панелей две.
                val backSwipe = rememberSwipeBackState()
                val detailKey = navigator.currentDestination?.contentKey
                val overlayOpen = isPairedDevicesOpen || detailKey != null

                Box(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .swipeBackUnderlay(backSwipe, blockInput = overlayOpen)
                    ) {
                        listPaneBody()
                    }

                    if (isPairedDevicesOpen) {
                        SwipeBackLayer(
                            state = backSwipe,
                            onBack = { isPairedDevicesOpen = false },
                            contentKey = "paired_devices"
                        ) {
                            chat.ratatosk.android.ui.settings.PairedDevicesScreen(
                                viewModel = viewModel,
                                onBack = { isPairedDevicesOpen = false }
                            )
                        }
                    } else if (detailKey != null) {
                        SwipeBackLayer(
                            state = backSwipe,
                            onBack = { scope.launch { navigator.navigateBack() } },
                            contentKey = detailKey
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.background)
                            ) {
                                DetailPaneContent(
                                    navigator = navigator,
                                    viewModel = viewModel,
                                    isCompanionMode = isCompanionMode,
                                    showBackButton = true,
                                    isCompact = true,
                                    activeChatId = activeChatId,
                                    activeContactId = activeContactId,
                                    onCropAvatar = onCropAvatar
                                )
                            }
                        }
                    }
                }
            } else if (isPairedDevicesOpen) {
                // Планшет: сюда приходят из полноэкранной панели настроек,
                // и показать под уходящим экраном пришлось бы paneArea —
                // второй ListDetailPaneScaffold с тем же scaffoldState,
                // а это падение. Поэтому здесь только обычное «назад».
                BackHandler(enabled = true) { isPairedDevicesOpen = false }
                chat.ratatosk.android.ui.settings.PairedDevicesScreen(
                    viewModel = viewModel,
                    onBack = { isPairedDevicesOpen = false }
                )
            } else {
                paneArea()
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
    activeContactId: ByteArray?,
    onCropAvatar: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val currentDestination = navigator.currentDestination
    val currentKey = currentDestination?.contentKey
    val groups by viewModel.groups.collectAsState()

    val detailContactId = remember(currentKey, activeContactId) {
        activeContactId ?: if (currentKey?.startsWith("contact_") == true) {
            try { currentKey.drop(8).hexToByteArray() } catch (e: Exception) { null }
        } else null
    }

    val detailChatId = remember(currentKey, activeChatId) {
        activeChatId ?: if (currentKey?.startsWith("chat_") == true) {
            try { currentKey.drop(5).hexToByteArray() } catch (e: Exception) { null }
        } else null
    }

    when {
        activeContactId != null || (currentKey?.startsWith("contact_") == true && detailContactId != null) -> {
            if (detailContactId != null) {
                val isGroup = groups.any { it.chatId.contentEquals(detailContactId) }
                if (isGroup) {
                    chat.ratatosk.android.ui.groups.GroupDetailsScreen(
                        viewModel = viewModel,
                        chatId = detailContactId,
                        onBack = { scope.launch { navigator.navigateBack() } },
                        onChatClick = { chatId: ByteArray ->
                            viewModel.setActiveChat(chatId)
                        },
                        onCropAvatar = onCropAvatar,
                        showBackButton = showBackButton,
                        isCompact = isCompact
                    )
                } else {
                    ContactDetailsScreen(
                        viewModel = viewModel,
                        chatId = detailContactId,
                        onBack = { scope.launch { navigator.navigateBack() } },
                        onChatClick = { chatId ->
                            viewModel.setActiveChat(chatId)
                        },
                        showBackButton = showBackButton,
                        isCompact = isCompact
                    )
                }
            }
        }
        activeChatId != null || (currentKey?.startsWith("chat_") == true && detailChatId != null) -> {
            if (detailChatId != null) {
                ChatScreen(
                    viewModel = viewModel,
                    chatId = detailChatId,
                    onBack = { scope.launch { navigator.navigateBack() } },
                    onHeaderClick = { 
                        viewModel.setActiveContact(detailChatId)
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
    activeContactId: ByteArray?,
    onCropAvatar: () -> Unit = {}
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
                onChatClick = { chatId: ByteArray ->
                    viewModel.setActiveChat(chatId)
                },
                onCropAvatar = onCropAvatar,
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
    viewModel: RatatoskViewModel,
    selectedAccount: FfiAccount?,
    myAvatar: ByteArray?,
    chats: List<ChatItem>,
    contactAvatars: Map<String, ByteArray>,
    unreadCounts: Map<String, Int>,
    activeChatId: ByteArray?,
    currentTab: MainTab,
    visibleTabs: List<MainTab>,
    totalUnreadCount: Int,
    onChatSelect: (ByteArray) -> Unit,
    onTabSelect: (MainTab) -> Unit
) {
    NavigationRail(
        modifier = Modifier.width(72.dp),
        header = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
            ) {
                SidebarProfileAvatar(
                    selectedAccount = selectedAccount,
                    myAvatar = myAvatar,
                    isSelected = currentTab == MainTab.PROFILE,
                    onClick = { onTabSelect(MainTab.PROFILE) }
                )
            }
        }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxHeight()
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val availableHeight = maxHeight
                val itemHeight = 56.dp
                val totalCapacity = (availableHeight / itemHeight).toInt().coerceAtLeast(0)
                val visibleChats = chats.take(totalCapacity)

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    visibleChats.forEach { chatItem ->
                        val hexId = chatItem.chatId.toHexString()
                        val avatarBytes = when (chatItem) {
                            is ChatItem.Contact -> {
                                val ikHex = chatItem.contact.peerIk.toHexString()
                                contactAvatars[ikHex] ?: viewModel.getAvatarOf(chatItem.contact.peerIk)
                            }
                            is ChatItem.Group -> {
                                contactAvatars[hexId] ?: viewModel.getGroupAvatar(chatItem.chatId)
                            }
                        }
                        val unreadCount = unreadCounts[hexId] ?: 0
                        val isSelected = activeChatId?.contentEquals(chatItem.chatId) == true

                        SideBarChatAvatar(
                            chatItem = chatItem,
                            avatarBytes = avatarBytes,
                            unreadCount = unreadCount,
                            isSelected = isSelected,
                            onClick = { onChatSelect(chatItem.chatId) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            if (visibleTabs.contains(MainTab.CHATS)) {
                NavigationRailItem(
                    selected = currentTab == MainTab.CHATS,
                    onClick = { onTabSelect(MainTab.CHATS) },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (totalUnreadCount > 0) {
                                    Badge { Text(if (totalUnreadCount > 99) "99+" else totalUnreadCount.toString()) }
                                }
                            }
                        ) {
                            Icon(MainTab.CHATS.icon, contentDescription = stringResource(MainTab.CHATS.labelRes))
                        }
                    },
                    alwaysShowLabel = false
                )
            }

            if (visibleTabs.contains(MainTab.CONTACTS)) {
                NavigationRailItem(
                    selected = currentTab == MainTab.CONTACTS,
                    onClick = { onTabSelect(MainTab.CONTACTS) },
                    icon = {
                        Icon(MainTab.CONTACTS.icon, contentDescription = stringResource(MainTab.CONTACTS.labelRes))
                    },
                    alwaysShowLabel = false
                )
            }

            if (visibleTabs.contains(MainTab.SETTINGS)) {
                NavigationRailItem(
                    selected = currentTab == MainTab.SETTINGS,
                    onClick = { onTabSelect(MainTab.SETTINGS) },
                    icon = {
                        Icon(MainTab.SETTINGS.icon, contentDescription = stringResource(MainTab.SETTINGS.labelRes))
                    },
                    alwaysShowLabel = false
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun SideBarChatAvatar(
    chatItem: ChatItem,
    avatarBytes: ByteArray?,
    unreadCount: Int,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier.size(48.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            when (chatItem) {
                is ChatItem.Contact -> {
                    Avatar(
                        avatarBytes = avatarBytes,
                        name = chatItem.title,
                        size = 40.dp
                    )
                }
                is ChatItem.Group -> {
                    Avatar(
                        avatarBytes = avatarBytes,
                        name = chatItem.title,
                        size = 40.dp,
                        icon = Icons.Default.Groups
                    )
                }
            }
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                )
            }
        }
        if (unreadCount > 0) {
            Badge(
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Text(if (unreadCount > 99) "99+" else unreadCount.toString())
            }
        }
    }
}

@Composable
fun SidebarProfileAvatar(
    selectedAccount: FfiAccount?,
    myAvatar: ByteArray?,
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
            avatarBytes = myAvatar,
            name = selectedAccount?.label ?: "",
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
