package fr.opta.testmobile.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object OptaBleProtocol {
    const val DEVICE_NAME = "OPTA-V10"
    val SUPPORTED_DEVICE_NAMES = setOf("OPTA-V10")

    val SERVICE_UUID: UUID = uuid("0001")
    val PRESSURE_CURRENT_UUID: UUID = uuid("0002")
    val PRESSURE_TEST_UUID: UUID = uuid("0003")
    val PHASE_UUID: UUID = uuid("0004")
    val SCREEN_UUID: UUID = uuid("0005")
    val FAULT_UUID: UUID = uuid("0006")
    val REMAINING_TIME_UUID: UUID = uuid("0007")
    val RECIPE_UUID: UUID = uuid("0008")
    val RESULT_UUID: UUID = uuid("0009")
    val PHASE_TEXT_UUID: UUID = uuid("000A")
    val SERIAL_NUMBER_UUID: UUID = uuid("000B")
    val SUMMARY_UUID: UUID = uuid("000C")
    val COMMAND_UUID: UUID = uuid("000D")
    val RESPONSE_UUID: UUID = uuid("000E")
    val LOG_UUID: UUID = uuid("000F")
    val CONFIG_UUID: UUID = uuid("0010")
    val INLET_PRESSURE_UUID: UUID = uuid("0011")
    val ELEMENT_PRESSURE_UUID: UUID = uuid("0012")
    val RELAYS_UUID: UUID = uuid("0013")

    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val readableCharacteristics: List<UUID> = listOf(
        PRESSURE_CURRENT_UUID,
        PRESSURE_TEST_UUID,
        PHASE_UUID,
        SCREEN_UUID,
        FAULT_UUID,
        REMAINING_TIME_UUID,
        RECIPE_UUID,
        RESULT_UUID,
        PHASE_TEXT_UUID,
        SERIAL_NUMBER_UUID,
        SUMMARY_UUID,
        RESPONSE_UUID,
        LOG_UUID,
        CONFIG_UUID,
        INLET_PRESSURE_UUID,
        ELEMENT_PRESSURE_UUID,
        RELAYS_UUID
    )

    fun uint16LittleEndian(bytes: ByteArray): Int {
        if (bytes.size < 2) return 0
        return ByteBuffer.wrap(bytes.copyOfRange(0, 2))
            .order(ByteOrder.LITTLE_ENDIAN)
            .short
            .toInt() and 0xFFFF
    }

    fun uint8(bytes: ByteArray): Int = bytes.firstOrNull()?.toInt()?.and(0xFF) ?: 0

    fun text(bytes: ByteArray): String = bytes
        .toString(Charsets.UTF_8)
        .trimEnd('\u0000', ' ', '\r', '\n')

    fun parseConfig(payload: String): OptaConfig? {
        if (!payload.startsWith("CFG;")) return null
        val values = payload.split(';')
            .drop(1)
            .mapNotNull { token ->
                val separator = token.indexOf('=')
                if (separator <= 0) null
                else token.substring(0, separator) to token.substring(separator + 1).toIntOrNull()
            }
            .filter { it.second != null }
            .associate { it.first to requireNotNull(it.second) }

        val required = listOf(
            "R54", "R40", "R41", "R42", "R43", "R44", "R45",
            "R46", "R47", "R48", "R49", "R60", "R61"
        )
        if (!required.all(values::containsKey)) return null

        return OptaConfig(
            recipe = values.getValue("R54"),
            pressureTargetRaw = values.getValue("R40"),
            pressureDropMaxRaw = values.getValue("R41"),
            flowPressureMinRaw = values.getValue("R42"),
            flowPressureMaxRaw = values.getValue("R43"),
            pressureBuildSeconds = values.getValue("R44"),
            holdSeconds = values.getValue("R45"),
            flowCheckSeconds = values.getValue("R46"),
            purgeSeconds = values.getValue("R47"),
            restPressureMaxRaw = values.getValue("R48"),
            safetyPressureMaxRaw = values.getValue("R49"),
            valveDelayMs = values.getValue("R60"),
            zebraEnabled = values.getValue("R61") != 0
        )
    }

    fun parseHistory(payload: String): OptaTestRecord? {
        if (!payload.startsWith("OK|HIST;")) return null
        val params = payload.removePrefix("OK|HIST;")
            .split(';').mapNotNull { field ->
                val pos = field.indexOf('=')
                if (pos < 1) null else field.substring(0, pos) to field.substring(pos + 1)
            }.toMap()
        return OptaTestRecord(
            id = params["ID"]?.toIntOrNull() ?: return null,
            serial = params["SN"] ?: "",
            testDate = params["D"] ?: "",
            testTime = params["T"] ?: "",
            pressureBar = params["P"] ?: "",
            fault = params["ERR"]?.toIntOrNull() ?: return null,
            recipe = params["REC"]?.toIntOrNull() ?: return null
        )
    }

    fun buildSetConfigCommand(config: OptaConfig): String = buildString(150) {
        append("SETCFG;R54=").append(config.recipe)
        append(";R40=").append(config.pressureTargetRaw)
        append(";R41=").append(config.pressureDropMaxRaw)
        append(";R42=").append(config.flowPressureMinRaw)
        append(";R43=").append(config.flowPressureMaxRaw)
        append(";R44=").append(config.pressureBuildSeconds)
        append(";R45=").append(config.holdSeconds)
        append(";R46=").append(config.flowCheckSeconds)
        append(";R47=").append(config.purgeSeconds)
        append(";R48=").append(config.restPressureMaxRaw)
        append(";R49=").append(config.safetyPressureMaxRaw)
        append(";R60=").append(config.valveDelayMs)
        append(";R61=").append(if (config.zebraEnabled) 1 else 0)
    }

    private fun uuid(suffix: String): UUID = UUID.fromString(
        "7A9A$suffix-6E0A-4DA1-9D8B-3A4E00000001"
    )
}
