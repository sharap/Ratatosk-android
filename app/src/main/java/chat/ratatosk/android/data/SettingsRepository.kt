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
        val THEME_COLOR = longPreferencesKey("theme_color")
        val BACKGROUND_IMAGE_URI = stringPreferencesKey("background_image_uri")
        val BACKGROUND_OPACITY = longPreferencesKey("background_opacity") // Stored as Long(bits) or scaled Int
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val NOTIFICATIONS_SHOW_NAME = booleanPreferencesKey("notifications_show_name")
        val NOTIFICATIONS_SHOW_TEXT = booleanPreferencesKey("notifications_show_text")
    }

    val displayName: Flow<String?> = context.dataStore.data.map { it[Keys.DISPLAY_NAME] }

    val notificationsShowName: Flow<Boolean> = context.dataStore.data.map { it[Keys.NOTIFICATIONS_SHOW_NAME] ?: true }
    val notificationsShowText: Flow<Boolean> = context.dataStore.data.map { it[Keys.NOTIFICATIONS_SHOW_TEXT] ?: true }

    val lanEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[Keys.LAN_ENABLED] ?: false
    }

    val chatTheme: Flow<ChatThemeData> = context.dataStore.data.map { preferences ->
        ChatThemeData(
            themeColor = preferences[Keys.THEME_COLOR]?.let { Color(it.toInt()) } ?: Color.Unspecified,
            backgroundImageUri = preferences[Keys.BACKGROUND_IMAGE_URI],
            backgroundOpacity = (preferences[Keys.BACKGROUND_OPACITY]?.toInt() ?: 100) / 100f
        )
    }

    suspend fun setLanEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[Keys.LAN_ENABLED] = enabled
        }
    }

    suspend fun setDisplayName(name: String) {
        context.dataStore.edit { it[Keys.DISPLAY_NAME] = name }
    }

    suspend fun setNotificationsShowName(show: Boolean) {
        context.dataStore.edit { it[Keys.NOTIFICATIONS_SHOW_NAME] = show }
    }

    suspend fun setNotificationsShowText(show: Boolean) {
        context.dataStore.edit { it[Keys.NOTIFICATIONS_SHOW_TEXT] = show }
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
