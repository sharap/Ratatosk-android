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
        val OUTGOING_BUBBLE_COLOR = longPreferencesKey("outgoing_bubble_color")
        val BACKGROUND_IMAGE_URI = stringPreferencesKey("background_image_uri")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
    }

    val displayName: Flow<String?> = context.dataStore.data.map { it[Keys.DISPLAY_NAME] }

    val lanEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[Keys.LAN_ENABLED] ?: false
    }

    val chatTheme: Flow<ChatThemeData> = context.dataStore.data.map { preferences ->
        ChatThemeData(
            backgroundImageUri = preferences[Keys.BACKGROUND_IMAGE_URI],
            outgoingBubbleColor = preferences[Keys.OUTGOING_BUBBLE_COLOR]?.let { Color(it.toInt()) } ?: Color.Unspecified
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

    suspend fun updateChatTheme(data: ChatThemeData) {
        context.dataStore.edit { preferences ->
            if (data.backgroundImageUri != null) {
                preferences[Keys.BACKGROUND_IMAGE_URI] = data.backgroundImageUri
            } else {
                preferences.remove(Keys.BACKGROUND_IMAGE_URI)
            }
            
            if (data.outgoingBubbleColor != Color.Unspecified) {
                preferences[Keys.OUTGOING_BUBBLE_COLOR] = data.outgoingBubbleColor.toArgb().toLong()
            } else {
                preferences.remove(Keys.OUTGOING_BUBBLE_COLOR)
            }
        }
    }
}
