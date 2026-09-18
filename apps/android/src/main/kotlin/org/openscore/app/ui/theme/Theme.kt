package org.openscore.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/*
 * The brand palette: the launcher icon's green (#006C43, HCT hue 161) as the primary key colour,
 * with every Material role filled in from the same tonal palettes so nothing falls back to the
 * library's purple baseline (secondaryContainer picks selected chips and the navigation
 * indicator; surfaceContainer* tint every card). Generated once from material-color-utilities:
 * primary chroma 44, secondary 16, tertiary hue +60 at chroma 24, neutral 6, neutral-variant 8,
 * standard error palette; light roles at the M3 default tones (P40/P90/N98/N96…), dark at
 * (P80/P30/N6/N10…). Keep the two lists in step if a role is retuned.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF006D44),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9BF5C0),
    onPrimaryContainer = Color(0xFF002111),
    inversePrimary = Color(0xFF7FD9A6),
    secondary = Color(0xFF4E6355),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD0E8D6),
    onSecondaryContainer = Color(0xFF0B1F14),
    tertiary = Color(0xFF3C6471),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBFE9F9),
    onTertiaryContainer = Color(0xFF001F27),
    background = Color(0xFFF6FBF4),
    onBackground = Color(0xFF171D19),
    surface = Color(0xFFF6FBF4),
    onSurface = Color(0xFF171D19),
    surfaceVariant = Color(0xFFDCE5DC),
    onSurfaceVariant = Color(0xFF404942),
    surfaceTint = Color(0xFF006D44),
    inverseSurface = Color(0xFF2C322D),
    inverseOnSurface = Color(0xFFEDF2EB),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF717972),
    outlineVariant = Color(0xFFC0C9C0),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF6FBF4),
    surfaceDim = Color(0xFFD6DBD5),
    surfaceContainer = Color(0xFFEAEFE9),
    surfaceContainerHigh = Color(0xFFE4EAE3),
    surfaceContainerHighest = Color(0xFFDFE4DD),
    surfaceContainerLow = Color(0xFFF0F5EE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD9A6),
    onPrimary = Color(0xFF003921),
    primaryContainer = Color(0xFF005232),
    onPrimaryContainer = Color(0xFF9BF5C0),
    inversePrimary = Color(0xFF006D44),
    secondary = Color(0xFFB5CCBB),
    onSecondary = Color(0xFF213528),
    secondaryContainer = Color(0xFF374B3E),
    onSecondaryContainer = Color(0xFFD0E8D6),
    tertiary = Color(0xFFA4CDDC),
    onTertiary = Color(0xFF043541),
    tertiaryContainer = Color(0xFF224C59),
    onTertiaryContainer = Color(0xFFBFE9F9),
    background = Color(0xFF0F1511),
    onBackground = Color(0xFFDFE4DD),
    surface = Color(0xFF0F1511),
    onSurface = Color(0xFFDFE4DD),
    surfaceVariant = Color(0xFF404942),
    onSurfaceVariant = Color(0xFFC0C9C0),
    surfaceTint = Color(0xFF7FD9A6),
    inverseSurface = Color(0xFFDFE4DD),
    inverseOnSurface = Color(0xFF2C322D),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8A938B),
    outlineVariant = Color(0xFF404942),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF353B36),
    surfaceDim = Color(0xFF0F1511),
    surfaceContainer = Color(0xFF1B211D),
    surfaceContainerHigh = Color(0xFF262B27),
    surfaceContainerHighest = Color(0xFF313632),
    surfaceContainerLow = Color(0xFF171D19),
    surfaceContainerLowest = Color(0xFF0A0F0C),
)

/**
 * Semantic colours the scoreboard needs beyond the Material roles: the in-play green, the break
 * purple, and the goal/card colours the event timeline uses. Each is used as small text and as a
 * tinted pill, so every value clears 4.5:1 against its theme's surface — the light set at tone
 * 42–48, the dark set at tone 80.
 */
@Immutable
class ScoreColors(
    val live: Color,
    val breakTime: Color,
    val goal: Color,
    val red: Color,
    val yellow: Color,
) {
    /** The saved/following state shares the app's positive, in-play green. */
    val favorite: Color get() = live
}

private val LightScoreColors = ScoreColors(
    live = Color(0xFF007247),
    breakTime = Color(0xFF9250A3),
    goal = Color(0xFF007240),
    red = Color(0xFFC33738),
    yellow = Color(0xFF9E6600),
)

private val DarkScoreColors = ScoreColors(
    live = Color(0xFF6FDBA1),
    breakTime = Color(0xFFF2AFFF),
    goal = Color(0xFF69DD95),
    red = Color(0xFFFFB3AE),
    yellow = Color(0xFFFFB956),
)

val LocalScoreColors = staticCompositionLocalOf { LightScoreColors }

/** The theme's score colours, read the way `MaterialTheme.colorScheme` is. */
val MaterialTheme.scoreColors: ScoreColors
    @Composable @ReadOnlyComposable get() = LocalScoreColors.current

@Composable
fun OpenScoreTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(LocalScoreColors provides if (darkTheme) DarkScoreColors else LightScoreColors) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}
