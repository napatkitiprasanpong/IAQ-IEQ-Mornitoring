package com.example.iaqieqmornitoring.utils

import android.content.Context
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class GestureState(
    val pose: PoseResult? = null,
    /** A hand has been raised (full arm or half arm) long enough. */
    val handRaised: Boolean = false,
    /** Both arms straight out to the sides (T-pose): the stop gesture. */
    val tPose: Boolean = false,
    /** A person (head and both shoulders) has been in view for a few frames in a row. */
    val personVisible: Boolean = false,
    /** The tablet is currently sweeping up and down looking for a person. */
    val scanning: Boolean = false,
    val tiltDeg: Int = 0,
    val error: String? = null,
    /** Pose estimates per second (rolling). */
    val fps: Int = 0
)

/**
 * Glue between the RGB camera, the pose model, the gesture detectors and temi:
 * camera frame -> MoveNet -> RaisedHandDetector -> (if enabled) temi follows the person for a while.
 * It only runs while the Camera & Depth tab is on screen.
 */
object GestureEngine {

    private const val MIN_FRAME_GAP_MS = 100L      // at most ~10 pose estimates per second
    private const val TRIGGER_COOLDOWN_MS = 10_000L // ignore new raised hands right after one was acted on

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(GestureState())
    val state: StateFlow<GestureState> = _state.asStateFlow()

    private var detector: PoseDetector? = null
    private val handDetector = RaisedHandDetector()
    private val tPoseDetector = TPoseDetector()
    private var wasTPose = false
    private var lastRunMs = 0L
    private var wasHandRaised = false
    private var lastTriggerMs = 0L
    private var followJob: Job? = null
    private var controlJob: Job? = null
    private var visibleFrames = 0
    private var fpsCount = 0
    private var fpsSince = 0L

    fun createAnalyzer(context: Context): ImageAnalysis.Analyzer {
        val appContext = context.applicationContext
        ensureControlLoop()
        return ImageAnalysis.Analyzer { image -> analyze(appContext, image) }
    }

    private fun analyze(context: Context, image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastRunMs < MIN_FRAME_GAP_MS) return
            lastRunMs = now

            val det = detector ?: PoseDetector(context).also { detector = it }
            val pose = det.detect(image.toBitmap(), image.imageInfo.rotationDegrees)
            val tPose = tPoseDetector.update(pose, now)
            // A T-pose is never a raised hand; the stop gesture takes priority.
            val handRaised = handDetector.update(pose, now) && !tPose

            if (now - fpsSince >= 1000) {
                _state.value = _state.value.copy(fps = fpsCount)
                fpsCount = 0; fpsSince = now
            }
            fpsCount++
            visibleFrames = if (isPersonInView(pose)) visibleFrames + 1 else 0
            _state.value = _state.value.copy(
                pose = pose, handRaised = handRaised, tPose = tPose,
                personVisible = visibleFrames >= 3, error = null
            )

            if (handRaised && !wasHandRaised) onHandRaised(now)
            wasHandRaised = handRaised

