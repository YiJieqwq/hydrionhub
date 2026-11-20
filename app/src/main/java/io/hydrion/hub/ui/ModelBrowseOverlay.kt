package io.hydrion.hub.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import io.hydrion.hub.provider.ChatModelPickerState
import io.hydrion.hub.ui.composer.PanelTravelClock

@Stable
internal class ModelBrowseMotion(private val picker: ChatModelPickerState, private val hidden: State<Boolean>) {
    private val clock = PanelTravelClock()
    private var lastPage by mutableStateOf<ChatModelPickerState.Page?>(null)
    val progress: Float get() = if (hidden.value) 0f else clock.progress
    val page by derivedStateOf {
        if (picker.modelListNavigation) picker.page else lastPage.takeUnless { clock.atStart }
    }
    val active by derivedStateOf { page != null }
    val interactive by derivedStateOf { picker.modelListNavigation && !hidden.value && clock.atEnd }

    fun retain(page: ChatModelPickerState.Page) { lastPage = page }

    suspend fun travel(open: Boolean, restoring: Boolean) {
        // During a config return the source remains mounted for scroll/focus restoration.
        // Only the existing measured composer/IME completion gate can release this hidden pose.
        if (restoring) clock.snapClosed() else clock.animateTo(open)
    }
}

@Composable
internal fun rememberModelBrowseMotion(picker: ChatModelPickerState, restoring: Boolean): ModelBrowseMotion {
    val hidden = rememberUpdatedState(restoring)
    val state = remember(picker) { ModelBrowseMotion(picker, hidden) }
    val open = picker.modelListNavigation
    val page = picker.page
    if (open && page != null) SideEffect { state.retain(page) }
    LaunchedEffect(state, open, restoring) { state.travel(open, restoring) }
    return state
}
