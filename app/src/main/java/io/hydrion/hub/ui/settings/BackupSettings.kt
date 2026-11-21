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
        val dir = staging ?: store.preparedExport(documentSource)?.parentFile
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
        val file = store.preparedExport(documentSource)
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
        if (uri != null) task {
            stage { dir ->
                val verified = withContext(Dispatchers.IO) { store.inspect(dir, uri) }
                try { mergeBackup(vm.backupSnapshot(), verified.workspace) }
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
        Header(stringResource(R.string.backup_title), onBack)
        Text(stringResource(R.string.backup_scope))
        Button(enabled = !busy && staging == null && documentSource == null, onClick = { task {
            val snapshot = vm.backupSnapshot()
            stage { dir -> withContext(Dispatchers.IO) { store.export(dir, snapshot) } }
            documentSource = requireNotNull(staging).name
            save.launch("HydrionHub-chats.zip")
        } }) { Text(stringResource(R.string.backup_export)) }
        OutlinedButton(enabled = !busy && staging == null && documentSource == null, onClick = {
            try { open.launch(arrayOf("application/zip", "application/octet-stream")) }
            catch (_: android.content.ActivityNotFoundException) { info = R.string.backup_failed }
        }) {
            Text(stringResource(R.string.backup_preview))
        }
        inspected?.let { candidate ->
            Text(stringResource(R.string.backup_summary, candidate.workspace.history.size, candidate.workspace.drafts.size,
                candidate.blobs.size))
            Button(enabled = !busy && (candidate.workspace.history.isNotEmpty() || candidate.workspace.pending.isNotEmpty() ||
                candidate.workspace.assistants.size > 1), onClick = { task {
                vm.applyBackup(candidate)
                clear()
                info = R.string.backup_restored
            } }) { Text(stringResource(R.string.backup_apply)) }
            TextButton(enabled = !busy, onClick = ::clear) { Text(stringResource(android.R.string.cancel)) }
        }
        if (canUndo) OutlinedButton(enabled = !busy && staging == null && documentSource == null, onClick = { undo = true }) {
            Text(stringResource(R.string.backup_undo))
        }
        if (busy) LinearProgressIndicator()
        if (info != 0) Text(stringResource(info))
        if (absent.isNotEmpty()) Text(stringResource(R.string.backup_missing, absent.joinToString("\n")))
    }
    if (undo) AlertDialog(onDismissRequest = { undo = false }, title = { Text(stringResource(R.string.backup_undo)) },
        text = { Text(stringResource(R.string.backup_undo_detail)) },
        confirmButton = { TextButton(onClick = { undo = false; task { vm.undoBackup(); info = R.string.backup_undone } }) {
            Text(stringResource(android.R.string.ok))
        } }, dismissButton = { TextButton(onClick = { undo = false }) { Text(stringResource(android.R.string.cancel)) } })
}
