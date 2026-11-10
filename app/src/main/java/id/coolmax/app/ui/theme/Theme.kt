package id.coolmax.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Tokens for the neumorphic design language: one base surface plus the highlight/shadow pair that is
 * used to extrude (raised) or emboss (inset) every component.
 */
data class NeuPalette(
    val base: Color,
    val highlight: Color,
    val shadow: Color,
    val accent: Color,
    /** Text/icon color that sits on [accent]. Light ink in the dark theme, white in the light one. */
    val onAccent: Color,
    val onBase: Color,
    val muted: Color,
    val danger: Color,
    val warning: Color,
    val success: Color,
    val isDark: Boolean
)

val LightNeuPalette = NeuPalette(
    base = NeuBaseLight,
    highlight = NeuHighlightLight,
    shadow = NeuShadowLight,
    accent = NeuAccentLight,
    onAccent = Color.White,
    onBase = NeuOnBaseLight,
    muted = NeuMutedLight,
    danger = NeuDangerLight,
    warning = NeuWarningLight,
    success = NeuSuccessLight,
    isDark = false
)

val DarkNeuPalette = NeuPalette(
    base = NeuBaseDark,
    highlight = NeuHighlightDark,
    shadow = NeuShadowDark,
    accent = NeuAccentDark,
    // The dark accent is a light periwinkle, so white text on it fails AA (2.3:1); dark ink gives 8:1.
    onAccent = Color(0xFF10141B),
    onBase = NeuOnBaseDark,
    muted = NeuMutedDark,
    danger = NeuDangerDark,
    warning = NeuWarningDark,
    success = NeuSuccessDark,
    isDark = true
)

val LocalNeuPalette = staticCompositionLocalOf { LightNeuPalette }

private val LightColorScheme = lightColorScheme(
    primary = NeuAccentLight,
    onPrimary = Color.White,
    primaryContainer = NeuBaseLight,
    onPrimaryContainer = NeuAccentLight,
    secondary = NeuMutedLight,
    onSecondary = Color.White,
    secondaryContainer = NeuBaseLight,
    onSecondaryContainer = NeuOnBaseLight,
    background = NeuBaseLight,
    onBackground = NeuOnBaseLight,
    surface = NeuBaseLight,
    onSurface = NeuOnBaseLight,
    surfaceVariant = NeuBaseLight,
    onSurfaceVariant = NeuMutedLight,
    surfaceContainer = NeuBaseLight,
    surfaceContainerHigh = NeuBaseLight,
    surfaceContainerHighest = NeuBaseLight,
    outline = NeuShadowLight,
    outlineVariant = NeuHighlightLight,
    error = NeuDangerLight,
    onError = Color.White,
    errorContainer = NeuBaseLight,
    onErrorContainer = NeuDangerLight
)

private val DarkColorScheme = darkColorScheme(
    primary = NeuAccentDark,
    onPrimary = Color(0xFF10141B),
    primaryContainer = NeuBaseDark,
    onPrimaryContainer = NeuAccentDark,
    secondary = NeuMutedDark,
    onSecondary = Color(0xFF10141B),
    secondaryContainer = NeuBaseDark,
    onSecondaryContainer = NeuOnBaseDark,
    background = NeuBaseDark,
    onBackground = NeuOnBaseDark,
    surface = NeuBaseDark,
    onSurface = NeuOnBaseDark,
    surfaceVariant = NeuBaseDark,
    onSurfaceVariant = NeuMutedDark,
    surfaceContainer = NeuBaseDark,
    surfaceContainerHigh = NeuBaseDark,
    surfaceContainerHighest = NeuBaseDark,
    outline = NeuShadowDark,
    outlineVariant = NeuHighlightDark,
    error = NeuDangerDark,
    onError = Color(0xFF10141B),
    errorContainer = NeuBaseDark,
    onErrorContainer = NeuDangerDark
)

/**
 * Neumorphism is the whole point of the UI, so dynamic (Material You) color is intentionally off:
 * wallpaper-derived colors would break the highlight/shadow relationship the style depends on.
 */
@Composable
fun CoolMaxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val palette = if (darkTheme) DarkNeuPalette else LightNeuPalette
    CompositionLocalProvider(LocalNeuPalette provides palette) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            content = content
        )
    }
}
