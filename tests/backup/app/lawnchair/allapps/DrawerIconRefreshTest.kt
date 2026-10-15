package app.lawnchair.allapps

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Process
import android.os.SystemClock
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.launcher3.BubbleTextView
import com.android.launcher3.Launcher
import com.android.launcher3.allapps.AllAppsGridAdapter
import com.android.launcher3.allapps.AllAppsStore
import com.android.launcher3.allapps.AlphabeticalAppsList
import com.android.launcher3.allapps.BaseAllAppsAdapter
import com.android.launcher3.allapps.BaseAllAppsAdapter.AdapterItem
import com.android.launcher3.icons.BitmapInfo
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.ItemInfoWithIcon.*
import com.android.launcher3.pm.PackageInstallInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DrawerIconRefreshTest {
    private val disabledStates = intArrayOf(
        FLAG_DISABLED_BY_PUBLISHER, FLAG_DISABLED_DEEP_SLEEP,
        FLAG_DISABLED_SUSPENDED, FLAG_DISABLED_NOT_AVAILABLE,
    )

    private fun app(): AppInfo = AppInfo().apply {
        componentName = ComponentName("test.drawer", "test.drawer.Main")
        user = Process.myUserHandle()
        title = "Drawer app"
        contentDescription = title
        sectionName = "D"
        intent = AppInfo.makeLaunchIntent(componentName)
        bitmap = BitmapInfo(Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }, Color.RED)
    }

    private fun drawerFolder(child: AppInfo): FolderInfo = FolderInfo().apply {
        id = 123
        title = "Drawer folder"
        add(child)
    }

    @Test fun inPlaceFreezeAndThawAreDetectedWhileBindingTheLiveModel() {
        val app = app()
        for (disabled in disabledStates) {
            val enabled = AdapterItem.asApp(app)
            app.runtimeStatusFlags = disabled
            val frozen = AdapterItem.asApp(app)
            assertSame(enabled.itemInfo, frozen.itemInfo)
            assertTrue(enabled.isSameAs(frozen))
            assertFalse(enabled.isContentSame(frozen))
            app.runtimeStatusFlags = 0
            val thawed = AdapterItem.asApp(app)
            assertFalse(frozen.isContentSame(thawed))
            assertTrue(enabled.isContentSame(thawed))
            assertSame(app, thawed.itemInfo)
        }
    }

    @Test fun unchangedAppAndFolderRowsStayUnchanged() {
        val app = app()
        assertTrue(AdapterItem.asApp(app).isContentSame(AdapterItem.asApp(app)))
        val folder = drawerFolder(app)
        assertTrue(AdapterItem.asFolder(folder).isContentSame(AdapterItem.asFolder(folder)))
        assertTrue(AdapterItem.asFolder(folder).isContentSame(AdapterItem.asFolder(drawerFolder(app))))
    }

    @Test fun folderPreviewDetectsMutatedChildFlagsAndIcons() {
        val child = app().apply { runtimeStatusFlags = FLAG_DISABLED_DEEP_SLEEP }
        val folder = drawerFolder(child)
        val frozen = AdapterItem.asFolder(folder)
        child.runtimeStatusFlags = 0
        val thawed = AdapterItem.asFolder(folder)
        assertTrue(frozen.isSameAs(thawed))
        assertFalse(frozen.isContentSame(thawed))
        child.bitmap = app().bitmap
        assertFalse(thawed.isContentSame(AdapterItem.asFolder(folder)))
    }

    @Test fun bitmapMetadataAndProgressChangesAreNotLost() {
        val app = app()
        val initial = AdapterItem.asApp(app)
        app.bitmap.flags = BitmapInfo.FLAG_WORK
        assertFalse(initial.isContentSame(AdapterItem.asApp(app)))
        app.setProgressLevel(10, PackageInstallInfo.STATUS_INSTALLING)
        val installing = AdapterItem.asApp(app)
        app.setProgressLevel(20, PackageInstallInfo.STATUS_INSTALLING)
        assertFalse(installing.isContentSame(AdapterItem.asApp(app)))
    }

    @Test fun productionDrawerAdapterRestoresTheDrawableAfterThaw() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val launcher = instrumentation.startActivitySync(
            Intent().setClassName(context.packageName, "app.lawnchair.LawnchairLauncher")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as Launcher
        try {
            val deadline = SystemClock.uptimeMillis() + 30_000
            while (!launcher.model.isModelLoaded() && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(50)
            }
            assertTrue(launcher.model.isModelLoaded())
            instrumentation.runOnMainSync {
                // Isolated drawer data: no packages, launcher preferences, or home items change.
                val store = AllAppsStore(launcher)
                val apps = AlphabeticalAppsList<Launcher>(launcher, store, null, null)
                val adapter = AllAppsGridAdapter(
                    launcher, LayoutInflater.from(launcher), apps, launcher.appsView.mainAdapterProvider,
                )
                apps.setAdapter(adapter)
                val app = app().apply { runtimeStatusFlags = disabledStates.first() }
                fun publish() = store.setApps(arrayOf(app), 0, emptyMap(), false)
                publish()
                val position = apps.adapterItems.indexOfFirst {
                    it.viewType == BaseAllAppsAdapter.VIEW_TYPE_ICON
                }
                assertTrue(position >= 0)
                val holder = adapter.createViewHolder(FrameLayout(launcher), BaseAllAppsAdapter.VIEW_TYPE_ICON)
                adapter.bindViewHolder(holder, position)
                val view = holder.itemView as BubbleTextView
                var rebinds = 0
                val observer = object : RecyclerView.AdapterDataObserver() {
                    override fun onItemRangeChanged(start: Int, count: Int, payload: Any?) {
                        if (position in start until start + count) {
                            rebinds++
                            adapter.bindViewHolder(holder, position)
                        }
                    }
                }
                adapter.registerAdapterDataObserver(observer)
                try {
                    for (disabled in disabledStates) {
                        app.runtimeStatusFlags = disabled
                        publish()
                        assertTrue("Frozen icon must be gray: $disabled", view.icon.isDisabled)
                        app.runtimeStatusFlags = 0
                        assertFalse(app.isDisabled)
                        assertTrue("Drawable remains gray until the update", view.icon.isDisabled)
                        val before = rebinds
                        publish()
                        assertTrue("Thaw must dispatch a row change", rebinds > before)
                        assertFalse("Thawed icon must recover its color: $disabled", view.icon.isDisabled)
                        assertSame(app, view.tag)
                        val unchanged = rebinds
                        publish()
                        assertEquals("Unchanged rows must not rebind", unchanged, rebinds)
                    }
                    app.runtimeStatusFlags = FLAG_DISABLED_BY_PUBLISHER or FLAG_DISABLED_QUIET_USER
                    publish()
                    app.runtimeStatusFlags = FLAG_DISABLED_QUIET_USER
                    publish()
                    assertTrue("A quiet profile must remain gray after thaw", view.icon.isDisabled)
                } finally {
                    adapter.unregisterAdapterDataObserver(observer)
                    store.removeUpdateListener(apps)
                }
            }
        } finally {
            instrumentation.runOnMainSync { launcher.finish() }
        }
    }
}
