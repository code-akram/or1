package com.or1.agent.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Warm dark palette with a single terracotta accent. */
object Palette {
    val Background = Color(0xFF1F1E1C)
    val Surface = Color(0xFF2A2926)
    val SurfaceHigh = Color(0xFF34332F)
    val Border = Color(0xFF3D3C37)
    val Text = Color(0xFFECEAE4)
    val TextDim = Color(0xFF9C9A92)
    val TextFaint = Color(0xFF6E6C66)
    val Accent = Color(0xFFD97757)
    val OnAccent = Color(0xFF1F1E1C)
    val ApprovalBackground = Color(0xFF2C2620)
    val ApprovalBorder = Color(0xFF5C4A34)
    val Error = Color(0xFFE5857A)
    val Success = Color(0xFF8FBF8A)
}

object Type {
    /** Agent replies read like prose. */
    val Reply = TextStyle(fontFamily = FontFamily.Serif, fontSize = 17.sp, lineHeight = 26.sp, color = Palette.Text)
    val Body = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, color = Palette.Text)
    val Step = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, color = Palette.TextDim)
    val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, color = Palette.TextFaint)
    val Caption = TextStyle(fontSize = 12.sp, color = Palette.TextFaint)
}

@Composable
fun Or1Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Accent,
            onPrimary = Palette.OnAccent,
            background = Palette.Background,
            surface = Palette.Background,
            onBackground = Palette.Text,
            onSurface = Palette.Text,
            outline = Palette.Border,
        ),
        content = content,
    )
}

/** Small icon set, drawn from Material icon path data (Apache 2.0), to avoid pulling in the icons library. */
object Icons {
    val ArrowUp = icon("M4,12l1.41,1.41L11,7.83V20h2V7.83l5.58,5.59L20,12l-8,-8 -8,8z")
    val Stop = icon("M7,7h10v10H7z")
    val NewChat = icon(
        "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34" +
            "c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z"
    )
    val ExpandMore = icon("M16.59,8.59L12,13.17 7.41,8.59 6,10l6,6 6,-6z")
    val ExpandLess = icon("M12,8l-6,6 1.41,1.41L12,10.83l4.59,4.58L18,14z")
    val Check = icon("M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z")
    val Close = icon("M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z")

    private fun icon(path: String): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
            .build()
}
