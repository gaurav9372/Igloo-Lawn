package app.lawnchair.allapps

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.UserHandle
import android.util.Log
import android.widget.Toast
import app.lawnchair.LawnchairLauncher
import com.android.launcher3.R
import com.android.launcher3.util.ApplicationInfoWrapper
import com.android.launcher3.util.ComponentKey
import java.util.ArrayDeque

internal data class MultiSelectUninstallTarget(
    val componentKey: ComponentKey,
    val packageName: String,
    val user: UserHandle,
)

internal data class MultiSelectUninstallResolution(
    val targets: List<MultiSelectUninstallTarget>,
    val skippedCount: Int,
)

/**
 * Resolves the current multi-select snapshot to packages that Android may uninstall.
 *
 * Every component must still exist in the live app store. This prevents removed, hidden, malformed,
 * or otherwise stale selection keys from reaching Package Installer. Packages are distinct per
 * Android user so personal and work-profile copies are handled independently.
 */
internal fun resolveMultiSelectUninstallTargets(
    launcher: LawnchairLauncher,
    selectedKeys: Set<String>,
): MultiSelectUninstallResolution {
    val seenPackages = mutableSetOf<Pair<String, UserHandle>>()
    val targets = buildList {
        selectedKeys.forEach { keyString ->
            val selectedKey = ComponentKey.fromString(keyString) ?: return@forEach
            val currentApp = launcher.appsView?.appsStore?.getApp(selectedKey) ?: return@forEach
            val currentKey = currentApp.toComponentKey()
            val packageName = currentKey.componentName.packageName

            if (packageName == launcher.packageName) return@forEach

            val appInfo = ApplicationInfoWrapper(
                launcher,
                packageName,
                currentKey.user,
            ).getInfo() ?: return@forEach
            if (ApplicationInfoWrapper(appInfo).isSystem()) return@forEach
            if (!seenPackages.add(packageName to currentKey.user)) return@forEach

            add(
                MultiSelectUninstallTarget(
                    componentKey = currentKey,
                    packageName = packageName,
                    user = currentKey.user,
                ),
            )
        }
    }
    return MultiSelectUninstallResolution(
        targets = targets,
        skippedCount = selectedKeys.size - targets.size,
    )
}

/** Launches one Package Installer confirmation at a time and advances when it returns. */
class MultiSelectUninstallController(
    private val launcher: LawnchairLauncher,
) {
    private val pendingTargets = ArrayDeque<MultiSelectUninstallTarget>()
    private var currentTarget: MultiSelectUninstallTarget? = null
    private var failedCount = 0
    private var skippedCount = 0

    fun eligibleTargetCount(selectedKeys: Set<String>): Int = resolveMultiSelectUninstallTargets(launcher, selectedKeys).targets.size

    fun start(selectedKeys: Set<String>) {
        if (currentTarget != null || pendingTargets.isNotEmpty()) return

        val resolution = resolveMultiSelectUninstallTargets(launcher, selectedKeys.toSet())
        if (resolution.targets.isEmpty()) {
            Toast.makeText(launcher, R.string.multi_select_no_uninstallable_apps, Toast.LENGTH_SHORT).show()
            return
        }

        pendingTargets.addAll(resolution.targets)
        skippedCount = resolution.skippedCount
        failedCount = 0
        MultiSelectManager.setUninstallInProgress(true)
        launchNext()
    }

    fun onActivityResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != REQUEST_CODE) return false

        currentTarget?.let { target ->
            val stillInstalled = ApplicationInfoWrapper(
                launcher,
                target.packageName,
                target.user,
            ).getInfo() != null
            Log.i(
                TAG,
                "Package Installer returned result=$resultCode package=${target.packageName} " +
                    "user=${target.user.identifier} installed=$stillInstalled",
            )
        }
        currentTarget = null
        launchNext()
        return true
    }

    private fun launchNext() {
        val target = pendingTargets.pollFirst()
        if (target == null) {
            finish()
            return
        }

        currentTarget = target
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.fromParts(
                "package",
                target.packageName,
                target.componentKey.componentName.className,
            )
            putExtra(Intent.EXTRA_USER, target.user)
        }

        try {
            Log.i(
                TAG,
                "Requesting uninstall package=${target.packageName} " +
                    "component=${target.componentKey.componentName.flattenToShortString()} " +
                    "user=${target.user.identifier}",
            )
            launcher.startActivityForResult(intent, REQUEST_CODE)
        } catch (e: ActivityNotFoundException) {
            handleLaunchFailure(target, e)
        } catch (e: SecurityException) {
            handleLaunchFailure(target, e)
        }
    }

    private fun handleLaunchFailure(target: MultiSelectUninstallTarget, error: RuntimeException) {
        Log.e(
            TAG,
            "Unable to open Package Installer for package=${target.packageName} " +
                "user=${target.user.identifier}",
            error,
        )
        failedCount++
        currentTarget = null
        launchNext()
    }

    private fun finish() {
        if (skippedCount > 0 || failedCount > 0) {
            Toast.makeText(
                launcher,
                launcher.resources.getQuantityString(
                    R.plurals.multi_select_uninstall_skipped,
                    skippedCount + failedCount,
                    skippedCount + failedCount,
                ),
                Toast.LENGTH_SHORT,
            ).show()
        }
        pendingTargets.clear()
        currentTarget = null
        skippedCount = 0
        failedCount = 0
        MultiSelectManager.setUninstallInProgress(false)
        MultiSelectManager.exitMultiSelect()
    }

    companion object {
        private const val TAG = "MultiSelectUninstall"
        private const val REQUEST_CODE = 0x4D53
    }
}
