package io.hydrion.hub.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.hydrion.hub.R
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.WorkspaceLoad
import io.hydrion.hub.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ContainerSettings(vm: HubViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val loaded by vm.workspaceLoad.collectAsStateWithLifecycle()
    val store = vm.environments
    val scope = rememberCoroutineScope()
    var catalog by remember { mutableStateOf<ProotCatalog?>(null) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var form by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<ProotCheck?>(null) }
    LaunchedEffect(loadAttempt) {
        error = false
        try { catalog = store.catalog() }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { error = true }
    }
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = false
        scope.launch {
            try { action() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { error = true }
            finally { busy = false }
        }
    }
    fun edit(kind: String, id: String?) {
        if (busy) return
        editing = id
        result = null
        error = false
        form = kind
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.settings_back)) }
            Text(stringResource(R.string.settings_environments), Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (catalog?.containers?.isNotEmpty() == true) IconButton({ edit("environment", null) }, enabled = !busy) {
                Icon(Icons.Outlined.Add, stringResource(R.string.env_add))
            }
        }
        val snapshot = catalog
        if (snapshot == null) {
            Text(stringResource(if (error) R.string.runtime_error else R.string.env_loading), Modifier.padding(16.dp))
            if (error) TextButton({ loadAttempt++ }) { Text(stringResource(R.string.env_retry)) }
        } else LazyColumn(contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp)) {
            item { RuntimeHeading(stringResource(R.string.container_section)) }
            if (snapshot.containers.isEmpty()) item {
                RuntimeRow(R.drawable.lucide_boxes, stringResource(R.string.container_add), null, enabled = !busy,
                    onClick = { edit("container", null) })
            }
            itemsIndexed(snapshot.containers, key = { _, c -> "container-${c.id}" }) { _, container ->
                RuntimeRow(R.drawable.lucide_boxes, container.name.ifBlank { stringResource(R.string.container_section) },
                    stringResource(if (container.rootfsSha256 == null) R.string.container_awaiting else R.string.container_registered),
                    enabled = !busy, onClick = { edit("container", container.id) })
            }
            item { Spacer(Modifier.height(12.dp)); RuntimeHeading(stringResource(R.string.container_environments)) }
            if (snapshot.environments.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(painterResource(R.drawable.lucide_folder_code), null, Modifier.size(44.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .30f))
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.env_none), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(if (snapshot.containers.isEmpty()) R.string.env_register_first else R.string.env_empty_hint),
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (snapshot.containers.isNotEmpty()) TextButton({ edit("environment", null) }, enabled = !busy) {
                        Text(stringResource(R.string.env_add))
                    }
                }
            }
            itemsIndexed(snapshot.environments, key = { _, e -> "environment-${e.id}" }) { index, env ->
                val current = state.current
                val bound = current.environmentId == env.id
                val bindingLabel = stringResource(if (bound) R.string.env_unbinding else R.string.env_binding)
                val container = snapshot.containers.first { it.id == env.containerId }
                val containerName = container.name.ifBlank { stringResource(R.string.container_section) }
                RuntimeRow(R.drawable.lucide_folder_code, env.name.ifBlank { env.id.take(8) },
                    "$containerName · ${env.guestCwd}", enabled = !busy, onClick = { edit("environment", env.id) },
                    first = index == 0, last = index == snapshot.environments.lastIndex) {
                    Checkbox(checked = bound, enabled = !busy && loaded == WorkspaceLoad.Ready,
                        modifier = Modifier.semantics { contentDescription = bindingLabel },
                        onCheckedChange = { select ->
                            perform { vm.bindEnvironment(current.id, env.id.takeIf { select }) }
                        })
                }
            }
            if (error && form == null) item { Text(stringResource(R.string.runtime_error), color = MaterialTheme.colorScheme.error) }
        }
    }
    val snapshot = catalog ?: return
    val kind = form ?: return
    val container = snapshot.containers.find { it.id == editing }
    val environment = snapshot.environments.find { it.id == editing }
    RuntimeEditor(kind == "container", container, environment, snapshot.containers, busy, error, result,
        onCancel = { form = null; error = false },
        onCheck = environment?.let { e -> { perform { result = store.checkRuntime(e.id) } } },
        onSave = { name, digest, containerId ->
            perform {
                if (kind == "container") store.saveContainer(editing, name, digest)
                else if (environment == null) store.createEnvironment(requireNotNull(containerId), name)
                else store.renameEnvironment(environment.id, name)
                catalog = store.catalog()
                form = null
            }
        })
}

