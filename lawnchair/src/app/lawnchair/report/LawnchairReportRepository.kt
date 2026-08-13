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

    fun onAppClicked(context: Context, appTitle: String, packageName: String) {
        if (packageName.isBlank()) return
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

        for (i in 0 until 7) {
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

        // Gather click counts for dateKey
        val clickList = mutableListOf<AppClickCount>()
        val prefix = "${dateKey}_app_"
        val titlePrefix = "${dateKey}_apptitle_"

        val allEntries = prefs.all
        for ((k, v) in allEntries) {
            if (k.startsWith(prefix) && !k.startsWith(titlePrefix) && v is Int && v > 0) {
                val pkg = k.removePrefix(prefix)
                val title = prefs.getString("${titlePrefix}$pkg", pkg) ?: pkg
                clickList.add(AppClickCount(packageName = pkg, appTitle = title, count = v))
            }
        }
        clickList.sortByDescending { it.count }
        val top10 = clickList.take(10)

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
