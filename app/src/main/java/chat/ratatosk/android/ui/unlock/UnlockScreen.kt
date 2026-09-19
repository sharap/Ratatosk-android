package chat.ratatosk.android.ui.unlock

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.ratatosk.android.R
import chat.ratatosk.android.ui.RatatoskViewModel
import org.ratatosk.core.FfiAccount

@Composable
fun UnlockScreen(
    viewModel: RatatoskViewModel,
    account: FfiAccount,
    onBack: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    val error by viewModel.error.collectAsState()

    val isOpening by viewModel.isOpening.collectAsState()
    val pinRequired by viewModel.pinRequired.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        
        Text(
            text = "Unlock ${account.label}",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(32.dp))

        error?.let {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = it,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (isOpening) {
            // Вывод ключа из PIN занимает секунды: без индикатора экран
            // выглядит застывшим, и человек жмёт кнопку ещё раз.
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.opening_account),
                style = MaterialTheme.typography.bodyMedium
            )
        } else if (pinRequired) {
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it },
                label = { Text(stringResource(R.string.enter_pin)) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { viewModel.unlock(account, pin.takeIf { it.isNotEmpty() }) },
            enabled = !isOpening && pinRequired,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.unlock))
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(onClick = onBack) {
            Text(stringResource(R.string.switch_account))
        }
    }
}
