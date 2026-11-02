package com.viby.app.theme

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Dark palette: Spotify-like neutral surfaces with a Viby violet accent. */
object VibyColors {
    val Background = Color(0xFF000000)
    val Panel = Color(0xFF121212)
    val Elevated = Color(0xFF1F1F1F)
    val Highlight = Color(0xFF2A2A2A)
    val Hover = Color(0x1AFFFFFF)
    val Selected = Color(0x33FFFFFF)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFB3B3B3)
    val TextMuted = Color(0xFF7A7A7A)
    val Accent = Color(0xFF8B6CFF)
    val AccentBright = Color(0xFFA78BFF)
    val Danger = Color(0xFFFF5A6E)
    val Divider = Color(0xFF2A2A2A)

    /** Gradient pairs for generated cover placeholders. */
    val CoverGradients = listOf(
        Color(0xFF6D4AFF) to Color(0xFF2B1B6B),
        Color(0xFFFF6B6B) to Color(0xFF6B1B2B),
        Color(0xFF1ED6A3) to Color(0xFF0B4D3E),
        Color(0xFFFFB547) to Color(0xFF6B3B0B),
        Color(0xFF3AA0FF) to Color(0xFF0B2B6B),
        Color(0xFFFF5FD2) to Color(0xFF5B0B4D),
        Color(0xFF9BE15D) to Color(0xFF2B4D0B),
        Color(0xFF00C2D1) to Color(0xFF07414A),
    )
}

private val typography = Typography(
    displaySmall = TextStyle(fontSize = 56.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.5).sp),
    headlineMedium = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun VibyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = VibyColors.Accent,
            onPrimary = Color.White,
            background = VibyColors.Background,
            surface = VibyColors.Panel,
            surfaceVariant = VibyColors.Elevated,
            surfaceContainer = VibyColors.Elevated,
            surfaceContainerHigh = VibyColors.Highlight,
            onSurface = VibyColors.TextPrimary,
            onSurfaceVariant = VibyColors.TextSecondary,
            outline = VibyColors.Divider,
            error = VibyColors.Danger,
        ),
        typography = typography,
    ) {
        CompositionLocalProvider(
            LocalScrollbarStyle provides ScrollbarStyle(
                minimalHeight = 32.dp,
                thickness = 8.dp,
                shape = RoundedCornerShape(4.dp),
                hoverDurationMillis = 200,
                unhoverColor = Color.White.copy(alpha = 0.18f),
                hoverColor = Color.White.copy(alpha = 0.45f),
            ),
            content = content,
        )
    }
}
