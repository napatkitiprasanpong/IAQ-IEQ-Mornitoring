package com.example.iaqieqmornitoring.utils

import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

enum class ApproachPhase { IDLE, TURNING, DRIVING }

data class ApproachState(
    val phase: ApproachPhase = ApproachPhase.IDLE,
    val distanceM: Float? = null,
    val bearingDeg: Float? = null
)

/** Where the person is, seen from temi's tablet camera. [bearingDeg] is positive when they are to temi's right. */
class PersonEstimate(val distanceM: Float, val bearingDeg: Float)

/**
 * Walks temi to a person who raised a hand from far away, using only the tablet camera.
 *
 * temi's built-in follow mode needs its own depth sensors to find a person, which only works at close
 * range. This controller does not need them: every half second it looks at the latest pose, estimates
 * how far away the person is (from the width of their shoulders in the picture) and in which direction,
 * then either turns towards them or drives forward with temi's obstacle avoidance ("smart" skidJoy).
 * It stops at the configured distance. It gives up if the person is not seen for a few seconds, after a
 * minute, when a mission or routine starts, or when the stop gesture (T-pose) is shown.
 *
 * The distance is an estimate (an adult's shoulders are taken as 0.38 m wide), so use the Calibrate
 * button on the Camera & Depth tab once, standing 2 m from the robot.
 */
object ApproachController {

    private const val SHOULDER_M = 0.38f
    private const val TICK_MS = 500L              // temi recommends >= 500 ms between manual drive commands
    private const val TURN_DEADBAND_DEG = 10f     // closer than this to straight ahead: just drive
    private const val MAX_TURN_STEP_DEG = 40
    private const val TURN_SETTLE_MS = 1500L
    private const val LOST_GIVE_UP_MS = 4000L
    private const val MAX_TOTAL_MS = 60_000L
    private const val FRESH_MS = 1500L            // ignore poses older than this
    private const val MIN_SHOULDER_PX = 6f

    private val _state = MutableStateFlow(ApproachState())
    val state: StateFlow<ApproachState> = _state.asStateFlow()

    val isActive: Boolean get() = _state.value.phase != ApproachPhase.IDLE

    /** Distance and direction of the person in [pose], or null if the shoulders are not clearly visible. */
    fun estimate(pose: PoseResult): PersonEstimate? {
        val ls = pose.points[Kp.L_SHOULDER]
        val rs = pose.points[Kp.R_SHOULDER]
        if (ls.score < 0.3f || rs.score < 0.3f) return null
        val w = pose.imageWidth.toFloat()
        val h = pose.imageHeight.toFloat()
        val shoulderPx = hypot((ls.x - rs.x) * w, (ls.y - rs.y) * h)
        if (shoulderPx < MIN_SHOULDER_PX) return null

        val k = AppSettings.approachFocalPermille.value / 1000f   // focal length in image widths
        val distance = k * w * SHOULDER_M / shoulderPx
        val centreX = (ls.x + rs.x) / 2f
        val bearing = Math.toDegrees(atan2((centreX - 0.5f).toDouble(), k.toDouble())).toFloat()
        return PersonEstimate(distance, bearing)
    }

    /** Stand [knownDistanceM] from the camera facing it, then call this: it stores the camera's focal length. */
    fun calibrate(pose: PoseResult?, knownDistanceM: Float = 2.0f): Boolean {
        if (pose == null) return false
        val ls = pose.points[Kp.L_SHOULDER]
        val rs = pose.points[Kp.R_SHOULDER]
        if (ls.score < 0.3f || rs.score < 0.3f) return false
        val w = pose.imageWidth.toFloat()
        val h = pose.imageHeight.toFloat()
        val shoulderPx = hypot((ls.x - rs.x) * w, (ls.y - rs.y) * h)
        if (shoulderPx < MIN_SHOULDER_PX) return false
        val k = knownDistanceM * shoulderPx / (w * SHOULDER_M)
        if (k < 0.3f || k > 2.0f) return false
        AppSettings.approachFocalPermille.set((k * 1000f).roundToInt())
        return true
    }

    /**
     * Drives towards the person. Returns true once temi is at the stop distance, false if it gave up.
     * Cancelling the calling coroutine stops the robot.
     */
    suspend fun approach(latest: () -> GestureState): Boolean {
        val stopM = AppSettings.approachStopDistanceCm.value / 100f
        val invertTurn = AppSettings.approachInvertTurn.value
        val startedAt = SystemClock.elapsedRealtime()
        var lostMs = 0L

        TemiRepository.speak("I'm coming")
        try {
            if (TemiRepository.state.value.simulated) {
                _state.value = ApproachState(ApproachPhase.DRIVING)
                delay(2500)
                TemiRepository.speak("I'm here")
                return true
            }

            while (currentCoroutineContext().isActive) {
                if (MissionManager.state.value.isActive || RoutineManager.state.value.isMoving) return false
                val now = SystemClock.elapsedRealtime()
                if (now - startedAt > MAX_TOTAL_MS) {
                    TemiRepository.speak("I could not reach you")
                    return false
                }

                val g = latest()
                val pose = g.pose
                val est = if (pose != null && now - g.poseTimeMs <= FRESH_MS) estimate(pose) else null

                if (est == null) {
                    TemiRepository.stopMovement()
                    lostMs += TICK_MS
                    if (lostMs >= LOST_GIVE_UP_MS) {
                        TemiRepository.speak("I can't see you anymore")
                        return false
                    }
                    delay(TICK_MS)
                    continue
                }
                lostMs = 0L

                if (est.distanceM <= stopM) {
                    TemiRepository.stopMovement()
                    TemiRepository.speak("I'm here")
                    return true
                }

                if (abs(est.bearingDeg) > TURN_DEADBAND_DEG) {
                    _state.value = ApproachState(ApproachPhase.TURNING, est.distanceM, est.bearingDeg)
                    val step = est.bearingDeg.coerceIn(-MAX_TURN_STEP_DEG.toFloat(), MAX_TURN_STEP_DEG.toFloat()).roundToInt()
                    // Person on the right (positive bearing) -> turn right, which is a negative turnBy.
                    TemiRepository.turnBy(if (invertTurn) step else -step)
                    delay(TURN_SETTLE_MS)
                } else {
                    _state.value = ApproachState(ApproachPhase.DRIVING, est.distanceM, est.bearingDeg)
                    TemiRepository.skidJoy(1f, 0f, true)
                    delay(TICK_MS)
                }
            }
            return false
        } finally {
            TemiRepository.stopMovement()
            _state.value = ApproachState()
        }
    }
}
