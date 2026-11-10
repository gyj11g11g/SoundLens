package com.soundlens.app.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import com.soundlens.app.Bus
import com.soundlens.app.CaptureMode
import com.soundlens.app.MainActivity
import com.soundlens.app.RecognitionSnapshot
import com.soundlens.app.R
import com.soundlens.app.SoundLensApp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that continuously reads 16 kHz mono PCM from the microphone.
 *
 * Modes:
 *  - CAPTURE: saves every ~0.975 s frame as a WAV clip and stores it in the database
 *             (tagged with the label the user selected in the UI, 0 = unlabeled).
 *  - RECOGNIZE: runs YAMNet embedding + trained classifier on each frame and
 *             publishes results to [Bus].
 *
 * Android requires mic access in the background to run as a foreground service with
 * a persistent notification — which also keeps the user informed (privacy by design).
 */
class AudioCaptureService : Service() {

    companion object {
        const val ACTION_START_CAPTURE = "com.soundlens.app.action.START_CAPTURE"
        const val ACTION_START_RECOGNIZE = "com.soundlens.app.action.START_RECOGNIZE"
        const val ACTION_STOP = "com.soundlens.app.action.STOP"
        const val EXTRA_LABEL_ID = "extra_label_id"
        private const val CHANNEL_ID = "soundlens_capture"
        private const val NOTIFICATION_ID = 42
        private const val FRAME_SAMPLES = 15600 // 0.975 s @ 16 kHz (YAMNet window)
        private const val CHUNK = 3900          // ~244 ms read chunk
    }

    @Volatile private var running = false
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null
    private var mode: CaptureMode = CaptureMode.IDLE
    private var captureLabelId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CAPTURE -> {
                captureLabelId = intent.getIntExtra(EXTRA_LABEL_ID, 0)
                Bus.captureLabelId.value = captureLabelId
                startWithMode(CaptureMode.CAPTURE)
            }
            ACTION_START_RECOGNIZE -> startWithMode(CaptureMode.RECOGNIZE)
            ACTION_STOP -> stopEverything()
        }
        return START_NOT_STICKY
    }

    private fun startWithMode(newMode: CaptureMode) {
        // If already running in a different mode, restart the loop.
        if (running && mode != newMode) stopLoop()
        mode = newMode
        Bus.mode.value = newMode
        Bus.sessionClipCount.value = 0
        Bus.recognition.value = null
        Bus.error.value = null

        startForegroundCompat()
        if (!running) startLoop()
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "声音采集", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (mode == CaptureMode.CAPTURE) {
            getString(R.string.capture_notification_title)
        } else {
            getString(R.string.recognize_notification_title)
        }
        val text = if (mode == CaptureMode.CAPTURE) {
            getString(R.string.capture_notification_text)
        } else {
            getString(R.string.recognize_notification_text)
        }
        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .setContentIntent(contentIntent)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .setContentIntent(contentIntent)
                .build()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startLoop() {
        val app = application as SoundLensApp
        val minBuf = AudioRecord.getMinBufferSize(
            WavUtil.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            Bus.error.value = "无法初始化音频缓冲区"
            stopEverything()
            return
        }
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                WavUtil.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, FRAME_SAMPLES * 4)
            )
        } catch (e: Exception) {
            Bus.error.value = "麦克风被占用或无权限: ${e.message}"
            stopEverything()
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            Bus.error.value = "麦克风初始化失败"
            stopEverything()
            return
        }
        audioRecord = record
        running = true
        record.startRecording()

        worker = Thread {
            val frame = ShortArray(FRAME_SAMPLES)
            val chunk = ShortArray(CHUNK)
            var filled = 0
            var classifier: com.soundlens.app.ml.SoftmaxClassifier? =
                if (mode == CaptureMode.RECOGNIZE && app.classifier.isTrained) app.classifier else null
            if (mode == CaptureMode.RECOGNIZE && classifier == null) {
                Bus.error.value = "尚未训练模型，请先在【训练】页完成训练"
                stopEverything()
                return@Thread
            }
            while (running) {
                val n = record.read(chunk, 0, CHUNK)
                if (n <= 0) continue
                var i = 0
                while (i < n) {
                    val take = minOf(CHUNK - i, FRAME_SAMPLES - filled)
                    System.arraycopy(chunk, i, frame, filled, take)
                    filled += take
                    i += take
                    if (filled == FRAME_SAMPLES) {
                        handleFrame(app, frame.copyOf())
                        filled = 0
                    }
                }
            }
        }.apply { start() }
    }

    private fun handleFrame(app: SoundLensApp, frame: ShortArray) {
        try {
            when (mode) {
                CaptureMode.CAPTURE -> saveClip(app, frame)
                CaptureMode.RECOGNIZE -> recognize(app, frame)
                CaptureMode.IDLE -> Unit
            }
        } catch (e: Exception) {
            Bus.error.value = e.message
        }
    }

    private fun saveClip(app: SoundLensApp, frame: ShortArray) {
        val dir = File(getExternalFilesDir(null), "clips").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val file = File(dir, "clip_$ts.wav")
        WavUtil.writeWav(file, frame)
        app.db.addClip(file.absolutePath, captureLabelId)
        Bus.sessionClipCount.value = Bus.sessionClipCount.value + 1
    }

    private fun recognize(app: SoundLensApp, frame: ShortArray) {
        val embedding = app.embedder.embedding(frame)
        val cls = app.classifier
        if (!cls.isTrained) return
        val probs = cls.predict(embedding)
        val rows = probs.indices.sortedByDescending { probs[it] }.take(3)
        val top = rows.mapNotNull { row ->
            val labelId = cls.labelIds.getOrNull(row) ?: return@mapNotNull null
            labelId to probs[row]
        }
        Bus.recognition.value = RecognitionSnapshot(top)
    }

    private fun stopLoop() {
        running = false
        try { worker?.join(1000) } catch (_: Exception) {}
        worker = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    private fun stopEverything() {
        stopLoop()
        Bus.mode.value = CaptureMode.IDLE
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopLoop()
        super.onDestroy()
    }
}
