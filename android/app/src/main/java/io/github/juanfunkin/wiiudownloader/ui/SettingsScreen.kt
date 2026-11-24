package io.github.juanfunkin.wiiudownloader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.juanfunkin.wiiudownloader.R
import io.github.juanfunkin.wiiudownloader.Repo
import io.github.juanfunkin.wiiudownloader.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onPickFolder: () -> Unit, modifier: Modifier = Modifier) {
    val settings by Repo.settings.collectAsStateWithLifecycle()
    val run by Repo.run.collectAsStateWithLifecycle()

    var stagingBytes by remember { mutableLongStateOf(0L) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(run.active) {
        stagingBytes = withContext(Dispatchers.IO) { Repo.stagingBytes() }
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle(stringResource(R.string.section_destination))
        Card {
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text(stringResource(R.string.download_folder)) },
                supportingContent = { Text(settings.folderName ?: stringResource(R.string.folder_none)) },
                trailingContent = {
                    Button(onClick = onPickFolder) {
                        Text(stringResource(if (settings.treeUri == null) R.string.choose else R.string.change))
                    }
                },
            )
        }

        SectionTitle(stringResource(R.string.section_download))
        Card {
            Column {
                SwitchRow(
                    title = stringResource(R.string.decrypt_title),
                    subtitle = stringResource(R.string.decrypt_sub),
                    checked = settings.decrypt,
                    enabled = !run.active,
                    onChange = Repo::setDecrypt,
                )
                SwitchRow(
                    title = stringResource(R.string.delete_enc_title),
                    subtitle = stringResource(R.string.delete_enc_sub),
                    checked = settings.deleteEncrypted,
                    enabled = settings.decrypt && !run.active,
                    onChange = Repo::setDeleteEncrypted,
                )
                SwitchRow(
                    title = stringResource(R.string.continue_title),
                    subtitle = stringResource(R.string.continue_sub),
                    checked = settings.continueOnError,
                    enabled = !run.active,
                    onChange = Repo::setContinueOnError,
                )
            }
        }

        SectionTitle(stringResource(R.string.section_staging))
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.staging_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.staging_now, formatBytes(stagingBytes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { confirmClear = true },
                    enabled = !run.active && stagingBytes > 0,
                ) {
                    Text(stringResource(R.string.clear_staging))
                }
            }
        }

        SectionTitle(stringResource(R.string.section_about))
        Text(
            stringResource(R.string.about_text, Repo.databaseSize),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.clear_dialog_title)) },
            text = {
                Text(stringResource(R.string.clear_dialog_text))
            },
            confirmButton = {
                TextButton(onClick = {
                    Repo.clearStaging()
                    stagingBytes = Repo.stagingBytes()
                    confirmClear = false
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
        },
    )
}
