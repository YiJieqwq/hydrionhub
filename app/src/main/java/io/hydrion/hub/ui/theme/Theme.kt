package io.hydrion.hub.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import io.hydrion.hub.R
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object HubStyle {
    val Background = Color.Black
    val Surface = Color(0xFF222222)
    val Raised = Color(0xFF303030)
    val Border = Color(0xFF414141)
    val Blue = Color(0xFF3566F0)
    val ComposerHint = Color(0xFFAFAFAF)
    val ComposerBorder = Color(0xFF4F4F4F)
    val SettingsOutline = BorderStroke(1.dp, ComposerBorder)
    val PageMargin = 16.dp
    val ControlIcon = 20.dp
    val ComposerActionDiameter = 32.dp
    val CardRadius = 24.dp
}

private val NightColors = darkColorScheme(
    primary = HubStyle.Blue,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF263754),
    onPrimaryContainer = Color(0xFFD8E4FF),
    secondary = Color(0xFFCCCCCC),
    onSecondary = Color(0xFF1D1D1D),
    background = HubStyle.Background,
    onBackground = Color(0xFFF5F5F5),
    surface = HubStyle.Surface,
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = HubStyle.Raised,
    onSurfaceVariant = Color(0xFFBFBFBF),
    surfaceContainerLowest = HubStyle.Background,
    surfaceContainerLow = Color(0xFF191919),
    surfaceContainer = HubStyle.Surface,
    surfaceContainerHigh = HubStyle.Raised,
    surfaceContainerHighest = Color(0xFF393939),
    outline = Color(0xFF858585),
    outlineVariant = HubStyle.Border,
    error = Color(0xFFFFA49D)
)

private val ProviderColors = NightColors.copy(
    primary = Color(0xFFC0BCF2),
    onPrimary = Color(0xFF28233D),
    primaryContainer = Color(0xFF343242),
    onPrimaryContainer = Color(0xFFE0DCF9),
    surfaceContainerLow = Color(0xFF1E1E1E)
)

@Composable
internal fun ProviderTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ProviderColors, typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes, content = content)
}

@Composable
fun HydrionTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NightColors,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(HubStyle.CardRadius),
            extraLarge = RoundedCornerShape(30.dp)
        ),
        content = content
    )
}

@Composable
fun IonMark(edge: Dp, modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.ic_hydrion), contentDescription = null, modifier = modifier.size(edge))
}
