package chat.ratatosk.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.ratatosk.android.R
import chat.ratatosk.android.ui.RatatoskViewModel
import chat.ratatosk.android.util.toHexString

/**
 * Выбор чата: группы, затем люди.
 *
 * Один список на пересылку и на «поделиться» — вопрос у них один и тот
 * же («кому?»), и расходиться им незачем.
 */
@Composable
fun ChatPickerDialog(
    viewModel: RatatoskViewModel,
    title: String,
    onPick: (ByteArray) -> Unit,
    onDismiss: () -> Unit
) {
    val allContacts by viewModel.contacts.collectAsState()
    val allGroups by viewModel.groups.collectAsState()
    val contactAvatars by viewModel.contactAvatars.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (allGroups.isEmpty() && allContacts.isEmpty()) {
                // Пустой список без слов выглядит поломкой, а это
                // обычное состояние нового аккаунта.
                Text(stringResource(R.string.share_no_chats))
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    if (allGroups.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.groups),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        items(allGroups) { group ->
                            ListItem(
                                headlineContent = { Text(group.title) },
                                leadingContent = {
                                    Avatar(
                                        avatarBytes = contactAvatars[group.chatId.toHexString()]
                                            ?: viewModel.getGroupAvatar(group.chatId),
                                        name = group.title
                                    )
                                },
                                modifier = Modifier.clickable { onPick(group.chatId) }
                            )
                        }
                    }

                    if (allContacts.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.contacts),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        items(allContacts) { contact ->
                            val name = contact.localName ?: contact.displayName
                            ListItem(
                                headlineContent = { Text(name) },
                                leadingContent = {
                                    Avatar(
                                        avatarBytes = contactAvatars[contact.peerIk.toHexString()]
                                            ?: viewModel.getAvatarOf(contact.peerIk),
                                        name = name
                                    )
                                },
                                modifier = Modifier.clickable { onPick(contact.chatId) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
