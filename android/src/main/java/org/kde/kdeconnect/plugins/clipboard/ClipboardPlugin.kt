package org.kde.kdeconnect.plugins.clipboard

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.logging.KdeLog
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect.plugins.clipboard.ClipboardListener.ClipboardObserver
import org.kde.kdeconnect.ui.PluginSettingsFragment
import org.kde.kdeconnect_tp.R

@LoadablePlugin
class ClipboardPlugin : Plugin() {

    override val displayName: String
        get() = context.getString(R.string.pref_plugin_clipboard)

    override val description: String
        get() = context.getString(R.string.pref_plugin_clipboard_desc)

    override val minSdk: Int
        get() = Build.VERSION_CODES.LOLLIPOP

    override fun hasSettings(): Boolean = true

    override fun getSettingsFragment(activity: Activity): PluginSettingsFragment {
        return PluginSettingsFragment.newInstance(pluginKey, device.deviceId, R.xml.clipboardplugin_preferences)
    }

    private fun isValidClipboardContent(content: String?): Boolean {
        if (content.isNullOrBlank()) return false
        val trimmed = content.trim()
        if (trimmed.equals("null", ignoreCase = true)) return false
        if (trimmed.startsWith("file://", ignoreCase = true)) return false
        if (trimmed.startsWith("content://", ignoreCase = true)) return false
        return true
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        val content = np.getString("content")
        if (!isValidClipboardContent(content)) {
            return true
        }

        ClipboardHistoryManager.addItem(context, content, isReceived = true)
        KdeLog.clipboard("Received Clipboard", content)

        when (np.type) {
            (PACKET_TYPE_CLIPBOARD) -> {
                ClipboardListener.instance(context).setText(content)
                return true
            }
            (PACKET_TYPE_CLIPBOARD_CONNECT) -> {
                val packetTime = np.getLong("timestamp")
                if (packetTime == 0L || packetTime < ClipboardListener.instance(context).updateTimestamp) {
                    return false
                }
                ClipboardListener.instance(context).setText(content)
                return true
            }
            else -> throw UnsupportedOperationException("Unknown packet type: " + np.type)
        }
    }

    private val observer: ClipboardObserver = object : ClipboardObserver {
        override fun clipboardChanged(content: String, contentType: ClipboardListener.ClipboardContentType) {
            if (contentType == ClipboardListener.ClipboardContentType.Password &&
                preferences!!.getBoolean(
                    context.getString(R.string.clipboard_preference_key_skip_sensitive),
                    false,
                )
            ) {
                return
            }
            propagateClipboard(content)
        }
    }

    @VisibleForTesting
    fun propagateClipboard(content: String) {
        if (!isValidClipboardContent(content)) return

        val np = NetworkPacket(PACKET_TYPE_CLIPBOARD)
        np["content"] = content
        device.sendPacket(np)
        KdeLog.clipboard("Propagated Clipboard", content)

        val host = device.getRemoteIpAddress().takeIf { !it.isNullOrEmpty() } ?: "127.0.0.1"
        if (host.isNotEmpty()) {
            org.kde.kdeconnect.helpers.ThreadHelper.execute {
                try {
                    val url = java.net.URL("http://$host:59001/clipboard")
                    val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 1500
                        readTimeout = 1500
                        requestMethod = "POST"
                        doOutput = true
                    }
                    java.io.OutputStreamWriter(conn.outputStream).use { it.write(content) }
                    conn.responseCode
                } catch (_: Exception) {}
            }
        }
    }

    override fun onCreate() {
        ClipboardListener.instance(context).registerObserver(observer)
    }

    override fun onDestroy() {
        ClipboardListener.instance(context).removeObserver(observer)
    }

    override val supportedPacketTypes: Array<String> = arrayOf(PACKET_TYPE_CLIPBOARD, PACKET_TYPE_CLIPBOARD_CONNECT)

    override val outgoingPacketTypes: Array<String> = arrayOf(PACKET_TYPE_CLIPBOARD, PACKET_TYPE_CLIPBOARD_CONNECT)

    override fun getUiButtons(): List<PluginUiButton> {
        return listOf(
            PluginUiButton(
                "Clipboard",
                R.drawable.ic_baseline_content_paste_24
            ) { parentActivity: Activity ->
                val intent = android.content.Intent(parentActivity, ClipboardManagerActivity::class.java).apply {
                    putExtra("deviceId", device.deviceId)
                }
                parentActivity.startActivity(intent)
            }
        )
    }

    override fun getUiMenuEntries(): List<PluginUiMenuEntry> {
        return emptyList()
    }

    private fun userInitiatedSendClipboard() {
        if (isDeviceInitialized) {
            val clipboardManager = ContextCompat.getSystemService<ClipboardManager>(this.context, ClipboardManager::class.java)
            val item: ClipData.Item
            if (clipboardManager != null && clipboardManager.hasPrimaryClip()) {
                item = clipboardManager.primaryClip!!.getItemAt(0)
                val content = item.coerceToText(this.context).toString()
                if (isValidClipboardContent(content)) {
                    this.propagateClipboard(content)
                    Toast.makeText(this.context, R.string.pref_plugin_clipboard_sent, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    companion object {
        private const val PACKET_TYPE_CLIPBOARD = "kdeconnect.clipboard"
        private const val PACKET_TYPE_CLIPBOARD_CONNECT = "kdeconnect.clipboard.connect"

        fun canSyncAutomatically(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return true
            }
            return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED
        }
    }
}
