package com.example.iaqieqmornitoring.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import android.graphics.Color as AColor

enum class DepthStatus {
    IDLE,
    STARTING,
    /** Frames are coming from a real depth sensor. */
    STREAMING,
    /** Frames are a synthetic test scene (Settings -> Simulated robot). */
    SIMULATED,
    /** The device does not expose any depth camera to apps. */
    UNAVAILABLE,
    NO_PERMISSION,
    ERROR
}

/**
 * One processed depth frame: a colourised bitmap plus a few distance read-outs.
 * All distances are in millimetres; null means "not enough valid readings".
 */
class DepthFrame(
    val bitmap: Bitmap,
    val width: Int,
    val height: Int,
    /** Average distance in a small window at the centre of the image. */
    val centerMm: Int?,
    /** Nearest obstacle in the left / middle / right third of the obstacle band. */
    val leftMm: Int?,
    val aheadMm: Int?,
    val rightMm: Int?,
    /** Nearest obstacle anywhere in the obstacle band. */
    val nearestMm: Int?,
    /** Share of the whole frame that has a valid depth reading (0..100). */
    val coveragePercent: Int
)

data class DepthState(
    val status: DepthStatus = DepthStatus.IDLE,
    val message: String = "",
    val frame: DepthFrame? = null
)

/**
 * Provides depth frames for the "Camera & Depth" screen.
 *
 *  - Real mode: looks for a camera that advertises DEPTH_OUTPUT through Android Camera2 and reads its
 *    DEPTH16 stream. If the device exposes none, the state becomes UNAVAILABLE (no fake data is shown).
 *  - Simulated mode (Settings -> Simulated robot): a synthetic scene, flagged as SIMULATED.
 *
 * Call [start] when the screen becomes visible and [stop] when it is hidden so the camera is released.
 */
object DepthCameraSource {

    /** Colour scale range: <= NEAR is the warmest colour, >= FAR the coolest. */
    const val NEAR_MM = 300
    const val FAR_MM = 4000

    /**
     * Distances are summarised only inside a horizontal band of the image (fractions of the height).
     * This keeps the floor, which is always visible at the bottom of the frame, from being reported
     * as an obstacle.
     */
    const val BAND_TOP = 0.2f
    const val BAND_BOTTOM = 0.7f

    private const val BIN_MM = 20
    private const val BINS = 8192 / BIN_MM + 1
    private const val MIN_SAMPLES = 50
    private const val MIN_FRAME_INTERVAL_MS = 80L
    private const val MAX_REAL_PIXELS = 640 * 480
    private const val SIM_MESSAGE = "Synthetic scene - not real sensor data."

    private val INVALID_COLOR = 0xFF0B1220.toInt()

    private val _state = MutableStateFlow(DepthState())
    val state: StateFlow<DepthState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()

    // Guarded by [lock]; [generation] changes on every start/stop so late callbacks can be ignored.
    private var generation = 0
    private var simJob: Job? = null
    private var thread: HandlerThread? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null

    @Volatile
    private var lastPublishMs = 0L

    // -------- colour scale --------

    fun colorAtFraction(t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        // red (near) -> blue/violet (far), slightly darker with distance for a depth cue
        return AColor.HSVToColor(floatArrayOf(250f * f, 1f, 1f - 0.35f * f))
    }

    private val lut = IntArray(256) { colorAtFraction(it / 255f) }

    private fun lutIndex(mm: Int): Int =
        (((mm - NEAR_MM).toFloat() / (FAR_MM - NEAR_MM)) * 255f).toInt().coerceIn(0, 255)

    /** ARGB colour used for a given distance (matches the depth image and the legend). */
    fun colorForMm(mm: Int): Int = lut[lutIndex(mm)]

    // -------- control --------

    /** Must be called from the main thread. */
    fun start(context: Context, simulated: Boolean, cameraGranted: Boolean) {
        stopInternal()
        if (simulated) {
            startSimulated()
            return
        }
        if (!cameraGranted) {
            _state.value = DepthState(
                DepthStatus.NO_PERMISSION,
                "Camera permission is needed to read the depth camera."
            )
            return
        }
        startReal(context.applicationContext)
    }

    /** Must be called from the main thread. */
    fun stop() {
        stopInternal()
        _state.value = DepthState()
    }

    private fun currentGeneration(): Int = synchronized(lock) { generation }

