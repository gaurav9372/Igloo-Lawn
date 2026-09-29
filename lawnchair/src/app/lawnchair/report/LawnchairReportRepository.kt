/*
 * Copyright 2026, Lawnchair
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.lawnchair.report

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class AppClickCount(
    val packageName: String,
    val appTitle: String,
    val count: Int,
)

data class DateOption(
    val key: String, // YYYY-MM-DD
    val label: String, // e.g. "Today", "Yesterday", "11 Aug"
    val dateStr: String, // e.g. "13 Aug, 2026"
)

data class DayReport(
    val dateKey: String,
    val displayDate: String,
    val totalRuntimeSeconds: Long,
    val processStartCount: Int,
    val lastProcessStartTime: String,
    val processExitCount: Int,
    val lowMemoryExitCount: Int,
    val crashExitCount: Int,
    val lastExitTime: String,
    val lastExitReason: String,
    val lastExitMemory: String,
    val topClickedApps: List<AppClickCount>,
    val totalAppsInDrawer: Int,
)

object LawnchairReportRepository {
    private const val PREF_NAME = "lawnchair_report_stats"
    private const val RETENTION_DAYS = 15L
    private const val LAST_PROCESSED_EXIT = "last_processed_exit"
    private var resumedTimeMillis: Long = 0L

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getTodayKey(): String {
        return LocalDate.now().toString() // YYYY-MM-DD
    }

    @Synchronized
    fun onLauncherRestarted(context: Context) {
        pruneExpiredStats(context)
        recordPreviousProcessExit(context)

        val today = getTodayKey()
        val prefs = getPrefs(context)
        val currentStarts = prefs.getInt("${today}_process_starts", 0)
        val now = System.currentTimeMillis()

        prefs.edit()
            .putInt("${today}_process_starts", currentStarts + 1)
            .putLong("${today}_last_process_start", now)
            .apply()
    }

    fun onLauncherResumed() {
        if (resumedTimeMillis == 0L) {
            resumedTimeMillis = SystemClock.elapsedRealtime()
        }
    }

    @Synchronized
    fun onLauncherPaused(context: Context) {
        if (resumedTimeMillis > 0) {
            val elapsed = (SystemClock.elapsedRealtime() - resumedTimeMillis) / 1000
            if (elapsed > 0) {
                val today = getTodayKey()
                val prefs = getPrefs(context)
                val currentRuntime = prefs.getLong("${today}_runtime", 0L)
                prefs.edit().putLong("${today}_runtime", currentRuntime + elapsed).apply()
            }
            resumedTimeMillis = 0L
        }
    }

    private fun pruneExpiredStats(context: Context) {
        val prefs = getPrefs(context)
        val cutoff = LocalDate.now().minusDays(RETENTION_DAYS - 1)
        val editor = prefs.edit()
        var changed = false

        prefs.all.keys.forEach { key ->
            if (key.length < 10) return@forEach
            val date = runCatching { LocalDate.parse(key.substring(0, 10)) }.getOrNull()
                ?: return@forEach
            if (date.isBefore(cutoff)) {
                editor.remove(key)
                changed = true
            }
        }
        if (changed) editor.apply()
    }

    private fun recordPreviousProcessExit(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        val exit = runCatching {
            activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 10)
                .firstOrNull { it.processName == context.packageName }
        }.getOrNull() ?: return

        val prefs = getPrefs(context)
        val fingerprint = "${exit.timestamp}:${exit.pid}:${exit.reason}"
        if (prefs.getString(LAST_PROCESSED_EXIT, null) == fingerprint) return

        val dateKey = Instant.ofEpochMilli(exit.timestamp)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString()
        val exitCount = prefs.getInt("${dateKey}_process_exits", 0) + 1
        val lowMemoryCount = prefs.getInt("${dateKey}_low_memory_exits", 0) +
            if (exit.reason == ApplicationExitInfo.REASON_LOW_MEMORY) 1 else 0
        val crashCount = prefs.getInt("${dateKey}_crash_exits", 0) +
            if (
                exit.reason == ApplicationExitInfo.REASON_CRASH ||
                exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                exit.reason == ApplicationExitInfo.REASON_ANR
            ) 1 else 0

        prefs.edit()
            .putString(LAST_PROCESSED_EXIT, fingerprint)
            .putInt("${dateKey}_process_exits", exitCount)
            .putInt("${dateKey}_low_memory_exits", lowMemoryCount)
            .putInt("${dateKey}_crash_exits", crashCount)
            .putLong("${dateKey}_last_exit", exit.timestamp)
            .putString("${dateKey}_last_exit_reason", describeExitReason(exit.reason))
            .putLong("${dateKey}_last_exit_pss_kb", exit.pss)
            .putLong("${dateKey}_last_exit_rss_kb", exit.rss)
            .apply()
    }

    private fun describeExitReason(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "Low memory"
        ApplicationExitInfo.REASON_CRASH -> "Java crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
        ApplicationExitInfo.REASON_ANR -> "App not responding"
        ApplicationExitInfo.REASON_EXIT_SELF -> "Launcher requested restart"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "User or system requested stop"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "Package updated"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "Permission changed"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Excessive resource use"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Initialization failure"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "Dependency died"
        ApplicationExitInfo.REASON_SIGNALED -> "Process signal"
        else -> "Other ($reason)"
    }

    fun isLauncherPackage(context: Context, packageName: String, appTitle: String = ""): Boolean {
        if (packageName.isBlank()) return true
        val targetPkg = packageName.lowercase()
        val myPkg = context.packageName.lowercase()
        val buildAppId = try { com.android.launcher3.BuildConfig.APPLICATION_ID.lowercase() } catch (_: Throwable) { "" }
        if (targetPkg == myPkg || (buildAppId.isNotEmpty() && targetPkg == buildAppId)) return true
        if (targetPkg == "com.android.launcher3") return true
        if (targetPkg.contains("lawnchair") || targetPkg.contains("igloo") || targetPkg.contains("lawnshair")) return true
        if (targetPkg.startsWith("app.lawnchair")) return true
        val title = appTitle.lowercase()
        if (title.contains("lawnchair") || title.contains("igloo") || title.contains("lawnshair")) return true
        return false
    }

    fun onAppClicked(context: Context, appTitle: String, packageName: String) {
        if (packageName.isBlank() || isLauncherPackage(context, packageName, appTitle)) return
        val today = getTodayKey()
        val prefs = getPrefs(context)
        val key = "${today}_app_${packageName}"
        val currentCount = prefs.getInt(key, 0)

        prefs.edit()
            .putInt(key, currentCount + 1)
            .putString("${today}_apptitle_${packageName}", appTitle)
            .apply()
    }

    fun getDateOptions(): List<DateOption> {
        val options = mutableListOf<DateOption>()
        val today = LocalDate.now()
        val displayFormatter = DateTimeFormatter.ofPattern("dd MMM, yyyy", Locale.ENGLISH)
        val shortFormatter = DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH)

        for (i in 0 until 15) {
            val date = today.minusDays(i.toLong())
            val key = date.toString()
            val label = when (i) {
                0 -> "Today (${date.format(shortFormatter)})"
                1 -> "Yesterday (${date.format(shortFormatter)})"
                else -> date.format(shortFormatter)
            }
            options.add(DateOption(key = key, label = label, dateStr = date.format(displayFormatter)))
        }
        return options
    }

    fun getTopClickedApps(context: Context, days: Int = 15): List<AppClickCount> {
        val prefs = getPrefs(context)
        val today = LocalDate.now()
        val allEntries = prefs.all

        // Collect all dateKeys for the last `days`
        val targetDateKeys = (0 until days).map { today.minusDays(it.toLong()).toString() }.toSet()

        val countsMap = mutableMapOf<String, Int>()
        val titlesMap = mutableMapOf<String, String>()

        for ((k, v) in allEntries) {
            if (v !is Int || v <= 0) continue
            // key format: "${dateKey}_app_${packageName}"
            val separatorIndex = k.indexOf("_app_")
            if (separatorIndex == -1) continue

            val dateKey = k.substring(0, separatorIndex)
            if (dateKey !in targetDateKeys) continue

            val pkg = k.substring(separatorIndex + 5)
            val titleKey = "${dateKey}_apptitle_$pkg"
            val title = prefs.getString(titleKey, null) ?: ""

            if (isLauncherPackage(context, pkg, title)) continue

            countsMap[pkg] = (countsMap[pkg] ?: 0) + v

            if (!titlesMap.containsKey(pkg) && title.isNotBlank()) {
                titlesMap[pkg] = title
            }
        }

        val pm = context.packageManager
        val clickList = countsMap.map { (pkg, count) ->
            val title = titlesMap[pkg] ?: try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) {
                pkg
            }
            AppClickCount(packageName = pkg, appTitle = title, count = count)
        }.sortedByDescending { it.count }

        return clickList.take(10)
    }

    fun getReportForDate(context: Context, dateKey: String): DayReport {
        val prefs = getPrefs(context)
        var runtime = prefs.getLong("${dateKey}_runtime", 0L)
        if (dateKey == getTodayKey() && resumedTimeMillis > 0) {
            val liveElapsed = (SystemClock.elapsedRealtime() - resumedTimeMillis) / 1000
            if (liveElapsed > 0) {
                runtime += liveElapsed
            }
        }
        val processStarts = prefs.getInt("${dateKey}_process_starts", 0)
        val lastProcessStartMillis = prefs.getLong("${dateKey}_last_process_start", 0L)

        val lastProcessStartStr = if (lastProcessStartMillis > 0) {
            val formatter = DateTimeFormatter.ofPattern("dd MMM, yyyy | hh:mm:ss a", Locale.ENGLISH)
            Instant.ofEpochMilli(lastProcessStartMillis).atZone(ZoneId.systemDefault()).format(formatter)
        } else {
            "N/A"
        }

        val processExits = prefs.getInt("${dateKey}_process_exits", 0)
        val lowMemoryExits = prefs.getInt("${dateKey}_low_memory_exits", 0)
        val crashExits = prefs.getInt("${dateKey}_crash_exits", 0)
        val lastExitMillis = prefs.getLong("${dateKey}_last_exit", 0L)
        val lastExitStr = if (lastExitMillis > 0) {
            val formatter = DateTimeFormatter.ofPattern("dd MMM, yyyy | hh:mm:ss a", Locale.ENGLISH)
            Instant.ofEpochMilli(lastExitMillis).atZone(ZoneId.systemDefault()).format(formatter)
        } else {
            "N/A"
        }
        val lastExitReason = prefs.getString("${dateKey}_last_exit_reason", null) ?: "N/A"
        val lastExitPssKb = prefs.getLong("${dateKey}_last_exit_pss_kb", 0L)
        val lastExitRssKb = prefs.getLong("${dateKey}_last_exit_rss_kb", 0L)
        val lastExitMemory = when {
            lastExitPssKb > 0 -> "${lastExitPssKb / 1024} MB PSS"
            lastExitRssKb > 0 -> "${lastExitRssKb / 1024} MB RSS"
            else -> "Memory unavailable"
        }

        // Top 10 most clicked apps across the last 15 days (excluding launcher app itself)
        val top10 = getTopClickedApps(context, days = 15)

        // Total apps count
        val totalApps = getTotalAppsCount(context)

        val dateOption = getDateOptions().find { it.key == dateKey }
        val displayDate = dateOption?.label ?: dateKey

        return DayReport(
            dateKey = dateKey,
            displayDate = displayDate,
            totalRuntimeSeconds = runtime,
            processStartCount = processStarts,
            lastProcessStartTime = lastProcessStartStr,
            processExitCount = processExits,
            lowMemoryExitCount = lowMemoryExits,
            crashExitCount = crashExits,
            lastExitTime = lastExitStr,
            lastExitReason = lastExitReason,
            lastExitMemory = lastExitMemory,
            topClickedApps = top10,
            totalAppsInDrawer = totalApps,
        )
    }

    fun getTotalAppsCount(context: Context): Int {
        return try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val list = context.packageManager.queryIntentActivities(mainIntent, 0)
            list.size
        } catch (e: Exception) {
            0
        }
    }

    fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "0s"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return buildString {
            if (hours > 0) append("${hours}h ")
            if (minutes > 0 || hours > 0) append("${minutes}m ")
            append("${secs}s")
        }.trim()
    }
}
