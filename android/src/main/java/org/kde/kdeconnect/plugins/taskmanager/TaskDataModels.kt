/*
 * SPDX-FileCopyrightText: 2026 Antigravity
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.taskmanager

data class PcSystemStats(
    val cpu: Double = 0.0,
    val cpuTemp: Double = 0.0,
    val load1: Double = 0.0,
    val load5: Double = 0.0,
    val load15: Double = 0.0,
    val memUsed: Int = 0,
    val memTotal: Int = 0,
    val memFree: Int = 0,
    val memCached: Int = 0,
    val swapUsed: Int = 0,
    val swapTotal: Int = 0,
    val disks: List<DiskPartitionItem> = emptyList(),
    val gpu: GpuStatsItem = GpuStatsItem(),
    val uptime: String = "--",
    val bootTime: String = "--",
    val cpuModel: String = "x86_64 CPU",
    val cpuCores: Int = 1,
    val osName: String = "Linux",
    val kernel: String = "--",
    val hostname: String = "--",
    val netRx: Long = 0L,
    val netTx: Long = 0L,
    val interfaces: List<NetworkInterfaceItem> = emptyList()
)

data class DiskPartitionItem(
    val mount: String = "/",
    val fs: String = "",
    val totalGb: Double = 0.0,
    val usedGb: Double = 0.0,
    val freeGb: Double = 0.0,
    val percent: Int = 0
)

data class GpuStatsItem(
    val name: String = "",
    val util: Double = 0.0,
    val memUsedMb: Int = 0,
    val memTotalMb: Int = 0
)

data class NetworkInterfaceItem(
    val name: String,
    val rxMb: Int,
    val txMb: Int
)

data class PcProcessItem(
    val pid: Int,
    val ppid: Int = 0,
    val user: String,
    val cpu: Double,
    val memMb: Int,
    val state: String = "R",
    val name: String,
    val cmd: String,
    val dataRxMb: Double = 0.0,
    val dataTxMb: Double = 0.0,
    val dataTotalMb: Double = 0.0
)

data class PcServiceItem(
    val unitName: String,
    val loadState: String,
    val activeState: String,
    val subState: String,
    val description: String,
    val isSystem: Boolean,
    val dataRxMb: Double = 0.0,
    val dataTxMb: Double = 0.0,
    val dataTotalMb: Double = 0.0
)

data class CachyAppItem(
    val name: String,
    val packageId: String,
    val source: String,
    val execCmd: String,
    val icon: String,
    val categories: String,
    val isRunning: Boolean,
    val cpu: Double,
    val memMb: Int,
    val dataRxMb: Double = 0.0,
    val dataTxMb: Double = 0.0,
    val dataTotalMb: Double = 0.0,
    val activeScreenSec: Long = 0L,
    val backgroundSec: Long = 0L,
    val batteryPercent: Double = 0.0
)

data class AnalyticsBarItem(
    val label: String,
    val timestamp: Long,
    val value: Double,
    val secondaryValue: Double = 0.0
)

data class AnalyticsRankItem(
    val id: String,
    val name: String,
    val category: String,
    val value: Double,
    val displayValue: String,
    val percent: Float,
    val icon: String = ""
)

data class AnalyticsDashboardData(
    val metric: String = "data",
    val category: String = "all",
    val timeframe: String = "1d",
    val totalValue: Double = 0.0,
    val totalDisplay: String = "0 MB",
    val bars: List<AnalyticsBarItem> = emptyList(),
    val items: List<AnalyticsRankItem> = emptyList()
)
