package io.hydrion.hub.provider

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.hydrion.hub.R

@Composable
internal fun ManualWebSearchPage(endpoint: SearchEndpoint, manual: ManualSearchPort? = null, onBack: () -> Unit) {
    val search: suspend (ManualTarget, String) -> List<SearchHit> = remember(endpoint, manual) {
        if (manual != null) manual::search else {
            val legacy = SearxngSearch()
            val execute: suspend (ManualTarget, String) -> List<SearchHit> = { _, query -> legacy.search(endpoint, query) }
            execute
        }
    }
    ManualWebSearchPage(ManualTarget.Searxng(endpoint.id), endpoint.name,
        stringResource(R.string.search_manual_notice, searchServiceAddress(endpoint.url)?.rawAuthority.orEmpty()),
        true, search, onBack)
}

@Composable
internal fun ManualWebSearchPage(
    target: ManualTarget, name: String, notice: String, hasKey: Boolean,
    search: suspend (ManualTarget, String) -> List<SearchHit>, onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val latestSearch by rememberUpdatedState(search)
    val session = remember(target, scope) { ManualSearchSession(target, scope) { source, query -> latestSearch(source, query) } }
    DisposableEffect(session) { onDispose { session.close() } }
    val back = { session.close(); onBack() }
    BackHandler(onBack = back)
    val status by key(session) { session.state.collectAsState() }
    var query by remember(session) { mutableStateOf("") }
    // A saveable lazy item or text selection must not put queries or result URLs in a saved bundle.
    CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
        ModelSettingsFrame(stringResource(R.string.search_manual), back) {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    ModelSettingsCard {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(name, style = MaterialTheme.typography.titleMedium)
                            Text(notice, style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(query, {
                                if (it.length <= SearxngSearch.MaxQuery) {
                                    query = it
                                    session.resetResults()
                                }
                            }, Modifier.fillMaxWidth(), enabled = status != ManualSearchState.Loading, singleLine = true,
                                label = { Text(stringResource(R.string.search_manual_query)) })
                            if (!hasKey && status !is ManualSearchState.Blocked) {
                                Text(stringResource(R.string.search_tavily_missing_key), style = MaterialTheme.typography.bodySmall)
                            }
                            if (status == ManualSearchState.Loading) {
                                TextButton(onClick = session::cancel) { Text(stringResource(R.string.search_manual_cancel)) }
                            } else {
                                Button(onClick = { session.submit(query) }, enabled = hasKey && query.isNotBlank()) {
                                    Text(stringResource(R.string.search_manual_submit))
                                }
                            }
                        }
                    }
                }
                when (val result = status) {
                    ManualSearchState.Idle, ManualSearchState.Loading -> Unit
                    ManualSearchState.Empty -> item { Text(stringResource(R.string.search_manual_empty)) }
                    is ManualSearchState.Blocked -> item {
                        val message = when (result.reason) {
                            SearchBlocked.Reason.MissingCredential -> R.string.search_tavily_missing_key
                            SearchBlocked.Reason.TargetUnavailable -> R.string.search_manual_unavailable
                        }
                        Text(stringResource(message), color = MaterialTheme.colorScheme.error)
                    }
                    is ManualSearchState.Failure -> item {
                        Text(searchErrorText(result.error, target), color = MaterialTheme.colorScheme.error)
                    }
                    is ManualSearchState.Results -> items(result.hits, key = { it.url }) { hit ->
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
}

@Composable
private fun searchErrorText(error: SearchFailure, target: ManualTarget): String = when (error.problem) {
    SearchProblem.InvalidRequest -> stringResource(
        if (target == ManualTarget.Tavily) R.string.search_tavily_invalid_query else R.string.search_manual_invalid)
    SearchProblem.InvalidResponse -> stringResource(
        if (target == ManualTarget.Tavily) R.string.search_tavily_format else R.string.search_manual_format)
    SearchProblem.Http -> {
        val message = if (target == ManualTarget.Tavily) when (error.status) {
            401, 403 -> R.string.search_tavily_auth
            429 -> R.string.search_tavily_rate
            else -> R.string.search_tavily_http
        } else R.string.search_manual_http
        stringResource(message, error.status)
    }
    SearchProblem.TooLarge -> stringResource(R.string.search_manual_limit)
    SearchProblem.Network -> stringResource(R.string.search_manual_network)
}
