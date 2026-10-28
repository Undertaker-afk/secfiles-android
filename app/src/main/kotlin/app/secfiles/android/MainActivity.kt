package app.secfiles.android

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.fragment.app.FragmentActivity
import app.secfiles.android.ui.*

class MainActivity : FragmentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyScreenshotPolicy()
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            SecFilesTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Root(vm)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyScreenshotPolicy()
        vm.mounts.touch()
    }

    override fun onStop() {
        super.onStop()
        // isChangingConfigurations: rotation also stops the activity; do not lock for that.
        if (!isChangingConfigurations && vm.settings.lockOnLeave) vm.lockAll()
    }

    /** Blocks screenshots, screen recording and the recents thumbnail unless the user opted out. */
    private fun applyScreenshotPolicy() {
        if (vm.settings.allowScreenshots) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    }
}

@Composable
private fun Root(vm: MainViewModel) {
    val mounted by vm.mounts.mountedIds.collectAsState()
    LaunchedEffect(mounted) { vm.onMountedChanged(mounted) }

    Box(Modifier.fillMaxSize()) {
        when (val s = vm.screen) {
            Screen.List -> VolumeListScreen(vm)
            Screen.Create -> CreateScreen(vm)
            Screen.Settings -> SettingsScreen(vm)
            is Screen.Browser -> BrowserScreen(vm, s.volumeId)
        }
        vm.busy?.let { BusyOverlay(it) }
        vm.message?.let { msg ->
            Snackbar(
                Modifier.align(Alignment.BottomCenter).padding(16.dp),
                action = { androidx.compose.material3.TextButton(onClick = { vm.message = null }) { androidx.compose.material3.Text("OK") } },
            ) { androidx.compose.material3.Text(msg) }
        }
    }
}
