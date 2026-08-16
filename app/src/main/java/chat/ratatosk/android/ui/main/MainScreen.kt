package chat.ratatosk.android.ui.main

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import chat.ratatosk.android.R
import chat.ratatosk.android.ui.RatatoskViewModel
import chat.ratatosk.android.ui.chatlist.ChatListScreen
import chat.ratatosk.android.ui.contacts.ContactsScreen
import chat.ratatosk.android.ui.profile.ProfileScreen
import chat.ratatosk.android.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

enum class MainTab(val icon: androidx.compose.ui.graphics.vector.ImageVector, val labelRes: Int) {
    CHATS(Icons.AutoMirrored.Filled.Chat, R.string.chats),
    CONTACTS(Icons.Default.Contacts, R.string.contacts),
    SETTINGS(Icons.Default.Settings, R.string.settings),
    PROFILE(Icons.Default.AccountCircle, R.string.profile)
}

@Composable
fun MainScreen(
    viewModel: RatatoskViewModel,
    onChatClick: (ByteArray) -> Unit,
    onContactClick: (ByteArray) -> Unit,
    onScanClick: () -> Unit
) {
    val tabs = MainTab.entries
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    val totalUnreadCount by viewModel.totalUnreadCount.collectAsState()

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
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
                        selected = pagerState.currentPage == index,
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            beyondViewportPageCount = 3
        ) { page ->
            when (tabs[page]) {
                MainTab.CHATS -> ChatListScreen(
                    viewModel = viewModel,
                    onChatClick = onChatClick,
                    onSettingsClick = { 
                        // Settings is now a tab, but we could still have a button to jump there
                        scope.launch { pagerState.animateScrollToPage(MainTab.SETTINGS.ordinal) }
                    },
                    onScanClick = onScanClick
                )
                MainTab.CONTACTS -> ContactsScreen(
                    viewModel = viewModel,
                    onContactClick = onContactClick,
                    onScanClick = onScanClick
                )
                MainTab.SETTINGS -> SettingsScreen(
                    viewModel = viewModel,
                    onBack = { 
                        // In tab mode, back could mean go to chats
                        scope.launch { pagerState.animateScrollToPage(MainTab.CHATS.ordinal) }
                    }
                )
                MainTab.PROFILE -> ProfileScreen(
                    viewModel = viewModel
                )
            }
        }
    }
}
