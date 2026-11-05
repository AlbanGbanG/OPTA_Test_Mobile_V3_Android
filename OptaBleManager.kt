package fr.opta.testmobile.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.ArrayDeque
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class OptaBleManager(private val context: Context) {

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private val mainHandler = Handler(Looper.getMainLooper())
    private val preferences = context.getSharedPreferences("opta_ble", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(OptaUiState())
    val state: StateFlow<OptaUiState> = _state.asStateFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var manualStop = false
    private var reconnectAttempts = 0
    private var writeInProgress = false
    private var localLogId = 0L
    private var lastLogPayload = ""
    private var historySyncIndex = 0
    private var historySyncTotal = 0
    private val eventFile = File(context.filesDir, "opta_evenements_v10.jsonl")
    private val testsFile = File(context.filesDir, "opta_essais_v10.json")

    init { loadLocalArchive() }

    private val notificationQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private val readQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private val writeQueue = ArrayDeque<String>()

    private val scanTimeout = Runnable {
        if (scanning) {
            stopScan()
            updateConnection(ConnectionStatus.NOT_FOUND, "OPTA-V10 introuvable. Vérifie qu’il est sous tension.")
        }
    }

    private val reconnectRunnable = Runnable {
        if (!manualStop && hasPermissions()) start()
    }

    private val commandTimeout = Runnable {
        if (_state.value.commandBusy) {
            _state.update {
                it.copy(commandBusy = false, commandFeedback = "Aucune réponse reçue de l’Opta.")
            }
            appendLocalLog(LogLevel.WARNING, "Délai dépassé pendant l’attente de la réponse Opta.")
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        manualStop = false

        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            updateConnection(
                ConnectionStatus.BLUETOOTH_UNAVAILABLE,
                "Ce téléphone ne prend pas en charge le Bluetooth Low Energy."
            )
            return
        }
        if (!hasPermissions()) {
            updateConnection(
                ConnectionStatus.PERMISSION_REQUIRED,
                "Autorise les appareils à proximité pour rechercher l’OPTA."
            )
            return
        }

        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null) {
            updateConnection(ConnectionStatus.BLUETOOTH_UNAVAILABLE, "Bluetooth indisponible sur ce téléphone.")
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            updateConnection(ConnectionStatus.BLUETOOTH_DISABLED, "Active le Bluetooth du téléphone.")
            return
        }
        if (bluetoothGatt != null) return
        startScan()
    }

    @SuppressLint("MissingPermission")
    fun retry() {
        manualStop = true
        reconnectAttempts = 0
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(commandTimeout)
        stopScan()
        bluetoothGatt?.run {
            disconnect()
            close()
        }
        bluetoothGatt = null
        commandCharacteristic = null
        writeQueue.clear()
        writeInProgress = false
        _state.update { it.copy(authenticated = false, historyLoading = false) }
        lastLogPayload = ""
        manualStop = false
        start()
    }

    @SuppressLint("MissingPermission")
    fun close() {
        manualStop = true
        mainHandler.removeCallbacksAndMessages(null)
        stopScan()
        bluetoothGatt?.run {
            disconnect()
            close()
        }
        bluetoothGatt = null
        commandCharacteristic = null
        writeQueue.clear()
        writeInProgress = false
        _state.update { it.copy(authenticated = false, historyLoading = false) }
        updateConnection(ConnectionStatus.IDLE, "Connexion arrêtée")
    }

    fun hasPermissions(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    fun requestConfig(): Boolean = sendCommand("GETCFG")

    fun authenticate(pin: String): Boolean {
        if (!pin.matches(Regex("[0-9]{6}"))) {
            _state.update { it.copy(commandFeedback = "Le code doit contenir exactement 6 chiffres.") }
            return false
        }
        // Le PIN n'est jamais journalise, sauvegarde, ni affiche dans les retours.
        return sendCommand("AUTH;PIN=$pin")
    }

    fun lock(): Boolean = sendCommand("LOCK")

    fun startRemote(): Boolean = sendCommand("START")
    fun stopRemote(): Boolean = sendCommand("STOP") // disponible meme sans authentification
    fun acknowledge(): Boolean = sendCommand("ACK")
    fun requestHistory(): Boolean {
        if (!_state.value.connected) return false
        _state.update { it.copy(historyLoading = true, historyProgress = 0) }
        return sendCommand("HISTINFO")
    }

    fun exportArchive(): File {
        val output = File(context.cacheDir, "OPTA_V10_historique.csv")
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine("TYPE;DATE_TELEPHONE;ID;SERIE;DATE_OPTA;HEURE_OPTA;PRESSION_BAR;DEFAUT;RECETTE;NIVEAU;MESSAGE")
            _state.value.tests.sortedBy { it.id }.forEach { t ->
                writer.appendLine(listOf("ESSAI", "", t.id, t.serial, t.testDate, t.testTime,
                    t.pressureBar, t.fault, t.recipe, "", "").joinToString(";") { csv(it.toString()) })
            }
            if (eventFile.exists()) eventFile.forEachLine { line ->
                runCatching {
                    val log = JSONObject(line)
                    writer.appendLine(listOf("EVENEMENT", log.optString("time"), "", "", "", "",
                        "", "", "", log.optString("level"), log.optString("message"))
                        .joinToString(";") { csv(it.toString()) })
                }
            }
        }
        return output
    }

    private fun csv(text: String): String = "\"${text.replace("\"", "\"\"")}\""


    fun requestStatus(): Boolean = sendCommand("STATUS")

    fun sendConsoleCommand(command: String): Boolean {
        val cleaned = command.trim()
        if (cleaned.isEmpty()) return false
        // Pas de saisie de PIN dans la console, et aucune commande privilegiee sans session.
        if (cleaned.startsWith("AUTH", ignoreCase = true)) {
            _state.update { it.copy(commandFeedback = "Utilise le champ Code d’accès, pas la console.") }
            return false
        }
        if (!(_state.value.authenticated) && cleaned.uppercase() !in setOf(
                "STATUS", "SENSORS", "CAPTEURS", "GETCFG", "SECURITY", "HISTINFO", "STOP", "HELP")) {
            _state.update { it.copy(commandFeedback = "Authentification nécessaire.") }
            return false
        }
        appendLocalLog(LogLevel.COMMAND, "> $cleaned")
        return sendCommand(cleaned)
    }

    fun saveConfig(config: OptaConfig): Boolean {
        val validation = config.validationError()
        if (validation != null) {
            _state.update { it.copy(commandFeedback = validation) }
            return false
        }
        val blocked = _state.value.settingsBlockedReason
        if (blocked != null) {
            _state.update { it.copy(commandFeedback = blocked) }
            return false
        }
        appendLocalLog(LogLevel.COMMAND, "Envoi des nouveaux paramètres de la recette ${config.recipe}.")
        return sendCommand(OptaBleProtocol.buildSetConfigCommand(config))
    }

    fun clearLogs() {
        _state.update { it.copy(logs = emptyList()) }
        // L'effacement de l'affichage ne supprime PAS le fichier d'audit persistant.
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (scanning) return
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            updateConnection(ConnectionStatus.ERROR, "Le scanner Bluetooth n’est pas disponible.")
            return
        }

        updateConnection(ConnectionStatus.SEARCHING, "Recherche de OPTA-V10…")
        scanning = true

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(OptaBleProtocol.SERVICE_UUID))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(filters, settings, scanCallback)
        mainHandler.removeCallbacks(scanTimeout)
        mainHandler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        scanning = false
        mainHandler.removeCallbacks(scanTimeout)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val advertisedName = result.scanRecord?.deviceName
            val deviceName = runCatching { result.device.name }.getOrNull()
            val savedAddress = preferences.getString(KEY_DEVICE_ADDRESS, null)
            val resolvedName = advertisedName ?: deviceName ?: OptaBleProtocol.DEVICE_NAME

            val nameMatches = resolvedName in OptaBleProtocol.SUPPORTED_DEVICE_NAMES
            val addressMatches = savedAddress != null && result.device.address == savedAddress
            if (!nameMatches && !addressMatches) return

            stopScan()
            preferences.edit().putString(KEY_DEVICE_ADDRESS, result.device.address).apply()
            _state.update { it.copy(deviceName = resolvedName) }
            updateConnection(ConnectionStatus.CONNECTING, "Connexion à $resolvedName…")

            bluetoothGatt?.close()
            bluetoothGatt = result.device.connectGatt(
                context,
                false,
                gattCallback,
                android.bluetooth.BluetoothDevice.TRANSPORT_LE
            )
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            updateConnection(ConnectionStatus.ERROR, "Échec de la recherche Bluetooth (code $errorCode).")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    reconnectAttempts = 0
                    bluetoothGatt = gatt
                    updateConnection(ConnectionStatus.DISCOVERING, "OPTA connecté, lecture des données…")
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    runCatching { gatt.requestMtu(247) }
                    if (!gatt.discoverServices()) {
                        handleGattFailure(gatt, "Impossible de découvrir les services BLE.")
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    gatt.close()
                    if (bluetoothGatt === gatt) bluetoothGatt = null
                    commandCharacteristic = null
                    notificationQueue.clear()
                    readQueue.clear()
                    writeQueue.clear()
                    writeInProgress = false
                    mainHandler.removeCallbacks(commandTimeout)
                    _state.update { it.copy(authenticated = false, historyLoading = false) }
                    updateConnection(ConnectionStatus.DISCONNECTED, "Connexion perdue. Nouvelle tentative…")
                    appendLocalLog(LogLevel.WARNING, "Connexion Bluetooth perdue.")
                    scheduleReconnect()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handleGattFailure(gatt, "Erreur pendant la découverte des services ($status).")
                return
            }

            val service = gatt.getService(OptaBleProtocol.SERVICE_UUID)
            if (service == null) {
                handleGattFailure(gatt, "Le service Bluetooth OPTA n’a pas été trouvé.")
                return
            }

            commandCharacteristic = service.getCharacteristic(OptaBleProtocol.COMMAND_UUID)
            if (commandCharacteristic == null) {
                handleGattFailure(gatt, "Le programme Opta n’est pas la V10.")
                return
            }

            notificationQueue.clear()
            readQueue.clear()

            service.characteristics
                .filter { characteristic ->
                    characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                }
                .forEach(notificationQueue::add)

            OptaBleProtocol.readableCharacteristics
                .mapNotNull(service::getCharacteristic)
                .forEach(readQueue::add)

            updateConnection(ConnectionStatus.CONNECTED, "Connecté à ${_state.value.deviceName}")
            appendLocalLog(LogLevel.INFO, "Téléphone connecté à l’Opta.")
            enableNextNotification(gatt)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            enableNextNotification(gatt)
        }

        @Deprecated("Deprecated in Android 13")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            parseValue(characteristic.uuid, characteristic.value ?: byteArrayOf())
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            parseValue(characteristic.uuid, value)
        }

        @Deprecated("Deprecated in Android 13")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                parseValue(characteristic.uuid, characteristic.value ?: byteArrayOf())
            }
            readNextCharacteristic(gatt)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) parseValue(characteristic.uuid, value)
            readNextCharacteristic(gatt)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            writeInProgress = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.update {
                    it.copy(commandBusy = false, commandFeedback = "Échec de l’envoi Bluetooth ($status).")
                }
                appendLocalLog(LogLevel.ERROR, "Échec de l’envoi d’une commande Bluetooth.")
                writeQueue.clear()
                return
            }
            writeNextCommand()
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNextNotification(gatt: BluetoothGatt) {
        val characteristic = notificationQueue.pollFirst()
        if (characteristic == null) {
            readNextCharacteristic(gatt)
            return
        }

        val localEnabled = gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(OptaBleProtocol.CCCD_UUID)
        if (!localEnabled || descriptor == null) {
            enableNextNotification(gatt)
            return
        }

        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
        if (!started) enableNextNotification(gatt)
    }

    @SuppressLint("MissingPermission")
    private fun readNextCharacteristic(gatt: BluetoothGatt) {
        val characteristic = readQueue.pollFirst() ?: run {
            mainHandler.post {
                if (_state.value.connected) {
                    sendCommand("SECURITY")
                    requestHistory()
                }
            }
            return
        }
        @Suppress("DEPRECATION")
        val started = gatt.readCharacteristic(characteristic)
        if (!started) readNextCharacteristic(gatt)
    }

    private fun parseValue(uuid: UUID, bytes: ByteArray) {
        when (uuid) {
            OptaBleProtocol.RESPONSE_UUID -> parseResponse(OptaBleProtocol.text(bytes))
            OptaBleProtocol.LOG_UUID -> parseRemoteLog(OptaBleProtocol.text(bytes))
            OptaBleProtocol.CONFIG_UUID -> parseConfiguration(OptaBleProtocol.text(bytes))
            else -> {
                _state.update { current ->
                    val old = current.measurements
                    val updated = when (uuid) {
                        OptaBleProtocol.PRESSURE_CURRENT_UUID -> old.copy(
                            pressureCurrentRaw = OptaBleProtocol.uint16LittleEndian(bytes)
                        )
                        OptaBleProtocol.INLET_PRESSURE_UUID -> old.copy(
                            pressureInletRaw = OptaBleProtocol.uint16LittleEndian(bytes)
                        )
                        OptaBleProtocol.ELEMENT_PRESSURE_UUID -> old.copy(
                            pressureElementRaw = OptaBleProtocol.uint16LittleEndian(bytes)
                        )
                        OptaBleProtocol.RELAYS_UUID -> old.copy(
                            relays = OptaBleProtocol.uint8(bytes)
                        )
                        OptaBleProtocol.PRESSURE_TEST_UUID -> old.copy(
                            pressureTestRaw = OptaBleProtocol.uint16LittleEndian(bytes)
                        )
                        OptaBleProtocol.PHASE_UUID -> old.copy(
                            phaseCode = OptaBleProtocol.uint8(bytes)
                        )
                        OptaBleProtocol.SCREEN_UUID -> old.copy(
                            screenCode = OptaBleProtocol.uint16LittleEndian(bytes)
                        )
                        OptaBleProtocol.FAULT_UUID -> old.copy(
                            faultCode = OptaBleProtocol.uint16LittleEndian(bytes)
                        )
                        OptaBleProtocol.REMAINING_TIME_UUID -> old.copy(
                            remainingSeconds = OptaBleProtocol.uint16LittleEndian(bytes)
                        )
                        OptaBleProtocol.RECIPE_UUID -> old.copy(
                            recipe = OptaBleProtocol.uint16LittleEndian(bytes)
                        )
                        OptaBleProtocol.RESULT_UUID -> old.copy(
                            result = OptaBleProtocol.text(bytes).ifBlank { old.result }
                        )
                        OptaBleProtocol.PHASE_TEXT_UUID -> old.copy(
                            phaseText = OptaBleProtocol.text(bytes).ifBlank { old.phaseText }
                        )
                        OptaBleProtocol.SERIAL_NUMBER_UUID -> old.copy(
                            serialNumber = OptaBleProtocol.text(bytes).ifBlank { "—" }
                        )
                        OptaBleProtocol.SUMMARY_UUID -> old.copy(
                            summary = OptaBleProtocol.text(bytes)
                        )
                        else -> old
                    }
                    current.copy(measurements = updated)
                }
            }
        }
    }

    private fun parseConfiguration(payload: String) {
        val config = OptaBleProtocol.parseConfig(payload) ?: return
        _state.update {
            it.copy(
                config = config,
                configLoaded = true,
                commandBusy = false,
                commandFeedback = if (it.commandFeedback.startsWith("Paramètres")) it.commandFeedback else ""
            )
        }
    }

    private fun parseResponse(payload: String) {
        if (payload.isBlank()) return
        mainHandler.removeCallbacks(commandTimeout)
        if (payload.startsWith("OK|HISTINFO;")) {
            historySyncTotal = Regex("COUNT=(\\d+)").find(payload)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 50) ?: 0
            historySyncIndex = 0
            _state.update { it.copy(historyCount = historySyncTotal, historyProgress = 0,
                historyLoading = historySyncTotal > 0, commandBusy = false,
                commandFeedback = "Historique OPTA : $historySyncTotal essais disponibles.") }
            if (historySyncTotal > 0) mainHandler.post { sendCommand("HIST;N=0") }
            return
        }
        if (payload.startsWith("OK|HIST;")) {
            OptaBleProtocol.parseHistory(payload)?.let(::addRecord)
            historySyncIndex++
            _state.update { it.copy(historyProgress = historySyncIndex,
                historyLoading = historySyncIndex < historySyncTotal,
                commandBusy = false) }
            if (historySyncIndex < historySyncTotal) {
                mainHandler.post { sendCommand("HIST;N=$historySyncIndex") }
            }
            return
        }
        if (payload.startsWith("ERR|HIST_")) {
            _state.update { it.copy(historyLoading = false, commandBusy = false,
                commandFeedback = "Lecture de l’historique interrompue : $payload") }
            return
        }
        val success = payload.startsWith("OK|")
        val message = humanReadableResponse(payload)
        _state.update {
            it.copy(commandBusy = false, commandFeedback = message,
                authenticated = when {
                    payload == "OK|AUTH" -> true
                    payload == "OK|LOCK" || payload == "ERR|AUTH_REQUISE" ||
                        payload == "ERR|CODE_INVALIDE" -> false
                    payload.startsWith("OK|SECURITY;") -> payload.contains("AUTH=1")
                    else -> it.authenticated
                },
                securityConfigured = if (payload.startsWith("OK|SECURITY;"))
                    payload.contains("CODE=1") else it.securityConfigured)
        }
        if (!payload.startsWith("OK|SECURITY;"))
            appendLocalLog(if (success) LogLevel.SUCCESS else LogLevel.ERROR, message)
        if (payload == "OK|CONFIG_ENREGISTREE") requestConfig()
        if (payload == "OK|START_DEMANDE" || payload == "OK|ACK_DEMANDE") {
            mainHandler.post { requestStatus() }
        }
    }

    private fun parseRemoteLog(payload: String) {
        if (payload.isBlank() || payload == lastLogPayload) return
        lastLogPayload = payload
        val parts = payload.split('|', limit = 3)
        val type = parts.getOrNull(0).orEmpty().uppercase()
        val message = parts.getOrNull(2) ?: parts.lastOrNull().orEmpty()
        val level = when (type) {
            "PHASE" -> LogLevel.PHASE
            "OK", "CYCLE_OK" -> LogLevel.SUCCESS
            "ALERTE" -> LogLevel.WARNING
            "ERREUR" -> LogLevel.ERROR
            "CONFIG" -> LogLevel.CONFIG
            "SECURITE" -> LogLevel.WARNING
            else -> LogLevel.INFO
        }
        appendLocalLog(level, message.replace(";", " · "))
    }

    private fun humanReadableResponse(payload: String): String = when {
        payload == "OK|CONFIG" -> "Paramètres reçus depuis l’Opta."
        payload == "OK|AUTH" -> "Code accepté : commandes de maintenance déverrouillées (5 min)."
        payload == "OK|LOCK" -> "Accès verrouillé."
        payload == "OK|START_DEMANDE" -> "Démarrage demandé à l’automate : vérification des sécurités."
        payload == "OK|STOP_PURGE" -> "Arrêt demandé : séquence de purge déclenchée."
        payload == "OK|ACK_DEMANDE" -> "Acquittement demandé à l’automate."
        payload == "ERR|CODE_NON_CONFIGURE" -> "Définis d’abord le code via le moniteur série USB de l’Opta."
        payload == "ERR|CODE_INVALIDE" -> "Code d’accès incorrect."
        payload == "ERR|AUTH_REQUISE" -> "Accès expiré ou verrouillé. Saisis à nouveau le code."
        payload == "ERR|AUTH_BLOQUEE" -> "Trop d’essais : réessaie après le délai de blocage."
        payload == "OK|CONFIG_ENREGISTREE" -> "Paramètres enregistrés et vérification en cours."
        payload == "OK|ZEBRA=ON" -> "Impression Zebra et archivage activés."
        payload == "OK|ZEBRA=OFF" -> "Impression Zebra et archivage désactivés."
        payload.startsWith("OK|STATUS;") -> payload.removePrefix("OK|STATUS;").replace(";", " · ")
        payload.startsWith("OK|SENSORS;") -> "Diagnostic GEMS : " +
            payload.removePrefix("OK|SENSORS;").replace(";", " · ")
        payload.startsWith("OK|") -> payload.removePrefix("OK|").replace('_', ' ')
        payload == "ERR|MACHINE_EN_CYCLE" -> "Modification refusée : la machine est en cycle."
        payload.startsWith("ERR|DEFAUT_ACTIF") -> "Modification refusée : un défaut doit être acquitté sur l’IHM."
        payload == "ERR|PRESSION_RESIDUELLE" -> "Modification refusée : la pression est encore trop élevée."
        payload == "ERR|R60_MIN_500MS" -> "La temporisation des électrovannes doit être au minimum de 500 ms."
        payload == "ERR|R42_SUPERIEUR_R43" -> "La pression minimale de débit dépasse la pression maximale."
        payload == "ERR|R48_DOIT_ETRE_INF_R40" -> "La pression de repos doit être inférieure à la pression cible."
        payload == "ERR|R49_HORS_PLAGE_CAPTEUR_10BAR" -> "Le seuil de sécurité doit rester entre 0,01 et 10,00 bar (capteurs GEMS 3100R)."
        payload == "ERR|R40_DOIT_ETRE_INF_R49" -> "La pression cible doit être inférieure à la pression de sécurité."
        payload == "ERR|R43_DOIT_ETRE_INF_R49" -> "La pression maximale de débit doit être inférieure à la sécurité."
        payload == "ERR|R41_SUPERIEUR_R40" -> "La chute maximale ne peut pas dépasser la pression cible."
        payload.startsWith("ERR|") -> "Commande refusée : ${payload.removePrefix("ERR|").replace('_', ' ').lowercase()}."
        else -> payload
    }

    private fun sendCommand(command: String): Boolean {
        val bytes = command.toByteArray(Charsets.UTF_8)
        if (!_state.value.connected || commandCharacteristic == null) {
            _state.update { it.copy(commandFeedback = "L’Opta n’est pas connecté.") }
            return false
        }
        if (bytes.size > MAX_COMMAND_BYTES) {
            _state.update { it.copy(commandFeedback = "Commande trop longue pour le Bluetooth.") }
            return false
        }
        writeQueue.add(command)
        _state.update { it.copy(commandBusy = true, commandFeedback = "Envoi en cours…") }
        mainHandler.removeCallbacks(commandTimeout)
        mainHandler.postDelayed(commandTimeout, COMMAND_TIMEOUT_MS)
        writeNextCommand()
        return true
    }

    @SuppressLint("MissingPermission")
    private fun writeNextCommand() {
        if (writeInProgress) return
        val command = writeQueue.pollFirst() ?: return
        val gatt = bluetoothGatt ?: return
        val characteristic = commandCharacteristic ?: return
        val bytes = command.toByteArray(Charsets.UTF_8)

        writeInProgress = true
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                bytes,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }

        if (!started) {
            writeInProgress = false
            writeQueue.clear()
            mainHandler.removeCallbacks(commandTimeout)
            _state.update {
                it.copy(commandBusy = false, commandFeedback = "Impossible de démarrer l’écriture Bluetooth.")
            }
        }
    }

    private fun addRecord(record: OptaTestRecord) {
        val existing = _state.value.tests
        if (existing.any { it.id == record.id }) return
        _state.update { it.copy(tests = (it.tests + record).sortedByDescending(OptaTestRecord::id)) }
        saveRecords()
    }

    private fun loadLocalArchive() {
        runCatching {
            if (testsFile.exists()) {
                val arr = JSONArray(testsFile.readText(Charsets.UTF_8))
                val tests = (0 until arr.length()).mapNotNull { i ->
                    val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                    OptaTestRecord(obj.getInt("id"), obj.optString("serial"),
                        obj.optString("date"), obj.optString("time"),
                        obj.optString("pressure"), obj.optInt("fault"), obj.optInt("recipe"))
                }
                _state.update { it.copy(tests = tests.sortedByDescending(OptaTestRecord::id)) }
            }
            if (eventFile.exists()) {
                val entries = eventFile.readLines(Charsets.UTF_8).takeLast(MAX_LOG_ENTRIES)
                    .mapNotNull { row ->
                        runCatching {
                            val obj = JSONObject(row)
                            OptaLogEntry(obj.getLong("id"), obj.getString("time"),
                                LogLevel.valueOf(obj.getString("level")), obj.getString("message"))
                        }.getOrNull()
                    }
                localLogId = entries.maxOfOrNull(OptaLogEntry::id) ?: 0L
                _state.update { it.copy(logs = entries) }
            }
        }
    }

    private fun saveRecords() {
        runCatching {
            val arr = JSONArray()
            _state.value.tests.forEach { t ->
                arr.put(JSONObject().put("id", t.id).put("serial", t.serial)
                    .put("date", t.testDate).put("time", t.testTime)
                    .put("pressure", t.pressureBar).put("fault", t.fault).put("recipe", t.recipe))
            }
            testsFile.writeText(arr.toString(), Charsets.UTF_8)
        }
    }

    private fun appendLocalLog(level: LogLevel, message: String) {
        val entry = newLocalLog(++localLogId, level, message)
        _state.update { current ->
            current.copy(logs = (current.logs + entry).takeLast(MAX_LOG_ENTRIES))
        }
        runCatching {
            eventFile.appendText(JSONObject().put("id", entry.id).put("time", entry.time)
                .put("level", entry.level.name).put("message", entry.message).toString() + "\n",
                Charsets.UTF_8)
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleGattFailure(gatt: BluetoothGatt, message: String) {
        updateConnection(ConnectionStatus.ERROR, message)
        appendLocalLog(LogLevel.ERROR, message)
        gatt.disconnect()
    }

    private fun scheduleReconnect() {
        if (manualStop || !hasPermissions()) return
        reconnectAttempts = (reconnectAttempts + 1).coerceAtMost(6)
        val delay = (1_500L * reconnectAttempts).coerceAtMost(8_000L)
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, delay)
    }

    private fun updateConnection(status: ConnectionStatus, message: String) {
        _state.update { it.copy(connectionStatus = status, connectionMessage = message) }
    }

    companion object {
        private const val SCAN_TIMEOUT_MS = 12_000L
        private const val COMMAND_TIMEOUT_MS = 8_000L
        private const val MAX_COMMAND_BYTES = 159
        private const val MAX_LOG_ENTRIES = 500
        private const val KEY_DEVICE_ADDRESS = "device_address"
    }
}
