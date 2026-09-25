/*
 * SPDX-FileCopyrightText: 2026 Antigravity
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.taskmanager

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.helpers.SshManager
import org.kde.kdeconnect.helpers.ThreadHelper
import org.kde.kdeconnect.logging.KdeLog
import org.kde.kdeconnect.plugins.runcommand.CommandEntry
import org.kde.kdeconnect.plugins.runcommand.RunCommandOutput
import org.kde.kdeconnect.ui.compose.KdeTheme
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

class TaskManagerActivity : AppCompatActivity() {

    private lateinit var deviceId: String
    private val systemStatsState = mutableStateOf<PcSystemStats?>(null)
    private val processList = mutableStateListOf<PcProcessItem>()
    private val serviceList = mutableStateListOf<PcServiceItem>()
    private val appList = mutableStateListOf<CachyAppItem>()
    private val commandList = mutableStateListOf<CommandEntry>()
    private val isRefreshing = mutableStateOf(false)
    private val connectionMode = mutableStateOf("Connecting...")
    private val isSshConnectedState = mutableStateOf(false)
    private val autoRefreshIntervalMs = mutableStateOf(2000L)
    val analyticsDataState = mutableStateOf<AnalyticsDashboardData?>(null)
    val isAnalyticsLoading = mutableStateOf(false)
    val selectedAnalyticsMetric = mutableStateOf("data")
    val selectedAnalyticsCategory = mutableStateOf("apps")
    val selectedAnalyticsTimeframe = mutableStateOf("1d")
    val selectedAnalyticsStartDate = mutableStateOf("")
    val selectedAnalyticsEndDate = mutableStateOf("")

    private val isLoopRunning = AtomicBoolean(false)
    private var autoRefreshThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        deviceId = intent.getStringExtra("deviceId") ?: ""
        if (deviceId.isEmpty()) {
            finish()
            return
        }

        val device = KdeConnect.getInstance().getDevice(deviceId)
        val deviceName = device?.name ?: "Remote Device"

        updateRunCommands()

        setContent {
            KdeTheme(this@TaskManagerActivity) {
                TaskManagerScreen(
                    deviceName = deviceName,
                    onBackPressedDispatcher = onBackPressedDispatcher,
                    stats = systemStatsState.value,
                    processes = processList,
                    services = serviceList,
                    apps = appList,
                    analyticsData = analyticsDataState.value,
                    isAnalyticsLoading = isAnalyticsLoading.value,
                    isRefreshing = isRefreshing.value,
                    connectionMode = connectionMode.value,
                    isSshConnected = isSshConnectedState.value,
                    autoRefreshIntervalMs = autoRefreshIntervalMs.value,
                    onSetRefreshInterval = { interval -> autoRefreshIntervalMs.value = interval },
                    onRefresh = {
                        fetchRemoteStatsAsync()
                        fetchAnalyticsAsync()
                    },
                    onFetchAnalytics = { metric, category, timeframe, startDate, endDate ->
                        fetchAnalyticsAsync(metric, category, timeframe, startDate, endDate)
                    },
                    onConnectSsh = {
                        val host = getTargetHost()
                        val saved = SshManager.getSavedCredentials(this@TaskManagerActivity, deviceId)
                        if (saved != null && saved.user.isNotEmpty()) {
                            val effectiveCreds = if (host.isNotEmpty() && host != "127.0.0.1") saved.copy(host = host) else saved
                            val progress = com.google.android.material.dialog.MaterialAlertDialogBuilder(this@TaskManagerActivity)
                                .setTitle("Connecting to SSH")
                                .setMessage("Authenticating with ${effectiveCreds.user}@${effectiveCreds.host}:${effectiveCreds.port}...")
                                .setCancelable(false)
                                .create()
                            progress.show()

                            SshManager.connect(deviceId, effectiveCreds) { success, error ->
                                progress.dismiss()
                                isSshConnectedState.value = success
                                if (success) {
                                    Toast.makeText(this@TaskManagerActivity, "SSH Connected Successfully!", Toast.LENGTH_SHORT).show()
                                    fetchRemoteStatsAsync()
                                } else {
                                    Toast.makeText(this@TaskManagerActivity, "Auto-reconnect failed: $error", Toast.LENGTH_LONG).show()
                                    SshManager.showSshDialog(this@TaskManagerActivity, deviceId, host) {
                                        isSshConnectedState.value = SshManager.isConnected(deviceId)
                                        fetchRemoteStatsAsync()
                                    }
                                }
                            }
                        } else {
                            SshManager.showSshDialog(this@TaskManagerActivity, deviceId, host) {
                                isSshConnectedState.value = SshManager.isConnected(deviceId)
                                fetchRemoteStatsAsync()
                            }
                        }
                    },
                    onKillProcess = { proc -> executeProcessAction(proc, "kill") },
                    onKillParentProcess = { proc -> executeProcessAction(proc, "kill_parent") },
                    onForceKillProcess = { proc -> executeProcessAction(proc, "force_kill") },
                    onPauseProcess = { proc -> executeProcessAction(proc, "pause") },
                    onResumeProcess = { proc -> executeProcessAction(proc, "resume") },
                    onStartService = { srv -> executeServiceAction(srv, "start") },
                    onStopService = { srv -> executeServiceAction(srv, "stop") },
                    onRestartService = { srv -> executeServiceAction(srv, "restart") },
                    onLaunchApp = { app -> launchCachyApp(app) },
                    onTerminateApp = { app -> terminateCachyApp(app) },
                    commands = commandList,
                    commandOutputs = emptyList(),
                    isCommandRunning = false,
                    onRunCommand = { cmd -> executeRunCommand(cmd) },
                    onExecuteCustomCommand = { cmd -> executeCustomCommand(cmd) },
                    onStopCommand = { },
                    onClearCommandOutput = { },
                    onCopyCommandToClipboard = { cmd -> copyCommandToClipboard(cmd) },
                    onAddPhoneCommand = { name, cmd -> addPhoneCommand(name, cmd) },
                    onEditPhoneCommand = { key, name, cmd -> editPhoneCommand(key, name, cmd) },
                    onDeletePhoneCommand = { key -> deletePhoneCommand(key) },
                    onMovePhoneCommand = { from, to -> movePhoneCommand(from, to) },
                    onReorderPhoneCommands = { list -> reorderPhoneCommands(list) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isSshConnectedState.value = SshManager.isConnected(deviceId)
        updateRunCommands()
        fetchAnalyticsAsync()
        startAutoRefreshLoop()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private val PREF_PHONE_COMMANDS_FILE = "phone_run_commands_db"
    private val PREF_PHONE_COMMANDS_KEY = "saved_commands_"

    private fun getSavedPhoneCommands(): List<CommandEntry> {
        val prefs = getSharedPreferences(PREF_PHONE_COMMANDS_FILE, MODE_PRIVATE)
        val jsonStr = prefs.getString(PREF_PHONE_COMMANDS_KEY + deviceId, null)
        if (!jsonStr.isNullOrEmpty()) {
            try {
                val arr = JSONArray(jsonStr)
                val list = mutableListOf<CommandEntry>()
                for (i in 0 until arr.length()) {
                    list.add(CommandEntry(arr.getJSONObject(i)))
                }
                return list
            } catch (e: Exception) {
                KdeLog.e(KdeLog.LogTag.TASK_MANAGER, "Error reading saved phone commands", e.message ?: "")
            }
        }
        return emptyList()
    }

    private fun savePhoneCommands(list: List<CommandEntry>) {
        val prefs = getSharedPreferences(PREF_PHONE_COMMANDS_FILE, MODE_PRIVATE)
        val arr = JSONArray()
        for (cmd in list) {
            val o = JSONObject().apply {
                put("key", cmd.key)
                put("name", cmd.name)
                put("command", cmd.command)
            }
            arr.put(o)
        }
        prefs.edit().putString(PREF_PHONE_COMMANDS_KEY + deviceId, arr.toString()).apply()
    }

    private fun updateRunCommands() {
        val local = getSavedPhoneCommands()
        if (local.isNotEmpty() && local.size > 8) {
            commandList.clear()
            commandList.addAll(local)
        } else {
            // Initial default phone commands
            val defaults = listOf(
                CommandEntry("A1) System Shut Down", "systemctl poweroff", "cmd_a1"),
                CommandEntry("A2) System Restart", "reboot", "cmd_a2"),
                CommandEntry("A3) System Lock", "loginctl lock-session", "cmd_a3"),
                CommandEntry("A4) Task Manager", "missioncenter 2>/dev/null || plasma-systemmonitor 2>/dev/null || ksysguard 2>/dev/null", "cmd_a4"),
                CommandEntry("B1) Close Active Window (Soft)", "ydotool key 56:1 62:1 62:0 56:0 2>/dev/null || qdbus6 org.kde.KWin /KWin org.kde.KWin.killWindow 2>/dev/null", "cmd_b1"),
                CommandEntry("B2) Close Active Window (Forced)", "bash -c 'qdbus6 org.kde.KWin /KWin org.kde.KWin.killWindow 2>/dev/null || ydotool key 29:1 56:1 1:1 1:0 56:0 29:0 2>/dev/null'", "cmd_b2"),
                CommandEntry("B3) Close All Windows (Soft)", "bash -c 'F=\$(mktemp /tmp/kwin_XXXXXX.js); echo \"for (const w of workspace.windowList()) { if (w.normalWindow && !w.specialWindow) w.closeWindow(); }\" > \"\$F\"; qdbus6 org.kde.KWin /Scripting loadScript \"\$F\" >/dev/null; qdbus6 org.kde.KWin /Scripting start >/dev/null; rm -f \"\$F\"'", "cmd_b3"),
                CommandEntry("B4) Close All Windows (Force)", "bash -c 'F=\$(mktemp /tmp/kwin_XXXXXX.js); echo \"for (const w of workspace.windowList()) { if (w.normalWindow && !w.specialWindow && w.pid > 0) console.log(\\\"FKPID:\\\" + w.pid); }\" > \"\$F\"; S=\$(date \"+%Y-%m-%d %H:%M:%S\"); qdbus6 org.kde.KWin /Scripting loadScript \"\$F\" >/dev/null; qdbus6 org.kde.KWin /Scripting start >/dev/null; sleep 0.2; PIDS=\$(journalctl --user -b --since \"\$S\" -o cat 2>/dev/null | grep 'FKPID:' | sed \"s/.*FKPID://\" | grep -E \"^[0-9]+$\" | sort -u | tr \"\\n\" \" \"); rm -f \"\$F\"; [ -n \"\$PIDS\" ] && kill -9 \$PIDS 2>/dev/null'", "cmd_b4"),
                CommandEntry("B5) Show Desktop", "ydotool key 125:1 32:1 32:0 125:0 2>/dev/null || qdbus6 org.kde.kglobalaccel /component/kwin org.kde.kglobalaccel.Component.invokeShortcut 'Show Desktop' 2>/dev/null", "cmd_b5"),
                CommandEntry("B6) Alt + Tab", "ydotool key 56:1 15:1 15:0 56:0 2>/dev/null || qdbus6 org.kde.kglobalaccel /component/kwin org.kde.kglobalaccel.Component.invokeShortcut 'Walk Through Windows' 2>/dev/null", "cmd_b6"),
                CommandEntry("C1) Volume Up", "wpctl set-volume @DEFAULT_AUDIO_SINK@ 5%+ 2>/dev/null || pactl set-sink-volume @DEFAULT_SINK@ +5% 2>/dev/null || amixer set Master 5%+ 2>/dev/null", "cmd_c1"),
                CommandEntry("C2) Volume Down", "wpctl set-volume @DEFAULT_AUDIO_SINK@ 5%- 2>/dev/null || pactl set-sink-volume @DEFAULT_SINK@ -5% 2>/dev/null || amixer set Master 5%- 2>/dev/null", "cmd_c2"),
                CommandEntry("C3) Brightness Up", "brightnessctl set 15%+ 2>/dev/null || ddcutil setvcp 10 + 15 2>/dev/null", "cmd_c3"),
                CommandEntry("C4) Brightness Down", "brightnessctl set 15%- 2>/dev/null || ddcutil setvcp 10 - 15 2>/dev/null", "cmd_c4"),
                CommandEntry("C5) Backlight On", "brightnessctl -d '*kbd_backlight*' set 100% 2>/dev/null || brightnessctl -d asus::kbd_backlight set 3 2>/dev/null", "cmd_c5"),
                CommandEntry("C6) Backlight Off", "brightnessctl -d '*kbd_backlight*' set 0% 2>/dev/null || brightnessctl -d asus::kbd_backlight set 0 2>/dev/null", "cmd_c6"),
                CommandEntry("D2) Screenshot", "mkdir -p ~/Pictures/Screenshots && (spectacle -b -n -o ~/Pictures/Screenshots/\$(date +%F_%H-%M-%S).png 2>/dev/null || grim ~/Pictures/Screenshots/\$(date +%F_%H-%M-%S).png 2>/dev/null || import -window root ~/Pictures/Screenshots/\$(date +%F_%H-%M-%S).png 2>/dev/null)", "cmd_d2"),
                CommandEntry("D3) Restart KDE Plasma", "kquitapp6 plasmashell 2>/dev/null; sleep 0.5; kstart plasmashell 2>/dev/null &", "cmd_d3"),
                CommandEntry("E1) Apache, Mysql Start", "sudo systemctl start mariadb httpd 2>/dev/null || sudo systemctl start apache2 mysql 2>/dev/null", "cmd_e1"),
                CommandEntry("E2) Apache, Mysql Stop", "sudo systemctl stop mariadb httpd 2>/dev/null || sudo systemctl stop apache2 mysql 2>/dev/null", "cmd_e2"),
                CommandEntry("E3) Bluetooth Stop", "bluetoothctl power off", "cmd_e3"),
                CommandEntry("E4) Bluetooth Start", "bluetoothctl power on", "cmd_e4"),
                CommandEntry("F1) Spotify", "setsid -f spotify-launcher >/dev/null 2>&1 || setsid -f spotify >/dev/null 2>&1 &", "cmd_f1"),
                CommandEntry("Screenshot + Send", "bash -c 'mkdir -p ~/Pictures/Screenshots; T=~/Pictures/Screenshots/Screenshot_\$(date +%Y%m%d_%H%M%S).png; (spectacle -b -n -o \$T 2>/dev/null || grim \$T 2>/dev/null || import -window root \$T 2>/dev/null); DEV=\$(kdeconnect-cli -a --id-only 2>/dev/null | head -n1); [ -n \"\$DEV\" ] && kdeconnect-cli -d \"\$DEV\" --share \$T 2>/dev/null'", "cmd_ss_send"),
                CommandEntry("Get Clipboard", "bash -c 'DEV=\$(kdeconnect-cli -a --id-only 2>/dev/null | head -n1); CLIP=\$(wl-paste 2>/dev/null || xclip -selection clipboard -o 2>/dev/null || xsel -b -o 2>/dev/null); [ -n \"\$DEV\" ] && [ -n \"\$CLIP\" ] && kdeconnect-cli -d \"\$DEV\" --share-text \"\$CLIP\"'", "cmd_get_clip")
            )
            savePhoneCommands(defaults)
            commandList.clear()
            commandList.addAll(defaults)
        }
    }

    private fun addPhoneCommand(name: String, command: String) {
        val key = "phone_cmd_" + System.currentTimeMillis()
        val newEntry = CommandEntry(name, command, key)
        val current = getSavedPhoneCommands().toMutableList()
        current.add(0, newEntry)
        savePhoneCommands(current)
        commandList.clear()
        commandList.addAll(current)
        Toast.makeText(this, "Saved on phone: $name", Toast.LENGTH_SHORT).show()
    }

    private fun editPhoneCommand(key: String, newName: String, newCommand: String) {
        val current = getSavedPhoneCommands().toMutableList()
        val index = current.indexOfFirst { it.key == key }
        if (index >= 0) {
            current[index] = CommandEntry(newName, newCommand, key)
            savePhoneCommands(current)
            commandList.clear()
            commandList.addAll(current)
            Toast.makeText(this, "Updated on phone: $newName", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deletePhoneCommand(key: String) {
        val current = getSavedPhoneCommands().toMutableList()
        val removed = current.removeAll { it.key == key }
        if (removed) {
            savePhoneCommands(current)
            commandList.clear()
            commandList.addAll(current)
            Toast.makeText(this, "Deleted from phone", Toast.LENGTH_SHORT).show()
        }
    }

    private fun reorderPhoneCommands(newList: List<CommandEntry>) {
        savePhoneCommands(newList)
        commandList.clear()
        commandList.addAll(newList)
    }

    private fun movePhoneCommand(fromIndex: Int, toIndex: Int) {
        val current = getSavedPhoneCommands().toMutableList()
        if (fromIndex in current.indices && toIndex in current.indices) {
            val item = current.removeAt(fromIndex)
            current.add(toIndex, item)
            savePhoneCommands(current)
            commandList.clear()
            commandList.addAll(current)
        }
    }

    private fun executeRunCommand(command: CommandEntry) {
        sendActionCommand(command.command, command.name)
    }

    private fun executeCustomCommand(cmd: String) {
        if (cmd.isBlank()) return
        sendActionCommand(cmd, "Custom Shell")
    }

    private fun copyCommandToClipboard(command: CommandEntry) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Command", command.command)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this@TaskManagerActivity, "Copied: ${command.name}", Toast.LENGTH_SHORT).show()
    }

    override fun onPause() {
        stopAutoRefreshLoop()
        super.onPause()
    }

    private fun startAutoRefreshLoop() {
        if (isLoopRunning.getAndSet(true)) return
        autoRefreshThread = Thread({
            while (isLoopRunning.get()) {
                fetchRemoteStatsAsync()
                val interval = autoRefreshIntervalMs.value
                if (interval <= 0) {
                    try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                    continue
                }
                try {
                    Thread.sleep(interval)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "TaskManagerAutoRefresh")
        autoRefreshThread?.isDaemon = true
        autoRefreshThread?.start()
    }

    private fun stopAutoRefreshLoop() {
        isLoopRunning.set(false)
        autoRefreshThread?.interrupt()
        autoRefreshThread = null
    }

    private fun getTargetHost(): String {
        val device = KdeConnect.getInstance().getDevice(deviceId)
        return device?.getRemoteIpAddress() ?: "127.0.0.1"
    }

    private fun fetchRemoteStatsAsync() {
        val host = getTargetHost()
        isRefreshing.value = true

        ThreadHelper.execute {
            // 1. Try Python HTTP Daemon
            var success = false
            try {
                val url = URL("http://$host:59001/task_manager_stats")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 2500
                    readTimeout = 3000
                    requestMethod = "GET"
                }

                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val json = JSONObject(resp)
                    success = parseStatsJson(json, "Live Daemon (HTTP:59001)")
                }
            } catch (e: Exception) {
                KdeLog.d(KdeLog.LogTag.TASK_MANAGER, "HTTP Daemon not reachable: ${e.message}")
            }

            if (success) return@execute

            // 2. Fallback: Try SSH execution if connected
            if (SshManager.isConnected(deviceId)) {
                val cmd = "curl -s http://127.0.0.1:59001/task_manager_stats"
                SshManager.executeCommand(deviceId, cmd, { out, err ->
                    if (out.isNotEmpty()) {
                        try {
                            val json = JSONObject(out)
                            parseStatsJson(json, "SSH Secure Channel")
                        } catch (e: Exception) {
                            runOnUiThread {
                                isRefreshing.value = false
                                connectionMode.value = "SSH Parse Error"
                            }
                        }
                    } else {
                        runOnUiThread {
                            isRefreshing.value = false
                            connectionMode.value = "SSH Error: $err"
                        }
                    }
                }, {
                    runOnUiThread {
                        isRefreshing.value = false
                    }
                })
            } else {
                runOnUiThread {
                    isRefreshing.value = false
                    connectionMode.value = "Disconnected"
                }
            }
        }
    }

    fun fetchAnalyticsAsync(
        metric: String = selectedAnalyticsMetric.value,
        category: String = selectedAnalyticsCategory.value,
        timeframe: String = selectedAnalyticsTimeframe.value,
        startDate: String = selectedAnalyticsStartDate.value,
        endDate: String = selectedAnalyticsEndDate.value
    ) {
        val host = getTargetHost()
        if (host.isEmpty()) return

        isAnalyticsLoading.value = true
        selectedAnalyticsMetric.value = metric
        selectedAnalyticsCategory.value = category
        selectedAnalyticsTimeframe.value = timeframe
        selectedAnalyticsStartDate.value = startDate
        selectedAnalyticsEndDate.value = endDate

        ThreadHelper.execute {
            try {
                val qMetric = URLEncoder.encode(metric, "UTF-8")
                val qCat = URLEncoder.encode(category, "UTF-8")
                val qTf = URLEncoder.encode(timeframe, "UTF-8")
                val qSt = URLEncoder.encode(startDate, "UTF-8")
                val qEnd = URLEncoder.encode(endDate, "UTF-8")
                val urlStr = "http://$host:59001/task_manager_analytics?metric=$qMetric&category=$qCat&timeframe=$qTf&start_date=$qSt&end_date=$qEnd"
                val url = URL(urlStr)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 5000
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)

                    val respMetric = json.optString("metric", metric)
                    val respCat = json.optString("category", category)
                    val respTf = json.optString("timeframe", timeframe)
                    val totalVal = json.optDouble("total_value", 0.0)
                    val totalDisp = json.optString("total_display", "0")

                    val barsJson = json.optJSONArray("bars") ?: JSONArray()
                    val bars = mutableListOf<AnalyticsBarItem>()
                    for (i in 0 until barsJson.length()) {
                        val b = barsJson.getJSONObject(i)
                        bars.add(AnalyticsBarItem(
                            label = b.optString("label", ""),
                            timestamp = b.optLong("timestamp", 0L),
                            value = b.optDouble("value", 0.0),
                            secondaryValue = b.optDouble("secondary_value", 0.0)
                        ))
                    }

                    val itemsJson = json.optJSONArray("items") ?: JSONArray()
                    val items = mutableListOf<AnalyticsRankItem>()
                    for (i in 0 until itemsJson.length()) {
                        val item = itemsJson.getJSONObject(i)
                        items.add(AnalyticsRankItem(
                            id = item.optString("id", ""),
                            name = item.optString("name", ""),
                            category = item.optString("category", ""),
                            value = item.optDouble("value", 0.0),
                            displayValue = item.optString("display_value", ""),
                            percent = item.optDouble("percent", 0.0).toFloat(),
                            icon = item.optString("icon", "")
                        ))
                    }

                    val dashboardData = AnalyticsDashboardData(
                        metric = respMetric,
                        category = respCat,
                        timeframe = respTf,
                        totalValue = totalVal,
                        totalDisplay = totalDisp,
                        bars = bars,
                        items = items
                    )

                    runOnUiThread {
                        analyticsDataState.value = dashboardData
                        isAnalyticsLoading.value = false
                    }
                } else {
                    runOnUiThread { isAnalyticsLoading.value = false }
                }
            } catch (e: Exception) {
                KdeLog.d(KdeLog.LogTag.TASK_MANAGER, "Analytics fetch error: ${e.message}")
                runOnUiThread { isAnalyticsLoading.value = false }
            }
        }
    }

    private fun parseStatsJson(json: JSONObject, source: String): Boolean {
        try {
            val ifaceJson = json.optJSONArray("interfaces") ?: JSONArray()
            val ifaceList = mutableListOf<NetworkInterfaceItem>()
            for (i in 0 until ifaceJson.length()) {
                val o = ifaceJson.getJSONObject(i)
                ifaceList.add(NetworkInterfaceItem(
                    name = o.optString("name", "eth0"),
                    rxMb = o.optInt("rx_mb", 0),
                    txMb = o.optInt("tx_mb", 0)
                ))
            }

            val disksJson = json.optJSONArray("disks") ?: JSONArray()
            val diskList = mutableListOf<DiskPartitionItem>()
            for (i in 0 until disksJson.length()) {
                val o = disksJson.getJSONObject(i)
                diskList.add(DiskPartitionItem(
                    mount = o.optString("mount", "/"),
                    fs = o.optString("fs", o.optString("filesystem", "")),
                    totalGb = o.optDouble("total_gb", 0.0),
                    usedGb = o.optDouble("used_gb", 0.0),
                    freeGb = o.optDouble("free_gb", 0.0),
                    percent = o.optInt("percent", 0)
                ))
            }

            val gpuObj = json.optJSONObject("gpu")
            val gpuItem = if (gpuObj != null) {
                GpuStatsItem(
                    name = gpuObj.optString("name", "GPU"),
                    util = gpuObj.optDouble("util", gpuObj.optDouble("usage", 0.0)),
                    memUsedMb = gpuObj.optInt("mem_used_mb", 0),
                    memTotalMb = gpuObj.optInt("mem_total_mb", 0)
                )
            } else GpuStatsItem()

            val parsedStats = PcSystemStats(
                cpu = json.optDouble("cpu", 0.0),
                cpuTemp = json.optDouble("cpu_temp", 0.0),
                load1 = json.optDouble("load1", 0.0),
                load5 = json.optDouble("load5", 0.0),
                load15 = json.optDouble("load15", 0.0),
                memUsed = json.optInt("mem_used", 0),
                memTotal = json.optInt("mem_total", 0),
                memFree = json.optInt("mem_free", 0),
                memCached = json.optInt("mem_cached", 0),
                swapUsed = json.optInt("swap_used", 0),
                swapTotal = json.optInt("swap_total", 0),
                disks = diskList,
                gpu = gpuItem,
                uptime = json.optString("uptime", "--"),
                bootTime = json.optString("boot_time", "--"),
                cpuModel = json.optString("cpu_model", "x86_64 CPU"),
                cpuCores = json.optInt("cpu_cores", 1),
                osName = json.optString("os_name", "Linux"),
                kernel = json.optString("kernel", "--"),
                hostname = json.optString("hostname", "--"),
                netRx = json.optLong("net_rx", 0L),
                netTx = json.optLong("net_tx", 0L),
                interfaces = ifaceList
            )

            val procsJson = json.optJSONArray("processes") ?: JSONArray()
            val newProcs = mutableListOf<PcProcessItem>()
            for (i in 0 until procsJson.length()) {
                val o = procsJson.getJSONObject(i)
                val rx = o.optDouble("data_rx_mb", 0.0)
                val tx = o.optDouble("data_tx_mb", 0.0)
                newProcs.add(PcProcessItem(
                    pid = o.optInt("pid", 0),
                    ppid = o.optInt("ppid", 0),
                    user = o.optString("user", "user"),
                    cpu = o.optDouble("cpu", 0.0),
                    memMb = o.optInt("mem_mb", 0),
                    state = o.optString("state", "R"),
                    name = o.optString("name", "proc"),
                    cmd = o.optString("cmd", ""),
                    dataRxMb = rx,
                    dataTxMb = tx,
                    dataTotalMb = o.optDouble("data_total_mb", rx + tx)
                ))
            }

            val srvsJson = json.optJSONArray("services") ?: JSONArray()
            val newSrvs = mutableListOf<PcServiceItem>()
            for (i in 0 until srvsJson.length()) {
                val o = srvsJson.getJSONObject(i)
                val sRx = o.optDouble("data_rx_mb", 0.0)
                val sTx = o.optDouble("data_tx_mb", 0.0)
                newSrvs.add(PcServiceItem(
                    unitName = o.optString("unit", ""),
                    loadState = o.optString("load", ""),
                    activeState = o.optString("active", ""),
                    subState = o.optString("sub", ""),
                    description = o.optString("desc", ""),
                    isSystem = o.optBoolean("is_system", true),
                    dataRxMb = sRx,
                    dataTxMb = sTx,
                    dataTotalMb = o.optDouble("data_total_mb", sRx + sTx)
                ))
            }

            val appsJson = json.optJSONArray("apps") ?: JSONArray()
            val newApps = mutableListOf<CachyAppItem>()
            for (i in 0 until appsJson.length()) {
                val o = appsJson.getJSONObject(i)
                val aRx = o.optDouble("data_rx_mb", 0.0)
                val aTx = o.optDouble("data_tx_mb", 0.0)
                newApps.add(CachyAppItem(
                    name = o.optString("name", ""),
                    packageId = o.optString("package", ""),
                    source = o.optString("source", "Pacman"),
                    execCmd = o.optString("exec", ""),
                    icon = o.optString("icon", ""),
                    categories = o.optString("categories", ""),
                    isRunning = o.optBoolean("is_running", false),
                    cpu = o.optDouble("cpu", 0.0),
                    memMb = o.optInt("mem_mb", 0),
                    dataRxMb = aRx,
                    dataTxMb = aTx,
                    dataTotalMb = o.optDouble("data_total_mb", aRx + aTx),
                    activeScreenSec = o.optLong("active_screen_sec", 0L),
                    backgroundSec = o.optLong("background_sec", 0L),
                    batteryPercent = o.optDouble("battery_percent", 0.0)
                ))
            }

            runOnUiThread {
                systemStatsState.value = parsedStats
                processList.clear()
                processList.addAll(newProcs)
                serviceList.clear()
                serviceList.addAll(newSrvs)
                appList.clear()
                appList.addAll(newApps)
                isRefreshing.value = false
                connectionMode.value = source
                KdeLog.taskManager("Updated Stats ($source)", "CPU: ${parsedStats.cpu}%, RAM: ${parsedStats.memUsed}/${parsedStats.memTotal} MB")
            }
            return true
        } catch (e: Exception) {
            KdeLog.e(KdeLog.LogTag.TASK_MANAGER, "Parse stats error", e.message ?: "")
            runOnUiThread { isRefreshing.value = false }
            return false
        }
    }

    private fun sendActionCommand(cmd: String, displayMessage: String) {
        val host = getTargetHost()
        if (host.isEmpty()) {
            Toast.makeText(this@TaskManagerActivity, "Device host IP not found", Toast.LENGTH_SHORT).show()
            return
        }

        ThreadHelper.execute {
            var executed = false
            var serverResult = ""

            // 1. Try POST to HTTP Daemon
            try {
                val enc = URLEncoder.encode(cmd, "UTF-8")
                val url = URL("http://$host:59001/task_manager_action?cmd=$enc")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 4000
                    requestMethod = "POST"
                    doOutput = true
                    doInput = true
                }
                conn.outputStream.use { it.write(cmd.toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode in 200..299) {
                    serverResult = conn.inputStream.bufferedReader().use { it.readText() }
                    executed = true
                }
            } catch (_: Exception) {}

            // 2. Fallback to GET to HTTP Daemon
            if (!executed) {
                try {
                    val enc = URLEncoder.encode(cmd, "UTF-8")
                    val url = URL("http://$host:59001/task_manager_action?cmd=$enc")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 3000
                        readTimeout = 4000
                        requestMethod = "GET"
                        doInput = true
                    }
                    if (conn.responseCode in 200..299) {
                        serverResult = conn.inputStream.bufferedReader().use { it.readText() }
                        executed = true
                    }
                } catch (_: Exception) {}
            }

            if (executed) {
                runOnUiThread {
                    Toast.makeText(this@TaskManagerActivity, "$displayMessage: $serverResult", Toast.LENGTH_SHORT).show()
                    fetchRemoteStatsAsync()
                    fetchAnalyticsAsync()
                }
                return@execute
            }

            // 3. Fallback to SSH
            if (SshManager.isConnected(deviceId)) {
                SshManager.executeCommand(deviceId, cmd, { _, _ -> }, {
                    runOnUiThread {
                        Toast.makeText(this@TaskManagerActivity, "$displayMessage sent via SSH", Toast.LENGTH_SHORT).show()
                        fetchRemoteStatsAsync()
                        fetchAnalyticsAsync()
                    }
                })
            } else {
                runOnUiThread {
                    Toast.makeText(this@TaskManagerActivity, "Failed to reach host at $host:59001", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun executeProcessAction(proc: PcProcessItem, action: String) {
        val cmd = when (action) {
            "kill" -> "kill -15 ${proc.pid}"
            "kill_parent" -> if (proc.ppid > 1) "kill -15 ${proc.ppid}" else "kill -15 ${proc.pid}"
            "force_kill" -> "kill -9 ${proc.pid}"
            "pause" -> "kill -STOP ${proc.pid}"
            "resume" -> "kill -CONT ${proc.pid}"
            else -> return
        }
        val label = when (action) {
            "kill" -> "Terminate ${proc.name}"
            "kill_parent" -> "Kill Process Group ${proc.name}"
            "force_kill" -> "Force Kill ${proc.name}"
            "pause" -> "Suspended ${proc.name}"
            "resume" -> "Resumed ${proc.name}"
            else -> "Action"
        }
        sendActionCommand(cmd, label)
    }

    private fun executeServiceAction(srv: PcServiceItem, action: String) {
        val cmd = when (action) {
            "start" -> "systemctl start ${srv.unitName}"
            "stop" -> "systemctl stop ${srv.unitName}"
            "restart" -> "systemctl restart ${srv.unitName}"
            else -> return
        }
        val label = "${action.capitalize()} ${srv.unitName}"
        sendActionCommand(cmd, label)
    }

    private fun launchCachyApp(app: CachyAppItem) {
        if (app.execCmd.isEmpty()) return
        val cmd = "nohup ${app.execCmd} >/dev/null 2>&1 &"
        sendActionCommand(cmd, "Launched ${app.name}")
    }

    private fun terminateCachyApp(app: CachyAppItem) {
        val target = if (app.packageId.isNotEmpty()) app.packageId else app.name
        val cmd = "terminate_app $target"
        sendActionCommand(cmd, "Terminated ${app.name}")
    }
}
