/*
 * SPDX-FileCopyrightText: 2026 Antigravity
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.screenmirror

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.graphics.Rect
import android.graphics.Paint
import android.os.Environment
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Locale

import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import android.util.Log
import org.kde.kdeconnect.logging.KdeLog
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import org.kde.kdeconnect.helpers.ThreadHelper
import org.kde.kdeconnect.plugins.clipboard.ClipboardListener
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import android.widget.Toast
import android.widget.Spinner
import android.widget.AdapterView
import android.widget.ArrayAdapter

import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.kde.kdeconnect.KdeConnect
import androidx.preference.PreferenceManager
import android.net.Uri
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import org.kde.kdeconnect.base.BaseActivity
import org.kde.kdeconnect.extensions.viewBinding
import org.kde.kdeconnect.helpers.SshManager
import org.kde.kdeconnect.plugins.mousepad.MousePadPlugin
import org.kde.kdeconnect_tp.R
import org.kde.kdeconnect_tp.databinding.ActivityScreenMirrorBinding
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.InetSocketAddress


class ScreenVideoRecorder(
    private val context: android.content.Context,
    private val hostIp: String,
    private val width: Int,
    private val height: Int,
    val outputFile: File? = null,
    val pfd: ParcelFileDescriptor? = null,
    private val recordAudio: Boolean = true,
    private val targetFps: Int = 60,
    private val scaleMode: String = "FIT_CENTER",
    private val aspectMode: String = "Auto (Match Laptop Native)",
    private val audioEndpoint: String = "/audio.pcm",
    private val audioSyncOffsetMs: Int = 60
) {
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var inputSurface: Surface? = null

    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var isMuxerStarted = false

    private val videoBufferInfo = MediaCodec.BufferInfo()
    private val audioBufferInfo = MediaCodec.BufferInfo()
    private val lock = Any()
    private val highQualityPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    // Synchronized relative timestamps
    private var firstVideoBufferPtsUs = -1L
    private var lastVideoPtsUs = -1L
    private var lastAudioPtsUs = -1L
    private var audioTimelinePtsUs = 0L

    private var pauseStartTimeUs = 0L
    private var totalPausedDurationUs = 0L
    private var lastFrameEncodedTimeNs = 0L
    private val minFrameIntervalNs = if (targetFps in 1..119) (1_000_000_000L / targetFps) else 0L

    // Pending samples queue before MediaMuxer starts
    private data class PendingSample(val track: Int, val data: ByteArray, val info: MediaCodec.BufferInfo)
    private val pendingSamples = mutableListOf<PendingSample>()

    private var audioCaptureThread: Thread? = null
    private var lastExternalAudioTimeNs = 0L

    // Real-time Phone Mic audio buffer for mixing
    private val micBufferLock = Any()
    private val micBuffer = java.io.ByteArrayOutputStream(16384)
    private var lastPcAudioTimeNs = 0L

    var isRecording = false
        private set
    var isPaused = false
        private set

    fun start() {
        synchronized(lock) {
            // 1. Video Encoder (H.264 High Quality, 8 Mbps, targetFps)
            val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val vCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            vCodec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = vCodec.createInputSurface()
            vCodec.start()
            videoCodec = vCodec

            // 2. Audio Encoder (AAC 44.1kHz Stereo 192kbps)
            if (recordAudio) {
                try {
                    val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_BIT_RATE, 192000)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                    }
                    val aCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                    aCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    aCodec.start()
                    audioCodec = aCodec
                } catch (e: Exception) {
                    Log.w("ScreenVideoRecorder", "Audio encoder init failed", e)
                }
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && pfd != null) {
                mediaMuxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            } else if (outputFile != null) {
                mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }

            isRecording = true
            isPaused = false
            firstVideoBufferPtsUs = -1L
            lastVideoPtsUs = -1L
            lastAudioPtsUs = -1L
            audioTimelinePtsUs = 0L
            videoTrackIndex = -1
            audioTrackIndex = -1
            isMuxerStarted = false
            pendingSamples.clear()
            pauseStartTimeUs = 0L
            totalPausedDurationUs = 0L
            lastFrameEncodedTimeNs = 0L
            lastExternalAudioTimeNs = 0L
            lastPcAudioTimeNs = 0L
            synchronized(micBufferLock) { micBuffer.reset() }

            // Background fallback audio stream if speaker audio is not active
            if (recordAudio) {
                startAudioCaptureThread()
            }
        }
    }

    private fun startAudioCaptureThread() {
        val thread = Thread {
            val buffer = ByteArray(4096)
            var connection: HttpURLConnection? = null
            var inputStream: InputStream? = null
            try {
                if (hostIp.isNotEmpty()) {
                    val url = URL("http://$hostIp:59001$audioEndpoint")
                    connection = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 3000
                        readTimeout = 5000
                        useCaches = false
                        doInput = true
                    }
                    connection.connect()
                    if (connection.responseCode == 200) {
                        inputStream = BufferedInputStream(connection.inputStream, 16384)
                        while (isRecording && !Thread.currentThread().isInterrupted) {
                            val read = inputStream.read(buffer, 0, buffer.size)
                            if (read <= 0) break
                            if (!isPaused) {
                                encodeAudio(buffer, read, false)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("ScreenVideoRecorder", "PC audio capture stream ended: ${e.message}")
            } finally {
                try { inputStream?.close() } catch (_: Exception) {}
                try { connection?.disconnect() } catch (_: Exception) {}
            }

            // Fallback to silence generator if stream closed/failed
            val silenceBuffer = ByteArray(4096)
            while (isRecording && !Thread.currentThread().isInterrupted) {
                if (!isPaused) {
                    encodeAudio(silenceBuffer, silenceBuffer.size, false)
                }
                try {
                    Thread.sleep(23)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        thread.name = "RecorderAudioWorker"
        thread.isDaemon = true
        thread.start()
        audioCaptureThread = thread
    }

    fun pause() {
        synchronized(lock) {
            if (isRecording && !isPaused) {
                isPaused = true
                pauseStartTimeUs = System.nanoTime() / 1000L
            }
        }
    }

    fun resume() {
        synchronized(lock) {
            if (isRecording && isPaused) {
                isPaused = false
                if (pauseStartTimeUs > 0L) {
                    totalPausedDurationUs += ((System.nanoTime() / 1000L) - pauseStartTimeUs)
                    pauseStartTimeUs = 0L
                }
            }
        }
    }

    fun encodeBitmap(bitmap: Bitmap) {
        synchronized(lock) {
            if (!isRecording || isPaused) return
            val surface = inputSurface ?: return
            val codec = videoCodec ?: return

            val nowNs = System.nanoTime()
            if (minFrameIntervalNs > 0L) {
                if (lastFrameEncodedTimeNs > 0L && (nowNs - lastFrameEncodedTimeNs) < (minFrameIntervalNs - 1_500_000L)) {
                    return // Drop frame to maintain configured FPS
                }
            }
            lastFrameEncodedTimeNs = nowNs

            try {
                val canvas = surface.lockCanvas(null)
                canvas.drawColor(Color.BLACK)

                if (scaleMode == "FIT_XY") {
                    val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
                    val destRect = Rect(0, 0, width, height)
                    canvas.drawBitmap(bitmap, srcRect, destRect, highQualityPaint)
                } else if (scaleMode == "CENTER_CROP" || (aspectMode.contains("Phone") && scaleMode != "FIT_CENTER")) {
                    val scale = maxOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
                    val cropW = (width / scale).toInt()
                    val cropH = (height / scale).toInt()
                    val cropLeft = (bitmap.width - cropW) / 2
                    val cropTop = (bitmap.height - cropH) / 2
                    val srcRect = Rect(cropLeft, cropTop, cropLeft + cropW, cropTop + cropH)
                    val destRect = Rect(0, 0, width, height)
                    canvas.drawBitmap(bitmap, srcRect, destRect, highQualityPaint)
                } else if (scaleMode == "CENTER") {
                    val drawLeft = (width - bitmap.width) / 2
                    val drawTop = (height - bitmap.height) / 2
                    val destRect = Rect(drawLeft, drawTop, drawLeft + bitmap.width, drawTop + bitmap.height)
                    canvas.drawBitmap(bitmap, Rect(0, 0, bitmap.width, bitmap.height), destRect, highQualityPaint)
                } else {
                    val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
                    val drawW = (bitmap.width * scale).toInt()
                    val drawH = (bitmap.height * scale).toInt()
                    val drawLeft = (width - drawW) / 2
                    val drawTop = (height - drawH) / 2
                    val destRect = Rect(drawLeft, drawTop, drawLeft + drawW, drawTop + drawH)
                    canvas.drawBitmap(bitmap, Rect(0, 0, bitmap.width, bitmap.height), destRect, highQualityPaint)
                }

                surface.unlockCanvasAndPost(canvas)
                drainVideoEncoder(false)
            } catch (e: Exception) {
                Log.e("ScreenVideoRecorder", "Error encoding video frame", e)
            }
        }
    }

    /**
     * Receives Phone Microphone Audio (Mono 16-bit PCM @ 44.1kHz)
     */
    fun encodePhoneMicAudio(monoPcmBytes: ByteArray, length: Int) {
        if (!recordAudio) return
        val alignedMonoBytes = (length / 2) * 2
        if (alignedMonoBytes <= 0) return

        synchronized(micBufferLock) {
            // Cap buffer to ~150ms to eliminate latency accumulation while ensuring no dropped chunks
            if (micBuffer.size() > 16384) {
                val current = micBuffer.toByteArray()
                micBuffer.reset()
                val keep = current.takeLast(8192).toByteArray()
                micBuffer.write(keep)
            }
            micBuffer.write(monoPcmBytes, 0, alignedMonoBytes)
        }
    }

    /**
     * Receives PC Desktop/Speaker Audio (Stereo 16-bit PCM @ 44.1kHz)
     */
    fun encodeAudio(pcmBytes: ByteArray, length: Int, isExternal: Boolean = false) {
        if (!recordAudio) return
        val nowNs = System.nanoTime()
        if (isExternal) {
            lastExternalAudioTimeNs = nowNs
        } else {
            if (lastExternalAudioTimeNs > 0L && (nowNs - lastExternalAudioTimeNs) < 200_000_000L) {
                return
            }
        }
        lastPcAudioTimeNs = nowNs

        val frameBytes = (length / 4) * 4
        if (frameBytes <= 0) return

        // Mix pending phone mic audio if available
        var mixedBytes = pcmBytes
        synchronized(micBufferLock) {
            val bufferedMicSize = micBuffer.size()
            if (bufferedMicSize > 0) {
                val micData = micBuffer.toByteArray()
                micBuffer.reset()

                val stereoSamples = frameBytes / 4
                val monoSamples = micData.size / 2
                val samplesToMix = minOf(stereoSamples, monoSamples)

                val out = pcmBytes.copyOf(frameBytes)
                for (i in 0 until samplesToMix) {
                    val sL = ((out[i * 4].toInt() and 0xFF) or (out[i * 4 + 1].toInt() shl 8)).toShort()
                    val sR = ((out[i * 4 + 2].toInt() and 0xFF) or (out[i * 4 + 3].toInt() shl 8)).toShort()
                    val m = ((micData[i * 2].toInt() and 0xFF) or (micData[i * 2 + 1].toInt() shl 8)).toShort()

                    val mixL = (sL.toInt() + m.toInt()).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    val mixR = (sR.toInt() + m.toInt()).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

                    out[i * 4 + 0] = (mixL.toInt() and 0xFF).toByte()
                    out[i * 4 + 1] = ((mixL.toInt() shr 8) and 0xFF).toByte()
                    out[i * 4 + 2] = (mixR.toInt() and 0xFF).toByte()
                    out[i * 4 + 3] = ((mixR.toInt() shr 8) and 0xFF).toByte()
                }

                // If mic had more samples than this frame, re-buffer the remainder
                if (monoSamples > samplesToMix) {
                    val remainingBytes = (monoSamples - samplesToMix) * 2
                    micBuffer.write(micData, samplesToMix * 2, remainingBytes)
                }

                mixedBytes = out
            }
        }

        encodeStereoAudioInternal(mixedBytes, frameBytes)
    }

    private fun encodeStereoAudioInternal(stereoBytes: ByteArray, frameBytes: Int) {
        synchronized(lock) {
            if (!isRecording || isPaused) return
            val aCodec = audioCodec ?: return

            val chunkDurationUs = ((frameBytes / 4) * 1_000_000L) / 44100L
            val offsetUs = audioSyncOffsetMs * 1000L
            var pts = audioTimelinePtsUs + offsetUs
            if (pts < 0L) pts = 0L
            audioTimelinePtsUs += chunkDurationUs

            try {
                val inputIndex = aCodec.dequeueInputBuffer(2000)
                if (inputIndex >= 0) {
                    val inputBuffer = aCodec.getInputBuffer(inputIndex)
                    if (inputBuffer != null) {
                        inputBuffer.clear()
                        inputBuffer.put(stereoBytes, 0, frameBytes)
                        aCodec.queueInputBuffer(inputIndex, 0, frameBytes, pts, 0)
                    }
                }
                drainAudioEncoder(false)
            } catch (e: Exception) {
                Log.e("ScreenVideoRecorder", "Error encoding audio", e)
            }
        }
    }

    private fun checkStartMuxer() {
        val muxer = mediaMuxer ?: return
        if (!isMuxerStarted && videoTrackIndex >= 0 && (!recordAudio || audioTrackIndex >= 0)) {
            try {
                muxer.start()
                isMuxerStarted = true
                for (sample in pendingSamples) {
                    val track = if (sample.track == 0) videoTrackIndex else audioTrackIndex
                    val buffer = ByteBuffer.wrap(sample.data)
                    muxer.writeSampleData(track, buffer, sample.info)
                }
                pendingSamples.clear()
            } catch (e: Exception) {
                Log.e("ScreenVideoRecorder", "Error starting MediaMuxer", e)
            }
        }
    }

    private fun drainVideoEncoder(endOfStream: Boolean) {
        val codec = videoCodec ?: return
        val muxer = mediaMuxer ?: return

        if (endOfStream) {
            try { codec.signalEndOfInputStream() } catch (_: Exception) {}
        }

        while (true) {
            val status = codec.dequeueOutputBuffer(videoBufferInfo, if (endOfStream) 10000 else 0)
            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                videoTrackIndex = muxer.addTrack(codec.outputFormat)
                checkStartMuxer()
            } else if (status >= 0) {
                val encodedData = codec.getOutputBuffer(status)
                if (encodedData != null) {
                    if ((videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        videoBufferInfo.size = 0
                    }
                    if (videoBufferInfo.size > 0 && videoTrackIndex >= 0) {
                        if (firstVideoBufferPtsUs == -1L) {
                            firstVideoBufferPtsUs = videoBufferInfo.presentationTimeUs
                        }
                        var pts = videoBufferInfo.presentationTimeUs - firstVideoBufferPtsUs - totalPausedDurationUs
                        if (pts < 0L) pts = 0L
                        if (pts <= lastVideoPtsUs) {
                            pts = lastVideoPtsUs + 1000L
                        }
                        lastVideoPtsUs = pts
                        videoBufferInfo.presentationTimeUs = pts

                        encodedData.position(videoBufferInfo.offset)
                        encodedData.limit(videoBufferInfo.offset + videoBufferInfo.size)

                        if (isMuxerStarted) {
                            muxer.writeSampleData(videoTrackIndex, encodedData, videoBufferInfo)
                        } else {
                            val dataCopy = ByteArray(videoBufferInfo.size)
                            encodedData.get(dataCopy)
                            val infoCopy = MediaCodec.BufferInfo().apply {
                                set(0, dataCopy.size, videoBufferInfo.presentationTimeUs, videoBufferInfo.flags)
                            }
                            pendingSamples.add(PendingSample(0, dataCopy, infoCopy))
                        }
                    }
                }
                codec.releaseOutputBuffer(status, false)
                if ((videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        }
    }

    private fun drainAudioEncoder(endOfStream: Boolean) {
        if (!recordAudio) return
        val codec = audioCodec ?: return
        val muxer = mediaMuxer ?: return

        while (true) {
            val status = codec.dequeueOutputBuffer(audioBufferInfo, if (endOfStream) 10000 else 0)
            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break
            } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                audioTrackIndex = muxer.addTrack(codec.outputFormat)
                checkStartMuxer()
            } else if (status >= 0) {
                val encodedData = codec.getOutputBuffer(status)
                if (encodedData != null) {
                    if ((audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        audioBufferInfo.size = 0
                    }
                    if (audioBufferInfo.size > 0 && audioTrackIndex >= 0) {
                        var pts = audioBufferInfo.presentationTimeUs
                        if (pts < 0L) pts = 0L
                        if (pts <= lastAudioPtsUs) {
                            pts = lastAudioPtsUs + 250L
                        }
                        lastAudioPtsUs = pts
                        audioBufferInfo.presentationTimeUs = pts

                        encodedData.position(audioBufferInfo.offset)
                        encodedData.limit(audioBufferInfo.offset + audioBufferInfo.size)

                        if (isMuxerStarted) {
                            muxer.writeSampleData(audioTrackIndex, encodedData, audioBufferInfo)
                        } else {
                            val dataCopy = ByteArray(audioBufferInfo.size)
                            encodedData.get(dataCopy)
                            val infoCopy = MediaCodec.BufferInfo().apply {
                                set(0, dataCopy.size, audioBufferInfo.presentationTimeUs, audioBufferInfo.flags)
                            }
                            pendingSamples.add(PendingSample(1, dataCopy, infoCopy))
                        }
                    }
                }
                codec.releaseOutputBuffer(status, false)
                if ((audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        }
    }

    fun stop(): File? {
        synchronized(lock) {
            if (!isRecording) return null
            isRecording = false
            isPaused = false

            audioCaptureThread?.interrupt()
            audioCaptureThread = null

            try { drainVideoEncoder(true) } catch (_: Exception) {}
            if (recordAudio) {
                try { drainAudioEncoder(true) } catch (_: Exception) {}
            }

            try { videoCodec?.stop(); videoCodec?.release() } catch (_: Exception) {}
            videoCodec = null
            try { audioCodec?.stop(); audioCodec?.release() } catch (_: Exception) {}
            audioCodec = null
            try { inputSurface?.release() } catch (_: Exception) {}
            inputSurface = null
            try {
                if (isMuxerStarted) { mediaMuxer?.stop() }
                mediaMuxer?.release()
            } catch (_: Exception) {}
            mediaMuxer = null

            try { pfd?.close() } catch (_: Exception) {}

            return outputFile
        }
    }

    fun discard() {
        synchronized(lock) {
            val file = stop()
            try {
                if (file != null && file.exists()) {
                    file.delete()
                }
            } catch (_: Exception) {}
        }
    }
}
class ScreenMirrorActivity : BaseActivity<ActivityScreenMirrorBinding>(),
    ScreenMirrorPlugin.FrameListener,
    GestureDetector.OnGestureListener,
    GestureDetector.OnDoubleTapListener,
    ClipboardListener.ClipboardObserver {

    private var currentSettingsStorageTextView: android.widget.TextView? = null
    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            try {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (e: Exception) {
                Log.w("ScreenMirror", "Failed to persist URI permission: ${e.message}")
            }
            val doc = DocumentFile.fromTreeUri(this, uri)
            val folderName = doc?.name ?: uri.lastPathSegment ?: "Custom Folder"
            val displayStr = "Folder: $folderName"
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            prefs.edit()
                .putString("pref_storage_uri", uri.toString())
                .putString("pref_storage_display_name", displayStr)
                .apply()
            currentSettingsStorageTextView?.text = displayStr
            Toast.makeText(this, "Recording folder set to: $folderName", Toast.LENGTH_SHORT).show()
        }
    }

    override val binding: ActivityScreenMirrorBinding by viewBinding(ActivityScreenMirrorBinding::inflate)

    private var plugin: ScreenMirrorPlugin? = null
    private var mousePlugin: MousePadPlugin? = null
    private lateinit var deviceId: String
    private lateinit var gestureDetector: GestureDetector

    private var isFullscreen = false
    private var isButtonsVisible = true
    private var isKeyboardVisible = false

    private var streamToggleMenuItem: MenuItem? = null
    private var isMjpegRunning = false
    private var videoRecorder: ScreenVideoRecorder? = null
    private var recordMenuItem: MenuItem? = null

    private var latestFrameBitmap: Bitmap? = null
    private var recordStartTime = 0L
    private var recordTimerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val recordTimerRunnable = object : Runnable {
        override fun run() {
            if (videoRecorder?.isRecording == true) {
                if (videoRecorder?.isPaused == true) {
                    binding.textRecordTimer.text = "❚❚ PAUSED"
                    binding.textRecordTimer.setTextColor(Color.parseColor("#FFC107"))
                    binding.textRecordTimerLand.text = "❚❚\nPAUSED"
                    binding.textRecordTimerLand.setTextColor(Color.parseColor("#FFC107"))
                } else {
                    val sec = (System.currentTimeMillis() - recordStartTime) / 1000
                    val m = sec / 60
                    val s = sec % 60
                    val timeStr = String.format(Locale.US, "● REC %02d:%02d", m, s)
                    binding.textRecordTimer.text = timeStr
                    binding.textRecordTimer.setTextColor(Color.parseColor("#FF5252"))
                    binding.textRecordTimerLand.text = String.format(Locale.US, "● REC\n%02d:%02d", m, s)
                    binding.textRecordTimerLand.setTextColor(Color.parseColor("#FF5252"))
                }
                recordTimerHandler.postDelayed(this, 1000)
            }
        }
    }

    private val isRenderingFrame = java.util.concurrent.atomic.AtomicBoolean(false)
    private var cachedDisableInput = false
    private var cachedDisplayDetails = false
    private var mjpegThread: Thread? = null
    private var selectedFps: String = "auto"

    // Live Speaker Audio (PC Audio -> Phone via AudioTrack PCM)
    private var isSpeakerActive = false
    private var speakerThread: Thread? = null
    private var audioTrack: AudioTrack? = null

    // Live Laptop Mic Audio (Laptop Physical Mic -> Phone via AudioTrack PCM)
    private var isLaptopMicActive = false
    private var laptopMicThread: Thread? = null
    private var audioTrackLaptopMic: AudioTrack? = null

    // Live Microphone Stream (Phone Mic -> PC with Noise Suppression & Gain Boost)
    private var isMicActive = false
    private var micThread: Thread? = null
    private var networkMicThread: Thread? = null
    private val networkMicQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>(20)
    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private var aec: AcousticEchoCanceler? = null

    private var lastTouchX = 0f
    private var lastTouchY = 0f

    // Live FPS Stats tracking
    private var frameCounter = 0
    private var lastFpsTimestamp = System.currentTimeMillis()
    private var liveFps = 0.0

    private val fullscreenBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (isFullscreen) {
                disableFullscreen()
            }
        }
    }

    private val requestMicPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startMicStreaming()
        } else {
            Toast.makeText(this, "Microphone permission is required to stream audio to PC", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        deviceId = intent.getStringExtra("deviceId") ?: ""
        if (deviceId.isEmpty()) {
            finish()
            return
        }

        plugin = KdeConnect.getInstance().getDevicePlugin(deviceId, ScreenMirrorPlugin::class.java)
        mousePlugin = KdeConnect.getInstance().getDevicePlugin(deviceId, MousePadPlugin::class.java)

        setSupportActionBar(binding.toolbarLayout.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = "Stream PC"

        gestureDetector = GestureDetector(this, this)
        gestureDetector.setOnDoubleTapListener(this)

        onBackPressedDispatcher.addCallback(this, fullscreenBackCallback)

        setupUI()
        setupListeners()
    }

    private fun setupUI() {
        binding.bottomControlsContainer.visibility = View.VISIBLE
        binding.keyListener.setDeviceId(deviceId)
        applyAllSettings()
        updateRecordToolbarUI()
        updateOrientationUI(resources.configuration)
    }

    private fun setupListeners() {
        binding.screenImageView.setOnTouchListener { _, event ->
            handleScreenTouch(event)
            true
        }

        // Mouse click listeners (Portrait)
        binding.mouseClickLeft.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    mousePlugin?.sendSingleHold() ?: plugin?.sendSingleHold()
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    mousePlugin?.sendSingleRelease() ?: plugin?.sendSingleRelease()
                    true
                }
                else -> false
            }
        }

        binding.mouseClickMiddle.setOnClickListener {
            mousePlugin?.sendMiddleClick() ?: plugin?.sendMiddleClick()
        }

        binding.mouseClickRight.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    mousePlugin?.sendRightClick() ?: plugin?.sendRightClick()
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    true
                }
                else -> false
            }
        }

        // Mouse click listeners (Landscape Sidebar)
        binding.mouseClickLeftLand.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    mousePlugin?.sendSingleHold() ?: plugin?.sendSingleHold()
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    mousePlugin?.sendSingleRelease() ?: plugin?.sendSingleRelease()
                    true
                }
                else -> false
            }
        }

        binding.mouseClickMiddleLand.setOnClickListener {
            mousePlugin?.sendMiddleClick() ?: plugin?.sendMiddleClick()
        }

        binding.mouseClickRightLand.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    mousePlugin?.sendRightClick() ?: plugin?.sendRightClick()
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    true
                }
                else -> false
            }
        }

        binding.btnSendKeystrokes.visibility = View.GONE

        var isUpdatingKeystrokes = false
        binding.editLiveKeystrokes.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (isUpdatingKeystrokes) return
                val currentStr = s?.toString() ?: ""
                if (count > 0 && count >= before) {
                    val added = currentStr.substring(start, start + count)
                    for (ch in added) {
                        if (ch == '\n' || ch == '\r') {
                            if (mousePlugin != null) mousePlugin?.sendSpecialKey(KeyEvent.KEYCODE_ENTER) else plugin?.sendSpecialKey(KeyEvent.KEYCODE_ENTER)
                        } else {
                            if (mousePlugin != null) mousePlugin?.sendText(ch.toString()) else plugin?.sendText(ch.toString())
                        }
                    }
                } else if (before > count) {
                    val deletes = before - count
                    for (i in 0 until deletes) {
                        if (mousePlugin != null) mousePlugin?.sendSpecialKey(KeyEvent.KEYCODE_DEL) else plugin?.sendSpecialKey(KeyEvent.KEYCODE_DEL)
                    }
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        binding.btnKeyboardLand.setOnClickListener {
            toggleKeyboard()
        }

        binding.editLiveKeystrokes.setOnEditorActionListener { _, actionId, event ->
            val isEnterKey = (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            val isImeAction = actionId in listOf(
                EditorInfo.IME_ACTION_SEND,
                EditorInfo.IME_ACTION_DONE,
                EditorInfo.IME_ACTION_GO,
                EditorInfo.IME_ACTION_NEXT,
                EditorInfo.IME_ACTION_UNSPECIFIED
            )

            if (isEnterKey || isImeAction) {
                if (mousePlugin != null) mousePlugin?.sendSpecialKey(KeyEvent.KEYCODE_ENTER) else plugin?.sendSpecialKey(KeyEvent.KEYCODE_ENTER)
                isUpdatingKeystrokes = true
                binding.editLiveKeystrokes.setText("")
                isUpdatingKeystrokes = false
                true
            } else {
                false
            }
        }

        binding.editLiveKeystrokes.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DEL && binding.editLiveKeystrokes.text.isNullOrEmpty()) {
                    if (mousePlugin != null) mousePlugin?.sendSpecialKey(KeyEvent.KEYCODE_DEL) else plugin?.sendSpecialKey(KeyEvent.KEYCODE_DEL)
                    return@setOnKeyListener true
                } else if (keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (mousePlugin != null) mousePlugin?.sendSpecialKey(KeyEvent.KEYCODE_ENTER) else plugin?.sendSpecialKey(KeyEvent.KEYCODE_ENTER)
                    isUpdatingKeystrokes = true
                    binding.editLiveKeystrokes.setText("")
                    isUpdatingKeystrokes = false
                    return@setOnKeyListener true
                }
            }
            false
        }

        // Screenshot buttons (Portrait & Landscape)
        binding.btnScreenshot.setOnClickListener {
            takeScreenshot()
        }

        binding.btnScreenshotLand.setOnClickListener {
            takeScreenshot()
        }

        // Recording Toolbar Button Listeners (Portrait & Landscape)
        fun onRecordStartStopClicked() {
            if (videoRecorder?.isRecording == true) {
                stopVideoRecording()
            } else {
                startVideoRecording()
            }
        }

        binding.btnRecordStartStop.setOnClickListener { onRecordStartStopClicked() }
        binding.btnRecordStartStopLand.setOnClickListener { onRecordStartStopClicked() }

        binding.btnRecordPauseResume.setOnClickListener { togglePauseResumeRecording() }
        binding.btnRecordPauseResumeLand.setOnClickListener { togglePauseResumeRecording() }

        binding.btnRecordDiscard.setOnClickListener { discardVideoRecording() }
        binding.btnRecordDiscardLand.setOnClickListener { discardVideoRecording() }

        binding.btnBigPlay.setOnClickListener {
            if (isMjpegRunning) {
                stopMjpegClient()
            } else {
                requestStreamStart()
            }
        }

        // Speaker Toggle Button (Portrait & Landscape)
        binding.btnStreamSpeaker.setOnClickListener { toggleSpeakerAudio() }
        binding.btnStreamSpeakerLand.setOnClickListener { toggleSpeakerAudio() }

                
        // Mic Toggle Button (Portrait & Landscape)
        binding.btnStreamMic.setOnClickListener { toggleMicStreaming() }
        binding.btnStreamMicLand.setOnClickListener { toggleMicStreaming() }

        // Fullscreen Toggle in Landscape
        binding.btnFullscreenLand.setOnClickListener { toggleFullscreen() }

        binding.statusOverlay.setOnClickListener {
            requestStreamStart()
        }

        binding.btnReconnect.setOnClickListener {
            requestStreamStart()
        }

        binding.fabExitFullscreen.setOnClickListener {
            disableFullscreen()
        }

        updateSpeakerButtonState()
        updateLaptopMicButtonState()
        updateMicButtonState()
        updateStreamToggleMenu()
    }

    private fun updateSpeakerButtonState() {
        runOnUiThread {
            val activeBg = ColorStateList.valueOf(Color.parseColor("#4F378B"))
            val activeFg = ColorStateList.valueOf(Color.parseColor("#D0BCFF"))
            val activeStroke = ColorStateList.valueOf(Color.parseColor("#D0BCFF"))
            val inactiveBg = ColorStateList.valueOf(Color.TRANSPARENT)
            val defaultStroke = binding.btnScreenshot.strokeColor ?: ColorStateList.valueOf(Color.parseColor("#49454F"))
            val whiteColor = ColorStateList.valueOf(Color.WHITE)

            if (isSpeakerActive) {
                binding.btnStreamSpeaker.backgroundTintList = activeBg
                binding.btnStreamSpeaker.strokeColor = activeStroke
                binding.btnStreamSpeaker.iconTint = activeFg
                binding.btnStreamSpeaker.setTextColor(activeFg)

                binding.btnStreamSpeakerLand.backgroundTintList = activeBg
                binding.btnStreamSpeakerLand.strokeColor = activeStroke
                binding.btnStreamSpeakerLand.iconTint = activeFg
            } else {
                binding.btnStreamSpeaker.backgroundTintList = inactiveBg
                binding.btnStreamSpeaker.strokeColor = defaultStroke
                binding.btnStreamSpeaker.iconTint = whiteColor
                binding.btnStreamSpeaker.setTextColor(whiteColor)

                binding.btnStreamSpeakerLand.backgroundTintList = inactiveBg
                binding.btnStreamSpeakerLand.strokeColor = defaultStroke
                binding.btnStreamSpeakerLand.iconTint = whiteColor
            }
        }
    }

    private fun updateMicButtonState() {
        runOnUiThread {
            val activeBg = ColorStateList.valueOf(Color.parseColor("#4F378B"))
            val activeFg = ColorStateList.valueOf(Color.parseColor("#D0BCFF"))
            val activeStroke = ColorStateList.valueOf(Color.parseColor("#D0BCFF"))
            val inactiveBg = ColorStateList.valueOf(Color.TRANSPARENT)
            val defaultStroke = binding.btnScreenshot.strokeColor ?: ColorStateList.valueOf(Color.parseColor("#49454F"))
            val whiteColor = ColorStateList.valueOf(Color.WHITE)

            if (isMicActive) {
                binding.btnStreamMic.backgroundTintList = activeBg
                binding.btnStreamMic.strokeColor = activeStroke
                binding.btnStreamMic.iconTint = activeFg
                binding.btnStreamMic.setTextColor(activeFg)

                binding.btnStreamMicLand.backgroundTintList = activeBg
                binding.btnStreamMicLand.strokeColor = activeStroke
                binding.btnStreamMicLand.iconTint = activeFg
            } else {
                binding.btnStreamMic.backgroundTintList = inactiveBg
                binding.btnStreamMic.strokeColor = defaultStroke
                binding.btnStreamMic.iconTint = whiteColor
                binding.btnStreamMic.setTextColor(whiteColor)

                binding.btnStreamMicLand.backgroundTintList = inactiveBg
                binding.btnStreamMicLand.strokeColor = defaultStroke
                binding.btnStreamMicLand.iconTint = whiteColor
            }
        }
    }

    private fun handleScreenTouch(event: MotionEvent): Boolean {
        if (cachedDisableInput) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                Toast.makeText(this, "Input Disabled (View-Only Mode)", Toast.LENGTH_SHORT).show()
            }
            return false
        }
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = x
                lastTouchY = y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (x - lastTouchX) * 1.5f
                val dy = (y - lastTouchY) * 1.5f
                if (kotlin.math.abs(dx) > 0.05f || kotlin.math.abs(dy) > 0.05f) {
                    mousePlugin?.sendMouseDelta(dx, dy) ?: plugin?.sendMouseDelta(dx, dy)
                    lastTouchX = x
                    lastTouchY = y
                }
            }
        }
        return gestureDetector.onTouchEvent(event)
    }

    private fun getTargetHost(): String {
        val device = KdeConnect.getInstance().getDevice(deviceId)
        var host = device?.getRemoteIpAddress() ?: ""
        if (host.isEmpty()) {
            host = SshManager.getSavedCredentials(this, deviceId)?.host ?: ""
        }
        return host
    }

    private fun toggleSpeakerAudio() {
        if (isSpeakerActive) {
            stopSpeakerAudio()
            Toast.makeText(this, "Speaker audio disabled", Toast.LENGTH_SHORT).show()
        } else {
            startSpeakerAudio()
        }
    }

    private fun startSpeakerAudio() {
        val host = getTargetHost()
        if (host.isEmpty()) {
            Toast.makeText(this, "Target host IP not available", Toast.LENGTH_SHORT).show()
            return
        }
        stopSpeakerAudio()
        isSpeakerActive = true
        updateSpeakerButtonState()
        
        Toast.makeText(this, "Speaker enabled (PC Audio -> Phone)", Toast.LENGTH_SHORT).show()

        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBufSize * 4, 32768)

        speakerThread = Thread {
            var connection: HttpURLConnection? = null
            var inStream: InputStream? = null
            try {
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(audioFormat)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack?.play()

                val prefs = PreferenceManager.getDefaultSharedPreferences(this@ScreenMirrorActivity)
            val useLaptopMic = prefs.getBoolean("pref_audio_laptop_mic", false)
            val useInternal = prefs.getBoolean("pref_audio_laptop_internal", true)
            val endpoint = if (useLaptopMic && !useInternal) "/laptop_mic.pcm" else "/audio.pcm"
            val url = URL("http://$host:59001$endpoint")
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 8000
                    useCaches = false
                    doInput = true
                }
                connection.connect()

                if (connection.responseCode == 200) {
                    inStream = BufferedInputStream(connection.inputStream, 16384)
                    val rawBuffer = ByteArray(4096)
                    val frameBuffer = ByteArray(4096 + 4)
                    var carryOver = 0

                    while (isSpeakerActive && !Thread.currentThread().isInterrupted) {
                        val bytesRead = inStream.read(rawBuffer, 0, rawBuffer.size)
                        if (bytesRead <= 0) break

                        System.arraycopy(rawBuffer, 0, frameBuffer, carryOver, bytesRead)
                        val total = carryOver + bytesRead
                        // Stereo 16-bit PCM = 4 bytes per frame. Guarantee exact frame alignment!
                        val alignedBytes = (total / 4) * 4
                        if (alignedBytes > 0) {
                            audioTrack?.write(frameBuffer, 0, alignedBytes, AudioTrack.WRITE_BLOCKING)
                            videoRecorder?.encodeAudio(frameBuffer, alignedBytes, true)
                        }
                        carryOver = total - alignedBytes
                        if (carryOver > 0) {
                            System.arraycopy(frameBuffer, alignedBytes, frameBuffer, 0, carryOver)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ScreenMirror", "Speaker stream error", e)
            } finally {
                try { inStream?.close() } catch (_: Exception) {}
                try { connection?.disconnect() } catch (_: Exception) {}
                try { audioTrack?.stop() } catch (_: Exception) {}
                try { audioTrack?.release() } catch (_: Exception) {}
                audioTrack = null
                runOnUiThread {
                    if (isSpeakerActive) {
                        stopSpeakerAudio()
                    }
                }
            }
        }.apply {
            name = "KdeConnectSpeakerAudioThread"
            start()
        }
    }

    private fun updateLaptopMicButtonState() {}

    private fun toggleLaptopMicAudio() {
        if (isLaptopMicActive) {
            stopLaptopMicAudio()
            Toast.makeText(this, "Laptop mic listening disabled", Toast.LENGTH_SHORT).show()
        } else {
            startLaptopMicAudio()
        }
    }

    private fun startLaptopMicAudio() {
        val host = getTargetHost()
        if (host.isEmpty()) {
            Toast.makeText(this, "Target host IP not available", Toast.LENGTH_SHORT).show()
            return
        }

        isLaptopMicActive = true
        updateLaptopMicButtonState()

        laptopMicThread = Thread {
            var connection: HttpURLConnection? = null
            var inStream: InputStream? = null
            try {
                val sampleRate = 44100
                val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                val bufferSize = (minBuf * 2).coerceAtLeast(8192)

                audioTrackLaptopMic = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(audioFormat)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrackLaptopMic?.play()

                val url = URL("http://$host:59001/laptop_mic.pcm")
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 8000
                    useCaches = false
                    doInput = true
                }
                connection.connect()

                if (connection.responseCode == 200) {
                    inStream = BufferedInputStream(connection.inputStream, 16384)
                    val rawBuffer = ByteArray(4096)
                    val frameBuffer = ByteArray(4096 + 4)
                    var carryOver = 0

                    while (isLaptopMicActive && !Thread.currentThread().isInterrupted) {
                        val bytesRead = inStream.read(rawBuffer, 0, rawBuffer.size)
                        if (bytesRead <= 0) break

                        System.arraycopy(rawBuffer, 0, frameBuffer, carryOver, bytesRead)
                        val total = carryOver + bytesRead
                        val alignedBytes = (total / 4) * 4
                        if (alignedBytes > 0) {
                            audioTrackLaptopMic?.write(frameBuffer, 0, alignedBytes, AudioTrack.WRITE_BLOCKING)
                        }
                        carryOver = total - alignedBytes
                        if (carryOver > 0) {
                            System.arraycopy(frameBuffer, alignedBytes, frameBuffer, 0, carryOver)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ScreenMirror", "Laptop mic stream error", e)
            } finally {
                try { inStream?.close() } catch (_: Exception) {}
                try { connection?.disconnect() } catch (_: Exception) {}
                try { audioTrackLaptopMic?.stop() } catch (_: Exception) {}
                try { audioTrackLaptopMic?.release() } catch (_: Exception) {}
                audioTrackLaptopMic = null
                runOnUiThread {
                    if (isLaptopMicActive) {
                        stopLaptopMicAudio()
                    }
                }
            }
        }.apply {
            name = "KdeConnectLaptopMicAudioThread"
            start()
        }
    }

    private fun stopLaptopMicAudio() {
        isLaptopMicActive = false
        updateLaptopMicButtonState()
        laptopMicThread?.interrupt()
        laptopMicThread = null
    }

    private fun stopSpeakerAudio() {
        isSpeakerActive = false
        updateSpeakerButtonState()
        speakerThread?.interrupt()
        speakerThread = null
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
        
    }

    private fun toggleMicStreaming() {
        if (isMicActive) {
            stopMicStreaming()
            Toast.makeText(this, "Microphone streaming stopped", Toast.LENGTH_SHORT).show()
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startMicStreaming()
            } else {
                requestMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

        private fun startMicStreaming() {
        val host = getTargetHost()
        stopMicStreaming()
        isMicActive = true
        updateMicButtonState()
        
        Toast.makeText(this, "Microphone active (Recording & Streaming)", Toast.LENGTH_SHORT).show()

        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBufSize * 2, 8192)

        micThread = Thread {
            try {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    runOnUiThread { stopMicStreaming() }
                    return@Thread
                }

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        bufferSize
                    )
                }
                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.DEFAULT,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        bufferSize
                    )
                }

                val sessionId = audioRecord?.audioSessionId ?: 0
                if (sessionId != 0) {
                    try {
                        if (NoiseSuppressor.isAvailable()) {
                            noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                        }
                    } catch (_: Exception) {}
                }

                audioRecord?.startRecording()

                // Start decoupled background network streamer to PC if host is available
                if (host.isNotEmpty()) {
                    startNetworkMicSender(host)
                }

                val buffer = ByteArray(2048)

                while (isMicActive && !Thread.currentThread().isInterrupted) {
                    val readBytes = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (readBytes > 0) {
                        // 1. Local Recording: Immediately pass audio chunk to recorder (zero network dependency)
                        videoRecorder?.encodePhoneMicAudio(buffer, readBytes)
                        // 2. Network Stream: Offer to non-blocking network queue
                        networkMicQueue.offer(buffer.copyOf(readBytes))
                    }
                }
            } catch (e: Exception) {
                Log.e("ScreenMirror", "Local mic recording error", e)
            } finally {
                try { noiseSuppressor?.release() } catch (_: Exception) {}
                noiseSuppressor = null
                try { audioRecord?.stop() } catch (_: Exception) {}
                try { audioRecord?.release() } catch (_: Exception) {}
                audioRecord = null
                runOnUiThread {
                    if (isMicActive) {
                        stopMicStreaming()
                    }
                }
            }
        }.apply {
            name = "KdeConnectMicThread"
            start()
        }
    }

    private fun startNetworkMicSender(host: String) {
        networkMicQueue.clear()
        networkMicThread = Thread {
            var tcpSocket: Socket? = null
            var outputStream: OutputStream? = null
            var httpConn: HttpURLConnection? = null
            try {
                try {
                    val sock = Socket()
                    sock.tcpNoDelay = true
                    sock.connect(InetSocketAddress(host, 59002), 2000)
                    tcpSocket = sock
                    outputStream = sock.getOutputStream()
                } catch (e: Exception) {
                    Log.d("ScreenMirror", "TCP 59002 fallback to HTTP POST /mic_stream: ${e.message}")
                    val url = URL("http://$host:59001/mic_stream")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        doOutput = true
                        setChunkedStreamingMode(2048)
                        connectTimeout = 3000
                        readTimeout = 6000
                    }
                    conn.connect()
                    httpConn = conn
                    outputStream = conn.outputStream
                }

                while (isMicActive && !Thread.currentThread().isInterrupted) {
                    val chunk = networkMicQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    try {
                        outputStream?.write(chunk)
                        outputStream?.flush()
                    } catch (e: Exception) {
                        Log.w("ScreenMirror", "Network mic stream write failed: ${e.message}")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.w("ScreenMirror", "Network mic sender connect failed: ${e.message}")
            } finally {
                try { outputStream?.close() } catch (_: Exception) {}
                try { tcpSocket?.close() } catch (_: Exception) {}
                try { httpConn?.disconnect() } catch (_: Exception) {}
            }
        }.apply {
            name = "KdeConnectNetworkMicSender"
            isDaemon = true
            start()
        }
    }

    private fun stopMicStreaming() {
        isMicActive = false
        updateMicButtonState()
        micThread?.interrupt()
        micThread = null
        networkMicThread?.interrupt()
        networkMicThread = null
        networkMicQueue.clear()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        noiseSuppressor = null
    }

    private fun requestStreamStart() {
        val host = getTargetHost()
        binding.statusOverlay.visibility = View.VISIBLE
        binding.textStatus.text = if (host.isNotEmpty()) {
            "Connecting to stream at http://$host:59001/stream.mjpeg?fps=$selectedFps...\n(Tap to refresh / change IP)"
        } else {
            "Host IP address not found.\nTap here to enter IP manually."
        }

        if (host.isNotEmpty()) {
            val spawnCmd = "pgrep -f screen_streamer.py >/dev/null || (for p in ~/tools/screen_streamer.py ~/.config/kdeconnect/screen_streamer.py /tmp/screen_streamer.py \$(find ~ -maxdepth 5 -name screen_streamer.py 2>/dev/null | head -n1); do [ -f \"\$p\" ] && nohup python3 \"\$p\" >/dev/null 2>&1 & break; done)"
            if (SshManager.isConnected(deviceId)) {
                SshManager.executeCommand(deviceId, spawnCmd, false, { _, _ -> })
            } else {
                val savedCreds = SshManager.getSavedCredentials(this@ScreenMirrorActivity, deviceId)
                if (savedCreds != null) {
                    SshManager.connect(deviceId, savedCreds) { success, _ ->
                        if (success) {
                            SshManager.executeCommand(deviceId, spawnCmd, false, { _, _ -> })
                        }
                    }
                }
            }
            startMjpegClient(host)
        } else {
            promptForHostIp()
        }
    }

    private fun promptForHostIp() {
        val liveIp = try {
            org.kde.kdeconnect.KdeConnect.getInstance().getDevice(deviceId)?.getRemoteIpAddress()
        } catch (_: Exception) { null }
        val saved = SshManager.getSavedCredentials(this@ScreenMirrorActivity, deviceId)
        val defaultIp = (if (!liveIp.isNullOrEmpty()) liveIp else saved?.host) ?: ""

        val input = EditText(this).apply {
            hint = "e.g. 192.168.1.100"
            setText(defaultIp)
        }
        AlertDialog.Builder(this)
            .setTitle("Enter Host PC IP Address")
            .setMessage("The streamer is running on your PC. Enter its local IP:")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) {
                    val creds = saved?.copy(host = ip) ?: SshManager.SshCredentials(host = ip, user = "", pass = "", port = 22)
                    SshManager.saveCredentials(this@ScreenMirrorActivity, deviceId, creds)
                    requestStreamStart()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startMjpegClient(host: String) {
        stopMjpegClient()
        isMjpegRunning = true
        updateStreamToggleMenu()

        val worker = Thread {
            var connection: HttpURLConnection? = null
            var inStream: InputStream? = null

            try {
                val url = URL("http://$host:59001/stream.mjpeg?fps=$selectedFps")
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 8000
                    useCaches = false
                    doInput = true
                }
                connection.connect()

                if (connection.responseCode == 200) {
                    inStream = BufferedInputStream(connection.inputStream, 32768)
                    val decodeOptions = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inMutable = true
                    }
                    val headerBuffer = ByteArrayOutputStream()
                    var contentLength = -1
                    var prev3 = -1
                    var prev2 = -1
                    var prev = -1

                    runOnUiThread {
                        binding.statusOverlay.visibility = View.GONE
                        binding.layoutPlayOverlay.visibility = View.GONE
                    }

                    while (isMjpegRunning && !Thread.currentThread().isInterrupted) {
                        val b = inStream.read()
                        if (b == -1) break

                        headerBuffer.write(b)

                        // Detect end of multipart header: \r\n\r\n or \n\n
                        val isHeaderEnd = (prev3 == 13 && prev2 == 10 && prev == 13 && b == 10) || (prev == 10 && b == 10)
                        if (isHeaderEnd) {
                            val headerStr = headerBuffer.toString("UTF-8")
                            headerBuffer.reset()

                            for (line in headerStr.lines()) {
                                val lower = line.lowercase(Locale.ROOT)
                                if (lower.startsWith("content-length:")) {
                                    contentLength = lower.substringAfter(":").trim().toIntOrNull() ?: -1
                                }
                            }

                            if (contentLength > 0) {
                                val frameBytes = ByteArray(contentLength)
                                var bytesRead = 0
                                while (bytesRead < contentLength && isMjpegRunning) {
                                    val read = inStream.read(frameBytes, bytesRead, contentLength - bytesRead)
                                    if (read == -1) break
                                    bytesRead += read
                                }

                                if (bytesRead == contentLength) {
                                    val bitmap = BitmapFactory.decodeByteArray(frameBytes, 0, frameBytes.size, decodeOptions)
                                    if (bitmap != null) {
                                        frameCounter++
                                        val now = System.currentTimeMillis()
                                        val elapsed = now - lastFpsTimestamp
                                        var updateStats = false
                                        var fpsText = ""
                                        if (elapsed >= 500) {
                                            liveFps = (frameCounter * 1000.0) / elapsed
                                            frameCounter = 0
                                            lastFpsTimestamp = now
                                            val kbSize = frameBytes.size / 1024
                                            fpsText = if (cachedDisplayDetails) {
                                                val res = "${bitmap.width}x${bitmap.height}"
                                                String.format(Locale.US, "● %.1f FPS • %s • %d KB", liveFps, res, kbSize)
                                            } else {
                                                String.format(Locale.US, "● %.1f FPS • %d KB", liveFps, kbSize)
                                            }
                                            updateStats = true
                                        }

                                        if (isRenderingFrame.compareAndSet(false, true)) {
                                            runOnUiThread {
                                                try {
                                                    binding.screenImageView.setImageBitmap(bitmap)
                                                    latestFrameBitmap = bitmap
                                                    videoRecorder?.encodeBitmap(bitmap)
                                                    if (updateStats && fpsText.isNotEmpty()) {
                                                        binding.textStreamStats.text = fpsText
                                                        binding.textStreamStatsLand.text = String.format(Locale.US, "%.0f FPS", liveFps)
                                                    }
                                                    if (binding.statusOverlay.visibility != View.GONE) {
                                                        binding.statusOverlay.visibility = View.GONE
                                                    }
                                                    if (binding.layoutPlayOverlay.visibility != View.GONE) {
                                                        binding.layoutPlayOverlay.visibility = View.GONE
                                                    }
                                                } finally {
                                                    isRenderingFrame.set(false)
                                                }
                                            }
                                        }
                                    }
                                }
                                contentLength = -1
                                prev3 = -1
                                prev2 = -1
                                prev = -1
                                continue
                            }
                        }

                        prev3 = prev2
                        prev2 = prev
                        prev = b
                    }
                } else {
                    runOnUiThread {
                        if (isMjpegRunning) {
                            binding.statusOverlay.visibility = View.VISIBLE
                            binding.textStatus.text = "HTTP ${connection.responseCode} from $host:59001\nTap to retry"
                        }
                    }
                }
            } catch (e: Exception) {
                if (isMjpegRunning && !Thread.currentThread().isInterrupted && e !is java.io.InterruptedIOException) {
                    Log.d("ScreenMirrorActivity", "MJPEG stream error: ${e.message}")
                    runOnUiThread {
                        if (isMjpegRunning) {
                            binding.statusOverlay.visibility = View.VISIBLE
                            binding.textStatus.text = "Cannot connect to http://$host:59001\n(${e.localizedMessage})\n\nMake sure python3 tools/screen_streamer.py is running!\nTap to retry / change IP"
                        }
                    }
                }
            } finally {
                try { inStream?.close() } catch (_: Exception) {}
                try { connection?.disconnect() } catch (_: Exception) {}
            }
        }
        mjpegThread = worker
        worker.isDaemon = true
        worker.start()
    }

    private fun stopMjpegClient() {
        isMjpegRunning = false
        mjpegThread?.interrupt()
        mjpegThread = null
        updateStreamToggleMenu()
    }

    private fun updateStreamToggleMenu() {
        runOnUiThread {
            streamToggleMenuItem?.let { item ->
                if (isMjpegRunning) {
                    item.title = "Stop Stream"
                    item.setIcon(R.drawable.ic_stop)
                } else {
                    item.title = "Start Stream"
                    item.setIcon(R.drawable.ic_play_white)
                }
            }
            if (isMjpegRunning || plugin?.isStreaming == true) {
                binding.layoutPlayOverlay.visibility = View.GONE
            } else {
                binding.layoutPlayOverlay.visibility = View.GONE
            }
        }
    }

    override fun onStart() {
        super.onStart()
        plugin?.registerFrameListener(this)
        binding.layoutPlayOverlay.visibility = View.GONE
        if (!isMjpegRunning && plugin?.isStreaming != true) {
            requestStreamStart()
        }
        updateStreamToggleMenu()
    }

    override fun onResume() {
        super.onResume()
        applyAllSettings()
        binding.layoutPlayOverlay.visibility = View.GONE
        ClipboardListener.instance(this).registerObserver(this)
        ClipboardListener.instance(this).refreshFromSystem()
        if (!isMjpegRunning && plugin?.isStreaming != true) {
            requestStreamStart()
        }
        updateStreamToggleMenu()
    }

    override fun clipboardChanged(content: String, contentType: ClipboardListener.ClipboardContentType) {
        if (!ClipboardListener.isValidClipboardText(content)) return
        val host = getTargetHost()
        if (!host.isNullOrEmpty()) {
            ThreadHelper.execute {
                try {
                    val url = URL("http://" + host + ":59001/clipboard")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 1500
                        readTimeout = 1500
                        requestMethod = "POST"
                        doOutput = true
                    }
                    OutputStreamWriter(conn.outputStream).use { it.write(content.trim()) }
                    conn.responseCode
                } catch (_: Exception) {}
            }
        }
    }

    override fun onStop() {
        super.onStop()
        plugin?.unregisterFrameListener(this)
        plugin?.stopStream()
        stopMjpegClient()
        stopSpeakerAudio()
        stopMicStreaming()
    }

    override fun onDestroy() {
        val host = getTargetHost()
        if (!host.isNullOrEmpty()) {
            org.kde.kdeconnect.helpers.ThreadHelper.execute {
                try {
                    val url = java.net.URL("http://$host:59001/terminate_session")
                    val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 1000
                        readTimeout = 1000
                    }
                    conn.responseCode
                } catch (_: Exception) {}
            }
        }
        try {
            ClipboardListener.instance(this).removeObserver(this)
        } catch (_: Exception) {}
        super.onDestroy()
        videoRecorder?.stop()
        videoRecorder = null
        stopSpeakerAudio()
        stopMicStreaming()
    }

    override fun onFrameReceived(bitmap: Bitmap?, width: Int, height: Int, timestamp: Long) {
        if (bitmap == null) return
        binding.screenImageView.setImageBitmap(bitmap)
        latestFrameBitmap = bitmap
        videoRecorder?.encodeBitmap(bitmap)
        if (binding.statusOverlay.visibility != View.GONE) {
            binding.statusOverlay.visibility = View.GONE
        }
        if (binding.layoutPlayOverlay.visibility != View.GONE) {
            binding.layoutPlayOverlay.visibility = View.GONE
        }
    }

    override fun onStreamStateChanged(streaming: Boolean, message: String?) {
        if (!streaming && !isMjpegRunning) {
            binding.statusOverlay.visibility = View.VISIBLE
            binding.textStatus.text = message ?: getString(R.string.screenmirror_waiting_stream)
        } else if (streaming) {
            binding.statusOverlay.visibility = View.GONE
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_screen_mirror, menu)
        streamToggleMenuItem = menu.findItem(R.id.menu_stream_toggle)
        updateStreamToggleMenu()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_stream_toggle -> {
                if (isMjpegRunning) {
                    stopMjpegClient()
                    plugin?.stopStream()
                    binding.statusOverlay.visibility = View.VISIBLE
                    binding.textStatus.text = "Screen streaming stopped.\n\nTap 'Start Stream' (top bar) or tap reconnect below to resume."
                    Toast.makeText(this, "Stream stopped", Toast.LENGTH_SHORT).show()
                } else {
                    requestStreamStart()
                    Toast.makeText(this, "Starting stream...", Toast.LENGTH_SHORT).show()
                }
                true
            }
            R.id.menu_clipboard -> {
                showClipboardDialog()
                true
            }
            R.id.menu_settings -> {
                showSettingsDialog()
                true
            }

            R.id.menu_take_screenshot -> {
                takeScreenshot()
                true
            }
            R.id.menu_refresh_stream -> {
                requestStreamStart()
                true
            }
            R.id.menu_fullscreen -> {
                toggleFullscreen()
                true
            }
            R.id.menu_toggle_buttons -> {
                toggleButtons()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    
    


    
    private fun updateRecordToolbarUI() {
        runOnUiThread {
            val rec = videoRecorder
            val redTint = ColorStateList.valueOf(Color.parseColor("#E53935"))
            val whiteTint = ColorStateList.valueOf(Color.WHITE)
            if (rec?.isRecording == true) {
                binding.textRecordTimer.visibility = View.VISIBLE
                binding.textRecordTimerLand.visibility = View.VISIBLE

                binding.btnRecordStartStop.setIconResource(R.drawable.ic_stop_record_24dp)
                binding.btnRecordStartStop.contentDescription = "Stop Recording"
                binding.btnRecordStartStop.backgroundTintList = redTint
                binding.btnRecordStartStop.iconTint = whiteTint

                binding.btnRecordStartStopLand.setIconResource(R.drawable.ic_stop_record_24dp)
                binding.btnRecordStartStopLand.contentDescription = "Stop Recording"
                binding.btnRecordStartStopLand.backgroundTintList = redTint
                binding.btnRecordStartStopLand.iconTint = whiteTint

                binding.btnRecordPauseResume.isEnabled = true
                binding.btnRecordPauseResume.alpha = 1.0f
                binding.btnRecordPauseResumeLand.isEnabled = true
                binding.btnRecordPauseResumeLand.alpha = 1.0f

                if (rec.isPaused) {
                    binding.btnRecordPauseResume.setIconResource(R.drawable.ic_play_white)
                    binding.btnRecordPauseResume.contentDescription = "Resume Recording"
                    binding.btnRecordPauseResumeLand.setIconResource(R.drawable.ic_play_white)
                    binding.btnRecordPauseResumeLand.contentDescription = "Resume Recording"
                } else {
                    binding.btnRecordPauseResume.setIconResource(R.drawable.ic_pause_white)
                    binding.btnRecordPauseResume.contentDescription = "Pause Recording"
                    binding.btnRecordPauseResumeLand.setIconResource(R.drawable.ic_pause_white)
                    binding.btnRecordPauseResumeLand.contentDescription = "Pause Recording"
                }

                binding.btnRecordDiscard.isEnabled = true
                binding.btnRecordDiscard.alpha = 1.0f
                binding.btnRecordDiscard.contentDescription = "Discard Recording"
                binding.btnRecordDiscardLand.isEnabled = true
                binding.btnRecordDiscardLand.alpha = 1.0f
                binding.btnRecordDiscardLand.contentDescription = "Discard Recording"
            } else {
                binding.textRecordTimer.visibility = View.GONE
                binding.textRecordTimerLand.visibility = View.GONE

                val defaultStroke = binding.btnScreenshot.strokeColor ?: ColorStateList.valueOf(Color.parseColor("#49454F"))
                val inactiveBg = ColorStateList.valueOf(Color.TRANSPARENT)
                val whiteColor = ColorStateList.valueOf(Color.WHITE)

                binding.btnRecordStartStop.setIconResource(R.drawable.ic_record_dot_24dp)
                binding.btnRecordStartStop.contentDescription = "Record"
                binding.btnRecordStartStop.backgroundTintList = inactiveBg
                binding.btnRecordStartStop.strokeColor = defaultStroke
                binding.btnRecordStartStop.iconTint = whiteColor

                binding.btnRecordStartStopLand.setIconResource(R.drawable.ic_record_dot_24dp)
                binding.btnRecordStartStopLand.contentDescription = "Record"
                binding.btnRecordStartStopLand.backgroundTintList = inactiveBg
                binding.btnRecordStartStopLand.strokeColor = defaultStroke
                binding.btnRecordStartStopLand.iconTint = whiteColor

                binding.btnRecordPauseResume.isEnabled = false
                binding.btnRecordPauseResume.alpha = 0.4f
                binding.btnRecordPauseResume.setIconResource(R.drawable.ic_pause_white)
                binding.btnRecordPauseResume.contentDescription = "Pause (Disabled)"
                binding.btnRecordPauseResumeLand.isEnabled = false
                binding.btnRecordPauseResumeLand.alpha = 0.4f
                binding.btnRecordPauseResumeLand.setIconResource(R.drawable.ic_pause_white)
                binding.btnRecordPauseResumeLand.contentDescription = "Pause (Disabled)"

                binding.btnRecordDiscard.isEnabled = false
                binding.btnRecordDiscard.alpha = 0.4f
                binding.btnRecordDiscard.contentDescription = "Discard (Disabled)"
                binding.btnRecordDiscardLand.isEnabled = false
                binding.btnRecordDiscardLand.alpha = 0.4f
                binding.btnRecordDiscardLand.contentDescription = "Discard (Disabled)"
            }
        }
    }

    private fun applyAllSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        cachedDisableInput = prefs.getBoolean("pref_disable_input", false)
        cachedDisplayDetails = prefs.getBoolean("pref_display_details", false)

        // 1. Scaling & Aspect Ratio Mode
        val scaleMode = prefs.getString("pref_scaling", "FIT_CENTER") ?: "FIT_CENTER"
        val aspectMode = prefs.getString("pref_aspect_ratio", "Auto (Match Laptop Screen)") ?: "Auto (Match Laptop Screen)"

        binding.screenImageView.scaleType = when {
            scaleMode == "FIT_XY" -> android.widget.ImageView.ScaleType.FIT_XY
            scaleMode == "CENTER" -> android.widget.ImageView.ScaleType.CENTER
            scaleMode == "CENTER_CROP" || aspectMode.contains("Phone") -> android.widget.ImageView.ScaleType.CENTER_CROP
            else -> android.widget.ImageView.ScaleType.FIT_CENTER
        }
        binding.screenImageView.requestLayout()
        binding.screenImageView.invalidate()

        // 2. Show HUD Info (Combined Live FPS & Details)
        val showDetails = prefs.getBoolean("pref_display_details", true)
        binding.layoutStreamStats.visibility = if (showDetails) View.VISIBLE else View.GONE
        binding.textStreamStatsLand.visibility = if (showDetails) View.VISIBLE else View.GONE

        // 3. Disable Input (View-Only Mode)
        val disableInput = prefs.getBoolean("pref_disable_input", false)
        binding.mouseClickLeft.isEnabled = !disableInput
        binding.mouseClickMiddle.isEnabled = !disableInput
        binding.mouseClickRight.isEnabled = !disableInput
        binding.editLiveKeystrokes.isEnabled = !disableInput
        binding.btnSendKeystrokes.isEnabled = !disableInput

        binding.mouseClickLeftLand.isEnabled = !disableInput
        binding.mouseClickMiddleLand.isEnabled = !disableInput
        binding.mouseClickRightLand.isEnabled = !disableInput
        binding.btnKeyboardLand.isEnabled = !disableInput

        val alpha = if (disableInput) 0.35f else 1.0f
        binding.mouseButtons.alpha = alpha
        binding.layoutKeystrokesContainer.alpha = alpha
    }

    private fun showSettingsDialog() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_screen_mirror_settings, null)

        val spinnerScaling = dialogView.findViewById<Spinner>(R.id.spinner_settings_scaling)
        val spinnerAspect = dialogView.findViewById<Spinner>(R.id.spinner_settings_aspect_ratio)
        val spinnerResolution = dialogView.findViewById<Spinner>(R.id.spinner_settings_resolution)
        val spinnerRecFps = dialogView.findViewById<Spinner>(R.id.spinner_settings_rec_fps)
        val spinnerAudioSync = dialogView.findViewById<Spinner>(R.id.spinner_settings_audio_sync)
        val checkDisplayDetails = dialogView.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.check_display_details)
        val checkAudioLaptopInternal = dialogView.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.check_audio_laptop_internal)
        val checkAudioLaptopMic = dialogView.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.check_audio_laptop_mic)
        val checkAudioPhoneMic = dialogView.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.check_audio_phone_mic)
        val checkDisableInput = dialogView.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.check_disable_input)
        val btnReset = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_settings_reset)
        val btnSave = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_settings_save)
        val textStorage = dialogView.findViewById<android.widget.TextView>(R.id.text_storage_location)
        val btnChangeStorage = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_change_storage)
        val btnResetStorage = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_reset_storage)

        currentSettingsStorageTextView = textStorage

        // 1. Scaling options
        val scalingOptions = arrayOf(
            "Fit Screen (Preserve Aspect)",
            "Stretch to Fill (No Bars)",
            "Crop to Fill (Zoom Center)",
            "Original 1:1 (Center)"
        )
        val scalingAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, scalingOptions)
        spinnerScaling.adapter = scalingAdapter
        val currentScaling = prefs.getString("pref_scaling", "FIT_CENTER")
        spinnerScaling.setSelection(when (currentScaling) {
            "FIT_XY" -> 1
            "CENTER_CROP" -> 2
            "CENTER" -> 3
            else -> 0
        })

        // 2. Aspect ratio options
        val aspectOptions = arrayOf(
            "Auto (Match Laptop Native)",
            "16:9 (Landscape Widescreen - YouTube/TV)",
            "9:16 (Portrait / Reels / Shorts / TikTok)",
            "16:10 (Productivity / Laptop)",
            "10:16 (Portrait Laptop)",
            "4:3 (Classic / iPad)",
            "3:4 (Portrait Tablet)",
            "1:1 (Square / Feed)",
            "21:9 (Ultrawide Cinema)",
            "9:21 (Ultrawide Tall Portrait)",
            "Match Phone Display (Auto-Orient)"
        )
        val aspectAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, aspectOptions)
        spinnerAspect.adapter = aspectAdapter
        val currentAspect = prefs.getString("pref_aspect_ratio", "Auto (Match Laptop Native)") ?: "Auto (Match Laptop Native)"
        val aspectIndex = aspectOptions.indexOfFirst { it.startsWith(currentAspect.take(4)) }.let { if (it >= 0) it else 0 }
        spinnerAspect.setSelection(aspectIndex)

        // 3. Resolution options
        val resOptions = arrayOf(
            "Native / Original (100% Stream)",
            "1440p 2K (Ultra HD)",
            "1080p FHD (High Quality)",
            "720p HD (Balanced)",
            "480p SD (Data Saver)"
        )
        val resAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resOptions)
        spinnerResolution.adapter = resAdapter
        val currentRes = prefs.getString("pref_resolution", "Native / Original (100% Stream)") ?: "Native / Original (100% Stream)"
        val resIndex = resOptions.indexOfFirst { it.startsWith(currentRes.take(4)) }.let { if (it >= 0) it else 0 }
        spinnerResolution.setSelection(resIndex)

        // 4. Rec FPS options
        val fpsOptions = arrayOf(
            "Auto (Match Stream)",
            "144 FPS",
            "120 FPS",
            "90 FPS",
            "75 FPS",
            "60 FPS",
            "45 FPS",
            "30 FPS",
            "24 FPS (Cinematic)",
            "15 FPS (Saver)"
        )
        val fpsAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fpsOptions)
        spinnerRecFps.adapter = fpsAdapter
        val currentRecFps = prefs.getString("pref_rec_fps", "60 FPS") ?: "60 FPS"
        val fpsIndex = fpsOptions.indexOfFirst { it.startsWith(currentRecFps.take(3)) }.let { if (it >= 0) it else 5 }
        spinnerRecFps.setSelection(fpsIndex)

        // 5. Checkboxes
        checkDisplayDetails.isChecked = prefs.getBoolean("pref_display_details", true)
        checkAudioLaptopInternal.isChecked = prefs.getBoolean("pref_audio_laptop_internal", true)
        checkAudioLaptopMic.isChecked = prefs.getBoolean("pref_audio_laptop_mic", false)
        checkAudioPhoneMic?.isChecked = prefs.getBoolean("pref_audio_phone_mic", true)
        checkDisableInput.isChecked = prefs.getBoolean("pref_disable_input", false)

        textStorage.text = prefs.getString("pref_storage_display_name", "Movies / KDEConnect") ?: "Movies / KDEConnect"

        btnChangeStorage.setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        btnResetStorage.setOnClickListener {
            prefs.edit().remove("pref_storage_uri").putString("pref_storage_display_name", "Movies / KDEConnect").apply()
            textStorage.text = "Movies / KDEConnect"
            Toast.makeText(this, "Reset to default: Movies/KDEConnect", Toast.LENGTH_SHORT).show()
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnReset.setOnClickListener {
            spinnerScaling.setSelection(0)
            spinnerAspect.setSelection(0)
            spinnerResolution.setSelection(0)
            spinnerRecFps.setSelection(5) // 60 FPS
            checkDisplayDetails.isChecked = true
            checkAudioLaptopInternal.isChecked = true
            checkAudioLaptopMic.isChecked = false
            checkAudioPhoneMic?.isChecked = true
            checkDisableInput.isChecked = false
            prefs.edit().remove("pref_storage_uri").putString("pref_storage_display_name", "Movies / KDEConnect").apply()
            textStorage.text = "Movies / KDEConnect"
            Toast.makeText(this, "Settings reset to defaults", Toast.LENGTH_SHORT).show()
        }

        fun saveSettingsValues() {
            val selScale = when (spinnerScaling.selectedItemPosition) {
                1 -> "FIT_XY"
                2 -> "CENTER_CROP"
                3 -> "CENTER"
                else -> "FIT_CENTER"
            }
            val selAspect = spinnerAspect.selectedItem?.toString() ?: "Auto (Match Laptop Native)"
            val selRes = spinnerResolution.selectedItem?.toString() ?: "Native / Original (100% Stream)"
            val selFps = spinnerRecFps.selectedItem?.toString() ?: "60 FPS"

            prefs.edit()
                .putString("pref_scaling", selScale)
                .putString("pref_aspect_ratio", selAspect)
                .putString("pref_resolution", selRes)
                .putString("pref_rec_fps", selFps)
                .putBoolean("pref_display_details", checkDisplayDetails.isChecked)
                .putBoolean("pref_audio_laptop_internal", checkAudioLaptopInternal.isChecked)
                .putBoolean("pref_audio_laptop_mic", checkAudioLaptopMic.isChecked)
                .putBoolean("pref_audio_phone_mic", checkAudioPhoneMic?.isChecked ?: true)
                .putBoolean("pref_disable_input", checkDisableInput.isChecked)
                .apply()


            applyAllSettings()
        }

        dialog.setOnDismissListener {
            saveSettingsValues()
        }

        btnSave.setOnClickListener {
            saveSettingsValues()
            dialog.dismiss()
            Toast.makeText(this, "Settings applied", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private fun startVideoRecording() {
        if (videoRecorder?.isRecording == true) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val recInternal = prefs.getBoolean("pref_audio_laptop_internal", true)
        val recLaptopMic = prefs.getBoolean("pref_audio_laptop_mic", false)
        val recPhoneMic = prefs.getBoolean("pref_audio_phone_mic", true)
        val shouldRecordAudio = true // Always initialize AAC audio track so phone mic can be toggled mid-recording
        val audioEndpoint = if (recLaptopMic && !recInternal) "/laptop_mic.pcm" else "/audio.pcm"
        val recFpsStr = prefs.getString("pref_rec_fps", "60 FPS") ?: "60 FPS"
        val targetFps = when {
            recFpsStr.contains("144") -> 144
            recFpsStr.contains("120") -> 120
            recFpsStr.contains("90") -> 90
            recFpsStr.contains("75") -> 75
            recFpsStr.contains("60") -> 60
            recFpsStr.contains("45") -> 45
            recFpsStr.contains("30") -> 30
            recFpsStr.contains("24") -> 24
            recFpsStr.contains("15") -> 15
            recFpsStr.contains("Auto") -> 0
            else -> 60
        }

        val aspectMode = prefs.getString("pref_aspect_ratio", "Auto (Match Laptop Native)") ?: "Auto (Match Laptop Native)"
        val resMode = prefs.getString("pref_resolution", "Native / Original") ?: "Native / Original"
        val scaleMode = prefs.getString("pref_scaling", "FIT_CENTER") ?: "FIT_CENTER"

        val rawW = latestFrameBitmap?.width ?: 1920
        val rawH = latestFrameBitmap?.height ?: 1200

        var baseW = 1920
        var baseH = 1200

        when {
            aspectMode.contains("9:16") -> { baseW = 1080; baseH = 1920 }
            aspectMode.contains("16:9") -> { baseW = 1920; baseH = 1080 }
            aspectMode.contains("16:10") -> { baseW = 1920; baseH = 1200 }
            aspectMode.contains("10:16") -> { baseW = 1200; baseH = 1920 }
            aspectMode.contains("4:3") -> { baseW = 1600; baseH = 1200 }
            aspectMode.contains("3:4") -> { baseW = 1200; baseH = 1600 }
            aspectMode.contains("1:1") -> { baseW = 1080; baseH = 1080 }
            aspectMode.contains("21:9") -> { baseW = 2560; baseH = 1080 }
            aspectMode.contains("9:21") -> { baseW = 1080; baseH = 2560 }
            aspectMode.contains("Phone") -> {
                val dm = resources.displayMetrics
                val isPortrait = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
                val maxDim = maxOf(dm.widthPixels, dm.heightPixels)
                val minDim = minOf(dm.widthPixels, dm.heightPixels)
                val phoneRatio = maxDim.toDouble() / minDim.toDouble()
                if (isPortrait) {
                    baseW = 1080
                    baseH = (1080 * phoneRatio).toInt()
                } else {
                    baseH = 1080
                    baseW = (1080 * phoneRatio).toInt()
                }
            }
            else -> {
                baseW = rawW
                baseH = rawH
            }
        }

        val scaleFactor = when {
            resMode.contains("1440p") || resMode.contains("2K") -> 1.3333f
            resMode.contains("720p") -> 0.6667f
            resMode.contains("480p") -> 0.4444f
            else -> 1.0f
        }

        var targetW = ((baseW * scaleFactor).toInt() / 16) * 16
        var targetH = ((baseH * scaleFactor).toInt() / 16) * 16
        if (targetW < 16) targetW = 16
        if (targetH < 16) targetH = 16

        val customUriStr = prefs.getString("pref_storage_uri", null)
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
        val fileName = "KDEConnect_Screen_$timeStamp.mp4"

        var outputFile: File? = null
        var pfd: ParcelFileDescriptor? = null
        var destName = "Movies/KDEConnect/$fileName"

        if (!customUriStr.isNullOrEmpty()) {
            try {
                val treeUri = Uri.parse(customUriStr)
                val pickedDir = DocumentFile.fromTreeUri(this, treeUri)
                val newFileDoc = pickedDir?.createFile("video/mp4", fileName)
                if (newFileDoc != null) {
                    pfd = contentResolver.openFileDescriptor(newFileDoc.uri, "rw")
                    destName = "${pickedDir.name}/$fileName"
                }
            } catch (e: Exception) {
                Log.e("ScreenMirror", "Error creating custom URI file, fallback to Movies", e)
            }
        }

        if (pfd == null) {
            val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                ?: getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: filesDir
            val targetDir = File(moviesDir, "KDEConnect")
            if (!targetDir.exists()) targetDir.mkdirs()
            outputFile = File(targetDir, fileName)
            destName = "Movies/KDEConnect/$fileName"
        }

        try {
            val rec = ScreenVideoRecorder(this, getTargetHost(), targetW, targetH, outputFile, pfd, shouldRecordAudio, if (targetFps > 0) targetFps else 60, scaleMode, aspectMode, audioEndpoint)
            rec.start()
            videoRecorder = rec
            recordStartTime = System.currentTimeMillis()

            updateRecordToolbarUI()
            recordTimerHandler.post(recordTimerRunnable)

            Toast.makeText(this, "REC $targetW x $targetH [$aspectMode | $scaleMode]", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("ScreenMirror", "Failed to start video recording", e)
            Toast.makeText(this, "Recording failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopVideoRecording() {
        recordTimerHandler.removeCallbacks(recordTimerRunnable)
        val savedFile = videoRecorder?.stop()
        videoRecorder = null
        updateRecordToolbarUI()

        if (savedFile != null && savedFile.exists()) {
            MediaScannerConnection.scanFile(this, arrayOf(savedFile.absolutePath), arrayOf("video/mp4"), null)
            Toast.makeText(this, "Video saved: ${savedFile.name}", Toast.LENGTH_LONG).show()

            val host = getTargetHost()
            if (!host.isNullOrEmpty()) {
                org.kde.kdeconnect.helpers.ThreadHelper.execute {
                    try {
                        val url = java.net.URL("http://$host:59001/upload_recording")
                        val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                            requestMethod = "POST"
                            doOutput = true
                            setRequestProperty("X-Filename", savedFile.name)
                            setRequestProperty("Content-Type", "video/mp4")
                            setFixedLengthStreamingMode(savedFile.length())
                            connectTimeout = 5000
                            readTimeout = 60000
                        }
                        savedFile.inputStream().use { input ->
                            conn.outputStream.use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (conn.responseCode == 200) {
                            runOnUiThread {
                                Toast.makeText(this@ScreenMirrorActivity, "Copy saved to ~/Videos/Kdeconnect on PC", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        } else {
            Toast.makeText(this, "Recording finished & saved", Toast.LENGTH_SHORT).show()
        }
    }

    private fun togglePauseResumeRecording() {
        val rec = videoRecorder ?: return
        if (!rec.isRecording) return

        if (rec.isPaused) {
            rec.resume()
            Toast.makeText(this, "Recording resumed", Toast.LENGTH_SHORT).show()
        } else {
            rec.pause()
            Toast.makeText(this, "Recording paused", Toast.LENGTH_SHORT).show()
        }
        updateRecordToolbarUI()
    }

    private fun discardVideoRecording() {
        recordTimerHandler.removeCallbacks(recordTimerRunnable)
        videoRecorder?.discard()
        videoRecorder = null
        updateRecordToolbarUI()
        Toast.makeText(this, "Recording discarded", Toast.LENGTH_SHORT).show()
    }

    private fun takeScreenshot() {
        val bmp = latestFrameBitmap
        if (bmp == null) {
            Toast.makeText(this, "No video frame available for screenshot", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                ?: getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?: filesDir
            val kdeConnectDir = File(picturesDir, "Screenshots")
            if (!kdeConnectDir.exists()) kdeConnectDir.mkdirs()

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
            val file = File(kdeConnectDir, "KDEConnect_Screenshot_$timeStamp.png")

            java.io.FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("image/png"), null)
            Toast.makeText(this, "Screenshot saved to Pictures: ${file.name}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e("ScreenMirror", "Screenshot error", e)
            Toast.makeText(this, "Failed to save screenshot: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showClipboardDialog() {
        ScreenMirrorClipboardDialog.newInstance(deviceId).show(
            supportFragmentManager,
            ScreenMirrorClipboardDialog.TAG
        )
    }

    private fun toggleKeyboard() {
        try {
            val imm = ContextCompat.getSystemService(this, InputMethodManager::class.java)
            if (isKeyboardVisible) {
                imm?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
                binding.editLiveKeystrokes.clearFocus()
                binding.keyListener.clearFocus()
                isKeyboardVisible = false
            } else {
                val targetView: View = if (binding.editLiveKeystrokes.isShown) {
                    binding.editLiveKeystrokes
                } else {
                    binding.keyListener.apply {
                        isFocusable = true
                        isFocusableInTouchMode = true
                    }
                }
                targetView.requestFocus()
                imm?.showSoftInput(targetView, InputMethodManager.SHOW_FORCED)
                isKeyboardVisible = true
            }
        } catch (e: Exception) {
            Log.e("ScreenMirror", "Error toggling keyboard", e)
        }
    }

    private fun toggleFullscreen() {
        if (isFullscreen) {
            disableFullscreen()
        } else {
            enableFullscreen()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateOrientationUI(newConfig)
    }

    private fun updateOrientationUI(config: Configuration) {
        val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (isLandscape) {
            // Landscape: Hide portrait bars, show Left and Right sidebars
            binding.topControlsOverlay.visibility = View.GONE
            binding.bottomControlsContainer.visibility = View.GONE
            binding.layoutStreamStats.visibility = View.GONE
            binding.landscapeLeftSidebar.visibility = if (isFullscreen) View.GONE else View.VISIBLE
            binding.landscapeRightSidebar.visibility = if (isFullscreen) View.GONE else View.VISIBLE
            supportActionBar?.hide()
        } else {
            // Portrait: Show top and bottom bars, hide sidebars
            binding.topControlsOverlay.visibility = if (isFullscreen) View.GONE else View.VISIBLE
            binding.bottomControlsContainer.visibility = if (isFullscreen || !isButtonsVisible) View.GONE else View.VISIBLE
            binding.layoutStreamStats.visibility = if (isFullscreen) View.GONE else View.VISIBLE
            binding.landscapeLeftSidebar.visibility = View.GONE
            binding.landscapeRightSidebar.visibility = View.GONE
            if (!isFullscreen) supportActionBar?.show()
        }
    }

    private fun enableFullscreen() {
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        supportActionBar?.hide()
        insetsController.hide(WindowInsetsCompat.Type.systemBars())

        // Pure buttonless fullscreen: hide top controls, bottom bar, sidebars and HUD, show floating exit button
        binding.topControlsOverlay.visibility = View.GONE
        binding.layoutStreamStats.visibility = View.GONE
        binding.bottomControlsContainer.visibility = View.GONE
        binding.landscapeLeftSidebar.visibility = View.GONE
        binding.landscapeRightSidebar.visibility = View.GONE
        binding.fabExitFullscreen.visibility = View.VISIBLE

        isFullscreen = true
        fullscreenBackCallback.isEnabled = true
    }

    private fun disableFullscreen() {
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        insetsController.show(WindowInsetsCompat.Type.systemBars())

        binding.fabExitFullscreen.visibility = View.GONE
        isFullscreen = false
        fullscreenBackCallback.isEnabled = false

        updateOrientationUI(resources.configuration)
    }

    private fun toggleButtons() {
        isButtonsVisible = !isButtonsVisible
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            binding.landscapeLeftSidebar.visibility = if (isButtonsVisible) View.VISIBLE else View.GONE
            binding.landscapeRightSidebar.visibility = if (isButtonsVisible) View.VISIBLE else View.GONE
        } else {
            binding.bottomControlsContainer.visibility = if (isButtonsVisible) View.VISIBLE else View.GONE
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onDown(e: MotionEvent): Boolean = true
    override fun onSingleTapUp(e: MotionEvent): Boolean = false

    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        mousePlugin?.sendLeftClick() ?: plugin?.sendLeftClick()
        return true
    }

    override fun onDoubleTap(e: MotionEvent): Boolean {
        mousePlugin?.sendDoubleClick() ?: plugin?.sendDoubleClick()
        return true
    }

    override fun onDoubleTapEvent(e: MotionEvent): Boolean = false

    override fun onLongPress(e: MotionEvent) {
        mousePlugin?.sendRightClick() ?: plugin?.sendRightClick()
    }

    override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean = false
    override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean = false
    override fun onShowPress(e: MotionEvent) {}
}
