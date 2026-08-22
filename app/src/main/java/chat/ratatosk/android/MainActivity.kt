package chat.ratatosk.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import chat.ratatosk.android.service.RatatoskService
import chat.ratatosk.android.ui.RatatoskViewModel
import chat.ratatosk.android.ui.onboarding.OnboardingScreen
import chat.ratatosk.android.ui.unlock.UnlockScreen
import chat.ratatosk.android.ui.unlock.AccountSelectionScreen
import chat.ratatosk.android.ui.main.MainScreen
import chat.ratatosk.android.ui.chat.ChatScreen
import chat.ratatosk.android.ui.profile.AvatarCropScreen
import chat.ratatosk.android.ui.contacts.ContactDetailsScreen
import chat.ratatosk.android.ui.theme.RatatoskTheme
import chat.ratatosk.android.util.hexToByteArray
import chat.ratatosk.android.util.toHexString

class MainActivity : ComponentActivity() {
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    private var pendingChatId by mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("chatId")?.let {
            pendingChatId = it
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        pendingChatId = intent.getStringExtra("chatId")

        setContent {
            val appViewModel: RatatoskViewModel = viewModel()
            val isCoreReady by appViewModel.isInitialized.collectAsState()
            val accounts by appViewModel.availableAccounts.collectAsState()
            val currentError by appViewModel.error.collectAsState()
            val chatTheme by appViewModel.chatTheme.collectAsState()
            val navController = rememberNavController()

            val selectedAccount by appViewModel.selectedAccount.collectAsState()
            val isCreatingNewAccount by appViewModel.isCreatingNewAccount.collectAsState()

            // Set initial selection if only one account exists
            LaunchedEffect(accounts) {
                if (accounts.size == 1 && selectedAccount == null && !isCreatingNewAccount && !isCoreReady) {
                    appViewModel.selectAccount(accounts.first())
                }
            }

            // Auto-request notifications
            LaunchedEffect(accounts) {
                if (accounts.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissionsLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                    }
                }
            }

            // Handle incoming notification intent
            LaunchedEffect(pendingChatId, isCoreReady) {
                if (isCoreReady) {
                    pendingChatId?.let { chatId ->
                        navController.navigate("chat/$chatId")
                        pendingChatId = null
                    }
                }
            }

            // Start the core service
            LaunchedEffect(accounts) {
                if (accounts.isNotEmpty()) {
                    val intent = Intent(this@MainActivity, RatatoskService::class.java)
                    startForegroundService(intent)
                }
            }

            RatatoskTheme(themeColor = chatTheme.themeColor) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (!isCoreReady) {
                        when {
                            isCreatingNewAccount || accounts.isEmpty() -> {
                                OnboardingScreen(
                                    viewModel = appViewModel,
                                    onBack = if (accounts.isNotEmpty()) { { appViewModel.setCreatingNewAccount(false) } } else null
                                )
                            }
                            selectedAccount == null -> {
                                AccountSelectionScreen(
                                    viewModel = appViewModel,
                                    onSelect = { appViewModel.selectAccount(it) },
                                    onCreateNew = { appViewModel.setCreatingNewAccount(true) }
                                )
                            }
                            else -> {
                                UnlockScreen(
                                    viewModel = appViewModel,
                                    account = selectedAccount!!,
                                    onBack = { appViewModel.selectAccount(null) }
                                )
                            }
                        }
                    } else {
                        NavHost(navController = navController, startDestination = "main") {
                            composable("main") {
                                MainScreen(
                                    viewModel = appViewModel,
                                    onChatClick = { chatId ->
                                        navController.navigate("chat/${chatId.toHexString()}")
                                    },
                                    onContactClick = { chatId ->
                                        navController.navigate("contact/${chatId.toHexString()}")
                                    },
                                    onScanClick = {
                                        navController.navigate("scanner")
                                    },
                                    onCropAvatar = {
                                        navController.navigate("crop")
                                    }
                                )
                            }
                            composable("crop") {
                                AvatarCropScreen(
                                    viewModel = appViewModel,
                                    onDone = { navController.popBackStack() },
                                    onBack = { navController.popBackStack() }
                                )
                            }
                            composable("chat/{chatId}") { backStackEntry ->
                                val chatIdHex = backStackEntry.arguments?.getString("chatId") ?: ""
                                val chatId = try {
                                    chatIdHex.hexToByteArray()
                                } catch (e: Exception) {
                                    byteArrayOf()
                                }
                                ChatScreen(
                                    viewModel = appViewModel,
                                    chatId = chatId,
                                    onBack = { navController.popBackStack() },
                                    onHeaderClick = {
                                        navController.navigate("contact/${chatIdHex}")
                                    }
                                )
                            }
                            composable("contact/{chatId}") { backStackEntry ->
                                val chatIdHex = backStackEntry.arguments?.getString("chatId") ?: ""
                                val chatId = try {
                                    chatIdHex.hexToByteArray()
                                } catch (e: Exception) {
                                    byteArrayOf()
                                }
                                ContactDetailsScreen(
                                    viewModel = appViewModel,
                                    chatId = chatId,
                                    onBack = { navController.popBackStack() },
                                    onChatClick = {
                                        navController.navigate("chat/${chatIdHex}") {
                                            popUpTo("main")
                                        }
                                    }
                                )
                            }
                            composable("scanner") {
                                chat.ratatosk.android.ui.qr.QRScannerScreen(
                                    onResult = { uri ->
                                        navController.popBackStack()
                                        appViewModel.addContact(uri, metInPerson = true)
                                    },
                                    onBack = { navController.popBackStack() }
                                )
                            }
                        }
                    }

                    // Global Error Overlay
                    if (isCoreReady && currentError != null) {
                        AlertDialog(
                            onDismissRequest = { /* Don't dismiss critical errors */ },
                            title = { Text(stringResource(R.string.core_not_running)) },
                            text = { Text(currentError ?: "") },
                            confirmButton = {
                                Button(onClick = { 
                                    appViewModel.clearError()
                                }) {
                                    Text(stringResource(R.string.retry))
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
