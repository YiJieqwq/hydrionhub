package io.hydrion.hub.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.hydrion.hub.R
import io.hydrion.hub.localtools.*
import io.hydrion.hub.model.HubViewModel

private val NoteType.label: Int get() = when (this) {
    NoteType.Identity -> R.string.memory_type_identity
    NoteType.Workflow -> R.string.memory_type_workflow
    NoteType.Voice -> R.string.memory_type_voice
    NoteType.Instruction -> R.string.memory_type_instruction
}

@Composable
internal fun MemorySettings(vm: HubViewModel, onBack: () -> Unit, ownerId: String? = null) {
    ScopedToolPage(vm, R.string.settings_memory, onBack, ownerId) { root, owner ->
        val file = remember(root, owner) { memoryFile(root, owner) }
        FeatureContent(file) { memory, saving, update ->
            var archived by rememberSaveable { mutableStateOf(false) }
            var query by remember { mutableStateOf("") }
            var editing by remember { mutableStateOf(false) }
            var target by remember { mutableStateOf<MemoryNote?>(null) }
            var delete by remember { mutableStateOf<MemoryNote?>(null) }
            val matches = remember(memory, query, archived) { memory.search(query, archived) }
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.memory_enable), Modifier.weight(1f))
                        Switch(memory.enabled, { on -> update { it.copy(enabled = on) } }, enabled = !saving)
                    }
                    Text(stringResource(R.string.memory_explain), style = MaterialTheme.typography.bodySmall)
                }
                if (memory.enabled) {
                    item {
                        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text(stringResource(R.string.memory_query)) })
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(!archived, { archived = false }, { Text(stringResource(R.string.memory_active)) })
                            Spacer(Modifier.width(8.dp))
                            FilterChip(archived, { archived = true }, { Text(stringResource(R.string.memory_archived)) })
                            Spacer(Modifier.weight(1f))
                            IconButton({ target = null; editing = true }, enabled = !saving && memory.notes.size < 256) {
                                Icon(Icons.Outlined.Add, stringResource(R.string.memory_add))
                            }
                        }
                    }
                    if (matches.isEmpty()) item { Text(stringResource(R.string.memory_empty)) }
                    items(matches, key = { it.id }) { note ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(stringResource(note.type.label), style = MaterialTheme.typography.labelSmall)
                                Text(note.text)
                                Row {
                                    IconButton({ target = note; editing = true }, enabled = !saving) {
                                        Icon(Icons.Outlined.Edit, stringResource(R.string.memory_edit))
                                    }
                                    IconButton({ update { it.archive(note.id, !note.archived) } }, enabled = !saving) {
                                        Icon(if (note.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                                            stringResource(if (note.archived) R.string.memory_restore else R.string.memory_archive))
                                    }
                                    IconButton({ delete = note }, enabled = !saving) {
                                        Icon(Icons.Outlined.Delete, stringResource(R.string.drawer_delete))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (editing && memory.enabled) MemoryEditor(target, { editing = false }) { text, type ->
                val id = target?.id
                update { it.save(id, text, type) }; editing = false
            }
            delete?.let { note ->
                AlertDialog(onDismissRequest = { delete = null }, text = { Text(stringResource(R.string.memory_delete_confirm)) },
                    confirmButton = { TextButton({ update { it.delete(note.id) }; delete = null }) { Text(stringResource(R.string.drawer_delete)) } },
                    dismissButton = { TextButton({ delete = null }) { Text(stringResource(R.string.settings_cancel)) } })
            }
        }
    }
}

@Composable
private fun MemoryEditor(note: MemoryNote?, close: () -> Unit, save: (String, NoteType) -> Unit) {
    var text by remember { mutableStateOf(note?.text.orEmpty()) }
    var type by remember { mutableStateOf(note?.type ?: NoteType.Instruction) }
    var open by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = close, title = { Text(stringResource(if (note == null) R.string.memory_add else R.string.memory_edit)) },
        text = { Column {
            Box {
                TextButton({ open = true }) { Text(stringResource(type.label)); Icon(Icons.Outlined.ArrowDropDown, null) }
                DropdownMenu(open, { open = false }) {
                    NoteType.entries.forEach { t -> DropdownMenuItem({ Text(stringResource(t.label)) }, {
                        type = t; open = false
                    }) }
                }
            }
            OutlinedTextField(text, { if (it.length <= 4000) text = it }, Modifier.fillMaxWidth(),
                minLines = 2, maxLines = 6, label = { Text(stringResource(R.string.memory_text)) })
        } }, confirmButton = { TextButton({ save(text, type) }, enabled = validNote(text)) { Text(stringResource(R.string.settings_save)) } },
        dismissButton = { TextButton(close) { Text(stringResource(R.string.settings_cancel)) } })
}
