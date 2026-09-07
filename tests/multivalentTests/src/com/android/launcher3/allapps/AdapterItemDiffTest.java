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
    public void uninstallMiddleApp_removesCorrectRowAndLeavesSurvivorsUnchanged() {
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
        assertEquals(0, changed[0]);
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
    public void appAndCategoryHeaderRebinding() {
        AdapterItem original = app("a", 0);
        assertTrue(original.isContentSame(AdapterItem.asApp(original.itemInfo)));
        AdapterItem differentCat = app("a", 0);
        differentCat.categoryId = "games";
        assertFalse(original.isContentSame(differentCat));
        assertFalse(AdapterItem.asCategoryHeader("Games", 3)
                .isContentSame(AdapterItem.asCategoryHeader("Games", 2)));
    }

    @Test
    public void folderContentDiffing_preventsSpuriousRebinds() {
        FolderInfo folder1 = new FolderInfo();
        folder1.id = 1;
        folder1.title = "Games";
        AdapterItem item1 = AdapterItem.asFolder(folder1);
        item1.categoryId = "games";

        FolderInfo folder2 = new FolderInfo();
        folder2.id = 1;
        folder2.title = "Games";
        AdapterItem item2 = AdapterItem.asFolder(folder2);
        item2.categoryId = "games";

        // Same folder content and category: content is same
        assertTrue(item1.isSameAs(item2));
        assertTrue(item1.isContentSame(item2));

        // Different category: not same item
        item2.categoryId = "other";
        assertFalse(item1.isSameAs(item2));
        assertFalse(item1.isContentSame(item2));
        item2.categoryId = "games";

        // Different folder title: same item, but content changed
        folder2.title = "New Games";
        assertTrue(item1.isSameAs(item2));
        assertFalse(item1.isContentSame(item2));
        folder2.title = "Games";

        // Different folder id: not same item
        folder2.id = 2;
        assertFalse(item1.isSameAs(item2));
        assertFalse(item1.isContentSame(item2));
    }

    @Test
    public void accordionCollapse_movesFoldersWithoutSpuriousChangeEvents() {
        AdapterItem headerAi = AdapterItem.asCategoryHeader("Ai", 2);
        headerAi.categoryId = "ai";
        headerAi.isAccordion = true;
        headerAi.isCollapsed = false;

        AdapterItem app1 = app("chatgpt", 0);
        app1.categoryId = "ai";
        AdapterItem app2 = app("gemini", 0);
        app2.categoryId = "ai";

        AdapterItem headerGames = AdapterItem.asCategoryHeader("Games", 2);
        headerGames.categoryId = "games";
        headerGames.isAccordion = true;
        headerGames.isCollapsed = false;

        FolderInfo gamesFolder = new FolderInfo();
        gamesFolder.id = 10;
        gamesFolder.title = "Favorites";
        AdapterItem folderItem = AdapterItem.asFolder(gamesFolder);
        folderItem.categoryId = "games";

        AdapterItem appGames = app("subway", 0);
        appGames.categoryId = "games";

        List<AdapterItem> before = Arrays.asList(headerAi, app1, app2, headerGames, folderItem, appGames);

        AdapterItem collapsedHeaderAi = AdapterItem.asCategoryHeader("Ai", 2);
        collapsedHeaderAi.categoryId = "ai";
        collapsedHeaderAi.isAccordion = true;
        collapsedHeaderAi.isCollapsed = true;

        FolderInfo gamesFolderAfter = new FolderInfo();
        gamesFolderAfter.id = 10;
        gamesFolderAfter.title = "Favorites";
        AdapterItem folderItemAfter = AdapterItem.asFolder(gamesFolderAfter);
        folderItemAfter.categoryId = "games";

        List<AdapterItem> after = Arrays.asList(collapsedHeaderAi, headerGames, folderItemAfter, appGames);

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

        int[] survivingItemsChanged = {0};
        diff.dispatchUpdatesTo(new ListUpdateCallback() {
            @Override public void onInserted(int position, int count) {}
            @Override public void onRemoved(int position, int count) {}
            @Override public void onMoved(int fromPosition, int toPosition) {}
            @Override public void onChanged(int position, int count, Object payload) {
                // Check if the change event target includes surviving folder (index 2) or app (index 3)
                if (position <= 3 && 2 < position + count) {
                    survivingItemsChanged[0]++;
                }
            }
        });

        // Surviving items (both folders and apps) MUST NOT receive an onChanged event during accordion collapse!
        assertEquals(0, survivingItemsChanged[0]);
    }
}
