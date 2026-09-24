/*
 * SPDX-FileCopyrightText: 2026 Antigravity
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.taskmanager

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.material.icons.filled.Menu


import androidx.compose.ui.res.painterResource
import org.kde.kdeconnect_tp.R
import androidx.compose.material.icons.filled.Delete

import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import org.kde.kdeconnect.plugins.runcommand.CommandEntry
import org.kde.kdeconnect.plugins.runcommand.RunCommandOutput
import org.kde.kdeconnect.plugins.runcommand.RunCommandStatus

import androidx.activity.OnBackPressedDispatcher
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.OutlinedButton

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.kde.kdeconnect.ui.compose.KdeTopAppBar

@Composable
fun TaskManagerScreen(
    deviceName: String,
    onBackPressedDispatcher: OnBackPressedDispatcher?,
    stats: PcSystemStats?,
    processes: List<PcProcessItem>,
    services: List<PcServiceItem>,
    apps: List<CachyAppItem>,
    analyticsData: AnalyticsDashboardData?,
    isAnalyticsLoading: Boolean,
    isRefreshing: Boolean,
    connectionMode: String,
    isSshConnected: Boolean,
    autoRefreshIntervalMs: Long,
    onSetRefreshInterval: (Long) -> Unit,
    onRefresh: () -> Unit,
    onFetchAnalytics: (metric: String, category: String, timeframe: String, startDate: String, endDate: String) -> Unit,
    onConnectSsh: () -> Unit,
    onKillProcess: (PcProcessItem) -> Unit,
    onKillParentProcess: (PcProcessItem) -> Unit,
    onForceKillProcess: (PcProcessItem) -> Unit,
    onPauseProcess: (PcProcessItem) -> Unit,
    onResumeProcess: (PcProcessItem) -> Unit,
    onStartService: (PcServiceItem) -> Unit,
    onStopService: (PcServiceItem) -> Unit,
    onRestartService: (PcServiceItem) -> Unit,
    onLaunchApp: (CachyAppItem) -> Unit,
    onTerminateApp: (CachyAppItem) -> Unit,
    commands: List<CommandEntry> = emptyList(),
    commandOutputs: List<RunCommandOutput> = emptyList(),
    isCommandRunning: Boolean = false,
    onRunCommand: (CommandEntry) -> Unit = {},
    onExecuteCustomCommand: (String) -> Unit = {},
    onStopCommand: () -> Unit = {},
    onClearCommandOutput: () -> Unit = {},
    onCopyCommandToClipboard: (CommandEntry) -> Unit = {},
    onAddPhoneCommand: (name: String, command: String) -> Unit = { _, _ -> },
    onEditPhoneCommand: (key: String, newName: String, newCommand: String) -> Unit = { _, _, _ -> },
    onDeletePhoneCommand: (key: String) -> Unit,
    onMovePhoneCommand: (fromIndex: Int, toIndex: Int) -> Unit = { _, _ -> },
    onReorderPhoneCommands: (List<CommandEntry>) -> Unit = {}
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var showRefreshMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            KdeTopAppBar(
                title = "Live Task Manager",
                subTitle = deviceName,
                navIconOnClick = { onBackPressedDispatcher?.onBackPressed() },
                actions = {
                    IconButton(onClick = onConnectSsh) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "SSH Configuration",
                            tint = if (isSshConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Now")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Live Status & Refresh Rate Ribbon
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SuggestionChip(
                    onClick = onConnectSsh,
                    label = {
                        Text(
                            text = connectionMode,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium
                        )
                    },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = if (stats != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                    )
                )

                Box {
                    OutlinedButton(
                        onClick = { showRefreshMenu = true },
                        modifier = Modifier.height(32.dp),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Text(
                            text = if (autoRefreshIntervalMs > 0) "${autoRefreshIntervalMs / 1000}s Auto" else "Manual",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    DropdownMenu(
                        expanded = showRefreshMenu,
                        onDismissRequest = { showRefreshMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Default (2s)") },
                            onClick = {
                                onSetRefreshInterval(2000L)
                                showRefreshMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Fast (1s)") },
                            onClick = {
                                onSetRefreshInterval(1000L)
                                showRefreshMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Eco Mode (5s)") },
                            onClick = {
                                onSetRefreshInterval(5000L)
                                showRefreshMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Relaxed (15s)") },
                            onClick = {
                                onSetRefreshInterval(15000L)
                                showRefreshMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Manual Only") },
                            onClick = {
                                onSetRefreshInterval(0L)
                                showRefreshMenu = false
                            }
                        )
                    }
                }
            }

            // Material 3 Navigation Tabs
            val tabTitles = listOf(
                "Overview",
                "Processes (${processes.size})",
                "Services (${services.size})",
                "Apps (${apps.size})",
                if (commands.isNotEmpty()) "Run (${commands.size})" else "Run",
                "Statistics"
            )
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 16.dp,
                divider = {},
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        height = 3.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            ) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedTab == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Tab Viewport Content
            when (selectedTab) {
                0 -> AnalyticsTabContent(stats = stats, onConnectSsh = onConnectSsh)
                1 -> ProcessesTabContent(
                    processes = processes,
                    onKill = onKillProcess,
                    onKillParent = onKillParentProcess,
                    onForceKill = onForceKillProcess,
                    onPause = onPauseProcess,
                    onResume = onResumeProcess
                )
                2 -> ServicesTabContent(
                    services = services,
                    onStart = onStartService,
                    onStop = onStopService,
                    onRestart = onRestartService
                )
                3 -> AppsTabContent(
                    apps = apps,
                    onLaunch = onLaunchApp,
                    onTerminate = onTerminateApp
                )
                4 -> RunTabContent(
                    commands = commands,
                    outputs = commandOutputs,
                    isCommandRunning = isCommandRunning,
                    onRun = onRunCommand,
                    onExecuteCustom = onExecuteCustomCommand,
                    onStop = onStopCommand,
                    onClearOutput = onClearCommandOutput,
                    onCopy = onCopyCommandToClipboard,
                    onAddPhoneCommand = onAddPhoneCommand,
                    onEditPhoneCommand = onEditPhoneCommand,
                    onDeletePhoneCommand = onDeletePhoneCommand,
                    onMovePhoneCommand = onMovePhoneCommand,
                    onReorderPhoneCommands = onReorderPhoneCommands
                )
                5 -> StatisticsTabContent(
                    analyticsData = analyticsData,
                    isLoading = isAnalyticsLoading,
                    onFetchAnalytics = onFetchAnalytics
                )
            }
        }
    }
}

@Composable
private fun AnalyticsTabContent(stats: PcSystemStats?, onConnectSsh: () -> Unit) {
    if (stats == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "No System Data Received Yet",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Ensure screen_streamer.py is running on your PC or tap below to authenticate via SSH.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                FilledTonalButton(onClick = onConnectSsh) {
                    Text("Connect via SSH")
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. CPU & Thermal Analytics Card
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "CPU Utilization",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (stats.cpuTemp > 0.0) {
                                SuggestionChip(
                                    onClick = {},
                                    label = { Text("${stats.cpuTemp}°C", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) },
                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = if (stats.cpuTemp > 75.0) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
                                    )
                                )
                            }
                            Text(
                                text = "${stats.cpu}%",
                                style = MaterialTheme.typography.titleLarge,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    val animatedCpu by animateFloatAsState(
                        targetValue = (stats.cpu / 100.0).toFloat().coerceIn(0f, 1f),
                        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
                        label = "cpu_progress"
                    )

                    LinearProgressIndicator(
                        progress = { animatedCpu },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp)),
                        color = when {
                            stats.cpu > 85.0 -> MaterialTheme.colorScheme.error
                            stats.cpu > 60.0 -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        },
                        trackColor = MaterialTheme.colorScheme.surface
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Processor", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stats.cpuModel, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Cores / Threads", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${stats.cpuCores} Cores", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Load Averages (1m, 5m, 15m)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "${stats.load1}  •  ${stats.load5}  •  ${stats.load15}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // 2. RAM & Swap Memory Card
        item {
            val memTotal = if (stats.memTotal > 0) stats.memTotal else 1
            val memUsed = stats.memUsed
            val memPercent = ((memUsed.toDouble() / memTotal.toDouble()) * 100).toInt()
            val swapTotal = if (stats.swapTotal > 0) stats.swapTotal else 1
            val swapUsed = stats.swapUsed
            val swapPercent = ((swapUsed.toDouble() / swapTotal.toDouble()) * 100).toInt()

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Memory & Swap",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${(memUsed / 1024.0).format(1)} / ${(memTotal / 1024.0).format(1)} GB ($memPercent%)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    val animatedMem by animateFloatAsState(
                        targetValue = (memUsed.toFloat() / memTotal.toFloat()).coerceIn(0f, 1f),
                        animationSpec = tween(500),
                        label = "mem_progress"
                    )

                    LinearProgressIndicator(
                        progress = { animatedMem },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp)),
                        color = MaterialTheme.colorScheme.secondary,
                        trackColor = MaterialTheme.colorScheme.surface
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Swap
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Swap Space", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${(swapUsed / 1024.0).format(1)} / ${(swapTotal / 1024.0).format(1)} GB ($swapPercent%)",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { (swapUsed.toFloat() / swapTotal.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = MaterialTheme.colorScheme.tertiary,
                        trackColor = MaterialTheme.colorScheme.surface
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Cached: ${stats.memCached} MB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Free: ${stats.memFree} MB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // 3. GPU Analytics Card (if detected)
        if (stats.gpu.name.isNotEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Dedicated GPU",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${stats.gpu.util.toInt()}% Util",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stats.gpu.name,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { (stats.gpu.util / 100.0).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surface
                        )

                        if (stats.gpu.memTotalMb > 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("VRAM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    "${stats.gpu.memUsedMb} MB / ${stats.gpu.memTotalMb} MB",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. Storage & Disks Card
        if (stats.disks.isNotEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Storage & Drives",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        stats.disks.forEach { disk ->
                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = disk.mount,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${disk.usedGb} / ${disk.totalGb} GB (${disk.percent}%)",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = { (disk.percent / 100.0).toFloat().coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = if (disk.percent > 90) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${disk.freeGb} GB Free  •  ${disk.fs}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // 5. Network Traffic Card
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Network Analytics",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Total Download (Rx)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = formatMb(stats.netRx),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text("Total Upload (Tx)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = formatMb(stats.netTx),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }

                    if (stats.interfaces.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        stats.interfaces.forEach { iface ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(iface.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Rx: ${formatMb(iface.rxMb.toLong())}  •  Tx: ${formatMb(iface.txMb.toLong())}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // 6. System Diagnostics Card
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Host Diagnostics",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    InfoRow("Operating System", stats.osName)
                    InfoRow("Kernel Version", stats.kernel)
                    InfoRow("Hostname", stats.hostname)
                    InfoRow("System Uptime", stats.uptime)
                    InfoRow("Boot Timestamp", stats.bootTime)
                }
            }
        }
    }
}

private data class ProcessGroupItem(
    val name: String,
    val processes: List<PcProcessItem>,
    val totalCpu: Double,
    val totalMemMb: Int,
    val totalDataMb: Double,
    val mainUser: String
)

@Composable
private fun ProcessesTabContent(
    processes: List<PcProcessItem>,
    onKill: (PcProcessItem) -> Unit,
    onKillParent: (PcProcessItem) -> Unit,
    onForceKill: (PcProcessItem) -> Unit,
    onPause: (PcProcessItem) -> Unit,
    onResume: (PcProcessItem) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var sortBy by remember { mutableStateOf("CPU") }
    var isGrouped by remember { mutableStateOf(true) }
    var processToKill by remember { mutableStateOf<PcProcessItem?>(null) }
    var groupToKill by remember { mutableStateOf<ProcessGroupItem?>(null) }
    var parentToKill by remember { mutableStateOf<PcProcessItem?>(null) }
    var expandedPid by remember { mutableStateOf<Int?>(null) }
    var expandedGroups by remember { mutableStateOf(setOf<String>()) }

    val filtered = remember(processes, searchQuery, sortBy) {
        val q = searchQuery.trim()
        val list = if (q.isEmpty()) {
            processes
        } else {
            processes.filter {
                it.name.contains(q, ignoreCase = true) ||
                        it.user.contains(q, ignoreCase = true) ||
                        it.pid.toString().contains(q) ||
                        it.cmd.contains(q, ignoreCase = true)
            }
        }
        when (sortBy) {
            "CPU" -> list.sortedByDescending { it.cpu }
            "RAM" -> list.sortedByDescending { it.memMb }
            "Data" -> list.sortedByDescending { it.dataTotalMb }
            "Name" -> list.sortedBy { it.name.lowercase() }
            "PID" -> list.sortedBy { it.pid }
            else -> list
        }
    }

    val groupedList = remember(filtered, sortBy) {
        val map = linkedMapOf<String, MutableList<PcProcessItem>>()
        filtered.forEach { proc ->
            val key = proc.name.lowercase()
            map.getOrPut(key) { mutableListOf() }.add(proc)
        }
        val groups = map.map { (_, procs) ->
            ProcessGroupItem(
                name = procs.first().name,
                processes = procs,
                totalCpu = procs.sumOf { it.cpu },
                totalMemMb = procs.sumOf { it.memMb },
                totalDataMb = procs.sumOf { it.dataTotalMb },
                mainUser = procs.first().user
            )
        }
        when (sortBy) {
            "CPU" -> groups.sortedByDescending { it.totalCpu }
            "RAM" -> groups.sortedByDescending { it.totalMemMb }
            "Data" -> groups.sortedByDescending { it.totalDataMb }
            "Name" -> groups.sortedBy { it.name.lowercase() }
            "PID" -> groups.sortedBy { it.processes.minOfOrNull { p -> p.pid } ?: 0 }
            else -> groups
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Search & Sort Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search process, PID, user...", style = MaterialTheme.typography.bodyMedium) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
        }

        // Sort & Grouping Chips Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = isGrouped,
                onClick = { isGrouped = !isGrouped },
                label = { Text(if (isGrouped) "Grouped (${groupedList.size})" else "Flat List (${filtered.size})", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer
                )
            )

            listOf("CPU", "RAM", "Data", "Name", "PID").forEach { opt ->
                FilterChip(
                    selected = sortBy == opt,
                    onClick = { sortBy = opt },
                    label = { Text("Sort: $opt", style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }
        }

        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (searchQuery.isEmpty()) "No running processes found" else "No processes matching '$searchQuery'",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (isGrouped) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(groupedList, key = { idx, group -> "group_${group.name}_$idx" }) { _, group ->
                    if (group.processes.size == 1) {
                        val proc = group.processes.first()
                        ProcessItemCard(
                            proc = proc,
                            isExpanded = expandedPid == proc.pid,
                            onToggleExpand = {
                                expandedPid = if (expandedPid == proc.pid) null else proc.pid
                            },
                            onPromptKill = { processToKill = proc },
                            onKillParent = { parentToKill = proc },
                            onForceKill = { onForceKill(proc) },
                            onPause = { onPause(proc) },
                            onResume = { onResume(proc) }
                        )
                    } else {
                        val isExp = expandedGroups.contains(group.name)
                        ProcessGroupCard(
                            group = group,
                            isExpanded = isExp,
                            expandedPid = expandedPid,
                            onToggleExpand = {
                                expandedGroups = if (isExp) expandedGroups - group.name else expandedGroups + group.name
                            },
                            onToggleChildExpand = { pid ->
                                expandedPid = if (expandedPid == pid) null else pid
                            },
                            onPromptKillGroup = { groupToKill = group },
                            onPromptKillChild = { proc -> processToKill = proc },
                            onKillParent = { proc -> parentToKill = proc },
                            onForceKill = { proc -> onForceKill(proc) },
                            onPause = { proc -> onPause(proc) },
                            onResume = { proc -> onResume(proc) }
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered, key = { "proc_${it.pid}" }) { proc ->
                    ProcessItemCard(
                        proc = proc,
                        isExpanded = expandedPid == proc.pid,
                        onToggleExpand = {
                            expandedPid = if (expandedPid == proc.pid) null else proc.pid
                        },
                        onPromptKill = { processToKill = proc },
                        onKillParent = { parentToKill = proc },
                        onForceKill = { onForceKill(proc) },
                        onPause = { onPause(proc) },
                        onResume = { onResume(proc) }
                    )
                }
            }
        }
    }

    if (groupToKill != null) {
        val g = groupToKill!!
        AlertDialog(
            onDismissRequest = { groupToKill = null },
            title = { Text("End All Instances?") },
            text = {
                Text("Are you sure you want to terminate all ${g.processes.size} processes of ${g.name}?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        g.processes.forEach { onKill(it) }
                        groupToKill = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("End All (${g.processes.size})")
                }
            },
            dismissButton = {
                TextButton(onClick = { groupToKill = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (processToKill != null) {
        val p = processToKill!!
        AlertDialog(
            onDismissRequest = { processToKill = null },
            title = { Text("End Process?") },
            text = {
                Text("Are you sure you want to terminate ${p.name} (PID: ${p.pid}) owned by ${p.user}?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onKill(p)
                        processToKill = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("End Task")
                }
            },
            dismissButton = {
                TextButton(onClick = { processToKill = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (parentToKill != null) {
        val p = parentToKill!!
        AlertDialog(
            onDismissRequest = { parentToKill = null },
            title = { Text("Kill Parent Process?") },
            text = {
                Text("Are you sure you want to terminate parent process PID ${p.ppid} of child ${p.name} (PID: ${p.pid})?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onKillParent(p)
                        parentToKill = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Kill Parent (-9)")
                }
            },
            dismissButton = {
                TextButton(onClick = { parentToKill = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ProcessGroupCard(
    group: ProcessGroupItem,
    isExpanded: Boolean,
    expandedPid: Int?,
    onToggleExpand: () -> Unit,
    onToggleChildExpand: (Int) -> Unit,
    onPromptKillGroup: () -> Unit,
    onPromptKillChild: (PcProcessItem) -> Unit,
    onKillParent: (PcProcessItem) -> Unit,
    onForceKill: (PcProcessItem) -> Unit,
    onPause: (PcProcessItem) -> Unit,
    onResume: (PcProcessItem) -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpand)
            .animateContentSize()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = group.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "${group.processes.size}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${group.processes.size} instances  •  User: ${group.mainUser}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Combined CPU Pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (group.totalCpu > 50.0) MaterialTheme.colorScheme.errorContainer
                                else if (group.totalCpu > 10.0) MaterialTheme.colorScheme.tertiaryContainer
                                else MaterialTheme.colorScheme.surface
                            )
                            .padding(horizontal = 5.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = String.format(java.util.Locale.US, "%.1f%%", group.totalCpu),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Combined RAM Pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 5.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "${group.totalMemMb}M",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (group.totalDataMb > 0) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 5.dp, vertical = 3.dp)
                        ) {
                            val dataText = if (group.totalDataMb >= 1024) {
                                String.format(java.util.Locale.US, "%.1fG", group.totalDataMb / 1024.0)
                            } else {
                                String.format(java.util.Locale.US, "%.0fM", group.totalDataMb)
                            }
                            Text(
                                text = dataText,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }

                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    FilledTonalButton(
                        onClick = onPromptKillGroup,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(34.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("End All ${group.processes.size} Instances", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    group.processes.forEach { childProc ->
                        ProcessItemCard(
                            proc = childProc,
                            isExpanded = expandedPid == childProc.pid,
                            onToggleExpand = { onToggleChildExpand(childProc.pid) },
                            onPromptKill = { onPromptKillChild(childProc) },
                            onKillParent = { onKillParent(childProc) },
                            onForceKill = { onForceKill(childProc) },
                            onPause = { onPause(childProc) },
                            onResume = { onResume(childProc) }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProcessItemCard(
    proc: PcProcessItem,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onPromptKill: () -> Unit,
    onKillParent: () -> Unit,
    onForceKill: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpand)
            .animateContentSize()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = proc.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (proc.ppid > 1) "PID ${proc.pid}  •  PPID ${proc.ppid}  •  ${proc.user}" else "PID ${proc.pid}  •  ${proc.user}  •  ${proc.state}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // CPU Pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (proc.cpu > 50.0) MaterialTheme.colorScheme.errorContainer
                                else if (proc.cpu > 10.0) MaterialTheme.colorScheme.tertiaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .padding(horizontal = 5.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = String.format(java.util.Locale.US, "%.1f%%", proc.cpu),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // RAM Pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 5.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "${proc.memMb}M",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (proc.dataTotalMb > 0) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 5.dp, vertical = 3.dp)
                        ) {
                            val dataText = if (proc.dataTotalMb >= 1024) {
                                String.format(java.util.Locale.US, "%.1fG", proc.dataTotalMb / 1024.0)
                            } else {
                                String.format(java.util.Locale.US, "%.0fM", proc.dataTotalMb)
                            }
                            Text(
                                text = dataText,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }

                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    Text(
                        text = "Command: ${proc.cmd}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = onPromptKill,
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("End Task", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = onForceKill,
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("Force Kill (-9)", style = MaterialTheme.typography.labelSmall)
                        }

                        OutlinedButton(
                            onClick = if (proc.state.contains("T")) onResume else onPause,
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text(if (proc.state.contains("T")) "Resume" else "Pause", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    if (proc.ppid > 1) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onKillParent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Kill Parent Process (PPID: ${proc.ppid})", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServicesTabContent(
    services: List<PcServiceItem>,
    onStart: (PcServiceItem) -> Unit,
    onStop: (PcServiceItem) -> Unit,
    onRestart: (PcServiceItem) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var filterStatus by remember { mutableStateOf("All") }

    val filtered = remember(services, searchQuery, filterStatus) {
        val q = searchQuery.trim()
        services.filter {
            val matchesSearch = q.isEmpty() || it.unitName.contains(q, ignoreCase = true) || it.description.contains(q, ignoreCase = true)
            val matchesFilter = when (filterStatus) {
                "Active" -> it.activeState == "active"
                "Inactive" -> it.activeState != "active"
                "Failed" -> it.activeState == "failed"
                else -> true
            }
            matchesSearch && matchesFilter
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            placeholder = { Text("Search systemd services...", style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("All", "Active", "Inactive", "Failed").forEach { status ->
                FilterChip(
                    selected = filterStatus == status,
                    onClick = { filterStatus = status },
                    label = { Text(status, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No services matching criteria", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered, key = { "srv_${it.unitName}" }) { srv ->
                    ServiceItemCard(
                        service = srv,
                        onStart = { onStart(srv) },
                        onStop = { onStop(srv) },
                        onRestart = { onRestart(srv) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ServiceItemCard(
    service: PcServiceItem,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit
) {
    val isActive = service.activeState.equals("active", ignoreCase = true) || service.subState.equals("running", ignoreCase = true)
    val isFailed = service.activeState.equals("failed", ignoreCase = true) || service.subState.equals("failed", ignoreCase = true)

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = service.unitName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val sDataStr = if (service.dataTotalMb >= 1024) ("  •  " + String.format(java.util.Locale.US, "%.2f", service.dataTotalMb / 1024.0) + " GB Net") else if (service.dataTotalMb > 0) ("  •  " + service.dataTotalMb + " MB Net") else ""
                    Text(
                        text = "${service.description.ifEmpty { "Systemd Service" }}$sDataStr",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            when {
                                isFailed -> MaterialTheme.colorScheme.errorContainer
                                isActive -> MaterialTheme.colorScheme.primaryContainer
                                else -> MaterialTheme.colorScheme.surface
                            }
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isActive) "Active" else if (isFailed) "Failed" else "Inactive",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            isFailed -> MaterialTheme.colorScheme.error
                            isActive -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isActive) {
                    OutlinedButton(
                        onClick = onRestart,
                        modifier = Modifier.height(32.dp),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Text("Restart", style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    FilledTonalButton(
                        onClick = onStop,
                        modifier = Modifier.height(32.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Text("Stop", style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    FilledTonalButton(
                        onClick = onStart,
                        modifier = Modifier.height(32.dp),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Text("Start", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppsTabContent(
    apps: List<CachyAppItem>,
    onLaunch: (CachyAppItem) -> Unit,
    onTerminate: (CachyAppItem) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    val filtered = remember(apps, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) apps
        else apps.filter { it.name.contains(q, ignoreCase = true) || it.packageId.contains(q, ignoreCase = true) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            placeholder = { Text("Search installed desktop apps...", style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No applications found", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(filtered, key = { idx, app -> "app_${app.packageId}_${app.name}_$idx" }) { _, app ->
                    AppItemCard(
                        app = app,
                        onLaunch = { onLaunch(app) },
                        onTerminate = { onTerminate(app) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AppItemCard(
    app: CachyAppItem,
    onLaunch: () -> Unit,
    onTerminate: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = app.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (app.isRunning) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
                Text(
                    text = "${app.packageId}  •  ${app.source}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (app.isRunning) {
                    val dataStr = if (app.dataTotalMb >= 1024) ("  •  " + String.format(java.util.Locale.US, "%.2f", app.dataTotalMb / 1024.0) + " GB Net") else if (app.dataTotalMb > 0) ("  •  " + app.dataTotalMb + " MB Net") else ""
                    Text(
                        text = String.format(java.util.Locale.US, "%.1f%% CPU  •  %d MB RAM%s", app.cpu, app.memMb, dataStr),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (app.isRunning) {
                    FilledTonalButton(
                        onClick = onTerminate,
                        modifier = Modifier.height(34.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Text("Kill", style = MaterialTheme.typography.labelSmall)
                    }
                }

                FilledTonalButton(
                    onClick = onLaunch,
                    modifier = Modifier.height(34.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) {
                    Text(if (app.isRunning) "Switch" else "Launch", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun formatMb(mb: Long): String {
    return if (mb >= 1024) {
        String.format("%.1f GB", mb.toDouble() / 1024.0)
    } else {
        "$mb MB"
    }
}

private fun Double.format(digits: Int) = "%.${digits}f".format(this)


@Composable
private fun CustomBarChart(
    bars: List<AnalyticsBarItem>,
    metricUnit: String,
    modifier: Modifier = Modifier
) {
    if (bars.isEmpty()) return

    val maxVal = remember(bars) {
        val m = bars.maxOfOrNull { it.value } ?: 1.0
        if (m <= 0.0) 1.0 else m
    }

    var selectedBarIndex by remember { mutableStateOf<Int?>(null) }

    Column(modifier = modifier) {
        selectedBarIndex?.let { idx ->
            if (idx in bars.indices) {
                val b = bars[idx]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Text(
                            text = "${b.label} : ${b.value} ",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            bars.forEachIndexed { index, bar ->
                val fraction = (bar.value / maxVal).toFloat().coerceIn(0.04f, 1.0f)
                val isSelected = selectedBarIndex == index

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(150.dp),
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height((150 * fraction).dp)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.primary
                            )
                            .clickable {
                                selectedBarIndex = if (selectedBarIndex == index) null else index
                            }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (bars.isNotEmpty()) {
                Text(
                    text = bars.first().label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (bars.size > 2) {
                    val mid = bars[bars.size / 2]
                    Text(
                        text = mid.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = bars.last().label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatisticsTabContent(
    analyticsData: AnalyticsDashboardData?,
    isLoading: Boolean,
    onFetchAnalytics: (metric: String, category: String, timeframe: String, startDate: String, endDate: String) -> Unit
) {
    var selectedMetric by remember { mutableStateOf(analyticsData?.metric ?: "data") }
    var selectedCategory by remember { mutableStateOf(analyticsData?.category ?: "apps") }
    var selectedTimeframe by remember { mutableStateOf(analyticsData?.timeframe ?: "1d") }
    var startDate by remember { mutableStateOf("") }
    var endDate by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }

    var showMetricMenu by remember { mutableStateOf(false) }
    var showCategoryMenu by remember { mutableStateOf(false) }
    var showTimeframeMenu by remember { mutableStateOf(false) }
    var showCustomDateDialog by remember { mutableStateOf(false) }

    fun refresh(
        m: String = selectedMetric,
        c: String = selectedCategory,
        tf: String = selectedTimeframe,
        st: String = startDate,
        et: String = endDate
    ) {
        onFetchAnalytics(m, c, tf, st, et)
    }

    LaunchedEffect(Unit) {
        if (analyticsData == null) {
            refresh()
        }
    }

    val filteredItems = remember(analyticsData?.items, searchQuery) {
        val all = analyticsData?.items ?: emptyList()
        val q = searchQuery.trim()
        if (q.isEmpty()) all
        else all.filter {
            it.name.contains(q, ignoreCase = true) ||
            it.id.contains(q, ignoreCase = true) ||
            it.category.contains(q, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        // Dropdown Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Metric Dropdown
            Box {
                FilledTonalButton(
                    onClick = { showMetricMenu = true },
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        when (selectedMetric) {
                            "active_time" -> "Active Screen Time"
                            "bg_time" -> "Background Time"
                            "battery" -> "Battery Usage"
                            else -> "Data Usage"
                        },
                        style = MaterialTheme.typography.labelMedium
                    )
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = showMetricMenu, onDismissRequest = { showMetricMenu = false }) {
                    DropdownMenuItem(text = { Text("Network Data Usage") }, onClick = { selectedMetric = "data"; showMetricMenu = false; refresh(m = "data") })
                    DropdownMenuItem(text = { Text("Active Screen Time") }, onClick = { selectedMetric = "active_time"; showMetricMenu = false; refresh(m = "active_time") })
                    DropdownMenuItem(text = { Text("Background Screen / CPU Time") }, onClick = { selectedMetric = "bg_time"; showMetricMenu = false; refresh(m = "bg_time") })
                    DropdownMenuItem(text = { Text("Battery Power Usage") }, onClick = { selectedMetric = "battery"; showMetricMenu = false; refresh(m = "battery") })
                }
            }

            // Category Dropdown
            Box {
                OutlinedButton(
                    onClick = { showCategoryMenu = true },
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        when (selectedCategory) {
                            "apps" -> "All Apps"
                            "services" -> "Services Only"
                            "processes" -> "Processes Only"
                            else -> "All Targets"
                        },
                        style = MaterialTheme.typography.labelMedium
                    )
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = showCategoryMenu, onDismissRequest = { showCategoryMenu = false }) {
                    DropdownMenuItem(text = { Text("All Targets") }, onClick = { selectedCategory = "all"; showCategoryMenu = false; refresh(c = "all") })
                    DropdownMenuItem(text = { Text("All Apps") }, onClick = { selectedCategory = "apps"; showCategoryMenu = false; refresh(c = "apps") })
                    DropdownMenuItem(text = { Text("System Services Only") }, onClick = { selectedCategory = "services"; showCategoryMenu = false; refresh(c = "services") })
                    DropdownMenuItem(text = { Text("Processes Only") }, onClick = { selectedCategory = "processes"; showCategoryMenu = false; refresh(c = "processes") })
                }
            }

            // Timeframe Dropdown
            Box {
                OutlinedButton(
                    onClick = { showTimeframeMenu = true },
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        when (selectedTimeframe) {
                            "1h" -> "Last 1 Hour"
                            "12h" -> "Last 12 Hours"
                            "1d" -> "Today / 1 Day"
                            "1w" -> "Last 7 Days"
                            "custom" -> if (startDate.isNotEmpty()) "$startDate ~ $endDate" else "Custom Range"
                            else -> "All Time"
                        },
                        style = MaterialTheme.typography.labelMedium
                    )
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = showTimeframeMenu, onDismissRequest = { showTimeframeMenu = false }) {
                    DropdownMenuItem(text = { Text("Last 1 Hour") }, onClick = { selectedTimeframe = "1h"; showTimeframeMenu = false; refresh(tf = "1h") })
                    DropdownMenuItem(text = { Text("Last 12 Hours") }, onClick = { selectedTimeframe = "12h"; showTimeframeMenu = false; refresh(tf = "12h") })
                    DropdownMenuItem(text = { Text("Today / 1 Day") }, onClick = { selectedTimeframe = "1d"; showTimeframeMenu = false; refresh(tf = "1d") })
                    DropdownMenuItem(text = { Text("Last 7 Days / 1 Week") }, onClick = { selectedTimeframe = "1w"; showTimeframeMenu = false; refresh(tf = "1w") })
                    DropdownMenuItem(text = { Text("All Time (30 Days)") }, onClick = { selectedTimeframe = "all"; showTimeframeMenu = false; refresh(tf = "all") })
                    DropdownMenuItem(text = { Text("Custom Date Range...") }, onClick = { showTimeframeMenu = false; showCustomDateDialog = true })
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (analyticsData == null && isLoading) {
            Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Loading statistics from PC...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else if (analyticsData == null) {
            Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No statistics available", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(6.dp))
                    FilledTonalButton(onClick = { refresh() }) {
                        Text("Fetch Statistics")
                    }
                }
            }
        } else if (analyticsData != null) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Total Summary Card
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = when (selectedMetric) {
                                    "active_time" -> "Total Active Screen Time"
                                    "bg_time" -> "Total Background Runtime"
                                    "battery" -> "Estimated Battery Consumption"
                                    else -> "Total Network Data Usage"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = analyticsData.totalDisplay,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Recorded across ${analyticsData.items.size} targets in selected timeframe",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                // Top 10 Bar Graph
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Top 10 Consumers Breakdown",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            val unit = when (selectedMetric) {
                                "active_time", "bg_time" -> "mins"
                                "battery" -> "mAh"
                                else -> "MB"
                            }
                            CustomBarChart(
                                bars = analyticsData.bars,
                                metricUnit = unit,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // Search Bar for All Entities
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "All Apps, Processes & Services (${filteredItems.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Search any app, process or service...", style = MaterialTheme.typography.bodyMedium) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }

                if (filteredItems.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text("No matching items found for \"$searchQuery\"", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    itemsIndexed(filteredItems, key = { index, item -> "${item.category}_${item.id}_$index" }) { _, item ->
                        AnalyticsRankCard(item = item)
                    }
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No statistics available for this selection", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (showCustomDateDialog) {
        var tempStart by remember { mutableStateOf(if (startDate.isNotEmpty()) startDate else "2026-09-18") }
        var tempEnd by remember { mutableStateOf(if (endDate.isNotEmpty()) endDate else "2026-09-19") }

        AlertDialog(
            onDismissRequest = { showCustomDateDialog = false },
            title = { Text("Select Date Range") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Enter date in YYYY-MM-DD format:", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = tempStart,
                        onValueChange = { tempStart = it },
                        label = { Text("Start Date") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = tempEnd,
                        onValueChange = { tempEnd = it },
                        label = { Text("End Date") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    startDate = tempStart.trim()
                    endDate = tempEnd.trim()
                    selectedTimeframe = "custom"
                    showCustomDateDialog = false
                    refresh()
                }) {
                    Text("Apply Range")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun AnalyticsRankCard(item: AnalyticsRankItem) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = item.category,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = item.displayValue,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth((item.percent / 100f).coerceIn(0.01f, 1f))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
                Text(
                    text = "${item.percent}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(42.dp),
                    textAlign = TextAlign.End
                )
            }
        }
    }
}


@Composable
private fun RunTabContent(
    commands: List<CommandEntry>,
    outputs: List<RunCommandOutput>,
    isCommandRunning: Boolean,
    onRun: (CommandEntry) -> Unit,
    onExecuteCustom: (String) -> Unit,
    onStop: () -> Unit,
    onClearOutput: () -> Unit,
    onCopy: (CommandEntry) -> Unit,
    onAddPhoneCommand: (name: String, command: String) -> Unit,
    onEditPhoneCommand: (key: String, newName: String, newCommand: String) -> Unit,
    onDeletePhoneCommand: (key: String) -> Unit,
    onMovePhoneCommand: (fromIndex: Int, toIndex: Int) -> Unit,
    onReorderPhoneCommands: (List<CommandEntry>) -> Unit = {}
) {
    var searchQuery by remember { mutableStateOf("") }
    var customCommandText by remember { mutableStateOf("") }
    val outputListState = rememberLazyListState()
    var localCommands by remember(commands) { mutableStateOf(commands) }
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(commands) {
        if (draggingKey == null) {
            localCommands = commands
        }
    }

    val filteredCommands = remember(localCommands, searchQuery) {
        if (searchQuery.isBlank()) localCommands
        else localCommands.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.command.contains(searchQuery, ignoreCase = true)
        }
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf("") }
    var cmdInput by remember { mutableStateOf("") }

    var editingCommand by remember { mutableStateOf<CommandEntry?>(null) }
    var editName by remember { mutableStateOf("") }
    var editCmd by remember { mutableStateOf("") }

    var deletingCommand by remember { mutableStateOf<CommandEntry?>(null) }

    // Quick presets
    val presets = listOf(
        "Lock Screen" to "loginctl lock-session",
        "Volume +5%" to "wpctl set-volume @DEFAULT_AUDIO_SINK@ 5%+",
        "Volume -5%" to "wpctl set-volume @DEFAULT_AUDIO_SINK@ 5%-",
        "Brightness Up" to "brightnessctl set 10%+",
        "Brightness Down" to "brightnessctl set 10%-",
        "Restart KDE Plasma" to "kquitapp6 plasmashell 2>/dev/null; sleep 0.5; kstart plasmashell 2>/dev/null &",
        "KDE System Monitor" to "plasma-systemmonitor",
        "Kill Active Window" to "qdbus6 org.kde.KWin /KWin org.kde.KWin.killWindow",
        "Show Desktop" to "ydotool key 125:1 32:1 32:0 125:0 2>/dev/null || qdbus6 org.kde.kglobalaccel /component/kwin org.kde.kglobalaccel.Component.invokeShortcut 'Show Desktop'"
    )

    // Add Command Dialog
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Phone Command", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("This command is saved directly inside your phone's storage.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Command Name") },
                        placeholder = { Text("e.g. Sleep / Suspend") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = cmdInput,
                        onValueChange = { cmdInput = it },
                        label = { Text("Shell Command") },
                        placeholder = { Text("e.g. systemctl suspend") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (nameInput.isNotBlank() && cmdInput.isNotBlank()) {
                            onAddPhoneCommand(nameInput.trim(), cmdInput.trim())
                            showAddDialog = false
                        }
                    },
                    enabled = nameInput.isNotBlank() && cmdInput.isNotBlank()
                ) {
                    Text("Save to Phone")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Edit Command Dialog
    editingCommand?.let { cmd ->
        LaunchedEffect(cmd) {
            editName = cmd.name
            editCmd = cmd.command
        }
        AlertDialog(
            onDismissRequest = { editingCommand = null },
            title = { Text("Edit Command", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("Command Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editCmd,
                        onValueChange = { editCmd = it },
                        label = { Text("Shell Command") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editName.isNotBlank() && editCmd.isNotBlank()) {
                            onEditPhoneCommand(cmd.key, editName.trim(), editCmd.trim())
                            editingCommand = null
                        }
                    },
                    enabled = editName.isNotBlank() && editCmd.isNotBlank()
                ) {
                    Text("Save Changes")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingCommand = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete Confirmation Dialog
    deletingCommand?.let { cmd ->
        AlertDialog(
            onDismissRequest = { deletingCommand = null },
            title = { Text("Delete Command?", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to remove '${cmd.name}' from your phone?") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeletePhoneCommand(cmd.key)
                        deletingCommand = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingCommand = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Custom Command Runner Box
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Execute Shell Command",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = customCommandText,
                            onValueChange = { customCommandText = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("e.g. systemctl restart bluetooth, btop...", style = MaterialTheme.typography.bodySmall) },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            trailingIcon = {
                                if (customCommandText.isNotEmpty()) {
                                    IconButton(onClick = { customCommandText = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        FilledTonalButton(
                            onClick = {
                                if (customCommandText.isNotBlank()) {
                                    onExecuteCustom(customCommandText.trim())
                                }
                            },
                            enabled = customCommandText.isNotBlank(),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(52.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Run")
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Run")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Quick Preset Chips
                    Text(
                        text = "Quick Presets:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presets.forEach { (label, cmd) ->
                            SuggestionChip(
                                onClick = {
                                    customCommandText = cmd
                                    onExecuteCustom(cmd)
                                },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    }
                }
            }
        }

        // 2. Output Console (If outputs available or command running)
        if (outputs.isNotEmpty() || isCommandRunning) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Console Output",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                if (isCommandRunning) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Running...", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }

                            Row {
                                if (isCommandRunning) {
                                    TextButton(
                                        onClick = onStop,
                                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                        contentPadding = PaddingValues(horizontal = 8.dp)
                                    ) {
                                        Text("Stop")
                                    }
                                }
                                if (outputs.isNotEmpty()) {
                                    TextButton(
                                        onClick = onClearOutput,
                                        contentPadding = PaddingValues(horizontal = 8.dp)
                                    ) {
                                        Text("Clear")
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(8.dp)
                        ) {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                state = outputListState
                            ) {
                                items(outputs) { out ->
                                    Text(
                                        text = out.string,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        fontWeight = if (out.isCommand) FontWeight.Bold else FontWeight.Normal,
                                        color = when (out.commandStatus) {
                                            RunCommandStatus.COMMAND_SUCCESSFUL -> Color(0xFF4CAF50)
                                            RunCommandStatus.COMMAND_FAILED -> MaterialTheme.colorScheme.error
                                            RunCommandStatus.STDERR -> MaterialTheme.colorScheme.error
                                            RunCommandStatus.COMMAND_RUNNING -> MaterialTheme.colorScheme.primary
                                            else -> MaterialTheme.colorScheme.onSurface
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Search Bar and Add / Sync buttons
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Saved Commands (" + localCommands.size.toString() + ")",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(
                        onClick = {
                            nameInput = ""
                            cmdInput = ""
                            showAddDialog = true
                        },
                        modifier = Modifier.height(32.dp),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Filter command names or values...", style = MaterialTheme.typography.bodyMedium) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
        }

        // 4. Configured Commands List
        if (filteredCommands.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (localCommands.isEmpty()) "No Saved Commands on Phone" else "No matching commands found",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (localCommands.isEmpty())
                                "Tap '+ Add' above to create custom shell commands stored directly on your phone."
                            else "Try searching for a different command name or executable string.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        if (localCommands.isEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            FilledTonalButton(onClick = {
                                nameInput = ""
                                cmdInput = ""
                                showAddDialog = true
                            }) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Add Command to Phone")
                            }
                        }
                    }
                }
            }
        } else {
            itemsIndexed(filteredCommands, key = { _, cmd -> cmd.key }) { index, cmd ->
                val isDragging = draggingKey == cmd.key
                val cardModifier = if (isDragging) {
                    Modifier
                        .fillMaxWidth()
                        .zIndex(2f)
                        .graphicsLayer {
                            translationY = dragOffsetY
                            scaleX = 1.02f
                            scaleY = 1.02f
                            alpha = 0.95f
                        }
                } else {
                    Modifier.fillMaxWidth()
                }

                CommandCardItem(
                    modifier = cardModifier,
                    command = cmd,
                    isDragging = isDragging,
                    onDragStart = {
                        draggingKey = cmd.key
                        dragOffsetY = 0f
                    },
                    onDragEnd = {
                        draggingKey = null
                        dragOffsetY = 0f
                        onReorderPhoneCommands(localCommands)
                    },
                    onDragCancel = {
                        draggingKey = null
                        dragOffsetY = 0f
                    },
                    onDrag = { dragAmountY ->
                        dragOffsetY += dragAmountY
                        val curKey = draggingKey ?: return@CommandCardItem
                        val currentIdx = localCommands.indexOfFirst { it.key == curKey }
                        if (currentIdx == -1) return@CommandCardItem
                        val threshold = 130f
                        if (dragOffsetY > threshold && currentIdx < localCommands.size - 1) {
                            val mutable = localCommands.toMutableList()
                            val item = mutable.removeAt(currentIdx)
                            mutable.add(currentIdx + 1, item)
                            localCommands = mutable
                            dragOffsetY -= threshold
                        } else if (dragOffsetY < -threshold && currentIdx > 0) {
                            val mutable = localCommands.toMutableList()
                            val item = mutable.removeAt(currentIdx)
                            mutable.add(currentIdx - 1, item)
                            localCommands = mutable
                            dragOffsetY += threshold
                        }
                    },
                    onRun = { onRun(cmd) },
                    onEdit = { editingCommand = cmd },
                    onDelete = { deletingCommand = cmd }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CommandCardItem(
    modifier: Modifier = Modifier,
    command: CommandEntry,
    isDragging: Boolean = false,
    onDragStart: () -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (Float) -> Unit = {},
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDragging)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isDragging) 8.dp else 0.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Drag Handle on the left
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .pointerInput(command.key) {
                        detectDragGestures(
                            onDragStart = { onDragStart() },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragCancel() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onDrag(dragAmount.y)
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Drag to reorder",
                    modifier = Modifier.size(22.dp),
                    tint = if (isDragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Command Title - expands to fill the remaining space with clear typography
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 6.dp)
            ) {
                Text(
                    text = command.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Compact Action Buttons on the right
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_action_image_edit_24dp),
                        contentDescription = "Edit Command",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Command",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                    )
                }

                Spacer(modifier = Modifier.width(2.dp))

                FilledTonalButton(
                    onClick = onRun,
                    modifier = Modifier.height(34.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Run",
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("Run", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}