            if (tPose && !wasTPose) onTPoseStarted(now)
            wasTPose = tPose
        } catch (e: Throwable) {
            _state.value = _state.value.copy(
                pose = null, handRaised = false, tPose = false, personVisible = false,
                error = "Pose model problem: ${e.message ?: e.javaClass.simpleName}"
            )
        } finally {
            image.close()
        }
    }

    private fun onHandRaised(now: Long) {
        if (!AppSettings.raiseHandFollowEnabled.value) return
        if (now - lastTriggerMs < TRIGGER_COOLDOWN_MS) return
        if (MissionManager.state.value.isActive) return      // never hijack a running inspection
        if (TemiRepository.following.value) return
        if (!TemiRepository.state.value.ready) return

        if (TemiRepository.beWithMe()) {
            lastTriggerMs = now
            TemiRepository.speak("Hi! I'm coming to you.")
            followJob?.cancel()
            followJob = scope.launch {
                delay(AppSettings.raiseHandFollowSeconds.value * 1000L)
                TemiRepository.stopFollowing()
            }
        }
    }

    // ---------------- find a person by tilting the tablet ----------------

    private const val SCAN_STEP_DEG = 5          // each move is small, so the scan is slow and smooth
    private const val SCAN_STEP_PAUSE_MS = 1200L // time to look at each angle before the next move
    private const val LOST_AFTER_MS = 4000L      // person out of view this long -> scan again
    private const val TARGET_FACE_Y = 0.35f      // where the head should sit in the image (0 top .. 1 bottom)

    /** Head and both shoulders clearly detected: a person, not noise. */
    private fun isPersonInView(pose: PoseResult): Boolean {
        val p = pose.points
        return p[Kp.NOSE].score >= 0.3f && p[Kp.L_SHOULDER].score >= 0.3f && p[Kp.R_SHOULDER].score >= 0.3f &&
            p.count { it.score >= 0.3f } >= 6
    }

    private fun ensureControlLoop() {
        if (controlJob?.isActive == true) return
        controlJob = scope.launch { runControlLoop() }
    }

    private fun canMoveTablet(): Boolean =
        AppSettings.findPersonEnabled.value &&
            TemiRepository.state.value.ready &&
            !TemiRepository.following.value &&
            !MissionManager.state.value.isActive

    private fun setScanning(on: Boolean) {
        val tilt = TemiRepository.tiltDegrees.value
        val s = _state.value
        if (s.scanning != on || s.tiltDeg != tilt) _state.value = s.copy(scanning = on, tiltDeg = tilt)
    }

    private suspend fun runControlLoop() {
        var dir = 1
        while (currentCoroutineContext().isActive) {
            if (!canMoveTablet()) {
                setScanning(false)
                delay(300)
                continue
            }

            if (_state.value.personVisible) {
                setScanning(false)
                centreOnPerson()
                // Stay put while the person is there; scan again only after they have been gone a while.
                var goneMs = 0L
                while (currentCoroutineContext().isActive && canMoveTablet() && goneMs < LOST_AFTER_MS) {
                    goneMs = if (_state.value.personVisible) 0L else goneMs + 200
                    delay(200)
                }
                continue
            }

            // Nobody in view: take one small step up or down, then look for a moment.
            var next = TemiRepository.tiltDegrees.value + dir * SCAN_STEP_DEG
            if (next >= TemiRepository.TILT_MAX_DEG) { next = TemiRepository.TILT_MAX_DEG; dir = -1 }
            if (next <= TemiRepository.TILT_MIN_DEG) { next = TemiRepository.TILT_MIN_DEG; dir = 1 }
            TemiRepository.tiltTo(next)
            setScanning(true)

            var waited = 0L
            while (waited < SCAN_STEP_PAUSE_MS && !_state.value.personVisible && canMoveTablet()) {
                delay(100); waited += 100
            }
        }
    }

    /** A few small nudges so the head ends up near the upper-middle of the image. */
    private suspend fun centreOnPerson() {
        repeat(4) {
            delay(900) // let the picture settle
            val s = _state.value
            val nose = s.pose?.points?.get(Kp.NOSE) ?: return
            if (!s.personVisible || nose.score < 0.3f || !canMoveTablet()) return
            val error = nose.y - TARGET_FACE_Y
            if (kotlin.math.abs(error) < 0.15f) return
            // Head high in the picture -> look up; head low -> look down.
            val delta = if (error < 0) 4 else -4
            TemiRepository.tiltTo(TemiRepository.tiltDegrees.value + delta)
        }
    }

    /** T-pose gesture: stop following. Always allowed while following, even if "raise hand to follow" is switched off. */
    private fun onTPoseStarted(now: Long) {
        if (!TemiRepository.following.value) return
        followJob?.cancel()
        TemiRepository.stopFollowing()
        TemiRepository.speak("Okay, I'll stop here.")
        lastTriggerMs = now // starts the cooldown so a raised hand right afterwards does not restart following
    }

    /** Call when the tab is hidden: frees the model and stops following. */
    fun stop() {
        followJob?.cancel()
        controlJob?.cancel()
        controlJob = null
        visibleFrames = 0
        TemiRepository.stopFollowing()
        detector?.close()
        detector = null
        handDetector.reset()
        tPoseDetector.reset()
        wasHandRaised = false
        wasTPose = false
        _state.value = GestureState()
    }
}
