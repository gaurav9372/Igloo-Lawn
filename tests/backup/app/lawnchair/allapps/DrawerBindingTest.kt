package app.lawnchair.allapps

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherState
import com.android.launcher3.ModelCallbacks
import com.android.launcher3.allapps.ActivityAllAppsContainerView
import com.android.launcher3.allapps.AllAppsStore
import com.android.launcher3.util.PackageUserKey
import app.lawnchair.util.createLauncherRestartIntent
import app.lawnchair.util.isLauncherRestartIntent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DrawerBindingTest {
    @Test fun finishingLoadWithChangedTabsKeepsTheUsersCurrentScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent = Intent().setClassName(context.packageName, "app.lawnchair.LawnchairLauncher")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launcher = instrumentation.startActivitySync(intent) as Launcher
        try {
            val deadline = SystemClock.uptimeMillis() + 30_000
            while (!launcher.model.isModelLoaded() && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(50)
            }
            assertTrue("Launcher model did not finish loading", launcher.model.isModelLoaded())
            instrumentation.runOnMainSync {
                val store = launcher.appsView.appsStore
                store.disableDeferUpdates(store.deferUpdatesFlags)
                val flagsField = AllAppsStore::class.java.getDeclaredField("mModelFlags")
                    .apply { isAccessible = true }
                val mapField = AllAppsStore::class.java.getDeclaredField("mPackageUserKeytoUidMap")
                    .apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                val uidMap = mapField.get(store) as Map<PackageUserKey, Int>
                val flags = flagsField.getInt(store)
                val tabsField = ActivityAllAppsContainerView::class.java
                    .getDeclaredField("mHasWorkApps").apply { isAccessible = true }
                val actualTabs = launcher.appsView.shouldShowTabs()
                val progress = launcher.model.drawerLoadProgress
                val generation = progress.begin()
                progress.update(generation, 0, 244)
                launcher.stateManager.goToState(LauncherState.ALL_APPS, false)
                // Simulate initial tab discovery without needing a managed profile on the device.
                tabsField.setBoolean(launcher.appsView, !actualTabs)
                ModelCallbacks(launcher).bindAllApplications(store.apps, flags, uidMap)
                assertEquals(actualTabs, launcher.appsView.shouldShowTabs())
                progress.update(generation, 244, 244)
                progress.finish(generation)
                assertSame(LauncherState.ALL_APPS, launcher.stateManager.state)
                assertEquals(100, progress.progress)
                assertEquals(244, progress.state.completed)

                // A delayed restart alarm must not act like a second Home button press.
                val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                val onNewIntent = Launcher::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
                    .apply { isAccessible = true }
                onNewIntent.invoke(launcher, createLauncherRestartIntent(home))
                onNewIntent.invoke(launcher, createLauncherRestartIntent(home))
                assertSame(LauncherState.ALL_APPS, launcher.stateManager.state)

                // Genuine Home intents must continue to close the drawer.
                onNewIntent.invoke(launcher, home)
                assertSame(LauncherState.NORMAL, launcher.stateManager.state)

                // A user who has already closed the drawer must also keep their chosen screen.
                launcher.stateManager.goToState(LauncherState.NORMAL, false)
                tabsField.setBoolean(launcher.appsView, !actualTabs)
                ModelCallbacks(launcher).bindAllApplications(store.apps, flags, uidMap)
                assertSame(LauncherState.NORMAL, launcher.stateManager.state)
            }
        } finally {
            instrumentation.runOnMainSync { launcher.finish() }
        }
    }

    @Test fun restartIntentIsMarkedWithoutChangingOrdinaryHomeIntents() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val restart = createLauncherRestartIntent(home)
        assertFalse(isLauncherRestartIntent(home))
        assertTrue(isLauncherRestartIntent(restart))
        assertTrue(restart.hasCategory(Intent.CATEGORY_HOME))
        assertFalse(isLauncherRestartIntent(Intent(restart).setAction(Intent.ACTION_VIEW)))
        assertFalse(isLauncherRestartIntent(null))
    }
}
