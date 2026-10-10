package com.or1.agent.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.or1.agent.R

/**
 * Pure black background (continuous with the boot screens, see brand/), warm near-black surfaces, a white primary action
 * and a single terracotta accent used sparingly.
 */
object Palette {
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF262624)
    val SurfaceHigh = Color(0xFF30302E)
    val SurfaceSunken = Color(0xFF141413)
    val Border = Color(0xFF393835)
    val Text = Color(0xFFECEAE4)
    val TextDim = Color(0xFF9C9A92)
    val TextFaint = Color(0xFF6E6C66)
    val Placeholder = Color(0xFF8A8880)
    val Primary = Color(0xFFF4F3EE)
    val OnPrimary = Color(0xFF1A1918)
    val Accent = Color(0xFFD97757)
    val ApprovalBorder = Color(0xFF5C4A3C)
    val Error = Color(0xFFE5857A)
    val Success = Color(0xFF8FBF8A)
}

object Fonts {
    val Sans = FontFamily(
        Font(R.font.inter_regular, FontWeight.Normal),
        Font(R.font.inter_medium, FontWeight.Medium),
    )
    val Serif = FontFamily(
        Font(R.font.source_serif_regular, FontWeight.Normal),
        Font(R.font.source_serif_semibold, FontWeight.SemiBold),
    )
}

object Type {
    val Greeting = TextStyle(fontFamily = Fonts.Serif, fontSize = 30.sp, lineHeight = 38.sp, color = Palette.Text, letterSpacing = (-0.3).sp)
    /** Agent replies read like prose. */
    val Reply = TextStyle(fontFamily = Fonts.Serif, fontSize = 17.sp, lineHeight = 26.sp, color = Palette.Text)
    val Body = TextStyle(fontFamily = Fonts.Sans, fontSize = 16.sp, lineHeight = 23.sp, color = Palette.Text)
    val Input = TextStyle(fontFamily = Fonts.Sans, fontSize = 17.sp, lineHeight = 24.sp, color = Palette.Text)
    val Suggestion = TextStyle(fontFamily = Fonts.Sans, fontSize = 16.sp, lineHeight = 22.sp, color = Palette.Text)
    val Label = TextStyle(fontFamily = Fonts.Sans, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = Palette.Text)
    val Chip = TextStyle(fontFamily = Fonts.Sans, fontSize = 14.sp, color = Palette.Text)
    val Step = TextStyle(fontFamily = Fonts.Sans, fontSize = 14.sp, lineHeight = 20.sp, color = Palette.TextDim)
    val Thinking = TextStyle(fontFamily = Fonts.Sans, fontSize = 13.sp, lineHeight = 19.sp, fontStyle = FontStyle.Italic, color = Palette.TextFaint)
    val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, color = Palette.TextDim)
    val Caption = TextStyle(fontFamily = Fonts.Sans, fontSize = 12.sp, color = Palette.TextFaint)
}

@Composable
fun Or1Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Primary,
            onPrimary = Palette.OnPrimary,
            background = Palette.Background,
            surface = Palette.Surface,
            surfaceContainer = Palette.Surface,
            onBackground = Palette.Text,
            onSurface = Palette.Text,
            outline = Palette.Border,
        ),
        content = content,
    )
}

