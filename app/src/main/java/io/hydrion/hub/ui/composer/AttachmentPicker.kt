package io.hydrion.hub.ui.composer

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.hydrion.hub.model.Attachment
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.Mode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun rememberAttachmentPicker(
    vm: HubViewModel,
    onError: (String) -> Unit,
): () -> Unit {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    var targetId by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val conversationId = targetId
        targetId = null
        if (uris.isNotEmpty() && conversationId == vm.state.value.current.id) {
            scope.launch {
                val imported = withContext(Dispatchers.IO) {
                    uris.distinct().map { uri ->
                        try {
                            resolver.readAttachment(uri)
                        } catch (_: RuntimeException) {
                            null
                        }
                    }
                }
                if (vm.state.value.current.id == conversationId) {
                    imported.filterNotNull().forEach(vm::addAttachment)
                    if (null in imported) onError("部分附件无法读取，请重新选择。")
                }
            }
        }
    }
    return {
        val current = vm.state.value.current
        targetId = current.id
        try {
            picker.launch(arrayOf(if (current.mode == Mode.Image) "image/*" else "*/*"))
        } catch (_: ActivityNotFoundException) {
            targetId = null
            onError("未找到可用的系统文件选择器。")
        }
    }
}

private fun ContentResolver.readAttachment(uri: Uri): Attachment {
    val name = query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
    val mime = getType(uri)
    // OpenDocument grants access to this selected file, including after an app restart.
    takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return Attachment(
        id = uri.toString(),
        name = name?.takeIf { it.isNotBlank() } ?: "附件",
        uri = uri.toString(),
        mimeType = mime,
    )
}
