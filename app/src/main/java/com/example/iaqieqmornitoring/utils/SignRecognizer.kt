package com.example.iaqieqmornitoring.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class SignStatus { IDLE, LOADING, READY, SIGNING, THINKING, ERROR }

/**
 * What the camera view draws: the square that was cut out around the hand and the forearm line.
 * All values are fractions (0..1) of the upright camera picture, [box] = left, top, right, bottom,
 * [arm] = elbow x, elbow y, wrist x, wrist y.
 */
class SignOverlay(
    val box: FloatArray,
    val arm: FloatArray,
    val imageWidth: Int,
    val imageHeight: Int
)

data class SignState(
    val status: SignStatus = SignStatus.IDLE,
    val message: String = "",
    /** The last finished word (the one temi said). */
    val caption: String = "",
    /** The letters spelled so far, before the word is finished. */
    val word: String = "",
    val confidence: Float = 0f,
    /** Best guesses for the letter being shown right now. */
    val guesses: List<SignPrediction> = emptyList(),
    val history: List<String> = emptyList(),
    val fps: Int = 0,
    val inferenceMs: Long = 0,
    val overlay: SignOverlay? = null,
    val error: String? = null
)

/**
 * Spells words from ASL fingerspelling, with no MediaPipe (works on Android 6).
 *
 * Every camera frame:
 *  1. The pose model (the same MoveNet that the Camera & Depth tab uses) finds the signing hand: the
 *     wrist that is above the hips, and the elbow it belongs to.
 *  2. A square around the hand is cut out of the picture, continuing the line elbow -> wrist, and
 *     scaled to the alphabet model's input size.
 *  3. The alphabet classifier names the letter.
 *
 * A letter is added to the word once the same letter has been held for [HOLD_MS]. The same letter twice in
 * a row needs a short gap or a longer hold. When the hand goes down for [WORD_GAP_MS] (or the model says
 * "space") the word is finished and temi says it. "del" removes the last letter.
 *
 * Only runs while the Sign Language tab is on screen.
 */
object SignRecognizer {

    // ---- tunables ----
    /** A letter must be this sure to count. */
    private const val LETTER_THRESHOLD = 0.70f
    /** The same letter must be held this long to be added. */
    private const val HOLD_MS = 800L
    /** The same letter again (double letters) needs this long since it was added, with the hand still showing it. */
    private const val REPEAT_MS = 1500L
    /** No letter for this long forgets the last one added, so the same letter may follow immediately. */
    private const val GAP_RESET_MS = 400L
    /** The hand is out of view this long: the word is finished. */
    private const val WORD_GAP_MS = 1800L
    private const val MAX_WORD_LENGTH = 20
    /** At most this often (milliseconds between analysed frames), to keep a modest tablet cool. */
    private const val MIN_FRAME_GAP_MS = 100L
    private const val MAX_SIDE_PX = 480
    private const val HISTORY_SIZE = 6
    private const val L_HIP = 11
    private const val R_HIP = 12

    private val NOTHING = setOf("nothing", "blank", "none", "background", "neutral")
    private val DELETE = setOf("del", "delete", "backspace")

    private val lock = Any()
    /** Held while a frame is analysed or the models are closed, so they are never closed mid-frame. */
    private val modelLock = Any()

    private val _state = MutableStateFlow(SignState())
    val state: StateFlow<SignState> = _state.asStateFlow()

    /** temi says each finished word aloud. */
    val speakEnabled = MutableStateFlow(true)

    /**
     * Mirror the hand picture before it goes to the model. Most alphabet datasets show a right hand; if you sign
     * with your left hand (or letters are misread as their mirror image), switch this on.
     */
    val flipInput = MutableStateFlow(false)

    @Volatile private var detector: PoseDetector? = null
    @Volatile private var classifier: SignClassifier? = null
    private var session = 0                   // bumped by stop() so a slow load can tell it was cancelled
    private val loading = AtomicBoolean(false)
    @Volatile private var wanted = false

