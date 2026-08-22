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

class SettingsRepository(private val context: Context) {
    private object Keys {
        val LAN_ENABLED = booleanPreferencesKey("lan_enabled")
        val TOR_ENABLED = booleanPreferencesKey("tor_enabled")
        val THEME_COLOR = longPreferencesKey("theme_color")
        val BACKGROUND_IMAGE_URI = stringPreferencesKey("background_image_uri")
        val BACKGROUND_OPACITY = longPreferencesKey("background_opacity") // Stored as Long(bits) or scaled Int
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val NOTIFICATIONS_SHOW_NAME = booleanPreferencesKey("notifications_show_name")
        val NOTIFICATIONS_SHOW_TEXT = booleanPreferencesKey("notifications_show_text")
        val DOWNLOAD_DIR_URI = stringPreferencesKey("download_dir_uri")
        val ACCOUNTS_MAP = stringPreferencesKey("accounts_map")
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
            // Migration: if this is the first registration and we have old global settings, move them to 'default'
            if (accountId == "default" && preferences[stringPreferencesKey("display_name")] != null) {
                val oldName = preferences[stringPreferencesKey("display_name")] ?: displayName
                val oldLan = preferences[booleanPreferencesKey("lan_enabled")] ?: false
                val oldNotifName = preferences[booleanPreferencesKey("notifications_show_name")] ?: true
                val oldNotifText = preferences[booleanPreferencesKey("notifications_show_text")] ?: true
                val oldDownload = preferences[stringPreferencesKey("download_dir_uri")]

                preferences[stringPreferencesKey(accountKey("default", "display_name"))] = oldName
                preferences[booleanPreferencesKey(accountKey("default", "lan_enabled"))] = oldLan
                preferences[booleanPreferencesKey(accountKey("default", "notifications_show_name"))] = oldNotifName
                preferences[booleanPreferencesKey(accountKey("default", "notifications_show_text"))] = oldNotifText
                if (oldDownload != null) preferences[stringPreferencesKey(accountKey("default", "download_dir_uri"))] = oldDownload
                
                // Remove old keys to avoid double migration
                preferences.remove(stringPreferencesKey("display_name"))
                // ... (removing others is safer but let's keep it simple)
            }

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
    fun getLanEnabled(accountId: String): Flow<Boolean> = context.dataStore.data.map { it[booleanPreferencesKey(accountKey(accountId, "lan_enabled"))] ?: false }
    fun getTorEnabled(accountId: String): Flow<Boolean> = context.dataStore.data.map { it[booleanPreferencesKey(accountKey(accountId, "tor_enabled"))] ?: false }

    val chatTheme: Flow<ChatThemeData> = context.dataStore.data.map { preferences ->
        ChatThemeData(
            themeColor = preferences[Keys.THEME_COLOR]?.let { Color(it.toInt()) } ?: Color.Unspecified,
            backgroundImageUri = preferences[Keys.BACKGROUND_IMAGE_URI],
            backgroundOpacity = (preferences[Keys.BACKGROUND_OPACITY]?.toInt() ?: 100) / 100f
        )
    }

    suspend fun setLanEnabled(accountId: String, enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[booleanPreferencesKey(accountKey(accountId, "lan_enabled"))] = enabled
        }
    }

    suspend fun setTorEnabled(accountId: String, enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[booleanPreferencesKey(accountKey(accountId, "tor_enabled"))] = enabled
        }
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