    private fun stopInternal() {
        var sim: Job? = null
        var s: CameraCaptureSession? = null
        var d: CameraDevice? = null
        var r: ImageReader? = null
        var t: HandlerThread? = null
        synchronized(lock) {
            generation++
            sim = simJob; simJob = null
            s = session; session = null
            d = device; device = null
            r = reader; reader = null
            t = thread; thread = null
        }
        sim?.cancel()
        try { s?.close() } catch (e: Exception) { /* ignore */ }
        try { d?.close() } catch (e: Exception) { /* ignore */ }
        try { r?.close() } catch (e: Exception) { /* ignore */ }
        t?.quitSafely()
    }

    private fun fail(gen: Int, message: String) {
        if (gen != currentGeneration()) return
        _state.value = DepthState(DepthStatus.ERROR, message)
    }

    // -------- real depth camera (Camera2, DEPTH16) --------

    private fun findDepthCamera(manager: CameraManager): Pair<String, Size>? {
        for (id in manager.cameraIdList) {
            val chars = try {
                manager.getCameraCharacteristics(id)
            } catch (e: Exception) {
                continue
            }
            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
            if (caps.none { it == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT }) continue
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
            val sizes = map.getOutputSizes(ImageFormat.DEPTH16) ?: continue
            val best = sizes.filter { it.width * it.height <= MAX_REAL_PIXELS }
                .maxByOrNull { it.width * it.height }
                ?: sizes.minByOrNull { it.width * it.height }
                ?: continue
            return id to best
        }
        return null
    }

