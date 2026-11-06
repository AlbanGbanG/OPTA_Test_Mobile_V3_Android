package fr.opta.testmobile.ble

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class ConnectionStatus {
    IDLE,
    BLUETOOTH_UNAVAILABLE,
    BLUETOOTH_DISABLED,
    PERMISSION_REQUIRED,
    SEARCHING,
    CONNECTING,
    DISCOVERING,
    CONNECTED,
    NOT_FOUND,
    DISCONNECTED,
    ERROR
}

enum class LogLevel {
    INFO,
    PHASE,
    SUCCESS,
    WARNING,
    ERROR,
    CONFIG,
    COMMAND
}

data class OptaLogEntry(
    val id: Long,
    val time: String,
    val level: LogLevel,
    val message: String
)

data class OptaTestRecord(
    val id: Int,
    val serial: String,
    val testDate: String,
    val testTime: String,
    val pressureBar: String,
    val fault: Int,
    val recipe: Int
) {
    val isSuccess: Boolean get() = fault == 0
}

data class OptaConfig(
    val recipe: Int = 41,
    val pressureTargetRaw: Int = 500,
    val pressureDropMaxRaw: Int = 50,
    val flowPressureMinRaw: Int = 100,
    val flowPressureMaxRaw: Int = 300,
    val pressureBuildSeconds: Int = 10,
    val holdSeconds: Int = 10,
    val flowCheckSeconds: Int = 5,
    val purgeSeconds: Int = 3,
    val restPressureMaxRaw: Int = 20,
    val safetyPressureMaxRaw: Int = 800,
    val valveDelayMs: Int = 500,
    val zebraEnabled: Boolean = false
) {
    val reference: String get() = recipeReference(recipe)

    fun validationError(): String? = when {
        recipe !in 41..52 -> "La recette doit être comprise entre 41 et 52."
        pressureBuildSeconds < 1 || holdSeconds < 1 || flowCheckSeconds < 1 || purgeSeconds < 1 ->
            "Les durées doivent être au minimum de 1 seconde."
        valveDelayMs < 500 -> "La temporisation des électrovannes doit être au minimum de 500 ms."
        flowPressureMinRaw > flowPressureMaxRaw ->
            "La pression minimale de débit doit être inférieure à la pression maximale."
        restPressureMaxRaw >= pressureTargetRaw ->
            "La pression de repos doit être inférieure à la pression cible."
        safetyPressureMaxRaw !in 1..1000 ->
            "La pression de sécurité doit être comprise entre 0,01 et 10,00 bar (capteurs GEMS 3100R)."
        pressureTargetRaw >= safetyPressureMaxRaw ->
            "La pression cible doit être inférieure à la pression de sécurité."
        flowPressureMaxRaw >= safetyPressureMaxRaw ->
            "La pression maximale de débit doit être inférieure à la pression de sécurité."
        pressureDropMaxRaw > pressureTargetRaw ->
            "La chute maximale ne peut pas dépasser la pression cible."
        listOf(
            pressureTargetRaw, pressureDropMaxRaw, flowPressureMinRaw, flowPressureMaxRaw,
            restPressureMaxRaw, safetyPressureMaxRaw, valveDelayMs
        ).any { it !in 0..65535 } -> "Une valeur dépasse la plage acceptée par l’Opta."
        else -> null
    }
}

data class OptaMeasurements(
    val pressureCurrentRaw: Int = 0,
    val pressureTestRaw: Int = 0,
    val pressureInletRaw: Int = 0,
    val pressureElementRaw: Int = 0,
    val relays: Int = 0,
    val phaseCode: Int = 0,
    val screenCode: Int = 21,
    val faultCode: Int = 0,
    val remainingSeconds: Int = 0,
    val recipe: Int = 41,
    val result: String = "PRET",
    val phaseText: String = "REPOS",
    val serialNumber: String = "—",
    val summary: String = ""
) {
    val pressureCurrentBar: Double get() = pressureCurrentRaw / 100.0
    val pressureTestBar: Double get() = pressureTestRaw / 100.0
    val pressureInletBar: Double get() = pressureInletRaw / 100.0
    val pressureElementBar: Double get() = pressureElementRaw / 100.0
    val exhaustActive: Boolean get() = relays and 1 != 0
    val inletActive: Boolean get() = relays and 2 != 0
    val reference: String get() = recipeReference(recipe)
    val faultText: String get() = faultDescription(faultCode)
}

data class OptaUiState(
    val connectionStatus: ConnectionStatus = ConnectionStatus.IDLE,
    val connectionMessage: String = "Prêt à rechercher l’OPTA",
    val deviceName: String = OptaBleProtocol.DEVICE_NAME,
    val measurements: OptaMeasurements = OptaMeasurements(),
    val config: OptaConfig = OptaConfig(),
    val configLoaded: Boolean = false,
    val commandBusy: Boolean = false,
    val commandFeedback: String = "",
    val authenticated: Boolean = false,
    val securityConfigured: Boolean = false,
    val historyLoading: Boolean = false,
    val historyProgress: Int = 0,
    val historyCount: Int = 0,
    val tests: List<OptaTestRecord> = emptyList(),
    val logs: List<OptaLogEntry> = emptyList()
) {
    val connected: Boolean get() = connectionStatus == ConnectionStatus.CONNECTED

    val settingsBlockedReason: String? get() = when {
        !connected -> "Connecte d’abord le téléphone à l’Opta."
        !configLoaded -> "Les paramètres sont en cours de lecture."
        !authenticated -> "Saisis le code d’accès pour modifier les paramètres."
        measurements.phaseCode != 0 -> "Les réglages sont autorisés uniquement lorsque la machine est au REPOS."
        measurements.faultCode != 0 -> "Acquitte d’abord le défaut depuis l’IHM."
        measurements.pressureCurrentRaw > config.restPressureMaxRaw ->
            "La pression doit être sous le seuil de repos avant toute modification."
        else -> null
    }
}

fun newLocalLog(id: Long, level: LogLevel, message: String): OptaLogEntry = OptaLogEntry(
    id = id,
    time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
    level = level,
    message = message
)

fun recipeReference(recipe: Int): String = when (recipe) {
    41 -> "A0294"
    42 -> "A0091"
    43 -> "93820"
    44 -> "A0343"
    45 -> "94191"
    46 -> "A0293"
    47 -> "94196"
    48 -> "A0465"
    49 -> "A0401"
    50 -> "95398"
    51 -> "A0342"
    52 -> "A0190"
    else -> "TEST"
}

fun faultDescription(code: Int): String = when (code) {
    0 -> "Aucun défaut"
    110 -> "Pression résiduelle au repos"
    120 -> "Pression trop basse pendant le contrôle de débit"
    121 -> "Pression trop haute ou circuit bouché"
    210 -> "Pression cible non atteinte"
    211 -> "Chute de pression pendant le test de tenue"
    212 -> "Surpression de sécurité"
    213 -> "Signal capteur pression invalide"
    310 -> "Purge impossible"
    else -> "Défaut non répertorié"
}
