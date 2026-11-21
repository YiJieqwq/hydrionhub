package io.hydrion.hub.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.clipboardOriginals
import io.hydrion.hub.R
import io.hydrion.hub.model.LocalStorageFiles
import io.hydrion.hub.model.LocalStorageUsage
import io.hydrion.hub.provider.Header
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun StorageSettings(vm: HubViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val files = remember(context) { LocalStorageFiles(context.filesDir, context.cacheDir, File(context.applicationInfo.dataDir, "shared_prefs"), context.noBackupFilesDir) }
    val scope = rememberCoroutineScope()
    var usage by remember { mutableStateOf<LocalStorageUsage?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    suspend fun refresh(clear: Boolean) {
        busy = true
        failed = false
        try {
            val originals = vm.backupSnapshot().clipboardOriginals(context.packageName + ".images")
            usage = withContext(Dispatchers.IO) {
                if (clear) files.clearExpired(protectedNames = originals)
                files.usage(protectedNames = originals)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        finally { busy = false }
    }
    LaunchedEffect(files) { refresh(false) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Header(stringResource(R.string.settings_data), onBack)
        usage?.let {
            for ((label, size) in listOf(R.string.storage_history to it.history, R.string.storage_images to it.images,
                R.string.storage_restored to it.imported, R.string.storage_settings to it.settings, R.string.storage_copies to it.cachedCopies,
                R.string.storage_rollback to it.rollback)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(label), Modifier.weight(1f))
                    Text(Formatter.formatFileSize(context, size))
                }
            }
            Text(stringResource(R.string.storage_external), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = it.expiredCopies > 0 && !busy, onClick = { scope.launch { refresh(true) } }) {
                Text(stringResource(R.string.storage_clear, Formatter.formatFileSize(context, it.expiredCopies)))
            }
        }
        if (busy) LinearProgressIndicator()
        if (failed) Text(stringResource(R.string.storage_read_failed))
    }
}
