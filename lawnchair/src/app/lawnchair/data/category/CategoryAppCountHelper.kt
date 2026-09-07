package app.lawnchair.data.category

import app.lawnchair.data.folder.FolderEntry

object CategoryAppCountHelper {
    const val NO_CATEGORY_ID = -100

    /**
     * Resolves the set of eligible main-profile app component key strings, excluding hidden apps.
     */
    fun getEligibleAppKeys(
        mainProfileAppKeys: Collection<String>,
        hiddenApps: Set<String>,
    ): Set<String> {
        return mainProfileAppKeys.filterNot { hiddenApps.contains(it) }.toSet()
    }

    /**
     * Computes the set of app keys claimed by all user-defined categories,
     * including apps pulled in through folder sharing.
     */
    fun getAllUserClaimedKeys(
        categories: List<CategoryEntry>?,
        folders: List<FolderEntry>?,
    ): Set<String> {
        val directUserCategoryKeys = categories
            ?.filter { it.id != NO_CATEGORY_ID }
            ?.flatMap { it.itemComponentKeys }
            ?.toSet() ?: emptySet()

        val userCategoryFolderAppKeys = folders
            ?.filter { folder -> folder.itemComponentKeys.any { directUserCategoryKeys.contains(it) } }
            ?.flatMap { it.itemComponentKeys }
            ?.toSet() ?: emptySet()

        return directUserCategoryKeys + userCategoryFolderAppKeys
    }

    /**
     * Computes unassigned app keys for "No Category".
     */
    fun getUnassignedKeys(
        eligibleAppKeys: Set<String>,
        allUserClaimedKeys: Set<String>,
    ): List<String> {
        return eligibleAppKeys.filterNot { allUserClaimedKeys.contains(it) }
    }

    /**
     * Computes the resolved app count for a specific category (matching the App Drawer header count).
     */
    fun getCategoryAppCount(
        category: CategoryEntry,
        folders: List<FolderEntry>?,
        eligibleAppKeys: Set<String>,
        unassignedKeys: List<String>,
    ): Int {
        if (category.id == NO_CATEGORY_ID) {
            return unassignedKeys.size
        }
        val folderList = folders ?: emptyList()
        val catFolderAppKeys = folderList
            .filter { folder -> folder.itemComponentKeys.any { category.itemComponentKeys.contains(it) } }
            .flatMap { it.itemComponentKeys }
        val fullCategoryAppKeys = (category.itemComponentKeys + catFolderAppKeys).distinct()
        return fullCategoryAppKeys.count { eligibleAppKeys.contains(it) }
    }

    /**
     * Computes a map of category ID to resolved app count for all categories.
     */
    fun computeCategoryCounts(
        categories: List<CategoryEntry>?,
        folders: List<FolderEntry>?,
        eligibleAppKeys: Set<String>,
        unassignedKeys: List<String>,
    ): Map<Int, Int> {
        val counts = mutableMapOf<Int, Int>()
        counts[NO_CATEGORY_ID] = unassignedKeys.size
        categories?.forEach { category ->
            if (category.id != NO_CATEGORY_ID) {
                counts[category.id] = getCategoryAppCount(category, folders, eligibleAppKeys, unassignedKeys)
            }
        }
        return counts
    }
}
