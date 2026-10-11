package app.zendiary.ui.theme

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zendiary.R
import kotlin.math.ceil
import kotlin.math.floor

private val Light = lightColorScheme(
    primary = Color(0xFF5E8566), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEBDC), onPrimaryContainer = Color(0xFF1F3A27),
    secondary = Color(0xFF8B7B5E), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEFE6D2), onSecondaryContainer = Color(0xFF3A3020),
    tertiary = Color(0xFF5F7388), tertiaryContainer = Color(0xFFDCE6F0),
    background = Color(0xFFF7F5EF), onBackground = Color(0xFF2B332D),
    surface = Color(0xFFFBFAF6), onSurface = Color(0xFF2B332D),
    surfaceVariant = Color(0xFFECE8DC), onSurfaceVariant = Color(0xFF5C645D),
    surfaceContainer = Color(0xFFF0EDE3), surfaceContainerHigh = Color(0xFFE9E5D9),
    outline = Color(0xFFB4B09F), outlineVariant = Color(0xFFDAD6C8),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9DC3A4), onPrimary = Color(0xFF12301B),
    primaryContainer = Color(0xFF2E4A36), onPrimaryContainer = Color(0xFFDCEBDC),
    secondary = Color(0xFFD1C29F), onSecondary = Color(0xFF362D17),
    secondaryContainer = Color(0xFF4A402A), onSecondaryContainer = Color(0xFFEFE6D2),
    tertiary = Color(0xFFA9BDD2), tertiaryContainer = Color(0xFF38485A),
    background = Color(0xFF151A17), onBackground = Color(0xFFE3E8E1),
    surface = Color(0xFF1A201D), onSurface = Color(0xFFE3E8E1),
    surfaceVariant = Color(0xFF2A332E), onSurfaceVariant = Color(0xFFB4BCB4),
    surfaceContainer = Color(0xFF212925), surfaceContainerHigh = Color(0xFF2A332E),
    outline = Color(0xFF6E776F), outlineVariant = Color(0xFF3A443E),
)

private val Zen = Typography().let {
    it.copy(
        headlineMedium = it.headlineMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium),
        titleLarge = it.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium),
        titleMedium = it.titleMedium.copy(fontFamily = FontFamily.Serif),
        bodyLarge = it.bodyLarge.copy(lineHeight = 26.sp),
    )
}

@Composable
fun ZenTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Zen, shapes = Shapes(
        small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp),
    ), content = content)
}

/** The five custom mood faces (original vector artwork in res/drawable/mood_*.xml). */
enum class Mood(val level: Int, val label: String, val color: Color, @DrawableRes val icon: Int) {
    AWFUL(1, "Awful", Color(0xFF7C8DB0), R.drawable.mood_1),
    BAD(2, "Bad", Color(0xFF93AEBE), R.drawable.mood_2),
    MEH(3, "Meh", Color(0xFFC8C2A0), R.drawable.mood_3),
    GOOD(4, "Good", Color(0xFFA8C79A), R.drawable.mood_4),
    RAD(5, "Radiant", Color(0xFFE3C16F), R.drawable.mood_5);

    companion object {
        fun of(level: Int): Mood = entries.firstOrNull { it.level == level } ?: MEH
        /** Smooth colour for fractional averages. */
        fun colorFor(avg: Float): Color {
            val a = avg.coerceIn(1f, 5f)
            val lo = floor(a).toInt(); val hi = ceil(a).toInt()
            return lerp(of(lo).color, of(hi).color, a - lo)
        }
    }
}

@Composable
fun MoodFace(level: Int, size: Dp, modifier: Modifier = Modifier) {
    val m = Mood.of(level)
    Image(painterResource(m.icon), contentDescription = m.label, modifier = modifier.size(size))
}
