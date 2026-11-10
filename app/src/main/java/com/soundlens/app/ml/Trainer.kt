package com.soundlens.app.ml

import com.soundlens.app.SoundLensApp
import com.soundlens.app.audio.WavUtil
import java.io.File

/**
 * Builds the training set from labeled clips and trains the classifier.
 * All heavy work happens on Dispatchers.Default at the call site.
 */
object Trainer {

    data class Result(
        val accuracy: Float,
        val samples: Int,
        val labels: List<String>
    )

    /**
     * Reads all labeled clips, extracts YAMNet embeddings, trains the classifier
     * and persists the model. Progress is reported via [onProgress] (0..1).
     */
    fun trainAndSave(
        app: SoundLensApp,
        onProgress: (Float) -> Unit = {}
    ): Result {
        val labeled = app.db.labeledClips() // (labelId, path)
        require(labeled.size >= 4) { "已标注样本太少（至少 4 条）" }

        val labelIds = labeled.map { it.first }.distinct().sorted()
        val rowOf = HashMap<Int, Int>()
        labelIds.forEachIndexed { i, id -> rowOf[id] = i }
        require(labelIds.size >= 2) { "至少需要 2 个不同标签" }

        val samples = mutableListOf<SoftmaxClassifier.Sample>()
        labeled.forEachIndexed { idx, (labelId, path) ->
            val pcm = try {
                WavUtil.readWav(File(path))
            } catch (_: Exception) {
                null
            }
            if (pcm != null && pcm.isNotEmpty()) {
                val frame = ShortArray(YamNetEmbedder.FRAME_SAMPLES)
                System.arraycopy(pcm, 0, frame, 0, minOf(pcm.size, frame.size))
                val emb = app.embedder.embedding(frame)
                samples.add(SoftmaxClassifier.Sample(rowOf[labelId]!!, emb))
            }
            onProgress((idx + 1).toFloat() / labeled.size)
        }

        require(samples.size >= 4) { "有效样本不足" }

        val names = app.db.getLabels()
            .filter { labelIds.contains(it.id) }
            .sortedBy { labelIds.indexOf(it.id) }
            .map { it.name }

        app.classifier.train(samples, labelIds.toIntArray())
        app.classifier.save(app.modelFile)

        return Result(app.classifier.trainAccuracy, samples.size, names)
    }
}
