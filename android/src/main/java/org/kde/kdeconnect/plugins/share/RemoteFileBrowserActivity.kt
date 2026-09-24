/*
 * SPDX-FileCopyrightText: 2026 Antigravity
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.share

import android.app.ProgressDialog
import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import org.json.JSONArray
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.base.BaseActivity
import org.kde.kdeconnect.extensions.viewBinding
import org.kde.kdeconnect.helpers.SshManager
import org.kde.kdeconnect_tp.R
import org.kde.kdeconnect_tp.databinding.ActivityRemoteFileBrowserBinding
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import androidx.preference.PreferenceManager
import org.json.JSONObject

class RemoteFileBrowserActivity : BaseActivity<ActivityRemoteFileBrowserBinding>() {

    override val binding: ActivityRemoteFileBrowserBinding by viewBinding(ActivityRemoteFileBrowserBinding::inflate)

    private lateinit var deviceId: String
    private var currentPath: String = "."
    private val pathStack = mutableListOf<String>()
    private val fileList = mutableListOf<SshManager.RemoteFileItem>()
    private lateinit var adapter: RemoteFileAdapter
    private val selectedItems = mutableSetOf<SshManager.RemoteFileItem>()
    private var isSelectionMode = false
    private val clipboardItems = mutableListOf<SshManager.RemoteFileItem>()
    private var isCutMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        deviceId = intent.getStringExtra("deviceId") ?: ""
        if (deviceId.isEmpty()) {
            finish()
            return
        }

        setSupportActionBar(binding.toolbarLayout.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = "Browse PC Files"
        binding.toolbarLayout.toolbar.setNavigationOnClickListener {
            navigateUp()
        }

        setupRecyclerView()
        setupListeners()
        checkConnectionAndLoad()
    }

    private fun setupRecyclerView() {
        adapter = RemoteFileAdapter(fileList,
            onItemClick = { item ->
                if (isSelectionMode) {
                    if (selectedItems.contains(item)) {
                        selectedItems.remove(item)
                    } else {
                        selectedItems.add(item)
                    }
                    if (selectedItems.isEmpty()) {
                        exitSelectionMode()
                    } else {
                        updateSelectionUI()
                        adapter.notifyDataSetChanged()
                    }
                } else {
                    if (item.isDirectory) {
                        navigateTo(item.fullPath)
                    } else {
                        promptFileOptions(item)
                    }
                }
            },
            onItemLongClick = { item ->
                if (isSelectionMode) {
                    if (selectedItems.contains(item)) {
                        selectedItems.remove(item)
                    } else {
                        selectedItems.add(item)
                    }
                    updateSelectionUI()
                    adapter.notifyDataSetChanged()
                } else {
                    if (item.isDirectory) {
                        promptDirectoryOptions(item)
                    } else {
                        promptFileOptions(item)
                    }
                }
            },
            onDownloadClick = { item ->
                if (item.isDirectory) {
                    downloadDirectoryAsZip(item)
                } else {
                    downloadFile(item)
                }
            }
        )
        binding.recyclerRemoteFiles.layoutManager = LinearLayoutManager(this)
        binding.recyclerRemoteFiles.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnNavUp.setOnClickListener {
            navigateUp()
        }

        binding.btnRefresh.setOnClickListener {
            loadDirectory(currentPath)
        }

        fun executeAddressBarNavigation() {
            val typedPath = binding.editAddressPath.text?.toString()?.trim() ?: ""
            if (typedPath.isNotEmpty()) {
                navigateTo(typedPath)
            }
        }

        binding.btnGoPath.setOnClickListener {
            executeAddressBarNavigation()
        }

        binding.editAddressPath.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                executeAddressBarNavigation()
                true
            } else {
                false
            }
        }

        binding.btnRecentPaths.setOnClickListener {
            showRecentPathsDialog()
        }

        binding.btnSelectionCopy.setOnClickListener {
            if (selectedItems.isNotEmpty()) {
                clipboardItems.clear()
                clipboardItems.addAll(selectedItems)
                isCutMode = false
                val count = clipboardItems.size
                exitSelectionMode()
                updatePasteBarUI()
                Toast.makeText(this, "Copied " + count + " items. Navigate and tap Paste Here", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSelectionCut.setOnClickListener {
            if (selectedItems.isNotEmpty()) {
                clipboardItems.clear()
                clipboardItems.addAll(selectedItems)
                isCutMode = true
                val count = clipboardItems.size
                exitSelectionMode()
                updatePasteBarUI()
                Toast.makeText(this, "Cut " + count + " items. Navigate and tap Paste Here", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSelectionCancel.setOnClickListener {
            exitSelectionMode()
        }

        binding.btnCancelPaste.setOnClickListener {
            clipboardItems.clear()
            updatePasteBarUI()
        }

        binding.btnPasteNow.setOnClickListener {
            executePaste(currentPath)
        }
    }

    private fun enterSelectionMode(initialItem: SshManager.RemoteFileItem? = null) {
        isSelectionMode = true
        selectedItems.clear()
        if (initialItem != null) {
            selectedItems.add(initialItem)
        }
        updateSelectionUI()
        adapter.notifyDataSetChanged()
    }

    private fun exitSelectionMode() {
        isSelectionMode = false
        selectedItems.clear()
        updateSelectionUI()
        adapter.notifyDataSetChanged()
    }

    private fun updateSelectionUI() {
        if (isSelectionMode) {
            binding.layoutSelectionBar.visibility = View.VISIBLE
            binding.textSelectionCount.text = "" + selectedItems.size + " Selected"
        } else {
            binding.layoutSelectionBar.visibility = View.GONE
        }
    }

    private fun updatePasteBarUI() {
        if (clipboardItems.isNotEmpty()) {
            binding.layoutPasteBar.visibility = View.VISIBLE
            val actionName = if (isCutMode) "Cut" else "Copied"
            val count = clipboardItems.size
            binding.textPasteInfo.text = actionName + ": " + count + " item" + (if (count > 1) "s" else "")
        } else {
            binding.layoutPasteBar.visibility = View.GONE
        }
    }

    private fun executePaste(targetDir: String) {
        if (clipboardItems.isEmpty()) return
        val count = clipboardItems.size
        val srcPaths = clipboardItems.map { it.fullPath }
        val wasCut = isCutMode

        val progress = ProgressDialog(this).apply {
            setTitle(if (wasCut) "Moving Items" else "Copying Items")
            setMessage("Pasting " + count + " items into " + targetDir + "...")
            setProgressStyle(ProgressDialog.STYLE_SPINNER)
            setCancelable(false)
            show()
        }

        val host = getTargetHost()
        if (host.isNotEmpty()) {
            Thread {
                try {
                    val actionCmd = if (wasCut) {
                        "mv " + srcPaths.joinToString(" ") { "\"$it\"" } + " \"$targetDir/\""
                    } else {
                        "cp -r " + srcPaths.joinToString(" ") { "\"$it\"" } + " \"$targetDir/\""
                    }
                    val enc = URLEncoder.encode(actionCmd, "UTF-8")
                    val url = URL("http://$host:59001/task_manager_action?cmd=$enc")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 4000
                        readTimeout = 8000
                        requestMethod = "POST"
                        doOutput = true
                        doInput = true
                    }
                    conn.outputStream.use { it.write(actionCmd.toByteArray(Charsets.UTF_8)) }
                    if (conn.responseCode in 200..299) {
                        runOnUiThread {
                            progress.dismiss()
                            Toast.makeText(this@RemoteFileBrowserActivity, (if (wasCut) "Moved " else "Copied ") + count + " items successfully", Toast.LENGTH_SHORT).show()
                            if (wasCut) {
                                clipboardItems.clear()
                                updatePasteBarUI()
                            }
                            loadDirectory(currentPath)
                        }
                        return@Thread
                    }
                } catch (_: Exception) {}

                if (SshManager.isConnected(deviceId)) {
                    SshManager.executeRemoteCopyOrMove(deviceId, wasCut, srcPaths, targetDir) { success, error ->
                        progress.dismiss()
                        if (success) {
                            Toast.makeText(this@RemoteFileBrowserActivity, (if (wasCut) "Moved " else "Copied ") + count + " items successfully", Toast.LENGTH_SHORT).show()
                            if (wasCut) {
                                clipboardItems.clear()
                                updatePasteBarUI()
                            }
                            loadDirectory(currentPath)
                        } else {
                            Toast.makeText(this@RemoteFileBrowserActivity, "Paste failed: " + (error ?: "Unknown error"), Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    runOnUiThread {
                        progress.dismiss()
                        Toast.makeText(this@RemoteFileBrowserActivity, "Failed to paste items", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        } else if (SshManager.isConnected(deviceId)) {
            SshManager.executeRemoteCopyOrMove(deviceId, wasCut, srcPaths, targetDir) { success, error ->
                progress.dismiss()
                if (success) {
                    Toast.makeText(this@RemoteFileBrowserActivity, (if (wasCut) "Moved " else "Copied ") + count + " items successfully", Toast.LENGTH_SHORT).show()
                    if (wasCut) {
                        clipboardItems.clear()
                        updatePasteBarUI()
                    }
                    loadDirectory(currentPath)
                } else {
                    Toast.makeText(this@RemoteFileBrowserActivity, "Paste failed: " + (error ?: "Unknown error"), Toast.LENGTH_LONG).show()
                }
            }
        } else {
            progress.dismiss()
            promptSshLogin()
        }
    }

    private fun getTargetHost(): String {
        val device = KdeConnect.getInstance().getDevice(deviceId)
        var host = device?.getRemoteIpAddress() ?: ""
        if (host.isEmpty()) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            host = prefs.getString("pref_last_known_ip_$deviceId", "") ?: ""
        }
        if (host.isEmpty()) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            host = prefs.getString("pref_last_known_ip", "") ?: ""
        }
        if (host.isEmpty()) {
            host = SshManager.getSavedCredentials(this, deviceId)?.host ?: ""
        }
        if (host.isEmpty()) {
            host = "127.0.0.1"
        }
        return host
    }

    private fun checkConnectionAndLoad() {
        // Direct zero-auth HTTP streamer first!
        loadDirectory(currentPath)
    }

    private fun promptSshLogin() {
        val defaultHost = getTargetHost()
        SshManager.showSshDialog(this, deviceId, defaultHost) {
            loadDirectory(currentPath)
        }
    }

    private fun navigateTo(path: String) {
        if (currentPath != path) {
            pathStack.add(currentPath)
            currentPath = path
            loadDirectory(currentPath)
        }
    }

    private fun navigateUp() {
        if (pathStack.isNotEmpty()) {
            currentPath = pathStack.removeAt(pathStack.size - 1)
            loadDirectory(currentPath)
        } else if (currentPath != "/" && currentPath != ".") {
            val parent = File(currentPath).parent ?: "/"
            currentPath = parent
            loadDirectory(currentPath)
        } else {
            finish()
        }
    }

    private fun saveRecentPath(path: String) {
        if (path.isEmpty() || path == ".") return
        val prefs = getSharedPreferences("remote_file_browser_prefs", Context.MODE_PRIVATE)
        val key = "recent_paths_$deviceId"
        val existing = getRecentPaths().toMutableList()
        existing.remove(path)
        existing.add(0, path)
        val limited = existing.take(12)
        val jsonArray = JSONArray(limited)
        prefs.edit().putString(key, jsonArray.toString()).apply()
    }

    private fun getRecentPaths(): List<String> {
        val prefs = getSharedPreferences("remote_file_browser_prefs", Context.MODE_PRIVATE)
        val key = "recent_paths_$deviceId"
        val jsonStr = prefs.getString(key, null) ?: return emptyList()
        return try {
            val jsonArray = JSONArray(jsonStr)
            val list = mutableListOf<String>()
            for (i in 0 until jsonArray.length()) {
                list.add(jsonArray.getString(i))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun clearRecentPaths() {
        val prefs = getSharedPreferences("remote_file_browser_prefs", Context.MODE_PRIVATE)
        val key = "recent_paths_$deviceId"
        prefs.edit().remove(key).apply()
        Toast.makeText(this, "Recent addresses cleared", Toast.LENGTH_SHORT).show()
    }

    private fun showRecentPathsDialog() {
        val recents = getRecentPaths()
        if (recents.isEmpty()) {
            Toast.makeText(this, "No recent addresses yet", Toast.LENGTH_SHORT).show()
            return
        }

        val items = recents.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Recent Addresses")
            .setItems(items) { _, which ->
                val selected = items[which]
                binding.editAddressPath.setText(selected)
                navigateTo(selected)
            }
            .setNeutralButton("Clear History") { _, _ ->
                clearRecentPaths()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadDirectory(path: String) {
        binding.progressLoading.visibility = View.VISIBLE
        if (binding.editAddressPath.text.toString() != path) {
            binding.editAddressPath.setText(path)
            binding.editAddressPath.setSelection(path.length)
        }

        val host = getTargetHost()
        if (host.isNotEmpty()) {
            Thread {
                try {
                    val encodedPath = URLEncoder.encode(path, "UTF-8")
                    val url = URL("http://$host:59001/list_files?path=$encodedPath")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 3000
                        readTimeout = 5000
                        useCaches = false
                    }
                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.bufferedReader().readText()
                        val json = JSONObject(body)
                        if (json.optBoolean("success", false)) {
                            val resolvedPath = json.optString("path", path)
                            val itemsArray = json.optJSONArray("items") ?: org.json.JSONArray()
                            val items = mutableListOf<SshManager.RemoteFileItem>()
                            for (i in 0 until itemsArray.length()) {
                                val obj = itemsArray.getJSONObject(i)
                                items.add(
                                    SshManager.RemoteFileItem(
                                        name = obj.getString("name"),
                                        fullPath = obj.getString("fullPath"),
                                        isDirectory = obj.getBoolean("isDirectory"),
                                        size = obj.optLong("size", 0L),
                                        lastModified = obj.optLong("lastModified", 0L)
                                    )
                                )
                            }
                            runOnUiThread {
                                binding.progressLoading.visibility = View.GONE
                                currentPath = resolvedPath
                                if (binding.editAddressPath.text.toString() != resolvedPath) {
                                    binding.editAddressPath.setText(resolvedPath)
                                    binding.editAddressPath.setSelection(resolvedPath.length)
                                }
                                saveRecentPath(resolvedPath)
                                fileList.clear()
                                fileList.addAll(items)
                                adapter.notifyDataSetChanged()
                                binding.textEmptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                            }
                            return@Thread
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.d("RemoteFileBrowser", "HTTP file list failed: ${e.message}, fallback to SSH")
                }

                // Fallback to SSH/SFTP
                SshManager.listRemoteDirectory(deviceId, path) { success, items, error ->
                    binding.progressLoading.visibility = View.GONE
                    if (success && items != null) {
                        saveRecentPath(path)
                        fileList.clear()
                        fileList.addAll(items)
                        adapter.notifyDataSetChanged()
                        binding.textEmptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                    } else {
                        Toast.makeText(this@RemoteFileBrowserActivity, "Failed to load directory: " + error, Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        } else {
            SshManager.listRemoteDirectory(deviceId, path) { success, items, error ->
                binding.progressLoading.visibility = View.GONE
                if (success && items != null) {
                    saveRecentPath(path)
                    fileList.clear()
                    fileList.addAll(items)
                    adapter.notifyDataSetChanged()
                    binding.textEmptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                } else {
                    Toast.makeText(this@RemoteFileBrowserActivity, "Failed to load directory: " + error, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun promptDirectoryOptions(item: SshManager.RemoteFileItem) {
        val options = arrayOf("Open Folder", "Copy", "Cut", "Select Multiple", "Download Folder as ZIP")
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> navigateTo(item.fullPath)
                    1 -> {
                        clipboardItems.clear()
                        clipboardItems.add(item)
                        isCutMode = false
                        updatePasteBarUI()
                        Toast.makeText(this, "Copied " + item.name + ". Navigate and tap Paste Here", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        clipboardItems.clear()
                        clipboardItems.add(item)
                        isCutMode = true
                        updatePasteBarUI()
                        Toast.makeText(this, "Cut " + item.name + ". Navigate and tap Paste Here", Toast.LENGTH_SHORT).show()
                    }
                    3 -> enterSelectionMode(item)
                    4 -> downloadDirectoryAsZip(item)
                }
            }
            .show()
    }

    private fun promptFileOptions(item: SshManager.RemoteFileItem) {
        val options = arrayOf("Download File", "Copy", "Cut", "Select Multiple")
        AlertDialog.Builder(this)
            .setTitle(item.name + " (" + formatFileSize(item.size) + ")")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> downloadFile(item)
                    1 -> {
                        clipboardItems.clear()
                        clipboardItems.add(item)
                        isCutMode = false
                        updatePasteBarUI()
                        Toast.makeText(this, "Copied " + item.name + ". Navigate and tap Paste Here", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        clipboardItems.clear()
                        clipboardItems.add(item)
                        isCutMode = true
                        updatePasteBarUI()
                        Toast.makeText(this, "Cut " + item.name + ". Navigate and tap Paste Here", Toast.LENGTH_SHORT).show()
                    }
                    3 -> enterSelectionMode(item)
                }
            }
            .show()
    }

    private fun downloadDirectoryAsZip(item: SshManager.RemoteFileItem) {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val targetDir = File(downloadsDir, "KDEConnect")
        targetDir.mkdirs()
        val zipFileName = (if (item.name.endsWith(".zip")) item.name else item.name + ".zip")
        val targetFile = File(targetDir, zipFileName)

        val progressDialog = ProgressDialog(this).apply {
            setTitle("Downloading Folder as ZIP")
            setMessage("Compressing and downloading " + item.name + "...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            show()
        }

        val host = getTargetHost()
        if (host.isNotEmpty()) {
            Thread {
                try {
                    val encodedPath = URLEncoder.encode(item.fullPath, "UTF-8")
                    val url = URL("http://$host:59001/download_file?path=$encodedPath")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 5000
                        readTimeout = 60000
                        useCaches = false
                    }
                    if (conn.responseCode == 200) {
                        val totalBytes = conn.contentLengthLong
                        var totalRead = 0L
                        val inStream = conn.inputStream
                        val outStream = java.io.FileOutputStream(targetFile)
                        val buffer = ByteArray(65536)
                        var bytesRead: Int
                        while (inStream.read(buffer).also { bytesRead = it } != -1) {
                            outStream.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (totalBytes > 0) {
                                val percent = ((totalRead * 100) / totalBytes).toInt()
                                runOnUiThread {
                                    progressDialog.progress = percent
                                    progressDialog.setMessage("Downloading " + zipFileName + " (" + formatFileSize(totalRead) + " / " + formatFileSize(totalBytes) + ")")
                                }
                            } else {
                                runOnUiThread {
                                    progressDialog.setMessage("Downloading " + zipFileName + " (" + formatFileSize(totalRead) + ")")
                                }
                            }
                        }
                        outStream.flush()
                        outStream.close()
                        inStream.close()
                        runOnUiThread {
                            progressDialog.dismiss()
                            Toast.makeText(this@RemoteFileBrowserActivity, "Saved to Downloads/KDEConnect/" + zipFileName, Toast.LENGTH_LONG).show()
                        }
                        return@Thread
                    }
                } catch (e: Exception) {
                    android.util.Log.d("RemoteFileBrowser", "HTTP zip download error: ${e.message}, fallback to SFTP")
                }

                if (SshManager.isConnected(deviceId)) {
                    SshManager.downloadFolderAsZip(
                        deviceId,
                        item.fullPath,
                        targetFile,
                        onProgress = { totalBytes ->
                            runOnUiThread {
                                progressDialog.setMessage("Compressing and downloading " + item.name + " (" + formatFileSize(totalBytes) + ")...")
                            }
                        },
                        callback = { success, error ->
                            progressDialog.dismiss()
                            if (success) {
                                Toast.makeText(this@RemoteFileBrowserActivity, "Saved to Downloads/KDEConnect/" + zipFileName, Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(this@RemoteFileBrowserActivity, "Download failed: " + error, Toast.LENGTH_LONG).show()
                            }
                        }
                    )
                } else {
                    runOnUiThread {
                        progressDialog.dismiss()
                        Toast.makeText(this@RemoteFileBrowserActivity, "Download failed from host", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        } else if (SshManager.isConnected(deviceId)) {
            SshManager.downloadFolderAsZip(
                deviceId,
                item.fullPath,
                targetFile,
                onProgress = { totalBytes ->
                    progressDialog.setMessage("Compressing and downloading " + item.name + " (" + formatFileSize(totalBytes) + ")...")
                },
                callback = { success, error ->
                    progressDialog.dismiss()
                    if (success) {
                        Toast.makeText(this, "Saved to Downloads/KDEConnect/" + zipFileName, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Download failed: " + error, Toast.LENGTH_LONG).show()
                    }
                }
            )
        } else {
            progressDialog.dismiss()
            promptSshLogin()
        }
    }

    private fun promptDownload(item: SshManager.RemoteFileItem) {
        AlertDialog.Builder(this)
            .setTitle("Download File")
            .setMessage("Download " + item.name + " (" + formatFileSize(item.size) + ") to your phone?")
            .setPositiveButton("Download") { _, _ ->
                downloadFile(item)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun downloadFile(item: SshManager.RemoteFileItem) {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val targetDir = File(downloadsDir, "KDEConnect")
        targetDir.mkdirs()
        val targetFile = File(targetDir, item.name)

        val progressDialog = ProgressDialog(this).apply {
            setTitle("Downloading File")
            setMessage("Downloading " + item.name + "...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            show()
        }

        val host = getTargetHost()
        if (host.isNotEmpty()) {
            Thread {
                try {
                    val encodedPath = URLEncoder.encode(item.fullPath, "UTF-8")
                    val url = URL("http://$host:59001/download_file?path=$encodedPath")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 5000
                        readTimeout = 30000
                        useCaches = false
                    }
                    if (conn.responseCode == 200) {
                        val totalBytes = conn.contentLengthLong
                        var totalRead = 0L
                        val inStream = conn.inputStream
                        val outStream = java.io.FileOutputStream(targetFile)
                        val buffer = ByteArray(65536)
                        var bytesRead: Int
                        while (inStream.read(buffer).also { bytesRead = it } != -1) {
                            outStream.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (totalBytes > 0) {
                                val percent = ((totalRead * 100) / totalBytes).toInt()
                                runOnUiThread {
                                    progressDialog.progress = percent
                                    progressDialog.setMessage("Downloading " + item.name + " (" + formatFileSize(totalRead) + " / " + formatFileSize(totalBytes) + ")")
                                }
                            }
                        }
                        outStream.flush()
                        outStream.close()
                        inStream.close()
                        runOnUiThread {
                            progressDialog.dismiss()
                            Toast.makeText(this@RemoteFileBrowserActivity, "Saved to Downloads/KDEConnect/" + item.name, Toast.LENGTH_LONG).show()
                        }
                        return@Thread
                    }
                } catch (e: Exception) {
                    android.util.Log.d("RemoteFileBrowser", "HTTP download error: ${e.message}, fallback to SFTP")
                }

                // Fallback to SFTP
                SshManager.downloadRemoteFile(
                    deviceId,
                    item.fullPath,
                    targetFile,
                    onProgress = { read, total ->
                        if (total > 0) {
                            val percent = ((read * 100) / total).toInt()
                            runOnUiThread {
                                progressDialog.progress = percent
                                progressDialog.setMessage("Downloading " + item.name + "... (" + formatFileSize(read) + " / " + formatFileSize(total) + ")")
                            }
                        }
                    },
                    callback = { success, error ->
                        progressDialog.dismiss()
                        if (success) {
                            Toast.makeText(this@RemoteFileBrowserActivity, "Saved to Downloads/KDEConnect/" + item.name, Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@RemoteFileBrowserActivity, "Download failed: " + error, Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }.start()
        } else {
            SshManager.downloadRemoteFile(
                deviceId,
                item.fullPath,
                targetFile,
                onProgress = { read, total ->
                    if (total > 0) {
                        val percent = ((read * 100) / total).toInt()
                        progressDialog.progress = percent
                        progressDialog.setMessage("Downloading " + item.name + "... (" + formatFileSize(read) + " / " + formatFileSize(total) + ")")
                    }
                },
                callback = { success, error ->
                    progressDialog.dismiss()
                    if (success) {
                        Toast.makeText(this@RemoteFileBrowserActivity, "Saved to Downloads/KDEConnect/" + item.name, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@RemoteFileBrowserActivity, "Download failed: " + error, Toast.LENGTH_LONG).show()
                    }
                }
            )
        }
    }

    override fun onBackPressed() {
        // Phone back button directly returns to Send / Receive menu
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        // Top toolbar back button navigates up directory hierarchy
        navigateUp()
        return true
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        val formatted = DecimalFormat("#,##0.#").format(size / Math.pow(1024.0, digitGroups.toDouble()))
        return formatted + " " + units[digitGroups]
    }

    inner class RemoteFileAdapter(
        private val items: List<SshManager.RemoteFileItem>,
        private val onItemClick: (SshManager.RemoteFileItem) -> Unit,
        private val onItemLongClick: (SshManager.RemoteFileItem) -> Unit,
        private val onDownloadClick: (SshManager.RemoteFileItem) -> Unit
    ) : RecyclerView.Adapter<RemoteFileAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val iconType: ImageView = view.findViewById(R.id.icon_file_type)
            val textName: TextView = view.findViewById(R.id.text_file_name)
            val textInfo: TextView = view.findViewById(R.id.text_file_info)
            val btnDownload: ImageView = view.findViewById(R.id.btn_action_download)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_remote_file, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.textName.text = item.name

            val isSelected = selectedItems.contains(item)
            val cardView = holder.itemView as? com.google.android.material.card.MaterialCardView
            if (isSelected) {
                cardView?.strokeColor = android.graphics.Color.parseColor("#D0BCFF")
                cardView?.strokeWidth = 4
                cardView?.setCardBackgroundColor(android.graphics.Color.parseColor("#382F48"))
            } else {
                cardView?.strokeColor = android.graphics.Color.parseColor("#333333")
                cardView?.strokeWidth = 2
                cardView?.setCardBackgroundColor(android.graphics.Color.parseColor("#1E1926"))
            }

            val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
            val dateStr = if (item.lastModified > 0) dateFormat.format(Date(item.lastModified)) else ""

            if (item.isDirectory) {
                holder.iconType.setImageResource(R.drawable.ic_folder_24dp)
                holder.textInfo.text = if (dateStr.isNotEmpty()) "Folder • " + dateStr else "Folder"
                holder.btnDownload.visibility = View.VISIBLE
                holder.btnDownload.contentDescription = "Download Folder as ZIP"
            } else {
                holder.iconType.setImageResource(R.drawable.ic_file_24dp)
                val sizeStr = formatFileSize(item.size)
                holder.textInfo.text = if (dateStr.isNotEmpty()) sizeStr + " • " + dateStr else sizeStr
                holder.btnDownload.visibility = View.VISIBLE
                holder.btnDownload.contentDescription = "Download File"
            }

            holder.itemView.setOnClickListener { onItemClick(item) }
            holder.itemView.setOnLongClickListener {
                onItemLongClick(item)
                true
            }
            holder.btnDownload.setOnClickListener { onDownloadClick(item) }
        }

        override fun getItemCount(): Int = items.size
    }
}
