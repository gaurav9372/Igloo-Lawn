/*
 * Copyright 2021, Lawnchair
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

package app.lawnchair.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.os.Handler
import android.os.Process
import android.os.UserHandle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.android.launcher3.AppFilter
import com.android.launcher3.LauncherAppState
import com.android.launcher3.Utilities
import com.android.launcher3.icons.cache.CacheLookupFlag.Companion.DEFAULT_LOOKUP_FLAG
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.pm.UserCache
import com.android.launcher3.util.ComponentKey
import com.android.launcher3.util.Executors.MODEL_EXECUTOR
import java.util.Comparator.comparing
import java.util.Locale

@Composable
fun appsState(
    filter: AppFilter = AppFilter(LocalContext.current),
    comparator: Comparator<App> = appComparator,
): State<List<App>> {
    val context = LocalContext.current
    val appsState = remember { mutableStateOf(emptyList<App>()) }
    DisposableEffect(Unit) {
        Utilities.postAsyncCallback(Handler(MODEL_EXECUTOR.looper)) {
            val launcherApps = context.getSystemService(LauncherApps::class.java)

            if (launcherApps != null) {
                val enabledApps = UserCache.INSTANCE.get(context).userProfiles.asSequence()
                    .flatMap { user ->
                        val list = launcherApps.getActivityList(null, user)
                        list?.asSequence() ?: emptySequence()
                    }
                    .filter { filter.shouldShowApp(it.componentName) }
                    .map { App(context, it) }
                    .toList()

                val loadedPackages = enabledApps.map { it.key.componentName.packageName }.toSet()
                val processedDisabledPackages = mutableSetOf<String>()
                val disabledApps = mutableListOf<App>()

                try {
                    val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                    }
                    val flags = PackageManager.MATCH_DISABLED_COMPONENTS or
                            PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS or
                            PackageManager.MATCH_DIRECT_BOOT_AWARE or
                            PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                    val pm = context.packageManager
                    val resolves = pm.queryIntentActivities(mainIntent, flags)
                    val user = Process.myUserHandle()
                    for (ri in resolves) {
                        val ai = ri.activityInfo ?: continue
                        val pkg = ai.packageName ?: continue
                        if (pkg.endsWith(".overlay") || pkg.endsWith(".auto_generated_rro__") ||
                            pkg.startsWith("com.android.internal") || pkg == "android") {
                            continue
                        }
                        val appInfo = ai.applicationInfo
                        val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        val state = pm.getApplicationEnabledSetting(pkg)
                        if (isSystemApp && state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
                            continue
                        }
                        val label = ri.loadLabel(pm)
                        if (label.isNullOrEmpty() || label.toString().trim().isEmpty() || label.toString() == ai.name) {
                            continue
                        }
                        if (ai.icon == 0 && appInfo.icon == 0) {
                            continue
                        }
                        if (!loadedPackages.contains(pkg) && !processedDisabledPackages.contains(pkg)) {
                            val cn = ComponentName(pkg, ai.name)
                            if (filter.shouldShowApp(cn)) {
                                disabledApps.add(App(context, ri, user))
                                processedDisabledPackages.add(pkg)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    // Ignore fallback errors
                }

                appsState.value = (enabledApps + disabledApps)
                    .sortedWith(comparator)
                    .toList()
            }
        }
        onDispose { }
    }
    return appsState
}

class App {

    val label: String
    val icon: Bitmap
    val key: ComponentKey
    private val activityInfo: LauncherActivityInfo?

    constructor(context: Context, info: LauncherActivityInfo) {
        activityInfo = info
        label = info.label.toString()
        key = ComponentKey(info.componentName, info.user)
        val appInfo = AppInfo(context, info, info.user)
        LauncherAppState.getInstance(context).iconCache.getTitleAndIcon(appInfo, DEFAULT_LOOKUP_FLAG)
        icon = appInfo.bitmap.icon
    }

    constructor(context: Context, ri: ResolveInfo, user: UserHandle) {
        activityInfo = null
        val pm = context.packageManager
        val ai = ri.activityInfo
        val cn = ComponentName(ai.packageName, ai.name)
        label = ri.loadLabel(pm)?.toString() ?: ai.packageName
        key = ComponentKey(cn, user)
        val appInfo = AppInfo().apply {
            componentName = cn
            this.user = user
            intent = AppInfo.makeLaunchIntent(cn)
            title = label
        }
        LauncherAppState.getInstance(context).iconCache.getTitleAndIcon(appInfo, DEFAULT_LOOKUP_FLAG)
        icon = appInfo.bitmap.icon
    }

    fun toAppInfo(context: Context): AppInfo {
        return if (activityInfo != null) {
            AppInfo(context, activityInfo, activityInfo.user)
        } else {
            AppInfo().apply {
                componentName = key.componentName
                user = key.user
                intent = AppInfo.makeLaunchIntent(key.componentName)
                title = label
            }
        }
    }
}

val appComparator: Comparator<App> = comparing { it.label.lowercase(Locale.getDefault()) }
