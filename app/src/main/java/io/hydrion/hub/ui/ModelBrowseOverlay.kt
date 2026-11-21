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
internal class ModelBrowseMotion(private val picker: ChatModelPickerState, private val restoring: State<Boolean>) {
    private val clock = PanelTravelClock()
    private var lastPage by mutableStateOf<ChatModelPickerState.Page?>(null)
    val progress: Float get() = if (restoring.value && picker.modelListNavigation) 1f else clock.progress
    val page by derivedStateOf {
        if (picker.modelListNavigation) picker.page else lastPage.takeUnless { clock.atStart }
    }
    val active by derivedStateOf { page != null }
    val interactive by derivedStateOf { picker.modelListNavigation && !restoring.value && clock.atEnd }

    fun retain(page: ChatModelPickerState.Page) { lastPage = page }

    suspend fun travel(open: Boolean, restoring: Boolean) {
        // Configuration covers an already-open search. Restore that endpoint without
        // replaying the ordinary draft-to-search animation after the cover disappears.
        if (restoring && open) clock.snapOpen() else clock.animateTo(open)
    }
}

@Composable
internal fun rememberModelBrowseMotion(picker: ChatModelPickerState, restoring: Boolean): ModelBrowseMotion {
    val restoreState = rememberUpdatedState(restoring)
    val state = remember(picker) { ModelBrowseMotion(picker, restoreState) }
    val open = picker.modelListNavigation
    val page = picker.page
    if (open && page != null) SideEffect { state.retain(page) }
    LaunchedEffect(state, open, restoring) { state.travel(open, restoring) }
    return state
}
