package io.hydrion.hub.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.hydrion.hub.R
import io.hydrion.hub.ui.messages.BitmapPreview
import io.hydrion.hub.ui.messages.rememberAttachmentPreview
import io.hydrion.hub.ui.profile.LocalProfile
import io.hydrion.hub.ui.profile.LocalProfileStore
import io.hydrion.hub.ui.profile.ProfileAvatar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private object ChooseLocalImage : ActivityResultContracts.GetContent() {
    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

@Composable
internal fun SettingsProfile(accounts: List<SignedInAccountUsage>) {
    val context = LocalContext.current
    val store = remember(context) { LocalProfileStore.shared(context) }
    val profile by store.profile.collectAsState()
    var editing by rememberSaveable { mutableStateOf(false) }
    val editLabel = stringResource(R.string.settings_edit_profile)
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).testTag("settings-profile")
        .clickable(role = Role.Button, onClickLabel = editLabel) { editing = true }
        .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ProfileAvatar(profile, Modifier.size(48.dp))
        Text(profile.name.ifBlank { stringResource(R.string.drawer_you) }, Modifier.weight(1f),
            fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    AccountUsage(accounts)
    if (editing) EditLocalProfile(profile, store, onClose = { editing = false })
}

@Composable
private fun EditLocalProfile(profile: LocalProfile, store: LocalProfileStore, onClose: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(profile.name) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var removed by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ChooseLocalImage) { uri ->
        if (uri != null) {
            selected = uri.toString()
            removed = false
            error = false
        }
    }
    AlertDialog(onDismissRequest = { if (!saving) onClose() },
        title = { Text(stringResource(R.string.settings_edit_profile)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val uri = selected
                    if (uri == null) ProfileAvatar(if (removed) LocalProfile() else profile, Modifier.size(44.dp))
                    else BitmapPreview(rememberAttachmentPreview(uri), stringResource(R.string.settings_avatar),
                        Modifier.size(44.dp).clip(CircleShape), fit = false)
                    TextButton(enabled = !saving, onClick = {
                        try { picker.launch("image/*") } catch (_: ActivityNotFoundException) { error = true }
                    }) { Text(stringResource(R.string.settings_choose_photo)) }
                }
                if (selected != null || (profile.avatar != null && !removed)) {
                    TextButton(enabled = !saving, onClick = { selected = null; removed = true; error = false }) {
                        Text(stringResource(R.string.settings_remove_photo))
                    }
                }
                OutlinedTextField(name, { name = it }, enabled = !saving, singleLine = true,
                    label = { Text(stringResource(R.string.settings_username)) })
                if (error) Text(stringResource(R.string.settings_profile_error), color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = {
            TextButton(enabled = !saving, onClick = {
                if (!saving) {
                    saving = true
                    scope.launch {
                        try {
                            store.save(name, selected?.let(Uri::parse), removed)
                            onClose()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            error = true
                        } finally {
                            saving = false
                        }
                    }
                }
            }) { Text(stringResource(R.string.settings_save)) }
        }, dismissButton = {
            TextButton(enabled = !saving, onClick = onClose) { Text(stringResource(R.string.settings_cancel)) }
        })
}
