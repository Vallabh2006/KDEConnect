/*
 * SPDX-FileCopyrightText: 2026 Antigravity
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.screenmirror

import android.app.Activity
import android.view.KeyEvent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.helpers.ThreadHelper
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect.plugins.clipboard.ClipboardListener
import org.kde.kdeconnect.plugins.mousepad.KeyListenerView
import org.kde.kdeconnect_tp.R
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList

@LoadablePlugin
class ScreenMirrorPlugin : Plugin() {

    interface FrameListener {
        fun onFrameReceived(bitmap: Bitmap?, width: Int, height: Int, timestamp: Long)
        fun onStreamStateChanged(streaming: Boolean, message: String?)
    }

    private val frameListeners = CopyOnWriteArrayList<FrameListener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    var isStreaming: Boolean = false
        private set

    var remoteWidth: Int = 1920
        private set

    var remoteHeight: Int = 1080
        private set

    override val displayName: String
        get() = context.getString(R.string.pref_plugin_screenmirror)

    override val description: String
        get() = context.getString(R.string.pref_plugin_screenmirror_desc)

    override fun getUiButtons(): List<PluginUiButton> {
        return listOf(
            PluginUiButton(
                "Stream",
                R.drawable.ic_screen_mirror_24dp
            ) { parentActivity: Activity ->
                val intent = Intent(parentActivity, ScreenMirrorActivity::class.java)
                intent.putExtra("deviceId", device.deviceId)
                parentActivity.startActivity(intent)
            }
        )
    }

    fun registerFrameListener(listener: FrameListener) {
        if (!frameListeners.contains(listener)) {
            frameListeners.add(listener)
        }
    }

    fun unregisterFrameListener(listener: FrameListener) {
        frameListeners.remove(listener)
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        when (np.type) {
            PACKET_TYPE_SCREENMIRROR, PACKET_TYPE_SCREENMIRROR_FRAME -> {
                handleFramePacket(np)
                return true
            }
            PACKET_TYPE_SCREENMIRROR_CONTROL -> {
                handleControlPacket(np)
                return true
            }
            PACKET_TYPE_CLIPBOARD -> {
                val content = np.getString("content")
                ClipboardListener.instance(context).setText(content)
                return true
            }
            PACKET_TYPE_CLIPBOARD_CONNECT -> {
                val content = np.getString("content")
                if (content.isNotEmpty()) {
                    ClipboardListener.instance(context).setText(content)
                }
                return true
            }
        }
        return false
    }

    private fun handleControlPacket(np: NetworkPacket) {
        if (np.has("streaming")) {
            isStreaming = np.getBoolean("streaming", false)
            val msg = np.getStringOrNull("message")
            mainHandler.post {
                for (listener in frameListeners) {
                    listener.onStreamStateChanged(isStreaming, msg)
                }
            }
        }
        if (np.has("width") && np.has("height")) {
            remoteWidth = np.getInt("width", 1920)
            remoteHeight = np.getInt("height", 1080)
        }
    }

    private fun handleFramePacket(np: NetworkPacket) {
        val width = np.getInt("width", remoteWidth)
        val height = np.getInt("height", remoteHeight)
        val timestamp = np.getLong("timestamp", System.currentTimeMillis())

        if (width > 0) remoteWidth = width
        if (height > 0) remoteHeight = height

        if (np.has("frame")) {
            val base64Data = np.getString("frame")
            ThreadHelper.execute {
                try {
                    val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                    if (bitmap != null) {
                        isStreaming = true
                        mainHandler.post {
                            for (listener in frameListeners) {
                                listener.onFrameReceived(bitmap, width, height, timestamp)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error decoding base64 screen frame", e)
                }
            }
        } else if (np.hasPayload()) {
            ThreadHelper.execute {
                try {
                    val payload = np.payload
                    val inputStream: InputStream? = payload?.inputStream
                    if (inputStream != null) {
                        val buffer = ByteArrayOutputStream()
                        val data = ByteArray(16384)
                        var nRead: Int
                        while (inputStream.read(data, 0, data.size).also { nRead = it } != -1) {
                            buffer.write(data, 0, nRead)
                        }
                        val imageBytes = buffer.toByteArray()
                        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                        if (bitmap != null) {
                            isStreaming = true
                            mainHandler.post {
                                for (listener in frameListeners) {
                                    listener.onFrameReceived(bitmap, width, height, timestamp)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error decoding payload screen frame", e)
                }
            }
        }
    }

    fun startStream(targetWidth: Int = 1280, targetHeight: Int = 720, quality: Int = 70, fps: Int = 30) {
        val np = NetworkPacket(PACKET_TYPE_SCREENMIRROR_REQUEST).apply {
            set("action", "start")
            set("targetWidth", targetWidth)
            set("targetHeight", targetHeight)
            set("quality", quality)
            set("fps", fps)
        }
        sendPacket(np)
    }

    fun stopStream() {
        isStreaming = false
        val np = NetworkPacket(PACKET_TYPE_SCREENMIRROR_REQUEST).apply {
            set("action", "stop")
        }
        sendPacket(np)
    }

    fun requestFrame() {
        val np = NetworkPacket(PACKET_TYPE_SCREENMIRROR_REQUEST).apply {
            set("action", "request_frame")
        }
        sendPacket(np)
    }

    fun sendMouseDelta(dx: Float, dy: Float) {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST).apply {
            set("dx", dx.toDouble())
            set("dy", dy.toDouble())
        }
        sendPacket(np)
    }

    fun sendLeftClick() {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST).apply {
            set("singleclick", true)
        }
        sendPacket(np)
    }

    fun sendDoubleClick() {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST).apply {
            set("doubleclick", true)
        }
        sendPacket(np)
    }

    fun sendMiddleClick() {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST).apply {
            set("middleclick", true)
        }
        sendPacket(np)
    }

    fun sendRightClick() {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST).apply {
            set("rightclick", true)
        }
        sendPacket(np)
    }

    fun sendSingleHold() {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST).apply {
            set("singlehold", true)
        }
        sendPacket(np)
    }

    fun sendSingleRelease() {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST).apply {
            set("singlerelease", true)
        }
        sendPacket(np)
    }

    fun sendScroll(dx: Double, dy: Double) {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST).apply {
            set("scroll", true)
            set("dx", dx)
            set("dy", dy)
        }
        sendPacket(np)
    }

    fun sendText(content: String) {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST).apply {
            set("key", content)
        }
        sendPacket(np)
    }

    fun sendBackspace() {
        sendSpecialKey(KeyEvent.KEYCODE_DEL)
    }

    fun sendReturn() {
        sendSpecialKey(KeyEvent.KEYCODE_ENTER)
    }

    fun sendSpecialKey(keyCode: Int, isShift: Boolean = false, isCtrl: Boolean = false, isAlt: Boolean = false, isMeta: Boolean = false) {
        val specialKey = KeyListenerView.SpecialKeysMap.get(keyCode, -1)
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST).apply {
            if (specialKey != -1) {
                set("specialKey", specialKey)
            }
            if (isShift) set("shift", true)
            if (isCtrl) set("ctrl", true)
            if (isAlt) set("alt", true)
            if (isMeta) set("super", true)
        }
        sendPacket(np)
    }

    fun sendClipboard(content: String) {
        val np = NetworkPacket(PACKET_TYPE_CLIPBOARD).apply {
            set("content", content)
        }
        sendPacket(np)
        ClipboardListener.instance(context).setText(content)
    }

    fun requestClipboard() {
        val np = NetworkPacket(PACKET_TYPE_CLIPBOARD_CONNECT).apply {
            set("timestamp", System.currentTimeMillis())
        }
        sendPacket(np)
    }

    fun sendPacket(np: NetworkPacket) {
        device.sendPacket(np)
    }

    override val supportedPacketTypes: Array<String> = arrayOf(
        PACKET_TYPE_SCREENMIRROR,
        PACKET_TYPE_SCREENMIRROR_FRAME,
        PACKET_TYPE_SCREENMIRROR_CONTROL,
        PACKET_TYPE_CLIPBOARD,
        PACKET_TYPE_CLIPBOARD_CONNECT
    )

    override val outgoingPacketTypes: Array<String> = arrayOf(
        PACKET_TYPE_SCREENMIRROR_REQUEST,
        PACKET_TYPE_SCREENMIRROR,
        PACKET_TYPE_MOUSEPAD_REQUEST,
        PACKET_TYPE_CLIPBOARD,
        PACKET_TYPE_CLIPBOARD_CONNECT
    )

    companion object {
        const val PACKET_TYPE_SCREENMIRROR = "kdeconnect.screenmirror"
        const val PACKET_TYPE_SCREENMIRROR_FRAME = "kdeconnect.screenmirror.frame"
        const val PACKET_TYPE_SCREENMIRROR_CONTROL = "kdeconnect.screenmirror.control"
        const val PACKET_TYPE_SCREENMIRROR_REQUEST = "kdeconnect.screenmirror.request"
        const val PACKET_TYPE_MOUSEPAD_REQUEST = "kdeconnect.mousepad.request"
        const val PACKET_TYPE_CLIPBOARD = "kdeconnect.clipboard"
        const val PACKET_TYPE_CLIPBOARD_CONNECT = "kdeconnect.clipboard.connect"

        private const val TAG = "ScreenMirrorPlugin"
    }
}
