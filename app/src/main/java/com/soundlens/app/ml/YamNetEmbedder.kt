package com.soundlens.app.ml

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * YAMNet TFLite embedder.
 *
 * The bundled model (lite-model_yamnet_classification_tflite_1) accepts a
 * float32[1, 15600] waveform (0.975 s @ 16 kHz, values in [-1, 1]) and exposes
 * two outputs: AudioSet scores float32[1, 521] and the 1024-dim embedding used
 * as the feature vector for our on-device classifier.
 */
class YamNetEmbedder(context: Context) : Closeable {

    companion object {
        const val FRAME_SAMPLES = 15600
        const val EMBEDDING_DIM = 1024
        private const val MODEL_ASSET = "yamnet.tflite"
    }

    private val interpreter: Interpreter

    init {
        val modelBuffer = loadModel(context)
        interpreter = Interpreter(modelBuffer, Interpreter.Options().apply { numThreads = 4 })
    }

    private fun loadModel(context: Context): ByteBuffer {
        context.assets.openFd(MODEL_ASSET).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { ins ->
                val channel = ins.channel
                val buffer = ByteBuffer.allocateDirect(fd.length.toInt())
                    .order(ByteOrder.LITTLE_ENDIAN)
                channel.read(buffer)
                buffer.rewind()
                return buffer
            }
        }
    }

    /** Returns the 1024-dim YAMNet embedding for one 15600-sample PCM16 frame. */
    fun embedding(pcm: ShortArray): FloatArray {
        require(pcm.size == FRAME_SAMPLES) { "expect $FRAME_SAMPLES samples, got ${pcm.size}" }
        val input = FloatArray(FRAME_SAMPLES) { pcm[it] / 32768f }
        val scores = Array(1) { FloatArray(521) }
        val embeddings = Array(1) { FloatArray(EMBEDDING_DIM) }
        val outputs = HashMap<Int, Any>(2)
        outputs[0] = scores
        outputs[1] = embeddings
        interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)
        return embeddings[0]
    }

    override fun close() {
        interpreter.close()
    }
}
