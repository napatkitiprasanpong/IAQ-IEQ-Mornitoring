package com.example.iaqieqmornitoring.utils

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import java.nio.IntBuffer
import kotlin.math.max

/** One body keypoint. x / y are 0..1 inside the upright camera image (y grows downwards). */
class Keypoint(val x: Float, val y: Float, val score: Float)

/** Indices of the 17 keypoints MoveNet returns. */
object Kp {
    const val NOSE = 0
    const val L_SHOULDER = 5
    const val R_SHOULDER = 6
    const val L_ELBOW = 7
    const val R_ELBOW = 8
    const val L_WRIST = 9
    const val R_WRIST = 10
}

class PoseResult(
    val points: List<Keypoint>,
    /** Size of the upright camera image the points refer to. */
    val imageWidth: Int,
    val imageHeight: Int,
    val inferenceMs: Long
)

/**
 * Runs MoveNet Lightning (assets/movenet_lightning.onnx) with ONNX Runtime.
 * Model: input int32 [1,192,192,3] RGB 0..255, output float [1,1,17,3] = (y, x, score) per keypoint.
 * Not thread-safe: use it from one analysis thread.
 */
class PoseDetector(context: Context) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    private val size = 192
    private val square = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(square)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(size * size)
    private val input = IntBuffer.allocate(size * size * 3)

    init {
        val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
        session = env.createSession(bytes, options)
        inputName = session.inputNames.first()
    }

    /**
     * @param bitmap the camera frame as delivered (not rotated)
     * @param rotationDegrees clockwise rotation needed to make it upright (ImageInfo.rotationDegrees)
     */
    fun detect(bitmap: Bitmap, rotationDegrees: Int): PoseResult {
        val t0 = SystemClock.elapsedRealtime()

        val sideways = rotationDegrees % 180 != 0
        val uw = if (sideways) bitmap.height else bitmap.width
        val uh = if (sideways) bitmap.width else bitmap.height

        // Rotate upright, shrink to fit the 192x192 square and pad the rest with black (no stretching).
        val scale = size.toFloat() / max(uw, uh)
        val m = Matrix().apply {
            postTranslate(-bitmap.width / 2f, -bitmap.height / 2f)
            postRotate(rotationDegrees.toFloat())
            postScale(scale, scale)
            postTranslate(size / 2f, size / 2f)
        }
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(bitmap, m, paint)

        square.getPixels(pixels, 0, size, 0, 0, size, size)
        input.rewind()
        for (p in pixels) {
            input.put((p shr 16) and 0xFF)
            input.put((p shr 8) and 0xFF)
            input.put(p and 0xFF)
        }
        input.rewind()

        val dw = uw * scale
        val dh = uh * scale
        val padX = (size - dw) / 2f
        val padY = (size - dh) / 2f

        val points = ArrayList<Keypoint>(17)
        OnnxTensor.createTensor(env, input, longArrayOf(1, size.toLong(), size.toLong(), 3)).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val out = result.get(0).value as Array<Array<Array<FloatArray>>>
                for (k in out[0][0]) {
                    val y = (k[0] * size - padY) / dh
                    val x = (k[1] * size - padX) / dw
                    points.add(Keypoint(x, y, k[2]))
                }
            }
        }
        return PoseResult(points, uw, uh, SystemClock.elapsedRealtime() - t0)
    }

    override fun close() {
        try { session.close() } catch (_: Exception) {}
    }

    companion object {
        const val MODEL_ASSET = "movenet_lightning.onnx"
    }
}
