package io.hydrion.hub.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.hydrion.hub.R
import io.hydrion.hub.localtools.AssistantFeatureFile
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.WorkspaceLoad
import io.hydrion.hub.provider.ModelSettingsFrame
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun Context.assistantFeatureDirectory() = File(noBackupFilesDir, "assistant-features")

@Composable
internal fun ScopedToolPage(vm: HubViewModel, title: Int, onBack: () -> Unit, fixedOwner: String? = null, content: @Composable (File, String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val load by vm.workspaceLoad.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf(false) }
    val owner = state.assistants.firstOrNull { it.id == selected } ?: state.assistants.firstOrNull { it.id == state.current.assistantId }
    val context = LocalContext.current
    val root = remember(context) { context.assistantFeatureDirectory() }
    if (fixedOwner != null) {
        when {
            load == WorkspaceLoad.Reading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            load != WorkspaceLoad.Ready || state.assistants.none { it.id == fixedOwner } ->
                Text(stringResource(R.string.feature_unavailable), Modifier.padding(16.dp))
            else -> key(fixedOwner) { content(root, fixedOwner) }
        }
        return
    }
    ModelSettingsFrame(stringResource(title), onBack) {
        when {
            load == WorkspaceLoad.Reading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            owner == null || load != WorkspaceLoad.Ready -> Text(stringResource(R.string.feature_unavailable), Modifier.padding(16.dp))
            else -> {
                Box(Modifier.padding(horizontal = 12.dp)) {
                    TextButton(onClick = { choosing = true }) {
                        Text(stringResource(R.string.feature_assistant, owner.name))
                        Icon(Icons.Outlined.ArrowDropDown, null)
                    }
                    DropdownMenu(choosing, { choosing = false }) {
                        state.assistants.forEach { assistant ->
                            DropdownMenuItem(text = { Text(assistant.name) }, onClick = { selected = assistant.id; choosing = false })
                        }
                    }
                }
                key(owner.id) { content(root, owner.id) }
            }
        }
    }
}

@Composable
internal fun <T : Any> FeatureContent(file: AssistantFeatureFile<T>, content: @Composable (T, Boolean, ((T) -> T) -> Unit) -> Unit) {
    var data by remember(file) { mutableStateOf<T?>(null) }
    var error by remember(file) { mutableStateOf(false) }
    var reload by remember(file) { mutableIntStateOf(0) }
    var saving by remember(file) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(file, reload) {
        try { data = file.read(); error = false }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { data = null; error = true }
    }
    if (error) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.feature_unavailable), Modifier.weight(1f))
        TextButton({ error = false; reload++ }) { Text(stringResource(R.string.feature_reload)) }
    }
    val current = data
    if (current != null) content(current, saving) { change ->
        if (!saving) {
            saving = true
            scope.launch {
                try { data = file.update(change); error = false }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { error = true }
                finally { saving = false }
            }
        }
    } else if (!error) LinearProgressIndicator(Modifier.fillMaxWidth())
}
