package dev.localai.chat.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    primaryContainer = AccentContainer,
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = TextSecondary,
    onSecondary = Bg,
    background = Bg,
    onBackground = TextPrimary,
    surface = Bg,
    onSurface = TextPrimary,
    surfaceVariant = Surface3,
    onSurfaceVariant = TextSecondary,
    surfaceDim = Bg,
    surfaceBright = Surface4,
    surfaceContainerLowest = Color(0xFF07080A),
    surfaceContainerLow = Surface1,
    surfaceContainer = Surface2,
    surfaceContainerHigh = Surface3,
    surfaceContainerHighest = Surface4,
    outline = Outline,
    outlineVariant = OutlineSoft,
    error = ErrorRed,
    onError = Bg,
    inverseSurface = TextPrimary,
    inverseOnSurface = Bg,
    inversePrimary = AccentDeep,
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** App sempre escuro (visual profissional); sem dynamic color para manter a identidade. */
@Composable
fun LocalAITheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography,
        shapes = AppShapes,
        content = content,
    )
}
