package fr.opta.testmobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import fr.opta.testmobile.ble.OptaBleManager
import fr.opta.testmobile.ble.OptaConfig

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val bleManager = OptaBleManager(application.applicationContext)

    val state = bleManager.state

    fun start() = bleManager.start()
    fun retry() = bleManager.retry()
    fun hasPermissions(): Boolean = bleManager.hasPermissions()
    fun requestConfig() = bleManager.requestConfig()
    fun requestStatus() = bleManager.requestStatus()
    fun saveConfig(config: OptaConfig) = bleManager.saveConfig(config)
    fun sendConsoleCommand(command: String) = bleManager.sendConsoleCommand(command)
    fun clearLogs() = bleManager.clearLogs()
    fun authenticate(code: String) = bleManager.authenticate(code)
    fun lock() = bleManager.lock()
    fun startRemote() = bleManager.startRemote()
    fun stopRemote() = bleManager.stopRemote()
    fun acknowledge() = bleManager.acknowledge()
    fun requestHistory() = bleManager.requestHistory()
    fun exportArchive() = bleManager.exportArchive()

    override fun onCleared() {
        bleManager.close()
        super.onCleared()
    }
}
