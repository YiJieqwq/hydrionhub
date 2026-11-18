package io.hydrion.hub.ui.theme

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

private val capsuleRim = Brush.verticalGradient(
    0f to HubStyle.ComposerBorder,
    .50f to Color(0xFF424242),
    1f to Color(0xFF4A4A4A)
)

/** A fine rim with a softer inner edge, independent of the size of the control. */
internal fun Modifier.capsuleEdge(shape: Shape = CircleShape): Modifier =
    border(1.dp, capsuleRim, shape).border(2.dp, Color.White.copy(alpha = .025f), shape)
