package com.soundlens.app

import android.app.Application
import com.soundlens.app.data.Db
import com.soundlens.app.ml.SoftmaxClassifier
import com.soundlens.app.ml.YamNetEmbedder
import java.io.File

/**
 * Application-level singletons shared between the UI and the capture service.
 */
class SoundLensApp : Application() {

    val db: Db by lazy { Db(this) }
    val embedder: YamNetEmbedder by lazy { YamNetEmbedder(this) }
    val classifier: SoftmaxClassifier by lazy { SoftmaxClassifier() }

    val modelFile: File get() = File(filesDir, "model.json")

    override fun onCreate() {
        super.onCreate()
        if (modelFile.exists()) {
            try {
                classifier.load(modelFile)
            } catch (_: Exception) {
                // Corrupt model file: ignore, user can retrain.
            }
        }
    }
}
