package com.example.iaqieqmornitoring.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

enum class SignStatus { IDLE, LOADING, READY, SIGNING, THINKING, ERROR }

/** The latest landmarks, for drawing on top of the camera preview. */
class SignOverlay(
    /** 543 * 3 floats (x, y, z), NaN where nothing was detected. x / y are 0..1 in the upright image. */
    val landmarks: FloatArray,
    val imageWidth: Int,
    val imageHeight: Int
)

data class SignState(
    val status: SignStatus = SignStatus.IDLE,
    /** Short human-readable note under the caption (what the recogniser is doing / why it did not answer). */
    val message: String = "",
    /** The last accepted word (confidence above the threshold), as shown and spoken. */
    val caption: String = "",
    val confidence: Float = 0f,
    /** Top guesses of the last classification, accepted or not. */
    val guesses: List<SignPrediction> = emptyList(),
    /** Last few accepted words, newest last. */
    val history: List<String> = emptyList(),
    val recordedFrames: Int = 0,
    val fps: Int = 0,
    val inferenceMs: Long = 0,
    val overlay: SignOverlay? = null,
    val error: String? = null
)

/**
 * Glue between the camera, MediaPipe Holistic, the sign model and temi's voice.
 *
 *  1. Every camera frame -> 543 landmarks (SignLandmarkExtractor).
 *  2. A "signing" recording starts when a hand is raised in view and keeps collecting frames.
 *  3. When no raised hand has been seen for [HAND_GONE_MS], the recording is classified (SignClassifier).
 *  4. If the best guess has at least [SPEAK_THRESHOLD] confidence it becomes the caption and temi says it
 *     (unless the same word was just spoken).
 *
 * Only runs while the Sign Language tab is on screen. Single words only, no sentences.
 */
object SignRecognizer {

    // ---- tunables ----
    /** Android 7.0: the minimum for MediaPipe Tasks. */
    const val MIN_SDK = 24
    /** Recording ends after this long without a raised hand. */
    private const val HAND_GONE_MS = 500L
    /** Recordings shorter than this are ignored (a hand just passing through the picture). */
    private const val MIN_FRAMES = 8
    private const val MAX_FRAMES = 200
    /** A recording this long is classified anyway (someone resting a hand in view). */
    private const val MAX_RECORD_MS = 6000L
    /** Minimum confidence to show a word and speak it. */
    const val SPEAK_THRESHOLD = 0.60f
    /** The same word is not spoken again within this time. */
    private const val SAME_WORD_GAP_MS = 4000L
    /** Never speak twice within this time, whatever the word. */
    private const val ANY_WORD_GAP_MS = 1200L
    /** Frames are shrunk so the longest side is at most this many pixels before MediaPipe sees them. */
    private const val MAX_SIDE_PX = 480
    private const val HISTORY_SIZE = 6

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Guards the model pointers and the "last spoken" bookkeeping (held only briefly). */
    private val lock = Any()
    /** Held while the landmark extractor runs or is closed, so it is never closed mid-frame. */
    private val extractLock = Any()
    /** Held while the classifier runs or is closed. */
    private val classifyLock = Any()

    private val _state = MutableStateFlow(SignState())
    val state: StateFlow<SignState> = _state.asStateFlow()

    /** temi says each recognised word aloud. */
    val speakEnabled = MutableStateFlow(true)

    /**
     * Mirror the picture before it goes to MediaPipe. The model was trained on someone else's camera
     * orientation; if right-handed signs are misread as left-handed ones, switch this on.
     */
    val flipInput = MutableStateFlow(false)

    @Volatile private var extractor: SignLandmarkExtractor? = null
    @Volatile private var classifier: SignClassifier? = null
    private var session = 0                   // bumped by stop() so a slow load can tell it was cancelled
    private val loading = AtomicBoolean(false)
    /** True while the Sign Language tab is open (set by prepare, cleared by stop). */
    @Volatile private var wanted = false

    // frame preparation (analysis thread only)
    private var prep: Bitmap? = null
    private var prepCanvas: Canvas? = null
    private val prepPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // recording (analysis thread only)
    private val frame = FloatArray(SignLayout.TOTAL * 3)
    private val recording = FloatArray(MAX_FRAMES * SignLayout.TOTAL * 3)
    private var frameCount = 0
    private var isRecording = false
    private var recordStartMs = 0L
    private var lastHandMs = 0L
    private var needHandsDown = false
    private var fpsCount = 0
    private var fpsSince = 0L

    // speaking (any thread, guarded by lock)
    private var lastSpokenWord = ""
    private var lastSpokenMs = 0L

    // ---------------------------------------------------------------------------------------------
    // Loading
    // ---------------------------------------------------------------------------------------------