/** Small icon set, drawn from Material Symbols path data (Apache 2.0), to avoid pulling in the icons library. */
object Icons {
    val Menu = icon("M3,7h18v1.8H3z M3,15.2h11v1.8H3z")
    val Plus = icon("M11.15,4.5h1.7v6.65h6.65v1.7h-6.65v6.65h-1.7v-6.65H4.5v-1.7h6.65z")
    val Mic = icon(
        "M12,14c1.66,0 3,-1.34 3,-3V5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM11,5c0,-0.55 0.45,-1 1,-1s1,0.45 1,1v6" +
            "c0,0.55 -0.45,1 -1,1s-1,-0.45 -1,-1V5zM17,11c0,2.76 -2.24,5 -5,5s-5,-2.24 -5,-5H5c0,3.53 2.61,6.43 6,6.92V21h2v-3.08" +
            "c3.39,-0.49 6,-3.39 6,-6.92h-2z"
    )
    val Waveform = icon(
        "M3.3,10.5h1.5v3H3.3z M6.6,8h1.5v8H6.6z M9.9,5h1.5v14H9.9z M13.2,7.5h1.5v9h-1.5z M16.5,9.5h1.5v5h-1.5z M19.8,11h1.5v2h-1.5z"
    )
    val ArrowUp = icon("M11.15,19.5V7.75l-5.3,5.3L4.65,11.85L12,4.5l7.35,7.35l-1.2,1.2l-5.3,-5.3V19.5z")
    val Stop = icon("M7,7h10v10H7z")
    val NewChat = icon(
        "M5,21q-0.825,0 -1.412,-0.587Q3,19.825 3,19V5q0,-0.825 0.588,-1.413Q4.175,3 5,3h8.925l-2,2H5v14h14v-6.95l2,-2V19" +
            "q0,0.825 -0.587,1.413Q19.825,21 19,21zM9,15v-4.25l9.175,-9.175q0.3,-0.3 0.675,-0.45 0.375,-0.15 0.75,-0.15 0.4,0 0.763,0.15" +
            " 0.362,0.15 0.662,0.45L22.425,3q0.275,0.3 0.425,0.662Q23,4.025 23,4.4t-0.137,0.738q-0.138,0.362 -0.438,0.662L13.25,15z" +
            "M11,13h1.4l5.8,-5.8 -0.7,-0.7 -0.725,-0.7L11,11.575z"
    )
    val ExpandMore = icon("M16.59,8.59L12,13.17 7.41,8.59 6,10l6,6 6,-6z")
    val ExpandLess = icon("M12,8l-6,6 1.41,1.41L12,10.83l4.59,4.58L18,14z")
    val Check = icon("M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z")
    val Close = icon("M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z")
    val Back = icon("M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z")
    val OpenInFront = icon(
        "M19,19H5V5h7V3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2v-7h-2v7zM14,3v2h3.59l-9.83,9.83 1.41,1.41" +
            "L19,6.41V10h2V3h-7z"
    )
    val Screen = icon(
        "M17,1.01L7,1C5.9,1 5,1.9 5,3v18c0,1.1 0.9,2 2,2h10c1.1,0 2,-0.9 2,-2V3c0,-1.1 -0.9,-1.99 -2,-1.99zM17,19H7V5h10v14z"
    )
    val Bell = icon(
        "M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.89,2 2,2zM18,16v-5c0,-3.07 -1.64,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5" +
            "s-1.5,0.67 -1.5,1.5v0.68C7.63,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z"
    )
    val Battery = icon(
        "M15.67,4H14V2h-4v2H8.33C7.6,4 7,4.6 7,5.33v15.33C7,21.4 7.6,22 8.33,22h7.33c0.74,0 1.34,-0.6 1.34,-1.33V5.33C17,4.6 16.4,4 15.67,4z"
    )
    val Timer = icon(
        "M15,1H9v2h6V1zm-4,13h2V8h-2v6zm8.03,-6.61l1.42,-1.42c-0.43,-0.51 -0.9,-0.99 -1.41,-1.41l-1.42,1.42C16.07,4.74 14.12,4 12,4" +
            "c-4.97,0 -9,4.03 -9,9s4.02,9 9,9 9,-4.03 9,-9c0,-2.12 -0.74,-4.07 -1.97,-5.61zM12,20c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7" +
            " 7,3.13 7,7 -3.13,7 -7,7z"
    )
    val Moon = icon(
        "M12,3c-4.97,0 -9,4.03 -9,9s4.03,9 9,9 9,-4.03 9,-9c0,-0.46 -0.04,-0.92 -0.1,-1.36 -0.98,1.37 -2.58,2.26 -4.4,2.26" +
            " -2.98,0 -5.4,-2.42 -5.4,-5.4 0,-1.81 0.89,-3.42 2.26,-4.4C12.92,3.04 12.46,3 12,3z"
    )

    val Routine = icon(
        "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2zM12,20c-4.42,0 -8,-3.58 -8,-8" +
            "s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8zM12.5,7H11v6l5.25,3.15 0.75,-1.23 -4.5,-2.67z"
    )

    private fun icon(path: String): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
            .build()
}
