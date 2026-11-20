package io.hydrion.hub.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReusableContentHost
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout

/** Keep the option nodes between visits, but forget their effects, scroll and expansion state. */
@Composable
internal fun RetainedToolOptions(active: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = { ReusableContentHost(active, content) }, modifier = modifier) { children, constraints ->
        if (active) {
            val page = children.single().measure(constraints)
            layout(page.width, page.height) { page.place(0, 0) }
        } else {
            layout(constraints.minWidth, constraints.minHeight) {}
        }
    }
}
