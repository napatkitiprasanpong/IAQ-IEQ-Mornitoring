package com.example.iaqieqmornitoring.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class RoutinePhase { IDLE, GOING, ASKING, RETURNING, WAITING, DONE, STOPPED, FAILED }

enum class PromptAnswer { YES, SNOOZE, NO_ANSWER }

/**
 * A one-destination mission created every time somebody answers "Come back in N mins" (or does not
 * answer). [dueAtMs] is when temi should leave home base again; 0 means "the timer starts as soon as
 * temi is back at home base".
 */
data class TempMission(val id: Int, val location: String, val dueAtMs: Long, val round: Int) {
    val label: String get() = "Temp mission #$id"
}

data class RoutineLogEntry(val timeMs: Long, val text: String)

data class RoutineState(
    val phase: RoutinePhase = RoutinePhase.IDLE,
    val routineName: String = "",
    val message: String = "Idle",
    val currentLocation: String = "",
    /** Temp missions that are still waiting to run. The routine is done when this is empty. */
    val tempMissions: List<TempMission> = emptyList(),
    val log: List<RoutineLogEntry> = emptyList(),
    /** Reason the last start request was rejected. */
    val error: String? = null
) {
    /** A routine is in progress, including the time temi waits at home base for a timer. */
    val isActive: Boolean
        get() = phase == RoutinePhase.GOING || phase == RoutinePhase.ASKING ||
            phase == RoutinePhase.RETURNING || phase == RoutinePhase.WAITING

    /** temi is driving or standing at a stop talking to someone (not just waiting at home base). */
    val isMoving: Boolean
        get() = phase == RoutinePhase.GOING || phase == RoutinePhase.ASKING || phase == RoutinePhase.RETURNING
}

/** What the popup shows. */
data class RoutinePrompt(
    val routineName: String,
    val location: String,
    val text: String,
    val snoozeMinutes: Int,
    val secondsLeft: Int
)

/**
 * Runs scheduled routines.
 *
 *  1. At the routine's time (while the app is running) temi drives the routine's route.
 *  2. At each stop it speaks the routine's message and shows a popup: "Yes" or "Come back in N mins".
 *       - Yes: temi carries on to the next stop.
 *       - Come back in N mins (or no answer): temi carries on too, and a temp mission for that stop is
 *         remembered.
 *  3. After the last stop temi returns to home base and starts the N-minute timer of every temp mission.
 *  4. When a timer ends, temi goes to that one stop and asks again. "Yes" -> back to home base, that
 *     temp mission is cleared. "Come back in N mins" -> back to home base and a new temp mission
 *     (new timer) replaces it.
 *  5. When no temp missions are left the routine is done.
 *
 * Nothing here survives the app being closed: the schedule is saved, pending temp missions are not.
 */
object RoutineManager {

    /** A routine whose time passed more than this many minutes ago (app was closed, robot busy) is skipped. */
    private const val LATE_GRACE_MIN = 10
    private const val SCHEDULER_TICK_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var schedulerJob: Job? = null

    @Volatile
    private var waiter: CompletableDeferred<PromptAnswer>? = null
    private var nextTempId = 1

    private val _state = MutableStateFlow(RoutineState())
    val state: StateFlow<RoutineState> = _state.asStateFlow()

    private val _prompt = MutableStateFlow<RoutinePrompt?>(null)
    val prompt: StateFlow<RoutinePrompt?> = _prompt.asStateFlow()

    private enum class VisitResult { YES, SNOOZED, UNREACHABLE }

    // ------------------------------------------------------------------------------------------
    // scheduling
    // ------------------------------------------------------------------------------------------

    /** Starts the once-every-10-seconds check for routines that are due. Safe to call repeatedly. */
    fun startScheduler() {
        if (schedulerJob?.isActive == true) return
        schedulerJob = scope.launch {
            while (isActive) {
                try {
                    checkSchedule()
                } catch (e: Exception) {
                    // never let the scheduler die
                }
                delay(SCHEDULER_TICK_MS)
            }
        }
    }

