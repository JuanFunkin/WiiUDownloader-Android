package io.github.juanfunkin.wiiudownloader.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.juanfunkin.wiiudownloader.DownloadService
import io.github.juanfunkin.wiiudownloader.Phase
import io.github.juanfunkin.wiiudownloader.QueueItem
import io.github.juanfunkin.wiiudownloader.R
import io.github.juanfunkin.wiiudownloader.Repo
import io.github.juanfunkin.wiiudownloader.RunState
import io.github.juanfunkin.wiiudownloader.formatBytes
import io.github.juanfunkin.wiiudownloader.formatSpeed

@Composable
fun QueueScreen(
    onStart: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val queue by Repo.queue.collectAsStateWithLifecycle()
    val run by Repo.run.collectAsStateWithLifecycle()
    val settings by Repo.settings.collectAsStateWithLifecycle()

    val knownSize = queue.sumOf { (it.sizeBytes ?: 0L).coerceAtLeast(0L) }
    val sizePending = queue.any { it.sizeBytes == null }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (run.active) {
                item { RunCard(run) }
            } else if (run.finished) {
                item { ResultCard(run) }
            }

            if (settings.treeUri == null && queue.isNotEmpty()) {
                item { FolderWarning(onOpenSettings) }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.queue_header, queue.size, formatBytes(knownSize)) +
                            if (sizePending) "…" else "",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (queue.isNotEmpty() && !run.active) {
                        TextButton(onClick = { Repo.clearQueue() }) { Text(stringResource(R.string.clear_queue)) }
                    }
                }
            }

            if (queue.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.queue_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(queue, key = { it.title.tid }) { item ->
                QueueRow(item, canRemove = !run.active)
            }
        }

        if (!run.active && queue.isNotEmpty()) {
            ExtendedFloatingActionButton(
                onClick = { if (settings.treeUri == null) onOpenSettings() else onStart() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                Text(stringResource(R.string.download))
            }
        }
    }
}

@Composable
private fun QueueRow(item: QueueItem, canRemove: Boolean) {
    ListItem(
        headlineContent = { Text(item.title.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val size = when {
                item.sizeBytes == null -> stringResource(R.string.size_calculating)
                item.sizeBytes < 0 -> stringResource(R.string.size_unknown)
                else -> formatBytes(item.sizeBytes)
            }
            Text("${item.title.kind} · ${item.title.region} · $size")
        },
        trailingContent = {
            if (canRemove) {
                IconButton(onClick = { Repo.removeFromQueue(item.title.tid) }) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.remove_from_queue))
                }
            }
        },
    )
}

@Composable
private fun FolderWarning(onOpenSettings: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Warning, contentDescription = null)
            Text(
                stringResource(R.string.folder_warning),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.tab_settings)) }
        }
    }
}

private fun sendToService(context: Context, action: String) = DownloadService.send(context, action)

@Composable
private fun RunCard(run: RunState) {
    val context = LocalContext.current
    val phaseLabel = stringResource(
        when (run.phase) {
            Phase.Downloading -> R.string.phase_downloading
            Phase.Decrypting -> R.string.phase_decrypting
            Phase.Copying -> R.string.phase_copying
            Phase.Idle -> R.string.phase_preparing
        },
    )
    val position = if (run.total > 0) "  (${run.index + 1}/${run.total})" else ""

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                run.title.ifEmpty { stringResource(R.string.preparing_ellipsis) },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                (if (run.paused) stringResource(R.string.state_paused) else phaseLabel) + position,
                style = MaterialTheme.typography.labelLarge,
            )

            val downloading = run.phase == Phase.Downloading
            when {
                downloading && run.size > 0 -> LinearProgressIndicator(
                    progress = { (run.downloaded.toFloat() / run.size).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                run.phase == Phase.Decrypting || run.phase == Phase.Copying -> LinearProgressIndicator(
                    progress = { run.fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                else -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (downloading && run.size > 0) {
                val remaining = (run.size - run.downloaded).coerceAtLeast(0)
                val eta = if (run.speed > 0 && !run.paused) " · ${formatEta(remaining / run.speed)}" else ""
                Text(
                    "${formatBytes(run.downloaded)} / ${formatBytes(run.size)} · ${formatSpeed(run.speed)}$eta",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (run.phase == Phase.Decrypting || run.phase == Phase.Copying) {
                Text("${(run.fraction * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        sendToService(
                            context,
                            if (run.paused) DownloadService.ACTION_RESUME else DownloadService.ACTION_PAUSE,
                        )
                    },
                ) {
                    Text(stringResource(if (run.paused) R.string.resume else R.string.pause))
                }
                Button(onClick = { sendToService(context, DownloadService.ACTION_CANCEL) }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}

@Composable
private fun ResultCard(run: RunState) {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val failed = run.results.count { !it.ok }
            Text(
                stringResource(
                    when {
                        run.cancelled -> R.string.result_cancelled
                        failed == 0 -> R.string.result_complete
                        else -> R.string.result_errors
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            run.results.forEach { line ->
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        if (line.ok) Icons.Default.Check else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (line.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(line.name, style = MaterialTheme.typography.bodyMedium)
                        if (line.message.isNotEmpty()) {
                            Text(
                                line.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
            TextButton(onClick = { Repo.clearRun() }) { Text(stringResource(R.string.close)) }
        }
    }
}

private fun formatEta(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}
