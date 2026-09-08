package app.lawnchair.search

import android.content.Context
import android.content.SharedPreferences
import com.android.launcher3.LauncherAppState
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.util.ComponentKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object AppLaunchTracker {
    private const val PREFS_NAME = "app_launch_tracker_prefs"
    private const val KEY_LAUNCH_EVENTS = "launch_events"
    private const val MAX_SAVED_EVENTS = 500

    data class LaunchEvent(val componentKey: String, val timestamp: Long)

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun recordAppLaunch(context: Context, componentKey: String) {
        if (componentKey.isBlank()) return
        val comp = ComponentKey.fromString(componentKey)
        val pkg = comp?.componentName?.packageName ?: ""
        if (app.lawnchair.report.LawnchairReportRepository.isLauncherPackage(context, pkg)) return
        val appContext = context.applicationContext
        com.android.launcher3.util.Executors.MODEL_EXECUTOR.execute {
            synchronized(this) {
                val prefs = getPrefs(appContext)
                val events = loadEvents(prefs).toMutableList()
                events.add(LaunchEvent(componentKey, System.currentTimeMillis()))

                val trimmedEvents = if (events.size > MAX_SAVED_EVENTS) {
                    events.takeLast(MAX_SAVED_EVENTS)
                } else {
                    events
                }
                saveEvents(prefs, trimmedEvents)
            }
        }
    }

    @Synchronized
    fun getRecentApps(context: Context, maxCount: Int = 10): List<AppInfo> {
        val prefs = getPrefs(context)
        val events = loadEvents(prefs)
        val recentKeys = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        for (event in events.asReversed()) {
            if (seen.add(event.componentKey)) {
                recentKeys.add(event.componentKey)
                if (recentKeys.size >= maxCount) break
            }
        }

        return resolveAppInfos(context, recentKeys)
    }

    @Synchronized
    fun getFrequentApps(context: Context, maxCount: Int = 5, daysWindow: Int = 28): List<AppInfo> {
        val prefs = getPrefs(context)
        val events = loadEvents(prefs)
        val cutoffTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(daysWindow.toLong())

        val counts = mutableMapOf<String, Int>()
        val lastSeen = mutableMapOf<String, Long>()

        for (event in events) {
            if (event.timestamp >= cutoffTime) {
                counts[event.componentKey] = (counts[event.componentKey] ?: 0) + 1
                lastSeen[event.componentKey] = event.timestamp
            }
        }

        val sortedKeys = counts.entries.sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenByDescending { lastSeen[it.key] ?: 0L }
        ).map { it.key }.take(maxCount)

        return resolveAppInfos(context, sortedKeys)
    }

    private fun resolveAppInfos(context: Context, componentKeys: List<String>): List<AppInfo> {
        if (componentKeys.isEmpty()) return emptyList()
        val launcher = app.lawnchair.LawnchairLauncher.instance
        if (launcher != null) {
            val appsStore = launcher.appsView?.appsStore
            if (appsStore != null) {
                return componentKeys.mapNotNull { keyStr ->
                    ComponentKey.fromString(keyStr)?.let { key ->
                        if (app.lawnchair.report.LawnchairReportRepository.isLauncherPackage(context, key.componentName.packageName)) {
                            null
                        } else {
                            appsStore.getApp(key)
                        }
                    }
                }
            }
        }
        return emptyList()
    }

    private fun loadEvents(prefs: SharedPreferences): List<LaunchEvent> {
        val rawJson = prefs.getString(KEY_LAUNCH_EVENTS, null) ?: return emptyList()
        val list = mutableListOf<LaunchEvent>()
        try {
            val jsonArray = JSONArray(rawJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(LaunchEvent(obj.getString("k"), obj.getLong("t")))
            }
        } catch (_: Exception) {}
        return list
    }

    private fun saveEvents(prefs: SharedPreferences, events: List<LaunchEvent>) {
        val jsonArray = JSONArray()
        for (event in events) {
            val obj = JSONObject()
            obj.put("k", event.componentKey)
            obj.put("t", event.timestamp)
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_LAUNCH_EVENTS, jsonArray.toString()).apply()
    }
}
