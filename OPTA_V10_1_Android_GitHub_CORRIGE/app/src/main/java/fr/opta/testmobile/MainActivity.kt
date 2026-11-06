package fr.opta.testmobile

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.opta.testmobile.ble.ConnectionStatus
import fr.opta.testmobile.ui.OptaApp
import fr.opta.testmobile.ui.theme.OptaTestMobileTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            OptaTestMobileTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                var permissionGranted by remember { mutableStateOf(viewModel.hasPermissions()) }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    permissionGranted = viewModel.hasPermissions()
                    if (permissionGranted) viewModel.start()
                }

                val enableBluetoothLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) {
                    viewModel.retry()
                }

                LaunchedEffect(permissionGranted) {
                    if (permissionGranted) viewModel.start()
                }

                OptaApp(
                    state = state,
                    onPrimaryAction = {
                        when {
                            !viewModel.hasPermissions() -> permissionLauncher.launch(requiredPermissions())
                            state.connectionStatus == ConnectionStatus.BLUETOOTH_DISABLED -> {
                                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                            }
                            else -> viewModel.retry()
                        }
                    },
                    primaryActionLabel = when {
                        !permissionGranted || state.connectionStatus == ConnectionStatus.PERMISSION_REQUIRED ->
                            "Autoriser le Bluetooth"
                        state.connectionStatus == ConnectionStatus.BLUETOOTH_DISABLED -> "Activer le Bluetooth"
                        state.connectionStatus == ConnectionStatus.CONNECTED -> "Reconnecter"
                        state.connectionStatus == ConnectionStatus.SEARCHING -> "Relancer la recherche"
                        else -> "Rechercher OPTA-V10"
                    },
                    onRequestConfig = viewModel::requestConfig,
                    onRequestStatus = viewModel::requestStatus,
                    onSaveConfig = viewModel::saveConfig,
                    onSendConsoleCommand = viewModel::sendConsoleCommand,
                    onClearLogs = viewModel::clearLogs,
                    onAuthenticate = viewModel::authenticate,
                    onLock = viewModel::lock,
                    onStartCycle = viewModel::startRemote,
                    onStopCycle = viewModel::stopRemote,
                    onAcknowledge = viewModel::acknowledge,
                    onRequestHistory = viewModel::requestHistory,
                    onExportArchive = {
                        val file = viewModel.exportArchive()
                        val uri = FileProvider.getUriForFile(this,
                            "${BuildConfig.APPLICATION_ID}.provider", file)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/csv"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(intent, "Exporter l’historique OPTA"))
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (viewModel.hasPermissions()) viewModel.start()
    }

    private fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
