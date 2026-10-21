package com.example.iaqieqmornitoring.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

enum class CheckState { CHECKING, OK, WARN, FAIL }

data class CheckResult(val title: String, val state: CheckState, val detail: String)

fun overallState(checks: List<CheckResult>): CheckState = when {
    checks.any { it.state == CheckState.FAIL } -> CheckState.FAIL
    checks.any { it.state == CheckState.WARN || it.state == CheckState.CHECKING } -> CheckState.WARN
    else -> CheckState.OK
}

/** Live results for the six startup cards. Recomputed as the sensor link / robot state changes. */
@Composable
fun rememberStartupChecks(): List<CheckResult> {
    val sensor by SensorRepository.data.collectAsState()
    val link by SensorRepository.link.collectAsState()
    val robot by TemiRepository.state.collectAsState()

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val storage = remember(now / 5000) { storageCheck() }

    // 1. MQTT / Pi link
    val mqtt = when {
        link.mode == SensorMode.SIMULATED ->
            CheckResult("MQTT Connection", CheckState.WARN, "Simulated (no Pi)")
        link.connected ->
            CheckResult("MQTT Connection", CheckState.OK, "Connected")
        link.connecting ->
            CheckResult("MQTT Connection", CheckState.CHECKING, link.detail)
        else ->
            CheckResult("MQTT Connection", CheckState.FAIL, link.detail)
    }

    // 2. Data actually arriving
    val ageSec = if (sensor.timestampMs == 0L) -1L else (now - sensor.timestampMs) / 1000
    val comms = when {
        ageSec < 0 -> CheckResult("Sensor Communication", CheckState.FAIL, "No data received yet")
        ageSec <= 15 -> CheckResult("Sensor Communication", CheckState.OK, "Last data ${ageSec}s ago")
        else -> CheckResult("Sensor Communication", CheckState.FAIL, "No data for ${ageSec}s")
    }

    // 3. How many of the 9 sensors have reported
    val total = SensorRepository.ALL_FIELDS.size
    val reporting = link.fields.size
    val missing = SensorRepository.ALL_FIELDS.filter { it !in link.fields }
    val sensors = when {
        reporting == 0 -> CheckResult("Air Quality Sensors", CheckState.FAIL, "No sensors reporting")
        reporting >= total -> CheckResult("Air Quality Sensors", CheckState.OK, "$total/$total sensors reporting")
        else -> CheckResult(
            "Air Quality Sensors", CheckState.WARN,
            "$reporting/$total — missing ${missing.joinToString(", ")}"
        )
    }

    // 4. temi battery
    val pct = robot.batteryPercent
    val battery = when {
        pct == null -> CheckResult("Battery Level", CheckState.FAIL, "Battery unavailable")
        pct < 15 -> CheckResult("Battery Level", CheckState.FAIL, "$pct%" + if (robot.charging) " · charging" else " · low")
        pct < 30 -> CheckResult("Battery Level", CheckState.WARN, "$pct%" + if (robot.charging) " · charging" else "")
        else -> CheckResult("Battery Level", CheckState.OK, "$pct%" + if (robot.charging) " · charging" else "")
    }

    // 5. temi navigation
    val navigation = when {
        robot.simulated -> CheckResult("Robot Navigation", CheckState.WARN, "Simulated robot")
        !robot.ready -> CheckResult("Robot Navigation", CheckState.FAIL, "temi not connected")
        robot.locations.isEmpty() -> CheckResult("Robot Navigation", CheckState.WARN, "No saved locations")
        else -> CheckResult("Robot Navigation", CheckState.OK, "${robot.locations.size} locations saved")
    }

    return listOf(mqtt, comms, sensors, battery, navigation, storage)
}

private fun storageCheck(): CheckResult {
    return try {
        val ctx = AppSettings.appContext
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val freeMb = dir.usableSpace / (1024 * 1024)
        when {
            !dir.canWrite() -> CheckResult("Storage System", CheckState.FAIL, "Cannot write to storage")
            freeMb < 100 -> CheckResult("Storage System", CheckState.WARN, "Only $freeMb MB free")
            else -> CheckResult("Storage System", CheckState.OK, "$freeMb MB free")
        }
    } catch (e: Exception) {
        CheckResult("Storage System", CheckState.FAIL, "Storage unavailable")
    }
}