    /** Loads both models. Call from a background thread when the tab opens (takes a second or two). */
    fun prepare(context: Context) {
        wanted = true
        if (!loading.compareAndSet(false, true)) return
        var stale = false
        val mySession = synchronized(lock) { if (extractor != null && classifier != null) -1 else session }
        if (mySession < 0) { loading.set(false); return }   // already loaded
        try {
            if (Build.VERSION.SDK_INT < MIN_SDK) {
                fail("Sign recognition needs Android 7.0 (API $MIN_SDK) or newer. " +
                    "This tablet runs Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}).")
                return
            }
            val missing = missingAssets(context)
            if (missing.isNotEmpty()) {
                fail("Missing model file(s) in app/src/main/assets: ${missing.joinToString(", ")}. " +
                    "See the \"Sign Language tab\" section of the README.")
                return
            }
            _state.value = SignState(status = SignStatus.LOADING, message = "Loading sign recognition models...")

            val cls = SignClassifier(context)
            val ext = try {
                SignLandmarkExtractor(context.applicationContext)
            } catch (t: Throwable) {
                cls.close(); throw t
            }
            synchronized(lock) {
                if (session != mySession) {          // the tab was closed while we were loading
                    ext.close(); cls.close()
                    stale = true
                    return@synchronized
                }
                classifier = cls
                extractor = ext
            }
            if (!stale) {
                resetRecording()
                _state.value = SignState(status = SignStatus.READY, message = "Raise your hand and sign one word.")
            }
        } catch (t: Throwable) {
            fail("Could not load the sign models: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            loading.set(false)
        }
        // The tab was closed and reopened while this load was running: load again for the new tab.
        if (stale && wanted) prepare(context)
    }

    private fun missingAssets(context: Context): List<String> {
        val present = context.assets.list("")?.toSet() ?: emptySet()
        return listOf(
            SignClassifier.MODEL_ASSET,
            SignClassifier.LABELS_ASSET,
            SignLandmarkExtractor.TASK_ASSET
        ).filter { it !in present }
    }

    private fun fail(message: String) {
        _state.value = SignState(status = SignStatus.ERROR, message = message, error = message)
    }

    /** Frees the models (they are big) and resets. Call when the tab is closed. */
    fun stop() {
        wanted = false
        val (e, c) = synchronized(lock) {
            session++
            val pair = Pair(extractor, classifier)
            extractor = null; classifier = null
            pair
        }
        // Closing can wait for a frame that is still being analysed, so keep it off the UI thread.
        scope.launch {
            synchronized(extractLock) { e?.close() }
            synchronized(classifyLock) { c?.close() }
        }
        resetRecording()
        _state.value = SignState()
    }

    private fun resetRecording() {
        frameCount = 0; isRecording = false; needHandsDown = false
    }

    // ---------------------------------------------------------------------------------------------
    // Camera frames
    // ---------------------------------------------------------------------------------------------

    fun createAnalyzer(): ImageAnalysis.Analyzer = ImageAnalysis.Analyzer { image -> analyze(image) }

    private fun analyze(image: ImageProxy) {
        try {
            val ext = extractor ?: return

            val now = SystemClock.elapsedRealtime()
            val bitmap = upright(image.toBitmap(), image.imageInfo.rotationDegrees, flipInput.value)

            val hasHand = synchronized(extractLock) {
                if (extractor !== ext) return          // the tab was closed while we were preparing the frame
                ext.extract(bitmap, now, frame)
            }
            val inferenceMs = SystemClock.elapsedRealtime() - now

            if (now - fpsSince >= 1000) {
                val fps = fpsCount
                _state.update { it.copy(fps = fps) }
                fpsCount = 0; fpsSince = now
            }
            fpsCount++

            val active = hasHand && handRaised(frame)
            if (!active) needHandsDown = false

            if (active && !isRecording && !needHandsDown) {
                isRecording = true
                recordStartMs = now
                frameCount = 0
            }
            if (active) lastHandMs = now

            if (isRecording) {
                if (frameCount < MAX_FRAMES) {
                    System.arraycopy(frame, 0, recording, frameCount * frame.size, frame.size)
                    frameCount++
                }
                val handsGone = !active && now - lastHandMs >= HAND_GONE_MS
                val tooLong = now - recordStartMs >= MAX_RECORD_MS
                if (handsGone || tooLong) {
                    finishRecording(tooLong)
                }
            }

            val recordingNow = isRecording
            val framesNow = frameCount
            val overlay = SignOverlay(frame.copyOf(), bitmap.width, bitmap.height)
            _state.update { s ->
                s.copy(
                    status = when {
                        s.status == SignStatus.THINKING -> SignStatus.THINKING
                        recordingNow -> SignStatus.SIGNING
                        else -> SignStatus.READY
                    },
                    message = when {
                        s.status == SignStatus.THINKING -> s.message
                        recordingNow -> "Signing... lower your hands when you finish."
                        s.message.startsWith("Signing") -> ""
                        else -> s.message
                    },
                    recordedFrames = if (recordingNow) framesNow else 0,
                    inferenceMs = inferenceMs,
                    overlay = overlay,
                    error = null
                )
            }
        } catch (t: Throwable) {
            fail("Recognition problem: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            image.close()
        }
    }

    /**
     * A hand counts as "signing" when it was detected and its wrist is above the hips
     * (hands resting in the lap or at the sides must not keep a recording open).
     */
    private fun handRaised(f: FloatArray): Boolean {
        fun y(landmark: Int) = f[landmark * 3 + 1]
        val hipL = y(SignLayout.POSE_START + SignLayout.POSE_L_HIP)
        val hipR = y(SignLayout.POSE_START + SignLayout.POSE_R_HIP)
        val hipLine = when {
            !hipL.isNaN() && !hipR.isNaN() -> (hipL + hipR) / 2f
            !hipL.isNaN() -> hipL
            !hipR.isNaN() -> hipR
            else -> 0.95f
        }
        val l = y(SignLayout.LEFT_HAND_START)       // wrist = landmark 0 of each hand
        val r = y(SignLayout.RIGHT_HAND_START)
        return (!l.isNaN() && l < hipLine) || (!r.isNaN() && r < hipLine)
    }

    private fun finishRecording(tooLong: Boolean) {
        val n = frameCount
        isRecording = false
        frameCount = 0
        if (tooLong) needHandsDown = true       // do not start over while the hand is still up

        if (n < MIN_FRAMES) {
            _state.update { it.copy(message = "That was too short. Raise your hand and sign one word.") }
            return
        }
        val cls = classifier ?: return

        val data = recording.copyOf(n * frame.size)   // copy: the next recording may start right away
        _state.update { it.copy(status = SignStatus.THINKING, message = "Thinking...", recordedFrames = n) }

        scope.launch {
            try {
                val t0 = SystemClock.elapsedRealtime()
                val guesses = synchronized(classifyLock) {
                    if (classifier !== cls) return@launch      // the tab was closed meanwhile
                    cls.classify(data, n)
                }
                onResult(guesses, SystemClock.elapsedRealtime() - t0)
            } catch (t: Throwable) {
                // Shown as a message (not ERROR) so the next camera frame does not wipe it
                _state.update {
                    it.copy(status = SignStatus.READY,
                        message = "Classification problem: ${t.message ?: t.javaClass.simpleName}")
                }
            }
        }
    }

    private fun onResult(guesses: List<SignPrediction>, ms: Long) {
        val best = guesses.firstOrNull() ?: return
        if (best.confidence >= SPEAK_THRESHOLD) {
            val word = pretty(best.label)
            _state.update { s ->
                s.copy(
                    status = SignStatus.READY,
                    caption = word, confidence = best.confidence, guesses = guesses,
                    history = (s.history + word).takeLast(HISTORY_SIZE),
                    message = "", inferenceMs = ms
                )
            }
            speak(word)
        } else {
            _state.update { s ->
                s.copy(
                    status = SignStatus.READY, guesses = guesses, inferenceMs = ms,
                    message = "Not sure (best guess: ${pretty(best.label)}, " +
                        "${(best.confidence * 100).toInt()}%). Try again."
                )
            }
        }
    }

    private fun speak(word: String) {
        if (!speakEnabled.value) return
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (now - lastSpokenMs < ANY_WORD_GAP_MS) return
            if (word == lastSpokenWord && now - lastSpokenMs < SAME_WORD_GAP_MS) return
            lastSpokenWord = word
            lastSpokenMs = now
        }
        TemiRepository.speak(word)
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /** Rotates the frame upright, optionally mirrors it, and shrinks it. Reuses one bitmap between frames. */
    private fun upright(src: Bitmap, rotationDegrees: Int, flip: Boolean): Bitmap {
        val sideways = rotationDegrees % 180 != 0
        val uw = if (sideways) src.height else src.width
        val uh = if (sideways) src.width else src.height
        val scale = minOf(1f, MAX_SIDE_PX.toFloat() / max(uw, uh))
        val dw = max(1, (uw * scale).toInt())
        val dh = max(1, (uh * scale).toInt())

        var bmp = prep
        if (bmp == null || bmp.width != dw || bmp.height != dh) {
            bmp = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888)
            prep = bmp
            prepCanvas = Canvas(bmp)
        }
        val m = Matrix().apply {
            postTranslate(-src.width / 2f, -src.height / 2f)
            postRotate(rotationDegrees.toFloat())
            if (flip) postScale(-1f, 1f)
            postScale(scale, scale)
            postTranslate(dw / 2f, dh / 2f)
        }
        prepCanvas!!.drawBitmap(src, m, prepPaint)
        return bmp
    }

    /** "thankyou" -> "thank you" etc., so temi pronounces the labels naturally. */
    fun pretty(label: String): String = when (label) {
        "TV" -> "T V"
        "thankyou" -> "thank you"
        "callonphone" -> "call on phone"
        "frenchfries" -> "french fries"
        "icecream" -> "ice cream"
        "hesheit" -> "he, she, it"
        "haveto" -> "have to"
        "minemy" -> "mine, my"
        "weus" -> "we, us"
        "shhh" -> "shh"
        "owie" -> "ouch"
        else -> label
    }
}
