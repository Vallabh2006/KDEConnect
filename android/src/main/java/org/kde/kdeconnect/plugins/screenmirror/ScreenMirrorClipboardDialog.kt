/*
 * SPDX-FileCopyrightText: 2026 Antigravity
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.screenmirror

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.json.JSONObject
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.helpers.ThreadHelper
import org.kde.kdeconnect.plugins.clipboard.ClipboardListener
import org.kde.kdeconnect_tp.R
import org.kde.kdeconnect_tp.databinding.DialogScreenMirrorClipboardBinding
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class ScreenMirrorClipboardDialog : BottomSheetDialogFragment(), ClipboardListener.ClipboardObserver {

    private var _binding: DialogScreenMirrorClipboardBinding? = null
    private val binding get() = _binding!!

    private var deviceId: String = ""
    private var lastKnownVersion = 0
    private var isWatcherActive = false
    private var watcherThread: Thread? = null

    private val plugin: ScreenMirrorPlugin?
        get() = KdeConnect.getInstance().getDevicePlugin(deviceId, ScreenMirrorPlugin::class.java)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceId = arguments?.getString(ARG_DEVICE_ID) ?: ""
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogScreenMirrorClipboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViews()
        loadRemoteClipboard()
        ClipboardListener.instance(requireContext()).registerObserver(this)
        startRemoteClipboardWatcher()
    }

    override fun clipboardChanged(content: String, contentType: ClipboardListener.ClipboardContentType) {
        activity?.runOnUiThread {
            if (_binding != null && ClipboardListener.isValidClipboardText(content)) {
                if (binding.textRemoteClipboard.text.isNullOrEmpty() || binding.textRemoteClipboard.text == "No clipboard received yet") {
                    binding.textRemoteClipboard.text = content
                }
            }
        }
    }

    private fun startRemoteClipboardWatcher() {
        isWatcherActive = true
        watcherThread = Thread {
            while (isWatcherActive && !Thread.currentThread().isInterrupted) {
                try {
                    val device = KdeConnect.getInstance().getDevice(deviceId)
                    val host = device?.getRemoteIpAddress()
                    if (!host.isNullOrEmpty()) {
                        val url = URL("http://:59001/clipboard?version=&timeout=3")
                        val conn = (url.openConnection() as HttpURLConnection).apply {
                            connectTimeout = 3000
                            readTimeout = 4500
                            requestMethod = "GET"
                        }
                        if (conn.responseCode == 200) {
                            val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                            val json = JSONObject(resp)
                            val ver = json.optInt("version", lastKnownVersion)
                            val curr = json.optString("current", "").trim()
                            lastKnownVersion = ver
                            if (ClipboardListener.isValidClipboardText(curr)) {
                                activity?.runOnUiThread {
                                    if (_binding != null) {
                                        binding.textRemoteClipboard.text = curr
                                    }
                                }
                            }
                        }
                    } else {
                        Thread.sleep(1500)
                    }
                } catch (_: Exception) {
                    try { Thread.sleep(1000) } catch (_: Exception) { break }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun stopRemoteClipboardWatcher() {
        isWatcherActive = false
        watcherThread?.interrupt()
        watcherThread = null
    }

    private fun setupViews() {
        binding.btnRefreshClipboard.setOnClickListener {
            plugin?.requestClipboard()
            loadRemoteClipboard()
            Toast.makeText(requireContext(), R.string.screenmirror_btn_refresh, Toast.LENGTH_SHORT).show()
        }

        binding.btnCopyToPhone.setOnClickListener {
            val remoteText = binding.textRemoteClipboard.text.toString().trim()
            if (ClipboardListener.isValidClipboardText(remoteText) && remoteText != "No clipboard received yet") {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = ClipData.newPlainText("KDE Connect", remoteText)
                clipboard?.setPrimaryClip(clip)
                ClipboardListener.instance(requireContext()).setText(remoteText)
                Toast.makeText(requireContext(), "Copied to Phone Clipboard", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "No valid clipboard text available to copy", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSendToLaptop.setOnClickListener {
            val textToSend = binding.editSendClipboard.text?.toString()?.trim() ?: ""
            if (ClipboardListener.isValidClipboardText(textToSend)) {
                plugin?.sendClipboard(textToSend)
                binding.textRemoteClipboard.text = textToSend
                sendDirectHttpClipboard(textToSend)
                Toast.makeText(requireContext(), "Sent to PC Clipboard", Toast.LENGTH_SHORT).show()
                dismiss()
            } else {
                Toast.makeText(requireContext(), R.string.screenmirror_send_clipboard_hint, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSendLatestClipboard.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
            if (ClipboardListener.isValidClipboardText(clip)) {
                plugin?.sendClipboard(clip)
                binding.textRemoteClipboard.text = clip
                sendDirectHttpClipboard(clip)
                Toast.makeText(requireContext(), "Sent latest phone clipboard to PC", Toast.LENGTH_SHORT).show()
                dismiss()
            } else {
                Toast.makeText(requireContext(), "Phone clipboard is empty or invalid", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendDirectHttpClipboard(text: String) {
        val device = KdeConnect.getInstance().getDevice(deviceId)
        val host = device?.getRemoteIpAddress()
        if (!host.isNullOrEmpty()) {
            ThreadHelper.execute {
                try {
                    val url = URL("http://:59001/clipboard")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 1500
                        readTimeout = 1500
                        requestMethod = "POST"
                        doOutput = true
                    }
                    OutputStreamWriter(conn.outputStream).use { it.write(text) }
                    conn.responseCode
                } catch (_: Exception) {}
            }
        }
    }

    private fun loadRemoteClipboard() {
        val current = ClipboardListener.instance(requireContext()).currentContent
        if (ClipboardListener.isValidClipboardText(current)) {
            binding.textRemoteClipboard.text = current
        }

        val device = KdeConnect.getInstance().getDevice(deviceId)
        val host = device?.getRemoteIpAddress()
        if (!host.isNullOrEmpty()) {
            ThreadHelper.execute {
                try {
                    val url = URL("http://:59001/clipboard")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 1500
                        readTimeout = 2000
                        requestMethod = "GET"
                    }
                    if (conn.responseCode == 200) {
                        val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                        val json = JSONObject(resp)
                        val curr = json.optString("current", "").trim()
                        val ver = json.optInt("version", lastKnownVersion)
                        lastKnownVersion = ver
                        if (ClipboardListener.isValidClipboardText(curr)) {
                            activity?.runOnUiThread {
                                _binding?.textRemoteClipboard?.text = curr
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    override fun onDestroyView() {
        stopRemoteClipboardWatcher()
        try {
            ClipboardListener.instance(requireContext()).removeObserver(this)
        } catch (_: Exception) {}
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ScreenMirrorClipboardDialog"
        private const val ARG_DEVICE_ID = "device_id"

        fun newInstance(deviceId: String): ScreenMirrorClipboardDialog {
            val args = Bundle().apply {
                putString(ARG_DEVICE_ID, deviceId)
            }
            return ScreenMirrorClipboardDialog().apply {
                arguments = args
            }
        }
    }
}
