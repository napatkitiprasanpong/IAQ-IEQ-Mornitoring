package com.example.iaqieqmornitoring.utils

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.holisticlandmarker.HolisticLandmarker

/** Where each landmark group sits inside the model's 543-landmark axis. */
object SignLayout {
    const val FACE_START = 0        // 468 face mesh points
    const val FACE_COUNT = 468
    const val LEFT_HAND_START = 468 // 21
    const val POSE_START = 489      // 33
    const val RIGHT_HAND_START = 522 // 21
    const val HAND_COUNT = 21
    const val POSE_COUNT = 33
    const val TOTAL = 543

    // Pose landmark indices used by the segmentation logic / overlay
    const val POSE_L_SHOULDER = 11
    const val POSE_R_SHOULDER = 12
    const val POSE_L_ELBOW = 13
    const val POSE_R_ELBOW = 14
    const val POSE_L_WRIST = 15
    const val POSE_R_WRIST = 16
    const val POSE_L_HIP = 23
    const val POSE_R_HIP = 24
}

/**
 * Turns a camera picture into the 543 x 3 landmark array the sign model expects, using the
 * MediaPipe Holistic Landmarker (assets/holistic_landmarker.task).
 *
 * MediaPipe's Holistic result has 478 face points (468 mesh + 10 iris); only the 468 mesh points are used.
 * Anything not detected stays NaN.
 *
 * IMPORTANT: this class touches MediaPipe, which needs Android 7.0+ (API 24). Create it only after checking
 * Build.VERSION.SDK_INT (SignRecognizer does). Not thread-safe.
 *
 * The accessors of the Holistic result (faceLandmarks(), poseLandmarks(), leftHandLandmarks(),
 * rightHandLandmarks(), and x()/y()/z() of each landmark) are looked up by name at run time, so the code
 * works whether the library returns a flat list or a list-per-person, and fails with a readable message
 * if a future version renames something.
 */
class SignLandmarkExtractor(context: Context) : AutoCloseable {

    private val landmarker: HolisticLandmarker
    private val getters = HashMap<String, java.lang.reflect.Method>()
    private var lastTimestampMs = 0L

    init {
        val base = BaseOptions.builder().setModelAssetPath(TASK_ASSET).build()
        val options = HolisticLandmarker.HolisticLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.VIDEO)      // tracks between frames: faster and steadier than IMAGE
            .setMinFaceDetectionConfidence(0.5f)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinHandLandmarksConfidence(0.5f)
            .build()
        landmarker = HolisticLandmarker.createFromOptions(context, options)
    }

    /**
     * @param bitmap upright (already rotated) picture
     * @param out receives 543 * 3 floats; everything not detected is NaN
     * @return true if at least one hand was detected
     */
    fun extract(bitmap: Bitmap, timestampMs: Long, out: FloatArray, outOffset: Int = 0): Boolean {
        // VIDEO mode needs strictly increasing timestamps
        val ts = if (timestampMs <= lastTimestampMs) lastTimestampMs + 1 else timestampMs
        lastTimestampMs = ts

        val result: Any = landmarker.detectForVideo(BitmapImageBuilder(bitmap).build(), ts)

        java.util.Arrays.fill(out, outOffset, outOffset + SignLayout.TOTAL * 3, Float.NaN)
        put(result, "faceLandmarks", out, outOffset, SignLayout.FACE_START, SignLayout.FACE_COUNT)
        val left = put(result, "leftHandLandmarks", out, outOffset, SignLayout.LEFT_HAND_START, SignLayout.HAND_COUNT)
        put(result, "poseLandmarks", out, outOffset, SignLayout.POSE_START, SignLayout.POSE_COUNT)
        val right = put(result, "rightHandLandmarks", out, outOffset, SignLayout.RIGHT_HAND_START, SignLayout.HAND_COUNT)
        return left > 0 || right > 0
    }

    /** Copies one landmark group into [out]; returns how many landmarks were written. */
    private fun put(result: Any, accessor: String, out: FloatArray, outOffset: Int, start: Int, max: Int): Int {
        val list = landmarkList(result, accessor) ?: return 0
        val n = minOf(list.size, max)
        if (n == 0) return 0
        val first = list[0]
        val mx = method(first, "x")
        val my = method(first, "y")
        val mz = method(first, "z")
        for (i in 0 until n) {
            val p = list[i]
            val o = outOffset + (start + i) * 3
            out[o] = (mx.invoke(p) as Number).toFloat()
            out[o + 1] = (my.invoke(p) as Number).toFloat()
            out[o + 2] = (mz.invoke(p) as Number).toFloat()
        }
        return n
    }

    private fun landmarkList(result: Any, accessor: String): List<Any>? {
        var v: Any? = method(result, accessor).invoke(result)
        if (v is java.util.Optional<*>) v = v.orElse(null)
        if (v !is List<*> || v.isEmpty()) return null
        // list-per-person (first person) or a flat list of landmarks
        val first = v[0]
        @Suppress("UNCHECKED_CAST")
        return if (first is List<*>) (if (first.isEmpty()) null else first as List<Any>) else v as List<Any>
    }

    private fun method(target: Any, name: String): java.lang.reflect.Method {
        val key = target.javaClass.name + "#" + name
        return getters.getOrPut(key) {
            try {
                target.javaClass.getMethod(name)
            } catch (e: NoSuchMethodException) {
                throw IllegalStateException(
                    "This MediaPipe version has no ${target.javaClass.simpleName}.$name(); " +
                        "the Holistic result API changed. Pin tasks-vision to an older version."
                )
            }
        }
    }

    override fun close() {
        try { landmarker.close() } catch (_: Exception) {}
    }

    companion object {
        const val TASK_ASSET = "holistic_landmarker.task"
    }
}
