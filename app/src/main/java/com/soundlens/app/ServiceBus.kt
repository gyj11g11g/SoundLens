package com.soundlens.app

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Lightweight in-process event bus between the audio foreground service and the UI.
 */
enum class CaptureMode { IDLE, CAPTURE, RECOGNIZE }

data class RecognitionSnapshot(
    /** Top results as label database ids with probability 0..1 */
    val top: List<Pair<Int, Float>>,
    val at: Long = System.currentTimeMillis()
)

object Bus {
    val mode = MutableStateFlow(CaptureMode.IDLE)
    val captureLabelId = MutableStateFlow(0)   // label applied to newly captured clips
    val sessionClipCount = MutableStateFlow(0) // clips saved in this capture session
    val recognition = MutableStateFlow<RecognitionSnapshot?>(null)
    val error = MutableStateFlow<String?>(null)

    fun reset() {
        mode.value = CaptureMode.IDLE
        sessionClipCount.value = 0
        recognition.value = null
        error.value = null
    }
}
