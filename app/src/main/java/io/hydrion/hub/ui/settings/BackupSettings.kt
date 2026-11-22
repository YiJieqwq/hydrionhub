package io.hydrion.hub.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.hydrion.hub.R
import io.hydrion.hub.model.*
import io.hydrion.hub.provider.Header
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun BackupSettings(vm: HubViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = vm.backups
    val scope = rememberCoroutineScope()
    var application by rememberSaveable { mutableStateOf(true) }
    var confirmReplace by remember { mutableStateOf(false) }
    var pendingRestart by remember { mutableStateOf(vm.appRestores.pendingRestart()) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    var staging by remember { mutableStateOf<File?>(null) }
    var inspected by remember { mutableStateOf<InspectedChatArchive?>(null) }
    var documentSource by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableIntStateOf(0) }
    var absent by remember { mutableStateOf(emptySet<String>()) }
    var undo by remember { mutableStateOf(false) }
    var canUndo by remember { mutableStateOf(false) }

    var operation by remember { mutableStateOf<Job?>(null) }
    fun release(dir: File) {
        CoroutineScope(Dispatchers.IO).launch { dir.deleteRecursively() }
    }
    fun clear() {
        val dir = staging ?: store.preparedExport(documentSource, application)?.parentFile
        staging = null
        inspected = null
        documentSource = null
        if (dir != null) release(dir)
    }
    DisposableEffect(Unit) {
        onDispose {
            if (documentSource == null) staging?.let { dir ->
                val job = operation
                if (job == null) release(dir) else job.invokeOnCompletion { release(dir) }
            }
        }
    }
    fun task(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        info = 0
        absent = emptySet()
        operation = scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (mismatch: ArchiveScopeMismatch) {
                info = if (mismatch.isApplicationArchive) R.string.backup_use_application else R.string.backup_use_chat
                clear()
            }
            catch (missing: UnreadableOriginals) { absent = missing.names; clear() }
            catch (_: Exception) { info = R.string.backup_failed; clear() }
            finally { busy = false }
        }
    }
    suspend fun stage(block: suspend (File) -> Unit) {
        val dir = withContext(Dispatchers.IO) { store.workDirectory() }
        try { block(dir); staging = dir }
        finally {
            if (staging != dir) withContext(NonCancellable + Dispatchers.IO) { dir.deleteRecursively() }
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val file = store.preparedExport(documentSource, application)
        if (uri == null) clear()
        else if (file == null) { clear(); info = R.string.backup_failed }
        else task {
            require(uri.scheme == "content" && uri.authority != context.packageName + ".images")
            withContext(Dispatchers.IO) {
                file.inputStream().use { input ->
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { input.copyTo(it) }
                }
            }
            clear()
            info = R.string.backup_saved
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        choosing = false
        if (uri != null) task {
            stage { dir ->
                val verified = withContext(Dispatchers.IO) { store.inspect(dir, uri, application) }
                try { if (!application) mergeBackup(vm.backupSnapshot(), verified.workspace) }
                catch (_: IllegalArgumentException) { info = R.string.backup_conflict; return@stage }
                inspected = verified
            }
            if (inspected == null) clear()
        }
    }
    LaunchedEffect(store, busy) {
        if (!busy) {
            val previous = withContext(Dispatchers.IO) {
                try { if (store.hasRollback()) store.rollbackPoint().second else null }
                catch (_: Exception) { null }
            }
            canUndo = previous != null && runCatching { vm.backupSnapshot() == previous }.getOrDefault(false)
        }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Header(stringResource(R.string.app_backup_page), onBack)
        val available = !busy && !choosing && staging == null && documentSource == null
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(application, { application = true; info = 0; absent = emptySet() },
                label = { Text(stringResource(R.string.app_backup_label)) }, enabled = available)
            FilterChip(!application, { application = false; info = 0; absent = emptySet() },
                label = { Text(stringResource(R.string.backup_chat_label)) }, enabled = available)
        }
        Text(stringResource(if (application) R.string.app_backup_scope else R.string.backup_scope))
        if (application && pendingRestart) {
            Text(stringResource(R.string.app_backup_pending))
            TextButton(enabled = available, onClick = { task {
                withContext(Dispatchers.IO) { vm.appRestores.cancelPending() }; pendingRestart = false
            } }) { Text(stringResource(R.string.app_backup_cancel_pending)) }
        }
        Button(enabled = available && !pendingRestart, onClick = { task {
            val snapshot = vm.backupSnapshot()
            val settings = if (application) vm.appBackupSettings() else null
            stage { dir -> withContext(Dispatchers.IO) { store.export(dir, snapshot, settings) } }
            documentSource = requireNotNull(staging).name
            save.launch(if (application) "HydrionHub-app-v1.zip" else "HydrionHub-chats.zip")
        } }) { Text(stringResource(R.string.backup_export)) }
        OutlinedButton(enabled = available && !pendingRestart, onClick = {
            try { choosing = true; open.launch(arrayOf("application/zip", "application/octet-stream")) }
            catch (_: android.content.ActivityNotFoundException) { choosing = false; info = R.string.backup_failed }
        }) {
            Text(stringResource(R.string.backup_preview))
        }
        if (application && !pendingRestart) vm.appRestores.previousBackup?.let { prior ->
            TextButton(enabled = available, onClick = { task {
                stage { dir -> inspected = withContext(Dispatchers.IO) { prior.inputStream().use { store.inspect(dir, it, true) } } }
            } }) { Text(stringResource(R.string.app_backup_previous)) }
        }
        inspected?.let { candidate ->
            val settings = candidate.settings
            if (settings != null) {
                Text(stringResource(R.string.app_backup_summary, candidate.workspace.history.size, candidate.workspace.drafts.size,
                    candidate.workspace.assistants.size, settings.catalog.providers.size, candidate.blobs.size))
                val missing = remember(candidate) { candidate.missingModelReferences() }
                if (missing > 0) Text(stringResource(R.string.app_backup_missing_models, missing))
                Text(stringResource(R.string.app_backup_impact))
                Button(enabled = !busy, onClick = { confirmReplace = true }) { Text(stringResource(R.string.app_backup_restore)) }
                TextButton(enabled = !busy, onClick = ::clear) { Text(stringResource(android.R.string.cancel)) }
            } else {
                ChatBackupPreview(candidate, busy, onImport = { task {
                    vm.applyBackup(candidate)
                    clear()
                    info = R.string.backup_restored
                } }, onCancel = ::clear)
            }
        }
        if (!application && canUndo && !pendingRestart) OutlinedButton(enabled = !busy && staging == null && documentSource == null, onClick = { undo = true }) {
            Text(stringResource(R.string.backup_undo))
        }
        if (busy) LinearProgressIndicator()
        if (info != 0) Text(stringResource(info))
        if (absent.isNotEmpty()) Text(stringResource(R.string.backup_missing, absent.joinToString("\n")))
    }
    if (confirmReplace) AlertDialog(onDismissRequest = { confirmReplace = false },
        title = { Text(stringResource(R.string.app_backup_restore)) }, text = { Text(stringResource(R.string.app_backup_impact)) },
        confirmButton = { TextButton(onClick = {
            confirmReplace = false
            val verified = inspected ?: return@TextButton
            task { vm.replaceFromAppBackup(verified); pendingRestart = true; clear() }
        }) { Text(stringResource(R.string.app_backup_confirm)) } },
        dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text(stringResource(android.R.string.cancel)) } })
    if (undo) AlertDialog(onDismissRequest = { undo = false }, title = { Text(stringResource(R.string.backup_undo)) },
        text = { Text(stringResource(R.string.backup_undo_detail)) },
        confirmButton = { TextButton(onClick = { undo = false; task { vm.undoBackup(); info = R.string.backup_undone } }) {
            Text(stringResource(android.R.string.ok))
        } }, dismissButton = { TextButton(onClick = { undo = false }) { Text(stringResource(android.R.string.cancel)) } })
}

@Composable
internal fun ChatBackupPreview(archive: InspectedChatArchive, busy: Boolean, onImport: () -> Unit, onCancel: () -> Unit) {
    val workspace = archive.workspace
    val history = workspace.history
    val canImport = history.isNotEmpty() || workspace.drafts.isNotEmpty() ||
        workspace.pending.isNotEmpty() || workspace.assistants.size > 1
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.backup_chat_version, archive.formatVersion), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.backup_summary, history.size, workspace.drafts.size, archive.blobs.size))
        val titles = history.take(3)
        titles.forEach { chat ->
            Text(chat.title.ifBlank { stringResource(R.string.backup_untitled) }, Modifier.fillMaxWidth(),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (history.size > titles.size) {
            Text(stringResource(R.string.backup_more_chats, history.size - titles.size), style = MaterialTheme.typography.bodySmall)
        }
        Button(enabled = !busy && canImport, onClick = onImport) { Text(stringResource(R.string.backup_apply)) }
        TextButton(enabled = !busy, onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
    }
}
