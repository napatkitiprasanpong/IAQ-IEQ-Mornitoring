package com.example.iaqieqmornitoring.utils

import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import com.robotemi.sdk.Robot
import com.robotemi.sdk.listeners.OnGoToLocationStatusChangedListener
import com.robotemi.sdk.listeners.OnLocationsUpdatedListener
import com.robotemi.sdk.listeners.OnRobotReadyListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Same string values the temi SDK reports in onGoToLocationStatusChanged. */
object GoToStatus {
    const val START = "start"
    const val CALCULATING = "calculating"
    const val GOING = "going"
    const val COMPLETE = "complete"
    const val ABORT = "abort"
    const val REPOSING = "reposing"
}

data class GoToEvent(
    val location: String,
    val status: String,
    val descriptionId: Int,
    val description: String
)

data class RobotState(
    val simulated: Boolean = false,
    /** temi SDK is connected and the robot can be commanded. */
    val ready: Boolean = false,
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    /** Locations saved on the robot's map. */
    val locations: List<String> = emptyList(),
    val navLocation: String? = null,
    val navStatus: String? = null
)

/**
 * The only class that talks to the temi SDK. Everything else (mission engine, UI) goes through here,
 * so a simulated robot can be used when the app runs on a normal tablet / emulator.
 */
object TemiRepository : OnRobotReadyListener, OnGoToLocationStatusChangedListener, OnLocationsUpdatedListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(RobotState())
    val state: StateFlow<RobotState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GoToEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<GoToEvent> = _events.asSharedFlow()

    private var activityInfo: ActivityInfo? = null
    private var listening = false
    private var pollJob: Job? = null
    private var simNavJob: Job? = null

    private val simLocations = listOf("Entrance", "Lab A", "Lab B", "Corridor", "home base")

    fun init(info: ActivityInfo?) {
        activityInfo = info
    }

    /** Re-reads the "simulate robot" setting and attaches to (or detaches from) the real robot. */
    fun applyMode() {
        mainHandler.post {
            detachReal()
            pollJob?.cancel()
            pollJob = null
            if (AppSettings.simulateRobot.value) {
                _state.value = RobotState(
                    simulated = true,
                    ready = true,
                    batteryPercent = 87,
                    charging = false,
                    locations = simLocations
                )
            } else {
                _state.value = RobotState(simulated = false, ready = false)
                attachReal()
            }
        }
    }

    /** Call from Activity.onStart so temi keeps the app's top-bar shortcut state in sync. */
    fun onActivityStart() {
        if (_state.value.ready && !_state.value.simulated) registerWithLauncher()
    }

    fun refresh() {
        if (_state.value.simulated) return
        scope.launch { pollReal() }
    }

    // -------- commands --------

    /** Sends the robot to a saved location. Returns false if the command could not be issued. */
    fun goTo(location: String): Boolean {
        if (location.isBlank()) return false
        if (_state.value.simulated) {
            simulateNavigation(location)
            return true
        }
        val robot = robotOrNull() ?: return false
        if (!_state.value.ready) return false
        return try {
            robot.goTo(location)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun stopMovement() {
        simNavJob?.cancel()
        if (_state.value.simulated) return
        try {
            robotOrNull()?.stopMovement()
        } catch (e: Exception) {
            // ignore
        }
    }

    // -------- temi SDK callbacks --------

    override fun onRobotReady(isReady: Boolean) {
        _state.update { it.copy(ready = isReady) }
        if (isReady) {
            registerWithLauncher()
            scope.launch { pollReal() }
        } else {
            _state.update { it.copy(batteryPercent = null) }
        }
    }

    override fun onGoToLocationStatusChanged(
        location: String,
        status: String,
        descriptionId: Int,
        description: String
    ) {
        _state.update { it.copy(navLocation = location, navStatus = status) }
        _events.tryEmit(GoToEvent(location, status, descriptionId, description))
    }

    override fun onLocationsUpdated(locations: List<String>) {
        _state.update { it.copy(locations = locations) }
    }

    // -------- internals --------

    private fun robotOrNull(): Robot? = try {
        Robot.getInstance()
    } catch (e: Throwable) {
        null
    }

    private fun attachReal() {
        val robot = robotOrNull() ?: return
        robot.addOnRobotReadyListener(this)
        robot.addOnGoToLocationStatusChangedListener(this)
        robot.addOnLocationsUpdatedListener(this)
        listening = true
        pollJob = scope.launch {
            while (isActive) {
                pollReal()
                delay(5000)
            }
        }
    }

    private fun detachReal() {
        if (!listening) return
        val robot = robotOrNull() ?: return
        robot.removeOnRobotReadyListener(this)
        robot.removeOnGoToLocationStatusChangedListener(this)
        robot.removeOnLocationsUpdateListener(this)
        listening = false
    }

    private fun registerWithLauncher() {
        val info = activityInfo ?: return
        mainHandler.post {
            try {
                robotOrNull()?.onStart(info)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun pollReal() {
        val robot = robotOrNull() ?: return
        try {
            val ready = robot.isReady
            if (!ready) {
                _state.update { it.copy(ready = false, batteryPercent = null) }
                return
            }
            val battery = robot.batteryData
            val locations = robot.locations
            _state.update { s ->
                s.copy(
                    ready = true,
                    batteryPercent = battery?.level ?: s.batteryPercent,
                    charging = battery?.isCharging ?: s.charging,
                    locations = locations
                )
            }
        } catch (e: Exception) {
            // keep the last known state
        }
    }

    private fun simulateNavigation(location: String) {
        simNavJob?.cancel()
        simNavJob = scope.launch {
            onGoToLocationStatusChanged(location, GoToStatus.START, 0, "simulated")
            delay(600)
            onGoToLocationStatusChanged(location, GoToStatus.GOING, 10007, "simulated")
            delay(3000)
            onGoToLocationStatusChanged(location, GoToStatus.COMPLETE, 500, "simulated")
        }
    }
}
