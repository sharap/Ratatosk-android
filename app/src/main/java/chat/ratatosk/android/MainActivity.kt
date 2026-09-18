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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import chat.ratatosk.android.service.RatatoskService
import chat.ratatosk.android.ui.RatatoskViewModel
import chat.ratatosk.android.ui.onboarding.OnboardingScreen
import chat.ratatosk.android.ui.onboarding.LinkCompanionScreen
import chat.ratatosk.android.ui.unlock.UnlockScreen
import chat.ratatosk.android.ui.unlock.AccountSelectionScreen
import chat.ratatosk.android.ui.main.MainScreen
import chat.ratatosk.android.ui.chat.ChatScreen
import chat.ratatosk.android.ui.profile.AvatarCropScreen
import chat.ratatosk.android.ui.contacts.ContactDetailsScreen
import chat.ratatosk.android.ui.media.MediaViewerScreen
import chat.ratatosk.android.ui.theme.RatatoskTheme
import chat.ratatosk.android.util.hexToByteArray
import chat.ratatosk.android.util.toHexString

class MainActivity : ComponentActivity() {
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    private var pendingChatId by mutableStateOf<String?>(null)
    // Сообщение, на котором надо открыть чат: уведомление о реакции
    // ведёт не просто в чат, а на то место, где её поставили.
    private var pendingMsgId by mutableStateOf<String?>(null)

    // «Поделиться» и ссылка ratatosk: приходят снаружи и могут застать
    // приложение запертым. Держим намерение, пока человек не откроет
    // аккаунт: выбросить его молча — значит съесть чужое действие.
    private var incoming by mutableStateOf<chat.ratatosk.android.util.Incoming?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("chatId")?.let {
            pendingChatId = it
            pendingMsgId = intent.getStringExtra("msgId")
        }
        chat.ratatosk.android.util.IncomingIntents.parse(intent)?.let { incoming = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        chat.ratatosk.android.util.IncomingIntents.save(outState, incoming)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        pendingChatId = intent.getStringExtra("chatId")
        pendingMsgId = intent.getStringExtra("msgId")
        // После поворота берём то, что осталось необработанным, а не
        // разбираем прежний Intent заново.
        incoming = if (savedInstanceState != null) {
            chat.ratatosk.android.util.IncomingIntents.restore(savedInstanceState)
        } else {
            chat.ratatosk.android.util.IncomingIntents.parse(intent)
        }

        setContent {
            val appViewModel: RatatoskViewModel = viewModel()
            val isCoreReady by appViewModel.isInitialized.collectAsState()
            val accounts by appViewModel.availableAccounts.collectAsState()
            val companionLinks by appViewModel.companionLinks.collectAsState()
            val lastAccountId by appViewModel.lastAccountId.collectAsState()
            
            val currentError by appViewModel.error.collectAsState()
            val chatTheme by appViewModel.chatTheme.collectAsState()
            val navController = rememberNavController()

            val selectedAccount by appViewModel.selectedAccount.collectAsState()
            val isCreatingNewAccount by appViewModel.isCreatingNewAccount.collectAsState()
            var isLinkingCompanion by remember { mutableStateOf(false) }
            var isScanningCompanion by remember { mutableStateOf(false) }
            var companionScanUri by remember { mutableStateOf<String?>(null) }
            var isRestoringAccount by remember { mutableStateOf(false) }

            val context = androidx.compose.ui.platform.LocalContext.current
            val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                ActivityResultContracts.GetContent()
            ) { uri ->
                uri?.let {
                    val file = chat.ratatosk.android.util.FileUtils.copyUriToInternalStorage(context, it)
                    if (file != null) {
                        companionScanUri = file.absolutePath
                        isRestoringAccount = true
                    }
                }
            }

            // Auto-select last account or auto-login companion
            LaunchedEffect(accounts, companionLinks, lastAccountId) {
                if (lastAccountId != null && selectedAccount == null && !isCreatingNewAccount && !isCoreReady) {
                    val localAccount = accounts.find { it.id.toHexString() == lastAccountId }
                    if (localAccount != null) {
                        appViewModel.selectAccount(localAccount)
                    } else {
                        val companionLink = companionLinks.find { "companion:${it.inviteUri.hashCode()}" == lastAccountId }
                        if (companionLink != null) {
                            appViewModel.unlockCompanion(companionLink)
                        }
                    }
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
                        val bytes = try { chatId.hexToByteArray() } catch (e: Exception) { null }
                        if (bytes != null) {
                            appViewModel.setActiveChat(bytes)
                            // Просьбу о переходе ставим после открытия чата:
                            // снимет её сам экран, когда доскроллит.
                            appViewModel.requestScrollToMessage(pendingMsgId)
                        }
                        pendingChatId = null
                        pendingMsgId = null
                    }
                }
            }

