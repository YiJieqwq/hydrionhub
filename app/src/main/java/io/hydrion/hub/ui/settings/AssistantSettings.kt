package io.hydrion.hub.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.hydrion.hub.R
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.WorkspaceLoad
import io.hydrion.hub.ui.sidebar.AssistantNameDialog
import io.hydrion.hub.ui.sidebar.AssistantRow

@Composable
internal fun AssistantSettings(vm: HubViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val load by vm.workspaceLoad.collectAsStateWithLifecycle()
    val ready = load == WorkspaceLoad.Ready
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.settings_back)) }
            Text(stringResource(R.string.settings_assistants), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton({ adding = true }, enabled = ready) {
                Icon(Icons.Outlined.Add, stringResource(R.string.assistant_add))
            }
        }
        LazyColumn {
            items(state.assistants, key = { it.id }) { assistant ->
                AssistantRow(assistant, assistant.id == state.current.assistantId, ready, showSelection = true,
                    onSelect = { vm.selectAssistant(assistant.id) }, onEdit = { editingId = assistant.id })
            }
        }
    }
    if (adding) AssistantNameDialog(null, { adding = false }, vm::addAssistant)
    state.assistants.find { it.id == editingId }?.let { assistant ->
        AssistantNameDialog(assistant, { editingId = null }) { vm.renameAssistant(assistant.id, it) }
    }
}