    @SuppressLint("MissingPermission") // the caller checks the CAMERA permission before start()
    private fun startReal(context: Context) {
        val gen = currentGeneration()
        _state.value = DepthState(DepthStatus.STARTING, "Opening depth camera...")

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        val found = try {
            manager?.let { findDepthCamera(it) }
        } catch (e: Exception) {
            null
        }
        if (manager == null || found == null) {
            _state.value = DepthState(
                DepthStatus.UNAVAILABLE,
                "No depth camera is exposed to apps on this device " +
                    "(Android Camera2 reports no depth output)."
            )
            return
        }
        val (cameraId, size) = found

        val t = HandlerThread("depth-camera").also { it.start() }
        val handler = Handler(t.looper)
        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.DEPTH16, 2)
        synchronized(lock) {
            thread = t
            reader = r
        }
        r.setOnImageAvailableListener({ rd -> onDepthImage(rd, gen) }, handler)

        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    val stale = synchronized(lock) {
                        if (gen != generation) true else {
                            device = camera
                            false
                        }
                    }
                    if (stale) {
                        camera.close()
                        return
                    }
                    createSession(camera, r, handler, gen)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    fail(gen, "Depth camera disconnected.")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    fail(gen, "Depth camera error (code $error). It may be in use by another app.")
                }
            }, handler)
        } catch (e: Exception) {
            fail(gen, "Could not open the depth camera: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    @Suppress("DEPRECATION") // the non-deprecated overload needs API 28; minSdk is 23
    private fun createSession(camera: CameraDevice, r: ImageReader, handler: Handler, gen: Int) {
        try {
            camera.createCaptureSession(
                listOf(r.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        val stale = synchronized(lock) {
                            if (gen != generation) true else {
                                session = s
                                false
                            }
                        }
                        if (stale) {
                            s.close()
                            return
                        }
                        try {
                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                .apply { addTarget(r.surface) }
                                .build()
                            s.setRepeatingRequest(request, null, handler)
                        } catch (e: Exception) {
                            fail(gen, "Could not start the depth stream: ${e.message ?: e.javaClass.simpleName}")
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        fail(gen, "The depth stream could not be configured.")
                    }
                },
                handler
            )
        } catch (e: Exception) {
            fail(gen, "Could not start the depth stream: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun onDepthImage(rd: ImageReader, gen: Int) {
        val image = try {
            rd.acquireLatestImage()
        } catch (e: Exception) {
            null
        }
        if (image == null) return
        try {
            if (gen != currentGeneration()) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastPublishMs < MIN_FRAME_INTERVAL_MS) return
            lastPublishMs = now

            val w = image.width
            val h = image.height
            val plane = image.planes[0]
            val shorts = plane.buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
            val rowShorts = plane.rowStride / 2

            // DEPTH16: low 13 bits = range in mm, top 3 bits = confidence (1 means "no confidence").
            val mm = IntArray(w * h)
            for (y in 0 until h) {
                val base = y * rowShorts
                for (x in 0 until w) {
                    val sample = shorts.get(base + x).toInt() and 0xFFFF
                    val range = sample and 0x1FFF
                    val confidence = (sample shr 13) and 0x7
                    mm[y * w + x] = if (range == 0 || confidence == 1) 0 else range
                }
            }
            val frame = processDepth(w, h, mm)
            if (gen == currentGeneration()) {
                _state.value = DepthState(DepthStatus.STREAMING, "", frame)
            }
        } catch (e: Exception) {
            fail(gen, "Depth frame error: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            image.close()
        }
    }

    // -------- simulated depth --------

    private fun startSimulated() {
        val gen = currentGeneration()
        _state.value = DepthState(DepthStatus.SIMULATED, SIM_MESSAGE)
        val job = scope.launch {
            val w = 160
            val h = 120
            val mm = IntArray(w * h)
            val t0 = SystemClock.elapsedRealtime()
            var n = 0
            while (isActive) {
                val t = (SystemClock.elapsedRealtime() - t0) / 1000f
                fillSimScene(mm, w, h, t, n++)
                val frame = processDepth(w, h, mm)
                if (gen == currentGeneration()) {
                    _state.value = DepthState(DepthStatus.SIMULATED, SIM_MESSAGE, frame)
                }
                delay(100)
            }
        }
        synchronized(lock) { simJob = job }
    }

    /** A back wall, a sloping floor, a box on the right and a ball swinging left and right. */
    private fun fillSimScene(mm: IntArray, w: Int, h: Int, t: Float, frameNo: Int) {
        val horizon = (h * 0.5f).toInt()
        val ballX = w * (0.5f + 0.28f * sin(t * 0.8f))
        val ballY = h * 0.58f
        val ballR = h * 0.22f
        val boxL = (w * 0.72f).toInt()
        val boxR = (w * 0.92f).toInt()
        val boxT = (h * 0.38f).toInt()
        val boxB = (h * 0.85f).toInt()
        for (y in 0 until h) {
            for (x in 0 until w) {
                var z = if (y <= horizon) 4200f
                else 4200f - (y - horizon).toFloat() / (h - horizon) * 3500f
                if (x in boxL..boxR && y in boxT..boxB) z = min(z, 2300f)
                val dx = x - ballX
                val dy = y - ballY
                val d2 = dx * dx + dy * dy
                if (d2 < ballR * ballR) {
                    val k = sqrt(1f - d2 / (ballR * ballR))
                    z = min(z, 1700f - 500f * k)
                }
                // sprinkle a few "no reading" pixels, like a real sensor
                mm[y * w + x] = if ((x * 7 + y * 13 + frameNo) % 97 == 0) 0 else z.toInt()
            }
        }
    }

    // -------- shared processing --------

    /** [mm] holds one distance per pixel in millimetres; 0 or less means "no reading". */
    private fun processDepth(w: Int, h: Int, mm: IntArray): DepthFrame {
        val pixels = IntArray(w * h)
        // histograms of distances inside the obstacle band: 0 = whole band, 1 = left, 2 = ahead, 3 = right
        val hist = Array(4) { IntArray(BINS) }
        val totals = IntArray(4)
        var valid = 0

        val bandTop = (h * BAND_TOP).toInt()
        val bandBottom = (h * BAND_BOTTOM).toInt()
        val third1 = w / 3
        val third2 = 2 * w / 3
        val cx = w / 2
        val cy = h / 2
        val win = max(2, min(w, h) / 40)
        var centerSum = 0L
        var centerCount = 0

        for (y in 0 until h) {
            val inBand = y in bandTop until bandBottom
            for (x in 0 until w) {
                val i = y * w + x
                val v = mm[i]
                if (v <= 0) {
                    pixels[i] = INVALID_COLOR
                    continue
                }
                valid++
                pixels[i] = lut[lutIndex(v)]
                if (inBand) {
                    val bin = min(v / BIN_MM, BINS - 1)
                    val zone = if (x < third1) 1 else if (x < third2) 2 else 3
                    hist[0][bin]++; totals[0]++
                    hist[zone][bin]++; totals[zone]++
                }
                if (abs(x - cx) <= win && abs(y - cy) <= win) {
                    centerSum += v
                    centerCount++
                }
            }
        }

        val bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        return DepthFrame(
            bitmap = bitmap,
            width = w,
            height = h,
            centerMm = if (centerCount >= 3) (centerSum / centerCount).toInt() else null,
            leftMm = nearest(hist[1], totals[1]),
            aheadMm = nearest(hist[2], totals[2]),
            rightMm = nearest(hist[3], totals[3]),
            nearestMm = nearest(hist[0], totals[0]),
            coveragePercent = valid * 100 / (w * h)
        )
    }

    /** The 2nd percentile of the distances, so a single noisy pixel does not count as an obstacle. */
    private fun nearest(hist: IntArray, total: Int): Int? {
        if (total < MIN_SAMPLES) return null
        val target = total / 50
        var acc = 0
        for (b in hist.indices) {
            acc += hist[b]
            if (acc > target) return b * BIN_MM + BIN_MM / 2
        }
        return null
    }
}
