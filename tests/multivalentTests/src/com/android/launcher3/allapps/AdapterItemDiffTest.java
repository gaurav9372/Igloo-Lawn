package com.android.launcher3.allapps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.os.UserHandle;

import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListUpdateCallback;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.runner.AndroidJUnit4;

import com.android.launcher3.allapps.BaseAllAppsAdapter.AdapterItem;
import com.android.launcher3.model.data.AppInfo;
import com.android.launcher3.model.data.FolderInfo;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class AdapterItemDiffTest {
    private static AdapterItem app(String name, int user) {
        AppInfo info = new AppInfo();
        info.componentName = new ComponentName("test." + name, "Main");
        info.user = new UserHandle(user);
        return AdapterItem.asApp(info);
    }

    @Test
    public void uninstallMiddleApp_removesCorrectRowAndRebindsSurvivors() {
        List<AdapterItem> before = Arrays.asList(app("a", 0), app("b", 0), app("c", 0));
        List<AdapterItem> after = Arrays.asList(app("a", 0), app("c", 0));
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return before.size(); }
            @Override public int getNewListSize() { return after.size(); }
            @Override public boolean areItemsTheSame(int oldPos, int newPos) {
                return before.get(oldPos).isSameAs(after.get(newPos));
            }
            @Override public boolean areContentsTheSame(int oldPos, int newPos) {
                return before.get(oldPos).isContentSame(after.get(newPos));
            }
        }, false);
        assertEquals(0, diff.convertOldPositionToNew(0));
        assertEquals(RecyclerView.NO_POSITION, diff.convertOldPositionToNew(1));
        assertEquals(1, diff.convertOldPositionToNew(2));
        int[] changed = {0};
        diff.dispatchUpdatesTo(new ListUpdateCallback() {
            @Override public void onInserted(int position, int count) {
                throw new AssertionError("Uninstall must not insert apps");
            }
            @Override public void onRemoved(int position, int count) {
                assertEquals(1, position);
                assertEquals(1, count);
            }
            @Override public void onMoved(int fromPosition, int toPosition) {
                throw new AssertionError("Move detection is disabled");
            }
            @Override public void onChanged(int position, int count, Object payload) {
                changed[0] += count;
            }
        });
        assertEquals(2, changed[0]);
    }

    @Test
    public void appIdentity_includesComponentProfileAndCategory() {
        AdapterItem original = app("a", 0);
        assertTrue(original.isSameAs(app("a", 0)));
        assertFalse(original.isSameAs(app("b", 0)));
        assertFalse(original.isSameAs(app("a", 10)));
        AdapterItem categorized = app("a", 0);
        categorized.categoryId = "games";
        assertFalse(original.isSameAs(categorized));
    }

    @Test
    public void sharedMutableModels_requireRebinding() {
        AdapterItem original = app("a", 0);
        assertFalse(original.isContentSame(AdapterItem.asApp(original.itemInfo)));
        FolderInfo folder = new FolderInfo();
        assertFalse(AdapterItem.asFolder(folder).isContentSame(AdapterItem.asFolder(folder)));
        assertFalse(AdapterItem.asCategoryHeader("Games", 3)
                .isContentSame(AdapterItem.asCategoryHeader("Games", 2)));
    }
}
