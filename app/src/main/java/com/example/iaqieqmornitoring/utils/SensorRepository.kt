package com.example.iaqieqmornitoring.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

data class SensorData(
    val temp: Float = 22f,
    val humidity: Float = 50f,
    val co2: Float = 400f,
    val pm25: Float = 10f,
    val pm10: Float = 20f,
    val tvoc: Float = 10f,
    val hcho: Float = 0.02f,
    val light: Float = 300f,
    val sound: Float = 40f,
    /** When this reading arrived (epoch ms). 0 = nothing received yet. */
    val timestampMs: Long = 0L
)

enum class SensorMode { MQTT, SIMULATED }

data class SensorLinkState(
    val mode: SensorMode = SensorMode.MQTT,
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val detail: String = "Not started",
    /** Sensor names (see [SensorRepository.ALL_FIELDS]) that have been reported so far. */
    val fields: Set<String> = emptySet()
)

object SensorRepository {

    val ALL_FIELDS = listOf("TEMP", "HUMIDITY", "CO2", "PM2.5", "PM10", "TVOC", "HCHO", "LIGHT", "SOUND")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var simJob: Job? = null

    private val _data = MutableStateFlow(SensorData())
    val data: StateFlow<SensorData> = _data.asStateFlow()

    private val _link = MutableStateFlow(SensorLinkState())
    val link: StateFlow<SensorLinkState> = _link.asStateFlow()

    /** (Re)starts either the simulator or the MQTT link to the Raspberry Pi, per AppSettings. */
    fun applySettings() {
        simJob?.cancel()
        simJob = null
        PiSensorClient.disconnect()

        if (AppSettings.simulateSensors.value) {
            startSimulation()
        } else {
            PiSensorClient.connect(
                host = AppSettings.mqttHost.value.trim(),
                port = AppSettings.mqttPort.value,
                topic = AppSettings.mqttTopic.value.trim(),
                user = AppSettings.mqttUser.value,
                pass = AppSettings.mqttPass.value
            )
        }
    }

    /** True if a reading arrived within [maxAgeMs]. */
    fun isFresh(maxAgeMs: Long = 15_000L): Boolean {
        val ts = _data.value.timestampMs
        return ts > 0L && System.currentTimeMillis() - ts <= maxAgeMs
    }

    // ---- called by PiSensorClient ----
    internal fun publish(newData: SensorData, fieldsSeen: Set<String>) {
        _data.value = newData.copy(timestampMs = System.currentTimeMillis())
        _link.update { it.copy(fields = it.fields + fieldsSeen) }
    }

    internal fun setLink(state: SensorLinkState) {
        _link.value = state
    }

    internal fun updateLink(transform: (SensorLinkState) -> SensorLinkState) {
        _link.update(transform)
    }

    // ---- simulator (same value ranges as the original prototype) ----
    private fun startSimulation() {
        val all = ALL_FIELDS.toSet()
        _link.value = SensorLinkState(
            mode = SensorMode.SIMULATED,
            connected = true,
            detail = "Simulated sensor data",
            fields = all
        )
        simJob = scope.launch {
            publish(SensorData(), all)
            while (isActive) {
                delay(2000)
                publish(
                    SensorData(
                        temp = Random.nextFloat() * 5 + 20,
                        humidity = Random.nextFloat() * 20 + 40,
                        co2 = Random.nextFloat() * 200 + 400,
                        pm25 = Random.nextFloat() * 50,
                        pm10 = Random.nextFloat() * 80,
                        tvoc = Random.nextFloat() * 80,
                        hcho = Random.nextFloat() * 0.1f,
                        light = Random.nextFloat() * 500,
                        sound = Random.nextFloat() * 80
                    ),
                    all
                )
            }
        }
    }
}
