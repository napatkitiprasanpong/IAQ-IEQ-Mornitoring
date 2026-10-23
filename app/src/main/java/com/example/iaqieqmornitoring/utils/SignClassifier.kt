package com.example.iaqieqmornitoring.utils

import android.content.Context
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.exp

class SignPrediction(val label: String, val confidence: Float)

/**
 * Runs the isolated-sign model (assets/asl_signs.tflite, Kaggle ISLR 1st place, 250 ASL signs).
 *
 * Model (read from the file): input float32 [frames, 543, 3] (frames is dynamic), output float32 [250].
 * The model contains its own pre-processing, so the input is the raw landmark sequence.
 * Landmark order along the 543 axis: 468 face, 21 left hand, 33 pose, 21 right hand.
 * Missing landmarks must be NaN (the model handles them itself).
 *
 * Labels come from assets/sign_to_prediction_index_map.json ({"sign": index, ...}).
 * Not thread-safe: call [classify] from one thread at a time.
 */
class SignClassifier(context: Context) : AutoCloseable {

    private val interpreter: Interpreter
    private val labels: Array<String>
    private val outputSize: Int

    init {
        labels = loadLabels(context)

        val options = Interpreter.Options().setNumThreads(2)
        interpreter = try {
            // Memory-mapped (the model is stored uncompressed, see noCompress in build.gradle.kts)
            val afd = context.assets.openFd(MODEL_ASSET)
            val mapped = FileInputStream(afd.fileDescriptor).channel
                .map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            Interpreter(mapped, options)
        } catch (e: java.io.IOException) {
            // Compressed asset: fall back to copying it into a direct buffer
            val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            val direct = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            direct.put(bytes).rewind()
            Interpreter(direct, options)
        }

        val inShape = interpreter.getInputTensor(0).shape()
        val outShape = interpreter.getOutputTensor(0).shape()
        check(inShape.size == 3 && inShape[1] == LANDMARKS && inShape[2] == 3) {
            "Unexpected model input shape ${inShape.joinToString("x")}, expected frames x $LANDMARKS x 3"
        }
        outputSize = outShape.last()
        check(outputSize == labels.size) {
            "Model has $outputSize outputs but sign_to_prediction_index_map.json has ${labels.size} signs"
        }
    }

    /**
     * @param landmarks frameCount * 543 * 3 floats, frame by frame (x, y, z per landmark)
     * @return the [topK] most likely signs, best first
     */
    fun classify(landmarks: FloatArray, frameCount: Int, topK: Int = 3): List<SignPrediction> {
        require(frameCount > 0 && landmarks.size >= frameCount * LANDMARKS * 3)

        val input = ByteBuffer.allocateDirect(frameCount * LANDMARKS * 3 * 4).order(ByteOrder.nativeOrder())
        input.asFloatBuffer().put(landmarks, 0, frameCount * LANDMARKS * 3)

        interpreter.resizeInput(0, intArrayOf(frameCount, LANDMARKS, 3))
        interpreter.allocateTensors()

        val raw = FloatArray(outputSize)
        interpreter.run(input, raw)

        val probs = toProbabilities(raw)
        return probs.indices
            .sortedByDescending { probs[it] }
            .take(topK)
            .map { SignPrediction(labels[it], probs[it]) }
    }

    /** The model may return logits or probabilities; make sure we always end up with probabilities. */
    private fun toProbabilities(raw: FloatArray): FloatArray {
        var sum = 0f
        var inRange = true
        for (v in raw) {
            if (v.isNaN()) return FloatArray(raw.size)          // broken output: everything 0 -> never spoken
            if (v < 0f || v > 1f) inRange = false
            sum += v
        }
        if (inRange && abs(sum - 1f) < 0.05f) return raw

        val max = raw.max()
        val e = FloatArray(raw.size) { exp((raw[it] - max).toDouble()).toFloat() }
        val total = e.sum()
        return FloatArray(raw.size) { e[it] / total }
    }

    override fun close() {
        try { interpreter.close() } catch (_: Exception) {}
    }

    companion object {
        const val MODEL_ASSET = "asl_signs.tflite"
        const val LABELS_ASSET = "sign_to_prediction_index_map.json"
        const val LANDMARKS = 543

        private fun loadLabels(context: Context): Array<String> {
            val json = JSONObject(context.assets.open(LABELS_ASSET).bufferedReader().use { it.readText() })
            var max = -1
            val keys = json.keys()
            while (keys.hasNext()) max = maxOf(max, json.getInt(keys.next()))
            val out = Array(max + 1) { "" }
            val it2 = json.keys()
            while (it2.hasNext()) {
                val k = it2.next()
                out[json.getInt(k)] = k
            }
            return out
        }
    }
}
