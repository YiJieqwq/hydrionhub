package io.hydrion.hub.provider

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.hydrion.hub.R
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun ManualWebSearchPage(endpoint: SearchEndpoint, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val search = remember { SearxngSearch() }
    val scope = rememberCoroutineScope()
    // The query and results live only while this explicit search page is open.
    var query by remember { mutableStateOf("") }
    var active by remember { mutableStateOf<Job?>(null) }
    var results by remember { mutableStateOf<List<SearchHit>?>(null) }
    var failure by remember { mutableStateOf<SearchFailure?>(null) }
    ModelSettingsFrame(stringResource(R.string.search_manual), onBack) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                ModelSettingsCard {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(endpoint.name, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.search_manual_notice, searchServiceAddress(endpoint.url)?.rawAuthority.orEmpty()),
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(query, { if (it.length <= SearxngSearch.MaxQuery) {
                            query = it; results = null; failure = null
                        } }, Modifier.fillMaxWidth(), enabled = active == null, singleLine = true,
                            label = { Text(stringResource(R.string.search_manual_query)) })
                        if (active == null) {
                            Button(enabled = query.isNotBlank(), onClick = {
                                if (active == null) {
                                    results = null
                                    failure = null
                                    active = scope.launch(start = CoroutineStart.LAZY) {
                                        try { results = search.search(endpoint, query) }
                                        catch (problem: SearchFailure) { failure = problem }
                                        finally { active = null }
                                    }
                                    active?.start()
                                }
                            }) { Text(stringResource(R.string.search_manual_submit)) }
                        } else TextButton({ active?.cancel() }) { Text(stringResource(R.string.search_manual_cancel)) }
                    }
                }
            }
            failure?.let { error ->
                item {
                    val label = when (error.problem) {
                        SearchProblem.InvalidRequest -> stringResource(R.string.search_manual_invalid)
                        SearchProblem.InvalidResponse -> stringResource(R.string.search_manual_format)
                        SearchProblem.Http -> stringResource(R.string.search_manual_http, error.status)
                        SearchProblem.TooLarge -> stringResource(R.string.search_manual_limit)
                        SearchProblem.Network -> stringResource(R.string.search_manual_network)
                    }
                    Text(label, color = MaterialTheme.colorScheme.error)
                }
            }
            results?.let { hits ->
                if (hits.isEmpty()) item { Text(stringResource(R.string.search_manual_empty)) }
                items(hits, key = { it.url }) { hit ->
                    ModelSettingsCard {
                        SelectionContainer {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(hit.title, style = MaterialTheme.typography.titleSmall)
                                Text(hit.url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                if (hit.snippet.isNotEmpty()) Text(hit.snippet, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
