package chat.ratatosk.android.data

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import chat.ratatosk.android.ui.theme.ChatThemeData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class CompanionLink(
    val label: String,
    val inviteUri: String,
    val port: Int,
    val peerAddr: String?,
    val cachePath: String?,
    val torDir: String? = null
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val THEME_COLOR = longPreferencesKey("theme_color")
        val BACKGROUND_IMAGE_URI = stringPreferencesKey("background_image_uri")
        val BACKGROUND_OPACITY = longPreferencesKey("background_opacity") // Stored as Long(bits) or scaled Int
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val NOTIFICATIONS_SHOW_NAME = booleanPreferencesKey("notifications_show_name")
        val NOTIFICATIONS_SHOW_TEXT = booleanPreferencesKey("notifications_show_text")
        val DOWNLOAD_DIR_URI = stringPreferencesKey("download_dir_uri")
        val ACCOUNTS_MAP = stringPreferencesKey("accounts_map")
        val COMPANION_LINKS = stringPreferencesKey("companion_links")
        val LAST_ACCOUNT_ID = stringPreferencesKey("last_account_id")
    }

    val lastAccountId: Flow<String?> = context.dataStore.data.map { it[Keys.LAST_ACCOUNT_ID] }

    suspend fun setLastAccountId(id: String?) {
        context.dataStore.edit { preferences ->
            if (id != null) preferences[Keys.LAST_ACCOUNT_ID] = id
            else preferences.remove(Keys.LAST_ACCOUNT_ID)
        }
    }

    val companionLinks: Flow<List<CompanionLink>> = context.dataStore.data.map { preferences ->
        val raw = preferences[Keys.COMPANION_LINKS] ?: ""
        if (raw.isEmpty()) emptyList()
        else {
            raw.split(";;").filter { it.isNotBlank() }.mapNotNull { entry ->
                val parts = entry.split("|")
                if (parts.size >= 5) {
                    CompanionLink(
                        label = parts[0],
                        inviteUri = parts[1],
                        port = parts[2].toIntOrNull() ?: 0,
                        peerAddr = parts[3].takeIf { it != "null" },
                        cachePath = parts[4].takeIf { it != "null" },
                        torDir = parts.getOrNull(5)?.takeIf { it != "null" }
                    )
                } else null
            }
        }
    }

    suspend fun saveCompanionLink(link: CompanionLink) {
        context.dataStore.edit { preferences ->
            val current = preferences[Keys.COMPANION_LINKS] ?: ""
            val links = current.split(";;").filter { it.isNotBlank() }.toMutableList()
            val entry = "${link.label}|${link.inviteUri}|${link.port}|${link.peerAddr ?: "null"}|${link.cachePath ?: "null"}|${link.torDir ?: "null"}"
            
            // Avoid duplicates by inviteUri
            val index = links.indexOfFirst { it.contains("|${link.inviteUri}|") }
            if (index != -1) {
                links[index] = entry
            } else {
                links.add(entry)
            }
            preferences[Keys.COMPANION_LINKS] = links.joinToString(";;")
        }
    }

    suspend fun removeCompanionLink(inviteUri: String) {
        context.dataStore.edit { preferences ->
            val current = preferences[Keys.COMPANION_LINKS] ?: ""
            val links = current.split(";;").filter { it.isNotBlank() }.toMutableList()
            links.removeAll { it.contains("|${inviteUri}|") }
            preferences[Keys.COMPANION_LINKS] = links.joinToString(";;")
        }
    }

    val accountsMap: Flow<Map<String, String>> = context.dataStore.data.map { preferences ->
        val raw = preferences[Keys.ACCOUNTS_MAP] ?: ""
        if (raw.isEmpty()) emptyMap()
        else {
            raw.split(";").filter { it.contains(":") }.associate { 
                val parts = it.split(":", limit = 2)
                parts[0] to parts[1]
            }
        }
    }

    suspend fun registerAccount(accountId: String, displayName: String) {
        context.dataStore.edit { preferences ->
            val current = preferences[Keys.ACCOUNTS_MAP] ?: ""
            val accounts = current.split(";").filter { it.isNotBlank() }.toMutableList()
            val entry = "$accountId:$displayName"
            if (!accounts.any { it.startsWith("$accountId:") }) {
                accounts.add(entry)
                preferences[Keys.ACCOUNTS_MAP] = accounts.joinToString(";")
            }
            preferences[stringPreferencesKey(accountKey(accountId, "display_name"))] = displayName
        }
    }

    private fun accountKey(accountId: String, key: String) = "${accountId}_$key"

    fun getDisplayName(accountId: String): Flow<String?> = context.dataStore.data.map { it[stringPreferencesKey(accountKey(accountId, "display_name"))] }
    fun getNotificationsShowName(accountId: String): Flow<Boolean> = context.dataStore.data.map { it[booleanPreferencesKey(accountKey(accountId, "notifications_show_name"))] ?: true }
    fun getNotificationsShowText(accountId: String): Flow<Boolean> = context.dataStore.data.map { it[booleanPreferencesKey(accountKey(accountId, "notifications_show_text"))] ?: true }
    fun getDownloadDirUri(accountId: String): Flow<String?> = context.dataStore.data.map { it[stringPreferencesKey(accountKey(accountId, "download_dir_uri"))] }

    val chatTheme: Flow<ChatThemeData> = context.dataStore.data.map { preferences ->
        ChatThemeData(
            themeColor = preferences[Keys.THEME_COLOR]?.let { Color(it.toInt()) } ?: Color.Unspecified,
            backgroundImageUri = preferences[Keys.BACKGROUND_IMAGE_URI],
            backgroundOpacity = (preferences[Keys.BACKGROUND_OPACITY]?.toInt() ?: 100) / 100f
        )
    }

    suspend fun setDisplayName(accountId: String, name: String) {
        context.dataStore.edit { it[stringPreferencesKey(accountKey(accountId, "display_name"))] = name }
    }

    suspend fun setNotificationsShowName(accountId: String, show: Boolean) {
        context.dataStore.edit { it[booleanPreferencesKey(accountKey(accountId, "notifications_show_name"))] = show }
    }

    suspend fun setNotificationsShowText(accountId: String, show: Boolean) {
        context.dataStore.edit { it[booleanPreferencesKey(accountKey(accountId, "notifications_show_text"))] = show }
    }

    suspend fun setDownloadDirUri(accountId: String, uri: String?) {
        context.dataStore.edit { preferences ->
            val key = stringPreferencesKey(accountKey(accountId, "download_dir_uri"))
            if (uri != null) {
                preferences[key] = uri
            } else {
                preferences.remove(key)
            }
        }
    }

    suspend fun updateChatTheme(data: ChatThemeData) {
        context.dataStore.edit { preferences ->
            if (data.backgroundImageUri != null) {
                preferences[Keys.BACKGROUND_IMAGE_URI] = data.backgroundImageUri
            } else {
                preferences.remove(Keys.BACKGROUND_IMAGE_URI)
            }
            
            if (data.themeColor != Color.Unspecified) {
                preferences[Keys.THEME_COLOR] = data.themeColor.toArgb().toLong()
            } else {
                preferences.remove(Keys.THEME_COLOR)
            }

            preferences[Keys.BACKGROUND_OPACITY] = (data.backgroundOpacity * 100).toLong()
        }
    }
}
