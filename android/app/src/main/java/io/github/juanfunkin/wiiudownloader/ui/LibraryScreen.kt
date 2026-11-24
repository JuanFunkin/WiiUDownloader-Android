package io.github.juanfunkin.wiiudownloader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.juanfunkin.wiiudownloader.R
import io.github.juanfunkin.wiiudownloader.Repo
import io.github.juanfunkin.wiiudownloader.Title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val categories = listOf(
    "game" to R.string.cat_game,
    "update" to R.string.cat_update,
    "dlc" to R.string.cat_dlc,
    "demo" to R.string.cat_demo,
    "all" to R.string.cat_all,
)

// Bit values match the MCP region flags used by the Go core.
private val regions = listOf(
    2 to R.string.region_usa,
    4 to R.string.region_europe,
    1 to R.string.region_japan,
)

@Composable
fun LibraryScreen(modifier: Modifier = Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("game") }
    var regionMask by rememberSaveable { mutableIntStateOf(0) }
    var results by remember { mutableStateOf<List<Title>>(emptyList()) }
    var searching by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<Title?>(null) }

    val queue by Repo.queue.collectAsStateWithLifecycle()
    val queuedIds = remember(queue) { queue.map { it.title.tid }.toSet() }

    LaunchedEffect(query, category, regionMask) {
        searching = true
        delay(200) // debounce typing
        results = withContext(Dispatchers.Default) { Repo.search(query, category, regionMask) }
        searching = false
    }

    Column(modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.clear_search))
                    }
                }
            },
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(categories) { (id, label) ->
                FilterChip(
                    selected = category == id,
                    onClick = { category = id },
                    label = { Text(stringResource(label)) },
                )
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(regions) { (bit, label) ->
                FilterChip(
                    selected = (regionMask and bit) != 0,
                    onClick = { regionMask = regionMask xor bit },
                    label = { Text(stringResource(label)) },
                )
            }
        }

        val resources = LocalContext.current.resources
        Text(
            text = if (searching) {
                stringResource(R.string.searching)
            } else {
                resources.getQuantityString(R.plurals.results_count, results.size, results.size)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(results, key = { it.tid }) { title ->
                ListItem(
                    modifier = Modifier.clickable { selected = title },
                    headlineContent = {
                        Text(title.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text("${title.kind} · ${title.region} · ${title.tid}")
                    },
                    trailingContent = {
                        if (title.tid in queuedIds) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = stringResource(R.string.in_queue),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }

    selected?.let { title ->
        TitleSheet(title = title, onDismiss = { selected = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TitleSheet(title: Title, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isGame = title.kind == "Game"

    var related by remember(title.tid) { mutableStateOf<Pair<Title?, Title?>?>(null) }
    var includeUpdate by remember(title.tid) { mutableStateOf(false) }
    var includeDlc by remember(title.tid) { mutableStateOf(false) }

    LaunchedEffect(title.tid) {
        if (isGame) {
            related = withContext(Dispatchers.Default) { Repo.related(title.tid) }
        }
    }

    val alreadyQueued = Repo.isQueued(title.tid)
    val update = related?.first
    val dlc = related?.second
    val toAdd = buildList {
        if (!alreadyQueued) add(title)
        if (includeUpdate && update != null) add(update)
        if (includeDlc && dlc != null) add(dlc)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title.name, style = MaterialTheme.typography.titleLarge)
            Text(
                "${title.kind} · ${title.region} · ${title.tid}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (isGame) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                if (related == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(R.string.finding_related),
                            modifier = Modifier.padding(start = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    RelatedRow(stringResource(R.string.related_update), update, includeUpdate) { includeUpdate = it }
                    RelatedRow(stringResource(R.string.related_dlc), dlc, includeDlc) { includeDlc = it }
                }
            }

            Button(
                onClick = {
                    Repo.enqueue(toAdd)
                    onDismiss()
                },
                enabled = toAdd.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text(
                    when {
                        toAdd.isEmpty() -> stringResource(R.string.already_in_queue)
                        toAdd.size == 1 -> stringResource(R.string.add_to_queue)
                        else -> stringResource(R.string.add_n_to_queue, toAdd.size)
                    },
                )
            }
        }
    }
}

@Composable
private fun RelatedRow(label: String, entry: Title?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = entry != null) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked && entry != null, onCheckedChange = onChange, enabled = entry != null)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = entry?.let { "${it.name} · ${it.tid}" } ?: stringResource(R.string.related_none, label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