    private fun today(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun checkSchedule() {
        if (_state.value.isActive || MissionManager.state.value.isActive) return
        if (!TemiRepository.state.value.ready) return

        val now = Calendar.getInstance()
        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val day = today()

        val due = AppSettings.routines.value
            .filter { it.enabled && it.lastRunDay != day && (nowMin - it.minuteOfDay) in 0 until LATE_GRACE_MIN }
            .minByOrNull { it.minuteOfDay }
            ?: return

        // Mark it first, so a routine that cannot start is not retried every 10 seconds.
        markRan(due.id, day)
        begin(due)
    }

    private fun markRan(id: String, day: String) {
        AppSettings.updateRoutines { list -> list.map { if (it.id == id) it.copy(lastRunDay = day) else it } }
    }

    /** Starts a routine right now (the "Run now" button). Returns an error message, or null if it started. */
    fun runNow(routineId: String): String? {
        val routine = AppSettings.routines.value.firstOrNull { it.id == routineId }
            ?: return "Routine not found"
        return begin(routine)
    }

    /** Stops the routine: temi stops moving and every waiting temp mission is discarded. */
    fun stop() {
        if (!_state.value.isActive) return
        _state.update { it.copy(message = "Stopping…") }
        job?.cancel()
    }

    /** Called by the popup buttons. */
    fun answer(answer: PromptAnswer) {
        waiter?.complete(answer)
    }

    // ------------------------------------------------------------------------------------------
    // starting
    // ------------------------------------------------------------------------------------------

    @Synchronized
    private fun begin(r: Routine): String? {
        if (_state.value.isActive) return "A routine is already in progress"
        if (MissionManager.state.value.isActive) return reject("A mission is running — wait for it to finish")

        val route = AppSettings.routes.value.firstOrNull { it.name == r.routeName }
            ?: return reject("Mission route \"${r.routeName}\" does not exist")
        if (route.waypoints.isEmpty()) return reject("Route \"${route.name}\" has no locations. Add some in Settings.")

        val robot = TemiRepository.state.value
        if (!robot.ready) return reject("temi is not connected")

        val known = robot.locations
        if (known.isNotEmpty()) {
            val missing = route.waypoints.filter { w -> known.none { it.equals(w, ignoreCase = true) } }
            if (missing.isNotEmpty()) return reject("Not saved on temi: ${missing.joinToString(", ")}")
        }

        nextTempId = 1
        _state.value = RoutineState(
            phase = RoutinePhase.GOING,
            routineName = r.name,
            message = "Starting ${r.name}…",
            log = listOf(RoutineLogEntry(System.currentTimeMillis(), "Routine \"${r.name}\" started"))
        )
        job = scope.launch { runRoutine(r, route) }
        return null
    }

    private fun reject(message: String): String {
        _state.update { it.copy(error = message) }
        return message
    }

    // ------------------------------------------------------------------------------------------
    // running
    // ------------------------------------------------------------------------------------------

    private suspend fun runRoutine(r: Routine, route: MissionRoute) {
        val timeoutMs = AppSettings.arrivalTimeoutSeconds.value * 1000L
        val retries = AppSettings.retries.value
        val total = route.waypoints.size
        val pending = mutableListOf<TempMission>()

        try {
            TemiRepository.speak("Starting routine ${r.name}")

            // ---- the mission itself: visit every stop in order ----
            for ((i, location) in route.waypoints.withIndex()) {
                val result = visit(r, location, "(${i + 1}/$total)", timeoutMs, retries)
                if (result == VisitResult.SNOOZED) {
                    val temp = TempMission(nextTempId++, location, 0L, 1)
                    pending += temp
                    addLog("${temp.label} created for $location")
                    publish(pending)
                }
            }

            // ---- back to home base, then the timers start ----
            goHome(timeoutMs)
            val snoozeMs = AppSettings.routineSnoozeMinutes.value * 60_000L
            val timerStart = System.currentTimeMillis()
            for (k in pending.indices) {
                if (pending[k].dueAtMs == 0L) pending[k] = pending[k].copy(dueAtMs = timerStart + snoozeMs)
            }
            publish(pending)

            // ---- temp missions, earliest timer first, until every one is cleared ----
            while (pending.isNotEmpty()) {
                val next = pending.minByOrNull { it.dueAtMs } ?: break
                _state.update {
                    it.copy(
                        phase = RoutinePhase.WAITING,
                        currentLocation = "",
                        message = "Waiting at home base — ${pending.size} temp mission(s) pending"
                    )
                }
                while (System.currentTimeMillis() < next.dueAtMs) delay(500)
                // Never drive off in the middle of an inspection mission; go as soon as it is over.
                while (MissionManager.state.value.isActive) delay(1000)

                pending.remove(next)
                publish(pending)

                val result = visit(r, next.location, "(${next.label})", timeoutMs, retries)
                goHome(timeoutMs)

                if (result == VisitResult.SNOOZED) {
                    val again = TempMission(
                        id = nextTempId++,
                        location = next.location,
                        dueAtMs = System.currentTimeMillis() + AppSettings.routineSnoozeMinutes.value * 60_000L,
                        round = next.round + 1
                    )
                    pending += again
                    addLog("${again.label} created for ${again.location} (round ${again.round})")
                } else if (result == VisitResult.YES) {
                    addLog("${next.label} cleared")
                }
                publish(pending)
            }

            TemiRepository.speak("Routine ${r.name} complete")
            addLog("Routine complete — every reminder cleared")
            _state.update {
                it.copy(
                    phase = RoutinePhase.DONE,
                    currentLocation = "",
                    message = "Routine complete — every reminder cleared."
                )
            }
        } catch (e: CancellationException) {
            TemiRepository.stopMovement()
            addLog("Routine stopped")
            _state.update {
                it.copy(
                    phase = RoutinePhase.STOPPED,
                    currentLocation = "",
                    tempMissions = emptyList(),
                    message = "Routine stopped."
                )
            }
            throw e
        } catch (e: Exception) {
            TemiRepository.stopMovement()
            addLog("Routine failed: ${e.message ?: "unexpected error"}")
            _state.update {
                it.copy(
                    phase = RoutinePhase.FAILED,
                    currentLocation = "",
                    tempMissions = emptyList(),
                    message = "Routine failed. ${e.message ?: ""}".trim()
                )
            }
        }
    }

    /** Drive to [location], speak the routine's message and wait for the answer. */
    private suspend fun visit(
        r: Routine,
        location: String,
        label: String,
        timeoutMs: Long,
        retries: Int
    ): VisitResult {
        setPhase(RoutinePhase.GOING, location, "Going to $location $label")

        var nav: MissionManager.NavResult = MissionManager.NavResult.Failed("not attempted")
        var attempt = 0
        while (attempt <= retries) {
            nav = MissionManager.navigate(location, timeoutMs)
            if (nav is MissionManager.NavResult.Arrived) break
            attempt++
            if (attempt <= retries) _state.update { it.copy(message = "Retrying $location ($attempt/$retries)…") }
        }
        val failed = nav as? MissionManager.NavResult.Failed
        if (failed != null) {
            addLog("Could not reach $location: ${failed.reason}")
            return VisitResult.UNREACHABLE
        }

        setPhase(RoutinePhase.ASKING, location, "Asking at $location: ${r.message}")
        val answer = ask(r, location)
        val minutes = AppSettings.routineSnoozeMinutes.value
        val minutesText = if (minutes == 1) "1 minute" else "$minutes minutes"

        return if (answer == PromptAnswer.YES) {
            addLog("$location: answered Yes")
            TemiRepository.speak("Great, thank you")
            VisitResult.YES
        } else {
            addLog(
                if (answer == PromptAnswer.NO_ANSWER) "$location: no answer, coming back in $minutesText"
                else "$location: come back in $minutesText"
            )
            TemiRepository.speak("Okay, I will come back in $minutesText")
            VisitResult.SNOOZED
        }
    }

    /** Speaks the message, shows the popup and waits for a button (or for the answer timeout). */
    private suspend fun ask(r: Routine, location: String): PromptAnswer {
        val deferred = CompletableDeferred<PromptAnswer>()
        waiter = deferred
        val snoozeMinutes = AppSettings.routineSnoozeMinutes.value
        var secondsLeft = AppSettings.routinePromptTimeoutSeconds.value

        TemiRepository.speak(r.message)
        try {
            var answer: PromptAnswer? = null
            while (answer == null && secondsLeft > 0) {
                _prompt.value = RoutinePrompt(r.name, location, r.message, snoozeMinutes, secondsLeft)
                answer = withTimeoutOrNull(1000L) { deferred.await() }
                secondsLeft--
            }
            return answer ?: PromptAnswer.NO_ANSWER
        } finally {
            waiter = null
            _prompt.value = null
        }
    }

    private suspend fun goHome(timeoutMs: Long) {
        val home = AppSettings.homeBase.value.ifBlank { "home base" }
        setPhase(RoutinePhase.RETURNING, home, "Returning to $home")
        val back = MissionManager.navigate(home, timeoutMs)
        if (back is MissionManager.NavResult.Failed) addLog("Could not return to $home: ${back.reason}")
    }

    // ------------------------------------------------------------------------------------------

    private fun setPhase(phase: RoutinePhase, location: String, message: String) {
        _state.update { it.copy(phase = phase, currentLocation = location, message = message) }
    }

    private fun publish(pending: List<TempMission>) {
        _state.update { it.copy(tempMissions = pending.toList()) }
    }

    private fun addLog(text: String) {
        _state.update { it.copy(log = (it.log + RoutineLogEntry(System.currentTimeMillis(), text)).takeLast(40)) }
    }
}