    // frame preparation (analysis thread only)
    private var prep: Bitmap? = null
    private var prepCanvas: Canvas? = null
    private val prepPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var cropBmp: Bitmap? = null
    private var cropCanvas: Canvas? = null
    private val cropPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var lastRunMs = 0L
    private var fpsCount = 0
    private var fpsSince = 0L

    // spelling (analysis thread only)
    private var candidate: String? = null
    private var candidateSince = 0L
    private var lastAdded: String? = null
    private var lastAddedMs = 0L
    private var noCandidateSince = 0L
    private var lastHandMs = 0L
    private val word = StringBuilder()

    private class Hand(val wx: Float, val wy: Float, val ex: Float, val ey: Float)

    // ---------------------------------------------------------------------------------------------
    // Loading
    // ---------------------------------------------------------------------------------------------

    /** Loads the models. Call from a background thread when the tab opens. */
    fun prepare(context: Context) {
        wanted = true
        if (!loading.compareAndSet(false, true)) return
        var stale = false
        val mySession = synchronized(lock) { if (detector != null && classifier != null) -1 else session }
        if (mySession < 0) { loading.set(false); return }   // already loaded
        try {
            val missing = missingAssets(context)
            if (missing.isNotEmpty()) {
                fail(
                    "Missing model file in app/src/main/assets: ${missing.joinToString(", ")}. " +
                        "Add an ASL alphabet TensorFlow Lite model named ${SignClassifier.MODEL_ASSET} " +
                        "(and ${SignClassifier.LABELS_ASSET} if it does not have 26 or 29 outputs). " +
                        "See the \"Sign Language tab\" section of the README."
                )
                return
            }
            _state.value = SignState(status = SignStatus.LOADING, message = "Loading models...")

            val cls = SignClassifier(context)
            val det = try {
                PoseDetector(context.applicationContext)
            } catch (t: Throwable) {
                cls.close(); throw t
            }
            synchronized(lock) {
                if (session != mySession) {          // the tab was closed while we were loading
                    det.close(); cls.close()
                    stale = true
                    return@synchronized
                }
                classifier = cls
                detector = det
            }
            if (!stale) {
                resetSpelling()
                _state.value = SignState(status = SignStatus.READY, message = READY_MESSAGE)
            }
        } catch (t: Throwable) {
            fail("Could not load the models: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            loading.set(false)
        }
        // The tab was closed and reopened while this load was running: load again for the new tab.
        if (stale && wanted) prepare(context)
    }

    private fun missingAssets(context: Context): List<String> {
        val present = context.assets.list("")?.toSet() ?: emptySet()
        return listOf(SignClassifier.MODEL_ASSET, PoseDetector.MODEL_ASSET).filter { it !in present }
    }

    private fun fail(message: String) {
        _state.value = SignState(status = SignStatus.ERROR, message = message, error = message)
    }

    /** Frees the models and resets. Call when the tab is closed. */
    fun stop() {
        wanted = false
        val (d, c) = synchronized(lock) {
            session++
            val pair = Pair(detector, classifier)
            detector = null; classifier = null
            pair
        }
        // Closing waits for a frame that is still being analysed, so keep it off the UI thread.
        Thread {
            synchronized(modelLock) {
                try { d?.close() } catch (_: Exception) {}
                try { c?.close() } catch (_: Exception) {}
            }
        }.start()
        resetSpelling()
        _state.value = SignState()
    }

    private fun resetSpelling() {
        candidate = null; lastAdded = null; noCandidateSince = 0L
        word.setLength(0)
    }

    // ---------------------------------------------------------------------------------------------
    // Camera frames
    // ---------------------------------------------------------------------------------------------

    fun createAnalyzer(): ImageAnalysis.Analyzer = ImageAnalysis.Analyzer { image -> analyze(image) }

    private fun analyze(image: ImageProxy) {
        try {
            val det = detector ?: return
            val cls = classifier ?: return

            val now = SystemClock.elapsedRealtime()
            if (now - lastRunMs < MIN_FRAME_GAP_MS) return
            lastRunMs = now

            val src = image.toBitmap()
            val rotation = image.imageInfo.rotationDegrees

            var overlay: SignOverlay? = null
            var guesses: List<SignPrediction> = emptyList()
            synchronized(modelLock) {
                if (detector !== det || classifier !== cls) return      // the tab was closed meanwhile
                val pose = det.detect(src, rotation)                     // coordinates of the upright picture
                val hand = pickHand(pose)
                if (hand != null) {
                    val upright = upright(src, rotation)
                    val box = FloatArray(4)
                    val crop = cropHand(upright, hand, cls.inputWidth, cls.inputHeight, flipInput.value, box)
                    guesses = cls.classify(crop, AppSettings.signInputMode.value)
                    overlay = SignOverlay(
                        box, floatArrayOf(hand.ex, hand.ey, hand.wx, hand.wy), upright.width, upright.height
                    )
                }
            }
            val inferenceMs = SystemClock.elapsedRealtime() - now

            if (now - fpsSince >= 1000) {
                val fps = fpsCount
                _state.update { it.copy(fps = fps) }
                fpsCount = 0; fpsSince = now
            }
            fpsCount++

            handleFrame(now, overlay != null, guesses, overlay, inferenceMs)
        } catch (t: Throwable) {
            fail("Recognition problem: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            image.close()
        }
    }

    /** The signing hand: the wrist above the hip line (highest if both), with its elbow. Null if none. */
    private fun pickHand(pose: PoseResult): Hand? {
        val p = pose.points
        val hipYs = listOf(p[L_HIP], p[R_HIP]).filter { it.score >= 0.3f }.map { it.y }
        val hipLine = if (hipYs.isNotEmpty()) hipYs.average().toFloat() else 0.95f

        var best: Hand? = null
        for ((wristIdx, elbowIdx) in listOf(Kp.L_WRIST to Kp.L_ELBOW, Kp.R_WRIST to Kp.R_ELBOW)) {
            val w = p[wristIdx]
            val e = p[elbowIdx]
            if (w.score < 0.3f || e.score < 0.3f) continue
            if (w.y >= hipLine) continue
            if (best == null || w.y < best.wy) best = Hand(w.x, w.y, e.x, e.y)
        }
        return best
    }

    /**
     * Cuts a square around the hand out of [bmp] and scales it to [w] x [h]. The hand is taken to sit just past
     * the wrist, along the forearm; the square is about as wide as the forearm is long.
     * [box] receives the square as fractions of the picture (left, top, right, bottom).
     */
    private fun cropHand(bmp: Bitmap, hand: Hand, w: Int, h: Int, mirror: Boolean, box: FloatArray): Bitmap {
        val bw = bmp.width
        val bh = bmp.height
        val vx = (hand.wx - hand.ex) * bw
        val vy = (hand.wy - hand.ey) * bh
        val forearm = hypot(vx, vy)

        val side = forearm.coerceIn(48f, min(bw, bh).toFloat()).roundToInt().coerceAtMost(min(bw, bh))
        val cx = hand.wx * bw + 0.35f * vx
        val cy = hand.wy * bh + 0.35f * vy
        val left = (cx - side / 2f).roundToInt().coerceIn(0, bw - side)
        val top = (cy - side / 2f).roundToInt().coerceIn(0, bh - side)

        var out = cropBmp
        if (out == null || out.width != w || out.height != h) {
            out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            cropBmp = out
            cropCanvas = Canvas(out)
        }
        val c = cropCanvas!!
        c.save()
        if (mirror) c.scale(-1f, 1f, w / 2f, h / 2f)
        c.drawBitmap(bmp, Rect(left, top, left + side, top + side), Rect(0, 0, w, h), cropPaint)
        c.restore()

        box[0] = left.toFloat() / bw
        box[1] = top.toFloat() / bh
        box[2] = (left + side).toFloat() / bw
        box[3] = (top + side).toFloat() / bh
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // Letters -> words
    // ---------------------------------------------------------------------------------------------

    private fun handleFrame(
        now: Long,
        handVisible: Boolean,
        guesses: List<SignPrediction>,
        overlay: SignOverlay?,
        inferenceMs: Long
    ) {
        if (handVisible) lastHandMs = now

        val best = guesses.firstOrNull()
        val letter = best?.takeIf {
            it.confidence >= LETTER_THRESHOLD && it.label.lowercase(Locale.ROOT) !in NOTHING
        }?.label

        var holdPercent = 0
        if (letter == null) {
            candidate = null
            if (noCandidateSince == 0L) noCandidateSince = now
            if (now - noCandidateSince >= GAP_RESET_MS) lastAdded = null
        } else {
            noCandidateSince = 0L
            if (letter != candidate) {
                candidate = letter
                candidateSince = now
            }
            val held = now - candidateSince
            holdPercent = (held * 100 / HOLD_MS).toInt().coerceAtMost(100)
            val repeatOk = letter != lastAdded || now - lastAddedMs >= REPEAT_MS
            if (held >= HOLD_MS && repeatOk) {
                addLetter(letter)
                lastAdded = letter
                lastAddedMs = now
                candidateSince = now
            }
        }

        if (!handVisible && word.isNotEmpty() && now - lastHandMs >= WORD_GAP_MS) finishWord()

        val spelled = word.toString()
        _state.update { s ->
            s.copy(
                status = if (handVisible) SignStatus.SIGNING else SignStatus.READY,
                message = when {
                    letter != null -> "Hold ${pretty(letter)}... $holdPercent%"
                    handVisible -> "Show a letter clearly."
                    spelled.isNotEmpty() -> "Lower your hand to finish the word."
                    else -> READY_MESSAGE
                },
                word = spelled,
                confidence = best?.confidence ?: 0f,
                guesses = guesses,
                inferenceMs = inferenceMs,
                overlay = overlay,
                error = null
            )
        }
    }

    private fun addLetter(label: String) {
        val l = label.lowercase(Locale.ROOT)
        when {
            l == "space" -> finishWord()
            l in DELETE -> if (word.isNotEmpty()) word.setLength(word.length - 1)
            label.length == 1 -> {
                word.append(label.uppercase(Locale.ROOT))
                if (word.length >= MAX_WORD_LENGTH) finishWord()
            }
            else -> {                                   // a whole-word label: say it straight away
                word.setLength(0)
                word.append(label)
                finishWord()
            }
        }
    }

    private fun finishWord() {
        val w = word.toString().trim()
        word.setLength(0)
        lastAdded = null
        if (w.isEmpty()) return
        _state.update { s ->
            s.copy(caption = w, history = (s.history + w).takeLast(HISTORY_SIZE))
        }
        if (speakEnabled.value) TemiRepository.speak(w.lowercase(Locale.ROOT))
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private const val READY_MESSAGE = "Raise a hand and spell with letters. Lower it to finish the word."

    /** Rotates the frame upright and shrinks it. Reuses one bitmap between frames. */
    private fun upright(src: Bitmap, rotationDegrees: Int): Bitmap {
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
            postScale(scale, scale)
            postTranslate(dw / 2f, dh / 2f)
        }
        prepCanvas!!.drawColor(android.graphics.Color.BLACK)
        prepCanvas!!.drawBitmap(src, m, prepPaint)
        return bmp
    }

    /** Label for the screen: letters in capitals, special labels in plain words. */
    fun pretty(label: String): String = when (label.lowercase(Locale.ROOT)) {
        "del", "delete", "backspace" -> "delete"
        "space" -> "space"
        "nothing", "blank", "none", "background", "neutral" -> "nothing"
        else -> if (label.length == 1) label.uppercase(Locale.ROOT) else label
    }
}
