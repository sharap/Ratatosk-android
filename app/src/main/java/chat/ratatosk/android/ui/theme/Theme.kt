package chat.ratatosk.android.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.graphics.luminance

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

/**
 * Цвет «на связи» для текущей темы. Вынесен из экранов: одна и та же точка
 * стояла в двух десятках мест, каждый раз своим значением.
 */
val successColor: Color
    @Composable get() = if (isSystemInDarkTheme()) SuccessDark else SuccessLight

@Composable
fun RatatoskTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    themeColor: Color = Color.Unspecified,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        themeColor != Color.Unspecified -> {
            val base = if (darkTheme) DarkColorScheme else LightColorScheme
            val isNeutral = themeColor == Color.Black || themeColor == Color.White || themeColor == Color.Gray
            val isDark = themeColor.luminance() < 0.5f
            
            base.copy(
                primary = themeColor,
                primaryContainer = if (isNeutral) {
                    if (darkTheme) Color.DarkGray else Color.LightGray
                } else themeColor,
                onPrimaryContainer = if (isDark) Color.White else Color.Black,
                secondary = themeColor.copy(alpha = 0.7f),
                onPrimary = if (isDark) Color.White else Color.Black,
                outline = themeColor.copy(alpha = 0.5f)
            )
        }
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