@Composable
private fun RuntimeHeading(label: String) {
    Text(label, Modifier.padding(start = 4.dp, bottom = 8.dp), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun RuntimeRow(icon: Int, label: String, subtitle: String?, enabled: Boolean, onClick: () -> Unit,
                       first: Boolean = true, last: Boolean = true, trailing: (@Composable () -> Unit)? = null) {
    val top = if (first) 16.dp else 0.dp
    val bottom = if (last) 16.dp else 0.dp
    Surface(onClick = onClick, enabled = enabled, color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)) {
        Column {
            if (!first) HorizontalDivider()
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(icon), null, Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
                    if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (trailing == null) Icon(painterResource(R.drawable.lucide_chevron_right), null, Modifier.size(18.dp))
                else trailing()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuntimeEditor(isContainer: Boolean, container: ProotContainer?, environment: ProotEnvironment?,
                          containers: List<ProotContainer>, busy: Boolean, error: Boolean, result: ProotCheck?,
                          onCancel: () -> Unit, onCheck: (() -> Unit)?, onSave: (String, String?, String?) -> Unit) {
    val editing = if (isContainer) container != null else environment != null
    var name by rememberSaveable { mutableStateOf(if (isContainer) container?.name.orEmpty() else environment?.name.orEmpty()) }
    var digest by rememberSaveable { mutableStateOf(container?.rootfsSha256.orEmpty()) }
    var target by rememberSaveable { mutableStateOf(environment?.containerId ?: containers.firstOrNull()?.id) }
    val fingerprint = digest.trim().lowercase().ifBlank { null }
    val digestValid = !isContainer || fingerprint == null || rootfsDigest.matches(fingerprint)
    ModalBottomSheet(onDismissRequest = { if (!busy) onCancel() }) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(if (isContainer && editing) R.string.container_edit else if (isContainer) R.string.container_add
                else if (editing) R.string.env_edit else R.string.env_add), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                label = { Text(stringResource(R.string.runtime_name)) })
            if (isContainer) {
                OutlinedTextField(digest, { digest = it }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                    label = { Text(stringResource(R.string.runtime_expected)) }, isError = !digestValid,
                    supportingText = { Text(stringResource(if (digestValid) R.string.runtime_expected_hint else R.string.runtime_digest_invalid)) })
            } else {
                Text(stringResource(R.string.container_section), fontWeight = FontWeight.Medium)
                for (c in containers.filter { !editing || it.id == target }) {
                    val label = c.name.ifBlank { "${stringResource(R.string.container_section)} · ${c.id.take(8)}" }
                    if (!editing && containers.size > 1) {
                        FilterChip(selected = c.id == target, onClick = { target = c.id }, label = { Text(label) }, enabled = !busy)
                    } else Text(label)
                }
                Text(stringResource(R.string.env_mount), fontWeight = FontWeight.Medium)
                Text(environment?.guestCwd ?: "/workspace")
                if (onCheck != null) OutlinedButton(onCheck, enabled = !busy) { Text(stringResource(R.string.env_check)) }
                if (result != null) Text(stringResource(checkLabel(result)), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (error) Text(stringResource(R.string.runtime_error), color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onCancel, enabled = !busy) { Text(stringResource(R.string.settings_cancel)) }
                Button({ onSave(name.trim(), fingerprint, target) },
                    enabled = !busy && validRuntimeName(name.trim()) && digestValid && (isContainer || target != null)) {
                    Text(stringResource(R.string.settings_save))
                }
            }
        }
    }
}

private fun checkLabel(check: ProotCheck): Int = when (check) {
    ProotCheck.Outcome.LauncherResponded -> R.string.env_probe_ok
    ProotCheck.Outcome.IncompatibleAbi -> R.string.env_bad_abi
    ProotCheck.Outcome.LaunchFailed -> R.string.env_probe_error
    ProotCheck.Outcome.TimedOut -> R.string.env_timed_out
    ProotCheck.Outcome.CleanupIncomplete -> R.string.env_cleanup_incomplete
    is ProotCheck.Unavailable -> when (check.reason) {
        ProotPreparation.Reason.RuntimeNotPackaged -> R.string.env_missing_tools
        ProotPreparation.Reason.IncompleteRootfs, ProotPreparation.Reason.MissingGuestExecutable -> R.string.env_rootfs_invalid
        ProotPreparation.Reason.UntrustedRootfs -> R.string.env_untrusted
        ProotPreparation.Reason.MissingWorkspace, ProotPreparation.Reason.MissingWorkingDirectory -> R.string.env_workspace_invalid
        ProotPreparation.Reason.UnknownEnvironment, ProotPreparation.Reason.UnsafeOrUnreadablePath -> R.string.env_unsafe
    }
}
