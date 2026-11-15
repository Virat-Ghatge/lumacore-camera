package com.lumacore.camera.core.video

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import com.lumacore.camera.core.storage.MediaSaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Configuration profile for hardware video encoding.
 */
data class VideoProfile(
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 30,
    val bitrate: Int = 20_000_000,
    val useHevc: Boolean = false
)

/**
 * High-performance hardware video recording engine.
 * Encapsulates MediaRecorder surface allocation, H.264/HEVC encoding at 1080p/4K 30fps/60fps,
 * 48kHz AAC stereo audio, real-time 50ms audio VU amplitude polling, and in-flight still snapshots.
 */
class VideoRecordingEngine(
    private val context: Context
) : AutoCloseable {

    private val engineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var amplitudeJob: Job? = null

    private val snapshotThread: HandlerThread = HandlerThread("VideoSnapshotThread").apply { start() }
    private val snapshotHandler: Handler = Handler(snapshotThread.looper)

    private var mediaRecorder: MediaRecorder? = null
    var recordingSurface: Surface? = null
        private set

    private var snapshotReader: ImageReader? = null
    var snapshotSurface: Surface? = null
        private set

    private var currentOutputFile: File? = null

    @Volatile
    var isRecording: Boolean = false
        private set

    /**
     * Prepares MediaRecorder with the specified video profile and returns its input Surface.
     */
    fun prepareRecorder(
        outputFile: File,
        profile: VideoProfile = VideoProfile()
    ): Surface {
        releaseRecorder()

        this.currentOutputFile = outputFile
        @Suppress("DEPRECATION")
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }

        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setOutputFile(outputFile.absolutePath)

            val encoder = if (profile.useHevc) {
                MediaRecorder.VideoEncoder.HEVC
            } else {
                MediaRecorder.VideoEncoder.H264
            }
            recorder.setVideoEncoder(encoder)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setVideoEncodingBitRate(profile.bitrate)
            recorder.setVideoFrameRate(profile.fps)
            recorder.setVideoSize(profile.width, profile.height)
            recorder.setAudioSamplingRate(AUDIO_SAMPLE_RATE)
            recorder.setAudioEncodingBitRate(AUDIO_BIT_RATE)

            recorder.prepare()
            val surface = recorder.surface
            this.mediaRecorder = recorder
            this.recordingSurface = surface

            // Prepare snapshot ImageReader matching video dimensions
            setupSnapshotReader(profile.width, profile.height)

            Log.i(TAG, "MediaRecorder prepared: ${profile.width}x${profile.height} @ ${profile.fps}fps (${if (profile.useHevc) "HEVC" else "H264"})")
            return surface
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare MediaRecorder", e)
            recorder.release()
            throw e
        }
    }

    private fun setupSnapshotReader(width: Int, height: Int): Unit {
        snapshotReader?.close()
        val reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 2)
        this.snapshotReader = reader
        this.snapshotSurface = reader.surface
    }

    /**
     * Starts recording video and launches live audio amplitude polling loop (every 50ms).
     */
    fun startRecording(
        outputFile: File,
        onAudioAmplitude: (Int) -> Unit = {}
    ): Unit {
        val recorder = mediaRecorder
            ?: prepareRecorder(outputFile, VideoProfile()).let { mediaRecorder!! }

        try {
            recorder.start()
            isRecording = true
            Log.i(TAG, "Video recording started: ${outputFile.name}")

            // 50ms coroutine loop for live audio VU meter polling
            amplitudeJob?.cancel()
            amplitudeJob = engineScope.launch {
                while (isRecording) {
                    try {
                        val amplitude = mediaRecorder?.maxAmplitude ?: 0
                        withContext(Dispatchers.Main) {
                            onAudioAmplitude(amplitude)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error querying max amplitude", e)
                    }
                    delay(50L)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaRecorder", e)
            isRecording = false
            throw e
        }
    }

    /**
     * Stops video recording and commits the video file into system MediaStore (DCIM/LumaCore).
     */
    suspend fun stopRecording(): Uri? = withContext(Dispatchers.IO) {
        if (!isRecording && mediaRecorder == null) {
            return@withContext null
        }

        isRecording = false
        amplitudeJob?.cancel()
        amplitudeJob = null

        val recorder = mediaRecorder
        val file = currentOutputFile

        try {
            recorder?.stop()
            recorder?.reset()
            recorder?.release()
            Log.i(TAG, "MediaRecorder stopped cleanly")
        } catch (e: Exception) {
            Log.w(TAG, "Exception during MediaRecorder stop/release", e)
        } finally {
            mediaRecorder = null
            recordingSurface = null
        }

        if (file != null && file.exists() && file.length() > 0) {
            return@withContext persistVideoToMediaStore(file)
        } else {
            Log.w(TAG, "Recorded output file is null or empty")
            return@withContext null
        }
    }

    /**
     * Takes an in-flight still snapshot without interrupting the active video recording session.
     */
    fun takeVideoSnapshot(
        captureSession: CameraCaptureSession?,
        cameraDevice: CameraDevice?,
        orientationDegrees: Int = 0,
        onComplete: (Uri?) -> Unit
    ): Unit {
        val session = captureSession ?: run {
            onComplete(null)
            return
        }
        val device = cameraDevice ?: run {
            onComplete(null)
            return
        }
        val reader = snapshotReader ?: run {
            onComplete(null)
            return
        }

        reader.setOnImageAvailableListener({ imageReader ->
            val image: Image? = imageReader.acquireLatestImage()
            if (image == null) {
                onComplete(null)
                return@setOnImageAvailableListener
            }
            engineScope.launch {
                val uri = MediaSaver.saveImage(
                    context = context,
                    image = image,
                    orientationDegrees = orientationDegrees
                )
                image.close()
                withContext(Dispatchers.Main) {
                    onComplete(uri)
                }
            }
        }, snapshotHandler)

        try {
            val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_VIDEO_SNAPSHOT)
            requestBuilder.addTarget(reader.surface)
            recordingSurface?.let { requestBuilder.addTarget(it) }

            session.capture(requestBuilder.build(), null, snapshotHandler)
            Log.i(TAG, "Video still snapshot requested")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture video snapshot", e)
            onComplete(null)
        }
    }

    private suspend fun persistVideoToMediaStore(file: File): Uri? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "LUMA_VID_$timeStamp.mp4"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Video.Media.RELATIVE_PATH, DIRECTORY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val uri: Uri? = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            Log.e(TAG, "Failed to insert video entry into MediaStore")
            return@withContext null
        }

        try {
            resolver.openOutputStream(uri)?.use { outputStream: OutputStream ->
                FileInputStream(file).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
                outputStream.flush()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }

            try {
                file.delete()
            } catch (delEx: Exception) {
                Log.w(TAG, "Could not delete temporary video cache file", delEx)
            }

            Log.i(TAG, "Video persisted successfully to MediaStore: $uri ($fileName)")
            return@withContext uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy video to MediaStore", e)
            try {
                resolver.delete(uri, null, null)
            } catch (delEx: Exception) {
                Log.w(TAG, "Failed to cleanup incomplete video MediaStore row", delEx)
            }
            return@withContext null
        }
    }

    private fun releaseRecorder(): Unit {
        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing MediaRecorder", e)
        } finally {
            mediaRecorder = null
            recordingSurface = null
        }
    }

    override fun close(): Unit {
        isRecording = false
        amplitudeJob?.cancel()
        amplitudeJob = null
        releaseRecorder()
        snapshotReader?.close()
        snapshotReader = null
        snapshotSurface = null
        snapshotThread.quitSafely()
    }

    companion object {
        private const val TAG: String = "VideoRecordingEngine"
        private const val DIRECTORY_NAME: String = "DCIM/LumaCore"
        private const val AUDIO_SAMPLE_RATE: Int = 48000
        private const val AUDIO_BIT_RATE: Int = 192000
    }
}
