package dev.idlegamer.app.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.idlegamer.app.domain.repository.ThemeMode

// Own palette: deep navy + cyan accent. Inspired by dark game-client aesthetics, not a copy of any brand.
private val Navy950 = Color(0xFF0E141B)
private val Navy900 = Color(0xFF151C25)
private val Navy800 = Color(0xFF1C2633)
private val Navy700 = Color(0xFF26354A)
private val Cyan400 = Color(0xFF5CC8F2)
private val Cyan700 = Color(0xFF1C7FA8)
private val Lime400 = Color(0xFFA4D65E)
private val Amber400 = Color(0xFFF2B84B)
private val Red400 = Color(0xFFF2706B)

private val DarkColors = darkColorScheme(
    primary = Cyan400,
    onPrimary = Navy950,
    primaryContainer = Navy700,
    onPrimaryContainer = Color(0xFFD4EEFB),
    secondary = Lime400,
    onSecondary = Navy950,
    tertiary = Amber400,
    background = Navy950,
    onBackground = Color(0xFFE3E8EE),
    surface = Navy900,
    onSurface = Color(0xFFE3E8EE),
    surfaceVariant = Navy800,
    onSurfaceVariant = Color(0xFFAAB6C4),
    surfaceContainer = Navy800,
    surfaceContainerHigh = Navy700,
    outline = Color(0xFF4A5C72),
    error = Red400,
    onError = Navy950,
)

private val LightColors = lightColorScheme(
    primary = Cyan700,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEBF8),
    secondary = Color(0xFF4F7A1F),
    tertiary = Color(0xFF9A6A00),
    background = Color(0xFFF6F8FA),
    surface = Color.White,
    surfaceVariant = Color(0xFFE6ECF2),
    error = Color(0xFFB3261E),
)

@Immutable
data class StatusColors(val ok: Color, val warning: Color, val error: Color, val neutral: Color)

val LocalStatusColors = staticCompositionLocalOf {
    StatusColors(ok = Lime400, warning = Amber400, error = Red400, neutral = Color(0xFF8A99AB))
}

@Composable
fun IdleGamerTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
