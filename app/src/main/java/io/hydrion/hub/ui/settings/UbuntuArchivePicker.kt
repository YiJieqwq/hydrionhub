package io.hydrion.hub.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.hydrion.hub.R
import io.hydrion.hub.runtime.ArchiveMatch
import io.hydrion.hub.runtime.inspectUbuntuBase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private object PickLocalArchive : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

@Composable
internal fun UbuntuArchivePicker(enabled: Boolean, onUseFingerprint: (String) -> Unit) {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    var reading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ArchiveMatch?>(null) }
    val picker = rememberLauncherForActivityResult(PickLocalArchive) { uri ->
        if (uri != null && !reading) scope.launch {
            reading = true
            failed = false
            result = null
            try {
                require(uri.scheme == "content")
                result = inspectUbuntuBase({ requireNotNull(resolver.openInputStream(uri)) }, Build.SUPPORTED_ABIS.toList())
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { failed = true }
            finally { reading = false }
        }
    }
    OutlinedButton({ picker.launch(arrayOf("*/*")) }, enabled = enabled && !reading) {
        Text(stringResource(R.string.ubuntu_archive_choose))
    }
    if (reading) LinearProgressIndicator()
    if (failed) Text(stringResource(R.string.ubuntu_archive_error))
    when (val checked = result) {
        is ArchiveMatch.Published -> {
            Text(stringResource(if (checked.matchesDeviceAbi) R.string.ubuntu_archive_ok else R.string.ubuntu_archive_abi,
                checked.image.filename))
            if (checked.matchesDeviceAbi) TextButton({ onUseFingerprint(checked.image.digest) }, enabled = enabled) {
                Text(stringResource(R.string.ubuntu_archive_use))
            }
        }
        ArchiveMatch.Unknown -> Text(stringResource(R.string.ubuntu_archive_other))
        ArchiveMatch.TooLarge -> Text(stringResource(R.string.ubuntu_archive_large))
        null -> Unit
    }
}
