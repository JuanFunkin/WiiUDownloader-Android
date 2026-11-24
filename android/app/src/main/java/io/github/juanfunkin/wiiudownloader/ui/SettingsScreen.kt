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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
        SectionTitle("Destino")
        Card {
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("Carpeta de descarga") },
                supportingContent = { Text(settings.folderName ?: "Sin elegir") },
                trailingContent = {
                    Button(onClick = onPickFolder) {
                        Text(if (settings.treeUri == null) "Elegir" else "Cambiar")
                    }
                },
            )
        }

        SectionTitle("Descarga")
        Card {
            Column {
                SwitchRow(
                    title = "Descifrar contenido",
                    subtitle = "Deja el juego listo para Cemu o la consola (code, content, meta). " +
                        "Si lo desactivas se guardan los archivos cifrados (.app).",
                    checked = settings.decrypt,
                    enabled = !run.active,
                    onChange = Repo::setDecrypt,
                )
                SwitchRow(
                    title = "Borrar archivos cifrados",
                    subtitle = "Tras descifrar no conserva la copia cifrada y ahorra espacio.",
                    checked = settings.deleteEncrypted,
                    enabled = settings.decrypt && !run.active,
                    onChange = Repo::setDeleteEncrypted,
                )
                SwitchRow(
                    title = "Continuar si un título falla",
                    subtitle = "Sigue con el resto de la cola en vez de detenerse.",
                    checked = settings.continueOnError,
                    enabled = !run.active,
                    onChange = Repo::setContinueOnError,
                )
            }
        }

        SectionTitle("Almacenamiento temporal")
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Cada título se descarga primero en el almacenamiento de la app y, al terminar, " +
                        "se copia a tu carpeta. Necesitas espacio libre para un título completo " +
                        "(más si descifras y conservas los archivos cifrados).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Ahora ocupa: ${formatBytes(stagingBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { confirmClear = true },
                    enabled = !run.active && stagingBytes > 0,
                ) {
                    Text("Borrar archivos temporales")
                }
            }
        }

        SectionTitle("Acerca de")
        Text(
            "Base de datos: ${Repo.databaseSize} títulos.\n" +
                "Portado desde WiiUDownloader de Xpl0itU (GPLv3). Las descargas vienen " +
                "directamente de los servidores de Nintendo.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Borrar archivos temporales") },
            text = {
                Text("Se perderán las descargas a medias y no se podrán reanudar.")
            },
            confirmButton = {
                TextButton(onClick = {
                    Repo.clearStaging()
                    stagingBytes = Repo.stagingBytes()
                    confirmClear = false
                }) { Text("Borrar") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancelar") }
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
