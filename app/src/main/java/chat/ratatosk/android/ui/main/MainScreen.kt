package chat.ratatosk.android.ui.main

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
    onScanClick: () -> Unit,
    onCropAvatar: () -> Unit,
    onPairedDevicesClick: () -> Unit
) {
    AdaptiveMainScreen(
        viewModel = viewModel,
        onScanClick = onScanClick,
        onCropAvatar = onCropAvatar,
        onPairedDevicesClick = onPairedDevicesClick
    )
}
