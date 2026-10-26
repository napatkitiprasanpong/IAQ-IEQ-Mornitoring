package com.example.iaqieqmornitoring.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class MissionStatus { IDLE, RUNNING, RETURNING, COMPLETED, STOPPED, FAILED }

enum class StopOutcome { PENDING, NAVIGATING, SAMPLING, DONE, FAILED }

data class MissionStopResult(
    val index: Int,
    val location: String,
    val outcome: StopOutcome,
    val timeMillis: Long = 0L,
    val iaq: Int? = null,
    val readings: SensorData? = null,
    val dataFresh: Boolean = true,
    val note: String = ""
)

data class MissionState(
    val status: MissionStatus = MissionStatus.IDLE,
    val routeName: String = "",
    val stops: List<MissionStopResult> = emptyList(),
    val currentIndex: Int = -1,
    val message: String = "Idle",
    val dwellRemaining: Int = 0,
    val startedAtMs: Long = 0L,
    /** Path of the CSV written when the mission ended. */
    val savedTo: String? = null,
    /** Reason the last start request was rejected. */
    val error: String? = null
) {
    val isActive: Boolean
        get() = status == MissionStatus.RUNNING || status == MissionStatus.RETURNING
}

/**
 * Runs an inspection mission: for each location of the chosen route, drive temi there, wait for
 * arrival, let the sensors settle for the configured dwell time, record the readings, move on.
 * Optionally finishes by returning to the home base (charging station).
 */
object MissionManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _state = MutableStateFlow(MissionState())
    val state: StateFlow<MissionState> = _state.asStateFlow()

    internal sealed class NavResult {
        object Arrived : NavResult()
        data class Failed(val reason: String) : NavResult()
    }

    /** Starts a mission. Returns an error message if it cannot start, otherwise null. */
    @Synchronized
    fun start(routeName: String): String? {
        if (_state.value.isActive) return reject("A mission is already running")
        if (RoutineManager.state.value.isMoving) return reject("A routine is running — stop it first")

        val route = AppSettings.routes.value.firstOrNull { it.name == routeName }
            ?: return reject("Route \"$routeName\" does not exist")
        if (route.waypoints.isEmpty()) return reject("This route has no locations. Add some in Settings.")

        val robot = TemiRepository.state.value
        if (!robot.ready) return reject("temi is not connected")

        val known = robot.locations
        if (known.isNotEmpty()) {
            val missing = route.waypoints.filter { w -> known.none { it.equals(w, ignoreCase = true) } }
            if (missing.isNotEmpty()) {
                return reject("Not saved on temi: ${missing.joinToString(", ")}")
            }
        }

        val startedAt = System.currentTimeMillis()
        _state.value = MissionState(
            status = MissionStatus.RUNNING,
            routeName = route.name,
            stops = route.waypoints.mapIndexed { i, w -> MissionStopResult(i, w, StopOutcome.PENDING) },
            currentIndex = 0,
            message = "Starting ${route.name}…",
            startedAtMs = startedAt
        )
        job = scope.launch { runMission(route, startedAt) }
        return null
    }

    @Synchronized
    fun stop() {
        if (!_state.value.isActive) return
        _state.update { it.copy(message = "Stopping…") }
        job?.cancel()
    }

    private fun reject(message: String): String {
        _state.update { it.copy(error = message) }
        return message
    }

    // ------------------------------------------------------------------------------------------

    private suspend fun runMission(route: MissionRoute, startedAt: Long) {
        val dwell = AppSettings.dwellSeconds.value
        val timeoutMs = AppSettings.arrivalTimeoutSeconds.value * 1000L
        val retries = AppSettings.retries.value
        val total = route.waypoints.size
        var returnNote = ""

        try {
            TemiRepository.speak("Starting inspection route ${route.name}")
            for ((i, location) in route.waypoints.withIndex()) {
                updateStop(i) { it.copy(outcome = StopOutcome.NAVIGATING) }
                _state.update { it.copy(currentIndex = i, message = "Going to $location (${i + 1}/$total)") }

                var nav: NavResult = NavResult.Failed("not attempted")
                var attempt = 0
                while (attempt <= retries) {
                    nav = navigate(location, timeoutMs)
                    if (nav is NavResult.Arrived) break
                    attempt++
                    if (attempt <= retries) {
                        _state.update { it.copy(message = "Retrying $location ($attempt/$retries)…") }
                    }
                }

                when (val result = nav) {
                    is NavResult.Arrived -> {
                        TemiRepository.speak("Arrived at $location, sampling air quality")
                        updateStop(i) { it.copy(outcome = StopOutcome.SAMPLING) }
                        for (remaining in dwell downTo 1) {
                            _state.update {
                                it.copy(dwellRemaining = remaining, message = "Sampling air at $location — ${remaining}s")
                            }
                            delay(1000)
                        }
                        val snapshot = SensorRepository.data.value
                        val fresh = SensorRepository.isFresh()
                        val iaq = computeIAQ(snapshot)
                        updateStop(i) {
                            it.copy(
                                outcome = StopOutcome.DONE,
                                timeMillis = System.currentTimeMillis(),
                                iaq = iaq,
                                readings = snapshot,
                                dataFresh = fresh,
                                note = if (fresh) "" else "sensor data was stale"
                            )
                        }
                    }
                    is NavResult.Failed -> {
                        updateStop(i) {
                            it.copy(
                                outcome = StopOutcome.FAILED,
                                timeMillis = System.currentTimeMillis(),
                                note = result.reason
                            )
                        }
                    }
                    else -> {}
                }
                _state.update { it.copy(dwellRemaining = 0) }
            }

            if (AppSettings.returnHome.value) {
                val home = AppSettings.homeBase.value.ifBlank { "home base" }
                _state.update {
                    it.copy(status = MissionStatus.RETURNING, currentIndex = -1, message = "Returning to $home")
                }
                val back = navigate(home, timeoutMs)
                if (back is NavResult.Failed) returnNote = " Could not return to $home (${back.reason})."
            }

            finish(MissionStatus.COMPLETED, route.name, startedAt, returnNote)
        } catch (e: CancellationException) {
            TemiRepository.stopMovement()
            withContext(NonCancellable) {
                finish(MissionStatus.STOPPED, route.name, startedAt, "")
            }
            throw e
        } catch (e: Exception) {
            TemiRepository.stopMovement()
            finish(MissionStatus.FAILED, route.name, startedAt, " ${e.message ?: "Unexpected error"}")
        }
    }

    internal suspend fun navigate(location: String, timeoutMs: Long): NavResult = coroutineScope {
        // Subscribe to temi's go-to events BEFORE sending the command so the result can't be missed.
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(timeoutMs) {
                TemiRepository.events.first {
                    it.location.equals(location, ignoreCase = true) &&
                        (it.status == GoToStatus.COMPLETE || it.status == GoToStatus.ABORT)
                }
            }
        }

        if (!TemiRepository.goTo(location)) {
            waiter.cancel()
            return@coroutineScope NavResult.Failed("temi did not accept the go-to command")
        }

        val event = waiter.await()
        when {
            event == null -> {
                TemiRepository.stopMovement()
                NavResult.Failed("timed out after ${timeoutMs / 1000}s")
            }
            event.status == GoToStatus.COMPLETE -> NavResult.Arrived
            else -> NavResult.Failed(abortReason(event))
        }
    }

    private fun abortReason(e: GoToEvent): String = when (e.descriptionId) {
        1003 -> "robot could not move"
        1004 -> "navigation timeout"
        1005 -> "stopped by user"
        1006 -> "target is outside the map"
        in 2000..2009 -> "blocked (${e.description.ifBlank { "obstacle" }})"
        else -> e.description.ifBlank { "navigation aborted" }
    }

    private fun updateStop(index: Int, transform: (MissionStopResult) -> MissionStopResult) {
        _state.update { s ->
            s.copy(stops = s.stops.map { if (it.index == index) transform(it) else it })
        }
    }

    private suspend fun finish(status: MissionStatus, routeName: String, startedAt: Long, extra: String) {
        val stops = _state.value.stops
        val done = stops.count { it.outcome == StopOutcome.DONE }
        val failed = stops.count { it.outcome == StopOutcome.FAILED }

        val path = if (done + failed > 0) {
            withContext(Dispatchers.IO) { saveCsv(routeName, startedAt, stops) }
        } else null

        val summary = when (status) {
            MissionStatus.COMPLETED -> {
                TemiRepository.speak("Inspection mission complete")
                "Mission complete — $done/${stops.size} stops sampled" + if (failed > 0) ", $failed failed." else "."
            }
            MissionStatus.STOPPED -> {
                TemiRepository.speak("Inspection mission stopped")
                "Mission stopped — $done/${stops.size} stops sampled."
            }
            else -> "Mission failed."
        } + extra

        _state.update {
            it.copy(
                status = status,
                message = summary,
                currentIndex = -1,
                dwellRemaining = 0,
                savedTo = path,
                // stops that never ran are shown as pending, but a stopped one must not stay "going"
                stops = it.stops.map { s ->
                    if (s.outcome == StopOutcome.NAVIGATING || s.outcome == StopOutcome.SAMPLING) {
                        s.copy(outcome = StopOutcome.FAILED, note = "interrupted")
                    } else s
                }
            )
        }
    }

    // ------------------------------------------------------------------------------------------

    private fun csv(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

    private fun saveCsv(routeName: String, startedAt: Long, stops: List<MissionStopResult>): String? {
        return try {
            val ctx = AppSettings.appContext
            val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "missions")
            dir.mkdirs()

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startedAt))
            val safeName = routeName.replace(Regex("[^A-Za-z0-9]+"), "_")
            val file = File(dir, "mission_${stamp}_$safeName.csv")
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

            file.bufferedWriter().use { w ->
                w.appendLine(
                    "time,route,stop,location,outcome,iaq,level,temp_c,humidity_pct,co2_ppm,pm25,pm10,tvoc,hcho,light_lux,sound_db,data_fresh,note"
                )
                for (s in stops) {
                    val r = s.readings
                    val level = s.iaq?.let { getLabel(getLevelFromScore(it.toFloat())) } ?: ""
                    w.appendLine(
                        listOf(
                            if (s.timeMillis > 0) time.format(Date(s.timeMillis)) else "",
                            csv(routeName),
                            (s.index + 1).toString(),
                            csv(s.location),
                            s.outcome.name,
                            s.iaq?.toString() ?: "",
                            level,
                            r?.temp?.toString() ?: "",
                            r?.humidity?.toString() ?: "",
                            r?.co2?.toString() ?: "",
                            r?.pm25?.toString() ?: "",
                            r?.pm10?.toString() ?: "",
                            r?.tvoc?.toString() ?: "",
                            r?.hcho?.toString() ?: "",
                            r?.light?.toString() ?: "",
                            r?.sound?.toString() ?: "",
                            if (r == null) "" else s.dataFresh.toString(),
                            csv(s.note)
                        ).joinToString(",")
                    )
                }
            }
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}
