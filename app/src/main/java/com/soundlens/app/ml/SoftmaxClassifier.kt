package com.soundlens.app.ml

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.exp
import kotlin.random.Random

/**
 * On-device trainable softmax (multinomial logistic regression) classifier over
 * 1024-dim YAMNet embeddings. Trained with mini-batch gradient descent — small
 * enough to run in a couple of seconds on a phone, and easy to "keep learning":
 * retrain any time new labeled clips arrive.
 *
 * Weights are persisted to JSON so recognition survives app restarts.
 */
class SoftmaxClassifier {

    data class Sample(val labelRow: Int, val x: FloatArray)

    var dim: Int = 1024
        private set
    var numLabels: Int = 0
        private set
    var labelIds: IntArray = IntArray(0) // row -> label database id
        private set
    private var weights = FloatArray(0) // [numLabels * dim]
    private var biases = FloatArray(0)
    private var mean = FloatArray(0)
    private var std = FloatArray(0)
    var trainAccuracy: Float = 0f
        private set
    var trainedAt: Long = 0
        private set
    var trainSamples: Int = 0
        private set

    val isTrained: Boolean get() = numLabels > 0 && weights.isNotEmpty()

    /**
     * @param samples embeddings with row indices 0..K-1 (contiguous).
     * @param labelIds label database ids aligned with rows.
     */
    fun train(
        samples: List<Sample>,
        labelIds: IntArray,
        epochs: Int = 150,
        lr: Float = 0.05f,
        l2: Float = 1e-4f,
        batchSize: Int = 16
    ) {
        require(samples.isNotEmpty()) { "没有可训练的样本" }
        val k = samples.maxOf { it.labelRow } + 1
        require(k == labelIds.size) { "标签行数与标签 id 数量不一致" }
        val d = samples.first().x.size
        dim = d
        numLabels = k
        this.labelIds = labelIds.copyOf()

        // Standardize features per dimension.
        mean = FloatArray(d)
        std = FloatArray(d)
        for (s in samples) for (i in 0 until d) mean[i] += s.x[i]
        val n = samples.size
        for (i in 0 until d) mean[i] /= n
        for (s in samples) for (i in 0 until d) {
            val diff = s.x[i] - mean[i]
            std[i] += diff * diff
        }
        for (i in 0 until d) std[i] = kotlin.math.sqrt(std[i] / n + 1e-6f)

        val xs = Array(n) { j ->
            FloatArray(d) { i -> (samples[j].x[i] - mean[i]) / std[i] }
        }
        val ys = IntArray(n) { samples[it].labelRow }

        weights = FloatArray(k * d)
        biases = FloatArray(k)
        val rng = Random(42)

        repeat(epochs) {
            val order = (0 until n).shuffled(rng)
            var bi = 0
            while (bi < n) {
                val end = minOf(bi + batchSize, n)
                val gw = FloatArray(k * d)
                val gb = FloatArray(k)
                for (oi in bi until end) {
                    val j = order[oi]
                    val x = xs[j]
                    val y = ys[j]
                    val p = probsOf(x)
                    for (c in 0 until k) {
                        val g = p[c] - (if (c == y) 1f else 0f)
                        gb[c] += g
                        val base = c * d
                        for (i in 0 until d) gw[base + i] += g * x[i]
                    }
                }
                val bs = (end - bi).toFloat()
                for (w in weights.indices) {
                    weights[w] -= lr * (gw[w] / bs + l2 * weights[w])
                }
                for (c in 0 until k) biases[c] -= lr * gb[c] / bs
                bi = end
            }
        }

        var correct = 0
        for (j in 0 until n) {
            if (argmax(probsOf(xs[j])) == ys[j]) correct++
        }
        trainAccuracy = correct.toFloat() / n
        trainSamples = n
        trainedAt = System.currentTimeMillis()
    }

    /** Returns probabilities aligned with [labelIds] rows. */
    fun predict(embedding: FloatArray): FloatArray {
        require(embedding.size == dim) { "特征维度不匹配" }
        val x = FloatArray(dim) { i -> (embedding[i] - mean[i]) / std[i] }
        return probsOf(x)
    }

    private fun probsOf(x: FloatArray): FloatArray {
        val k = numLabels
        val logits = FloatArray(k)
        var maxLogit = Float.NEGATIVE_INFINITY
        for (c in 0 until k) {
            var z = biases[c]
            val base = c * dim
            for (i in 0 until dim) z += weights[base + i] * x[i]
            logits[c] = z
            if (z > maxLogit) maxLogit = z
        }
        var sum = 0f
        val p = FloatArray(k)
        for (c in 0 until k) {
            p[c] = exp(logits[c] - maxLogit)
            sum += p[c]
        }
        for (c in 0 until k) p[c] /= sum
        return p
    }

    private fun argmax(p: FloatArray): Int {
        var best = 0
        for (i in 1 until p.size) if (p[i] > p[best]) best = i
        return best
    }

    // ---- Persistence ----

    fun save(file: File) {
        val obj = JSONObject()
            .put("dim", dim)
            .put("numLabels", numLabels)
            .put("labelIds", JSONArray(labelIds.toList()))
            .put("weights", toJsonArray(weights))
            .put("biases", toJsonArray(biases))
            .put("mean", toJsonArray(mean))
            .put("std", toJsonArray(std))
            .put("trainAccuracy", trainAccuracy.toDouble())
            .put("trainedAt", trainedAt)
            .put("trainSamples", trainSamples)
        file.writeText(obj.toString())
    }

    fun load(file: File) {
        val obj = JSONObject(file.readText())
        dim = obj.getInt("dim")
        numLabels = obj.getInt("numLabels")
        labelIds = toInts(obj.getJSONArray("labelIds"))
        weights = toFloats(obj.getJSONArray("weights"))
        biases = toFloats(obj.getJSONArray("biases"))
        mean = toFloats(obj.getJSONArray("mean"))
        std = toFloats(obj.getJSONArray("std"))
        trainAccuracy = obj.getDouble("trainAccuracy").toFloat()
        trainedAt = obj.getLong("trainedAt")
        trainSamples = obj.getInt("trainSamples")
    }

    private fun toJsonArray(a: FloatArray): JSONArray {
        val arr = JSONArray()
        for (v in a) arr.put(v.toDouble())
        return arr
    }

    private fun toInts(arr: JSONArray): IntArray {
        val out = IntArray(arr.length())
        for (i in 0 until arr.length()) out[i] = arr.getInt(i)
        return out
    }

    private fun toFloats(arr: JSONArray): FloatArray {
        val out = FloatArray(arr.length())
        for (i in 0 until arr.length()) out[i] = arr.getDouble(i).toFloat()
        return out
    }
}