            // Ссылка сопряжения имеет смысл только там, где своего
            // аккаунта не открыто: терминалом становятся вместо него,
            // а не вдобавок. Пока ядро не поднято — ведём прямо на экран
            // связывания, он же и спросит подтверждение.
            LaunchedEffect(incoming, isCoreReady) {
                val link = incoming
                if (link is chat.ratatosk.android.util.Incoming.PairDevice && !isCoreReady) {
                    companionScanUri = link.uri
                    isLinkingCompanion = true
                    incoming = null
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
                            isScanningCompanion -> {
                                chat.ratatosk.android.ui.qr.QRScannerScreen(
                                    onResult = { uri ->
                                        companionScanUri = uri
                                        isScanningCompanion = false
                                    },
                                    onBack = { isScanningCompanion = false }
                                )
                            }
                            isRestoringAccount -> {
                                chat.ratatosk.android.ui.unlock.ImportArchiveDialog(
                                    path = companionScanUri!!,
                                    viewModel = appViewModel,
                                    onDismiss = { 
                                        isRestoringAccount = false
                                        companionScanUri = null
                                    }
                                )
                            }
                            isLinkingCompanion -> {
                                LinkCompanionScreen(
                                    viewModel = appViewModel,
                                    initialUri = companionScanUri,
                                    onScan = { isScanningCompanion = true },
                                    onBack = { 
                                        isLinkingCompanion = false
                                        companionScanUri = null
                                    }
                                )
                            }
                            isCreatingNewAccount || (accounts.isEmpty() && companionLinks.isEmpty()) -> {
                                OnboardingScreen(
                                    viewModel = appViewModel,
                                    onBack = if (accounts.isNotEmpty() || companionLinks.isNotEmpty()) { { appViewModel.setCreatingNewAccount(false) } } else null,
                                    onLinkCompanion = { isLinkingCompanion = true },
                                    onImport = { importLauncher.launch("*/*") }
                                )
                            }
                            selectedAccount == null -> {
                                AccountSelectionScreen(
                                    viewModel = appViewModel,
                                    onSelect = { appViewModel.selectAccount(it) },
                                    onSelectCompanion = { appViewModel.unlockCompanion(it) },
                                    onCreateNew = { appViewModel.setCreatingNewAccount(true) },
                                    onLinkCompanion = { isLinkingCompanion = true }
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
                        Box(modifier = Modifier.fillMaxSize()) {
                            NavHost(
                                navController = navController, 
                                startDestination = "main",
                                modifier = Modifier.background(MaterialTheme.colorScheme.background)
                            ) {
                                composable("main") {
                                    MainScreen(
                                        viewModel = appViewModel,
                                        onChatClick = { chatId ->
                                            appViewModel.setActiveChat(chatId)
                                        },
                                        onContactClick = { chatId ->
                                            appViewModel.setActiveContact(chatId)
                                        },
                                        onScanClick = {
                                            navController.navigate("scanner")
                                        },
                                        onCropAvatar = {
                                            navController.navigate("crop")
                                        },
                                        onPairedDevicesClick = {
                                            navController.navigate("paired_devices")
                                        }
                                    )
                                }
                                composable("paired_devices") {
                                    chat.ratatosk.android.ui.settings.PairedDevicesScreen(
                                        viewModel = appViewModel,
                                        onBack = { navController.popBackStack() }
                                    )
                                }
                                composable("crop") {
                                    AvatarCropScreen(
                                        viewModel = appViewModel,
                                        onDone = { navController.popBackStack() },
                                        onBack = { navController.popBackStack() }
                                    )
                                }
                                composable("scanner") {
                                    var hasHandledResult by remember { mutableStateOf(false) }
                                    chat.ratatosk.android.ui.qr.QRScannerScreen(
                                        onResult = { uri ->
                                            if (!hasHandledResult) {
                                                hasHandledResult = true
                                                navController.popBackStack()
                                                appViewModel.addContact(uri, metInPerson = true)
                                            }
                                        },
                                        onBack = {
                                            if (!hasHandledResult) {
                                                hasHandledResult = true
                                                navController.popBackStack()
                                            }
                                        }
                                    )
                                }
                            }

                            // Media Overlay
                            val activeMediaFile by appViewModel.activeMediaFile.collectAsState()
                            if (activeMediaFile != null) {
                                MediaViewerScreen(
                                    viewModel = appViewModel,
                                    onClose = { appViewModel.closeMedia() }
                                )
                            }
                        }
                    }

                    // Пришедшее снаружи: показываем, когда аккаунт открыт.
                    // Раньше нельзя — ни чатов, ни ядра ещё нет.
                    if (isCoreReady) {
                        when (val inc = incoming) {
                            is chat.ratatosk.android.util.Incoming.Share -> {
                                chat.ratatosk.android.ui.components.ChatPickerDialog(
                                    viewModel = appViewModel,
                                    title = stringResource(R.string.share_into_chat),
                                    onPick = { chatId ->
                                        appViewModel.shareInto(chatId, inc.text, inc.uris)
                                        appViewModel.setActiveChat(chatId)
                                        // Могли стоять на сканере или кропе —
                                        // черновик ляжет в чат, а человек его
                                        // не увидит.
                                        navController.popBackStack("main", false)
                                        incoming = null
                                    },
                                    onDismiss = { incoming = null }
                                )
                            }
                            is chat.ratatosk.android.util.Incoming.AddContact -> {
                                chat.ratatosk.android.ui.components.AddContactByLinkDialog(
                                    onAdd = { metInPerson ->
                                        appViewModel.addContact(inc.uri, metInPerson)
                                        incoming = null
                                    },
                                    onDismiss = { incoming = null }
                                )
                            }
                            is chat.ratatosk.android.util.Incoming.PairDevice -> {
                                chat.ratatosk.android.ui.components.PairLinkBusyDialog(
                                    onDismiss = { incoming = null }
                                )
                            }
                            null -> {}
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
