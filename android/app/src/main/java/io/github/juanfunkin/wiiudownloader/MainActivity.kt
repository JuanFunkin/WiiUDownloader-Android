package io.github.juanfunkin.wiiudownloader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.github.juanfunkin.wiiudownloader.ui.AppRoot
import io.github.juanfunkin.wiiudownloader.ui.WiiUTheme

class MainActivity : ComponentActivity() {

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) Repo.setFolder(this, uri)
    }

    // The download starts whether or not the notification permission was granted;
    // without it the transfer still runs, only its notification is hidden.
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        Repo.startDownload(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            WiiUTheme {
                AppRoot(
                    onPickFolder = { pickFolder.launch(null) },
                    onStart = ::requestStart,
                )
            }
        }
    }

    private fun requestStart() {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            Repo.startDownload(this)
        }
    }
}
