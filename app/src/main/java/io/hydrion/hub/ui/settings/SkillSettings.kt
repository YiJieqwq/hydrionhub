package io.hydrion.hub.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.hydrion.hub.R
import io.hydrion.hub.localtools.*
import io.hydrion.hub.model.HubViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SkillSettings(vm: HubViewModel, onBack: () -> Unit) {
    ScopedToolPage(vm, R.string.settings_skill, onBack) { root, owner ->
        val file = remember(root, owner) { skillFile(root, owner) }
        FeatureContent(file) { catalog, saving, update -> SkillManager(owner, catalog, saving, update) }
    }
}

@Composable
private fun SkillManager(owner: String, catalog: LocalSkills, saving: Boolean, update: ((LocalSkills) -> LocalSkills) -> Unit) {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    var choosingFor by rememberSaveable { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var paste by remember { mutableStateOf(false) }
    var imported by remember { mutableStateOf<SkillDocument?>(null) }
    var reviewing by remember { mutableStateOf<SkillEntry?>(null) }
    var deleting by remember { mutableStateOf<SkillEntry?>(null) }
    var viewing by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val targetOwner = choosingFor
        choosingFor = null
        if (uri != null && targetOwner == owner) scope.launch {
            reading = true
            try {
                require(uri.scheme == "content")
                imported = withContext(Dispatchers.IO) {
                    requireNotNull(resolver.openInputStream(uri)).use { SkillDocument.read(it) }
                }
                error = false
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { error = true }
            finally { reading = false }
        }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(stringResource(R.string.skill_help), style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton({ error = false; paste = true }, enabled = !saving && !reading) { Text(stringResource(R.string.skill_paste)) }
                TextButton({
                    choosingFor = owner
                    picker.launch(arrayOf("*/*"))
                }, enabled = !saving && !reading) { Text(stringResource(R.string.skill_file)) }
            }
            if (reading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error) Text(stringResource(R.string.skill_invalid), color = MaterialTheme.colorScheme.error)
        }
        if (catalog.entries.isEmpty()) item { Text(stringResource(R.string.skill_empty)) }
        items(catalog.entries, key = { it.document.name }) { entry ->
            val doc = entry.document
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(doc.name, style = MaterialTheme.typography.titleMedium)
                    Text(doc.description)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.skill_available), Modifier.weight(1f))
                        Switch(entry.enabled, { on -> update { it.enable(doc.name, doc.digest, on) } }, enabled = entry.trusted && !saving)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton({ reviewing = entry }, enabled = !saving) { Text(stringResource(R.string.skill_review)) }
                        if (entry.enabled && entry.trusted) TextButton({ viewing = catalog.instructions(doc.name) }) {
                            Text(stringResource(R.string.skill_read))
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton({ deleting = entry }, enabled = !saving) { Icon(Icons.Outlined.Delete, stringResource(R.string.drawer_delete)) }
                    }
                }
            }
        }
    }
    if (paste) PasteSkill(close = { paste = false }, preview = { imported = it; paste = false })
    imported?.let { doc ->
        val oldDigest = catalog.entries.find { it.document.name == doc.name }?.document?.digest
        val replace = oldDigest != null && oldDigest != doc.digest
        SkillReview(doc.name, doc.source, { imported = null }, if (replace) R.string.skill_replace else R.string.skill_import,
            if (replace) R.string.skill_replace_note else null) {
            update { it.install(doc, oldDigest) }; imported = null
        }
    }
    reviewing?.let { entry ->
        val doc = entry.document
        SkillReview(doc.name, doc.source, { reviewing = null }, if (entry.trusted) R.string.skill_revoke else R.string.skill_trust) {
            update { if (entry.trusted) it.revoke(doc.name, doc.digest) else it.approve(doc.name, doc.digest) }
            reviewing = null
        }
    }
    viewing?.let { source ->
        SkillReview(stringResource(R.string.skill_read), source, { viewing = null }, R.string.skill_done, action = { viewing = null })
    }
    deleting?.let { entry ->
        AlertDialog(onDismissRequest = { deleting = null }, text = { Text(stringResource(R.string.skill_remove)) },
            confirmButton = { TextButton({ update { it.remove(entry.document.name, entry.document.digest) }; deleting = null }) {
                Text(stringResource(R.string.drawer_delete))
            } }, dismissButton = { TextButton({ deleting = null }) { Text(stringResource(R.string.settings_cancel)) } })
    }
}

@Composable
private fun SkillReview(title: String, source: String, close: () -> Unit, actionLabel: Int, note: Int? = null, action: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            if (note != null) Text(stringResource(note), style = MaterialTheme.typography.titleSmall)
            SelectionContainer { Text(source, style = MaterialTheme.typography.bodySmall) }
        } },
        confirmButton = { TextButton(action) { Text(stringResource(actionLabel)) } },
        dismissButton = { if (actionLabel != R.string.skill_done) TextButton(close) { Text(stringResource(R.string.settings_cancel)) } })
}

@Composable
private fun PasteSkill(close: () -> Unit, preview: (SkillDocument) -> Unit) {
    var source by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.skill_paste)) },
        text = { Column {
            OutlinedTextField(source, { if (it.length <= SkillByteLimit) { source = it; error = false } else error = true },
                Modifier.fillMaxWidth(), minLines = 4, maxLines = 8, label = { Text("SKILL.md") })
            if (error) Text(stringResource(R.string.skill_invalid), color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton({
            val doc = runCatching { SkillDocument.parse(source) }.getOrNull()
            if (doc == null) error = true else preview(doc)
        }, enabled = source.isNotBlank()) { Text(stringResource(R.string.skill_review)) } },
        dismissButton = { TextButton(close) { Text(stringResource(R.string.settings_cancel)) } })
}
