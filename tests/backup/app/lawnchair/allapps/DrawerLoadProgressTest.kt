package app.lawnchair.allapps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.pm.LauncherApps
import android.os.Process
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.launcher3.allapps.DrawerLoadProgress
import com.android.launcher3.allapps.DrawerLoadingView
import com.android.launcher3.LauncherAppState
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.IconRequestInfo
import com.android.launcher3.util.Executors.MODEL_EXECUTOR
import java.io.File
import java.util.function.Consumer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DrawerLoadProgressTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun progressCountsIconsAndWaitsForBinding() {
        val progress = DrawerLoadProgress()
        assertEquals(-1, progress.progress)
        val load = progress.begin()
        assertEquals(0, progress.progress)
        progress.update(load, 50, 100)
        assertEquals(50, progress.progress)
        progress.update(load, 100, 100)
        assertEquals(99, progress.progress)
        progress.finish(load)
        assertEquals(100, progress.progress)
        progress.update(load, 1, 100)
        assertEquals(100, progress.progress)
    }

    @Test fun supersededLoaderCannotChangeNewLoad() {
        val progress = DrawerLoadProgress()
        val old = progress.begin()
        progress.update(old, 90, 100)
        val current = progress.begin()
        progress.update(old, 100, 100)
        progress.finish(old)
        progress.cancel(old)
        assertEquals(0, progress.progress)
        progress.update(current, 30, 100)
        assertEquals(30, progress.progress)
    }

    @Test fun cancellationCannotBeResurrectedByQueuedWork() {
        val progress = DrawerLoadProgress()
        val load = progress.begin()
        progress.cancel(load)
        progress.update(load, 100, 100)
        progress.finish(load)
        assertEquals(-1, progress.progress)
    }

    @Test fun emptyDrawerAndRegressingCountsAreHandled() {
        val progress = DrawerLoadProgress()
        val load = progress.begin()
        progress.update(load, 7, 10)
        progress.update(load, 1, 10)
        assertEquals(70, progress.progress)
        val empty = progress.begin()
        progress.update(empty, 0, 0)
        assertEquals(99, progress.progress)
        progress.finish(empty)
        assertEquals(100, progress.progress)
    }

    @Test fun reattachedListenerReceivesCurrentLoadAndDetachedListenerStops() {
        val progress = DrawerLoadProgress()
        val load = progress.begin()
        progress.update(load, 50, 100)
        instrumentation.waitForIdleSync()
        var calls = 0
        val listener = Consumer<DrawerLoadProgress.State> { calls++; assertEquals(50, it.progress) }
        instrumentation.runOnMainSync {
            progress.addListener(listener)
            progress.removeListener(listener)
        }
        progress.finish(load)
        instrumentation.waitForIdleSync()
        assertEquals(1, calls)
    }

    @Test fun ringShowsPercentageAndSkipsCompletedLoadOnRecreation() {
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val view = DrawerLoadingView(context)
            view.setProgress(100)
            assertEquals(View.GONE, view.visibility)
            val loading = DrawerLoadProgress()
            val generation = loading.begin()
            loading.update(generation, 0, 244)
            view.setProgress(loading.state)
            assertEquals(View.VISIBLE, view.visibility)
            assertTrue(view.contentDescription.toString().contains("0/244"))
            loading.update(generation, 120, 244)
            view.setProgress(loading.state)
            assertTrue(view.contentDescription.toString().contains("49"))
            assertTrue(view.contentDescription.toString().contains("120/244"))
            val density = context.resources.displayMetrics.density
            val width = (176 * density).toInt()
            val height = (176 * density).toInt()
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(context.cacheDir, "drawer-loading-preview.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
            loading.finish(generation)
            view.setProgress(loading.state)
            assertTrue(view.contentDescription.toString().contains("244/244"))
            view.setProgress(-1)
            assertEquals(View.GONE, view.visibility)
        }
    }

    @Test fun countsChangeEvenWhenRoundedPercentageDoesNot() {
        val progress = DrawerLoadProgress()
        val generation = progress.begin()
        progress.update(generation, 0, 244)
        assertEquals(0, progress.state.completed)
        assertEquals(244, progress.state.total)
        progress.update(generation, 1, 244)
        assertEquals(0, progress.progress)
        assertEquals(1, progress.state.completed)
        progress.update(generation, 0, 244)
        assertEquals(1, progress.state.completed)
        val previous = progress.state
        progress.finish(generation)
        assertEquals(244, progress.state.completed)
        assertEquals(244, progress.state.total)
        assertEquals(1, previous.completed) // Published snapshots stay immutable.
        progress.begin()
        assertEquals(0, progress.state.completed)
        assertEquals(-1, progress.state.total)
    }

    @Test fun bulkIconProgressCountsDuplicateAndMissingComponents() {
        val context = instrumentation.targetContext
        val user = Process.myUserHandle()
        val activity = context.getSystemService(LauncherApps::class.java)
            .getActivityList(context.packageName, user).first()
        MODEL_EXECUTOR.submit {
            val cache = LauncherAppState.getInstance(context).iconCache
            val requests = listOf(
                AppInfo(context, activity, user),
                AppInfo(context, activity, user),
                AppInfo().apply { this.user = user },
            ).map { IconRequestInfo(it, activity, it.matchingLookupFlag) }
            val counts = mutableListOf<Int>()
            cache.getTitlesAndIconsInBulk(requests) { counts.add(it) }
            assertEquals(3, counts.last())
            assertTrue(counts.zipWithNext().all { (a, b) -> b >= a })
            assertSame(requests[0].itemInfo.bitmap, requests[1].itemInfo.bitmap)
            assertTrue(cache.isDefaultIcon(requests[2].itemInfo.bitmap, user))
        }.get()
    }
}
