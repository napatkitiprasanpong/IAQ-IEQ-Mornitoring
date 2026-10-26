package com.example.iaqieqmornitoring.utils

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.exp

class SignPrediction(val label: String, val confidence: Float)

/**
 * Runs an ASL alphabet image classifier (assets/asl_alphabet.tflite) on a cropped picture of one hand.
 *
 * Works with the usual MobileNet-style models: input [1, height, width, 3] (RGB, float32 or uint8),
 * output [1, classes] (float32 or quantised, probabilities or logits). Size and types are read from the
 * file. What cannot be read from a .tflite file is how the pixels were scaled during training, so
 * [classify] takes an input mode (see [INPUT_ZERO_ONE] and friends); the Sign Language tab has a pill
 * to switch it. A model with uint8 input ignores the mode.
 *
 * Labels come from assets/asl_alphabet_labels.txt (one label per line, in output order). Without that
 * file, 26 outputs are read as A-Z, and 29 outputs as A-Z, del, nothing, space (the Kaggle "ASL Alphabet"
 * dataset order). Anything else needs the labels file.
 *
 * Does not use MediaPipe, so it runs on Android 5.0+ (TensorFlow Lite's minimum). Not thread-safe.
 */
class SignClassifier(context: Context) : AutoCloseable {

    private val interpreter: Interpreter
    private val labels: List<String>
    private val outputSize: Int
    private val inType: DataType
    private val outType: DataType
    private val outScale: Float
    private val outZero: Int
    private val inputBuffer: ByteBuffer
    private val outputBuffer: ByteBuffer
    private val pixels: IntArray

    val inputWidth: Int
    val inputHeight: Int

    init {
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

        val inTensor = interpreter.getInputTensor(0)
        val inShape = inTensor.shape()
        check(inShape.size == 4 && inShape[0] == 1 && inShape[3] == 3) {
            "Unexpected model input shape ${inShape.joinToString("x")}, expected 1 x height x width x 3 (RGB)"
        }
        inputHeight = inShape[1]
        inputWidth = inShape[2]
        inType = inTensor.dataType()
        check(inType == DataType.FLOAT32 || inType == DataType.UINT8) {
            "Unsupported model input type $inType (float32 or uint8 expected)"
        }

        val outTensor = interpreter.getOutputTensor(0)
        outputSize = outTensor.shape().last()
        outType = outTensor.dataType()
        val q = outTensor.quantizationParams()
        outScale = q.scale
        outZero = q.zeroPoint
        check(outType == DataType.FLOAT32 || outType == DataType.UINT8 || outType == DataType.INT8) {
            "Unsupported model output type $outType"
        }

        labels = loadLabels(context, outputSize)

        inputBuffer = ByteBuffer.allocateDirect(inTensor.numBytes()).order(ByteOrder.nativeOrder())
        outputBuffer = ByteBuffer.allocateDirect(outTensor.numBytes()).order(ByteOrder.nativeOrder())
        pixels = IntArray(inputWidth * inputHeight)
    }

    /**
     * @param crop a picture of exactly [inputWidth] x [inputHeight] pixels
     * @param mode how the model expects pixel values: [INPUT_ZERO_ONE], [INPUT_MINUS_ONE_ONE] or [INPUT_RAW_255]
     * @return the [topK] most likely letters, best first
     */
    fun classify(crop: Bitmap, mode: Int, topK: Int = 3): List<SignPrediction> {
        require(crop.width == inputWidth && crop.height == inputHeight) { "Crop must be ${inputWidth}x$inputHeight" }
        crop.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        inputBuffer.rewind()
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (inType == DataType.FLOAT32) {
                inputBuffer.putFloat(scale(r, mode)); inputBuffer.putFloat(scale(g, mode)); inputBuffer.putFloat(scale(b, mode))
            } else {
                inputBuffer.put(r.toByte()); inputBuffer.put(g.toByte()); inputBuffer.put(b.toByte())
            }
        }
        inputBuffer.rewind()
        outputBuffer.rewind()
        interpreter.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()

        val raw = FloatArray(outputSize)
        when (outType) {
            DataType.FLOAT32 -> outputBuffer.asFloatBuffer().get(raw)
            DataType.UINT8 -> for (i in raw.indices) raw[i] = dequant(outputBuffer.get().toInt() and 0xFF)
            else -> for (i in raw.indices) raw[i] = dequant(outputBuffer.get().toInt())
        }

        val probs = toProbabilities(raw)
        return probs.indices
            .sortedByDescending { probs[it] }
            .take(topK)
            .map { SignPrediction(labels[it], probs[it]) }
    }

    private fun scale(v: Int, mode: Int): Float = when (mode) {
        INPUT_MINUS_ONE_ONE -> v / 127.5f - 1f
        INPUT_RAW_255 -> v.toFloat()
        else -> v / 255f
    }

    private fun dequant(v: Int): Float = if (outScale == 0f) v / 255f else (v - outZero) * outScale

    /** The model may return logits or probabilities; make sure we always end up with probabilities. */
    private fun toProbabilities(raw: FloatArray): FloatArray {
        var sum = 0f
        var inRange = true
        for (v in raw) {
            if (v.isNaN()) return FloatArray(raw.size)          // broken output: everything 0 -> nothing accepted
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
        const val MODEL_ASSET = "asl_alphabet.tflite"
        const val LABELS_ASSET = "asl_alphabet_labels.txt"

        const val INPUT_ZERO_ONE = 0        // pixel / 255                (most Keras models trained on rescaled images)
        const val INPUT_MINUS_ONE_ONE = 1   // pixel / 127.5 - 1          (MobileNet preprocess_input)
        const val INPUT_RAW_255 = 2         // pixel as is, 0..255        (model rescales inside itself)

        private fun loadLabels(context: Context, outputs: Int): List<String> {
            val fromFile = try {
                context.assets.open(LABELS_ASSET).bufferedReader().use { r ->
                    r.readLines().map { it.trim() }.filter { it.isNotEmpty() }
                }
            } catch (e: java.io.IOException) {
                null
            }
            if (fromFile != null) {
                check(fromFile.size == outputs) {
                    "$LABELS_ASSET has ${fromFile.size} labels but the model has $outputs outputs"
                }
                return fromFile
            }
            val letters = ('A'..'Z').map { it.toString() }
            return when (outputs) {
                26 -> letters
                29 -> letters + listOf("del", "nothing", "space")
                else -> error("The model has $outputs outputs. Add $LABELS_ASSET (one label per line) to app/src/main/assets.")
            }
        }
    }
}
