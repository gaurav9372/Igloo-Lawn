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

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
    val restartCount: Int,
    val lastRestartTime: String,
    val lastKilledTime: String,
    val batteryForeground: String,
    val batteryBackground: String,
    val topClickedApps: List<AppClickCount>,
    val totalAppsInDrawer: Int,
)

object LawnchairReportRepository {
    private const val PREF_NAME = "lawnchair_report_stats"
    private var resumedTimeMillis: Long = 0L

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getTodayKey(): String {
        return LocalDate.now().toString() // YYYY-MM-DD
    }

    fun onLauncherRestarted(context: Context) {
        val today = getTodayKey()
        val prefs = getPrefs(context)
        val currentRestarts = prefs.getInt("${today}_restarts", 0)
        val now = System.currentTimeMillis()

        prefs.edit()
            .putInt("${today}_restarts", currentRestarts + 1)
            .putLong("${today}_last_restart", now)
            .apply()
    }

    fun onLauncherResumed() {
        if (resumedTimeMillis == 0L) {
            resumedTimeMillis = SystemClock.elapsedRealtime()
        }
    }

    fun onLauncherStopped(context: Context) {
        onLauncherPaused(context)
        val today = getTodayKey()
        val prefs = getPrefs(context)
        val now = System.currentTimeMillis()
        prefs.edit().putLong("${today}_last_killed", now).apply()
    }

    fun onLauncherPaused(context: Context) {
        if (resumedTimeMillis > 0) {
            val elapsed = (SystemClock.elapsedRealtime() - resumedTimeMillis) / 1000
            if (elapsed > 0) {
                val today = getTodayKey()
                val prefs = getPrefs(context)
                val currentRuntime = prefs.getLong("${today}_runtime", 0L)
                prefs.edit().putLong("${today}_runtime", currentRuntime + elapsed).apply()
            }
            resumedTimeMillis = SystemClock.elapsedRealtime()
        }
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
        val restarts = prefs.getInt("${dateKey}_restarts", 0)
        val lastRestartMillis = prefs.getLong("${dateKey}_last_restart", 0L)

        val lastRestartStr = if (lastRestartMillis > 0) {
            val formatter = DateTimeFormatter.ofPattern("dd MMM, yyyy | hh:mm:ss a", Locale.ENGLISH)
            Instant.ofEpochMilli(lastRestartMillis).atZone(ZoneId.systemDefault()).format(formatter)
        } else {
            "N/A"
        }

        val lastKilledMillis = prefs.getLong("${dateKey}_last_killed", 0L)
        val lastKilledStr = if (lastKilledMillis > 0) {
            val formatter = DateTimeFormatter.ofPattern("dd MMM, yyyy | hh:mm:ss a", Locale.ENGLISH)
            Instant.ofEpochMilli(lastKilledMillis).atZone(ZoneId.systemDefault()).format(formatter)
        } else {
            "N/A"
        }

        // Top 10 most clicked apps across the last 15 days (excluding launcher app itself)
        val top10 = getTopClickedApps(context, days = 15)

        // Total apps count
        val totalApps = getTotalAppsCount(context)

        // Estimated Battery stats calculation
        val fgBattery = if (runtime > 0) String.format(Locale.ENGLISH, "%.1f%%", (runtime / 3600.0) * 1.8 + 0.1) else "0.0%"
        val bgBattery = String.format(Locale.ENGLISH, "%.1f%%", (restarts * 0.05) + 0.1)

        val dateOption = getDateOptions().find { it.key == dateKey }
        val displayDate = dateOption?.label ?: dateKey

        return DayReport(
            dateKey = dateKey,
            displayDate = displayDate,
            totalRuntimeSeconds = runtime,
            restartCount = restarts,
            lastRestartTime = lastRestartStr,
            lastKilledTime = lastKilledStr,
            batteryForeground = fgBattery,
            batteryBackground = bgBattery,
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
