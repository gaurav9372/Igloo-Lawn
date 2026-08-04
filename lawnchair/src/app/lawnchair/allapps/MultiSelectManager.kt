package app.lawnchair.allapps

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object MultiSelectManager {
    private val _isMultiSelectActive = MutableStateFlow(false)
    val isMultiSelectActive: StateFlow<Boolean> = _isMultiSelectActive.asStateFlow()

    private val _selectedComponentKeys = MutableStateFlow<Set<String>>(emptySet())
    val selectedComponentKeys: StateFlow<Set<String>> = _selectedComponentKeys.asStateFlow()

    /** True when multi-select was triggered from the homescreen (not the app drawer). */
    private val _isHomescreenMode = MutableStateFlow(false)
    val isHomescreenMode: StateFlow<Boolean> = _isHomescreenMode.asStateFlow()

    /**
     * Maps componentKey string → workspace ItemInfo.id for homescreen items.
     * Populated only when [isHomescreenMode] is true.
     */
    private val _selectedItemIds = MutableStateFlow<Map<String, Int>>(emptyMap())
    val selectedItemIds: StateFlow<Map<String, Int>> = _selectedItemIds.asStateFlow()

    /** Start multi-select from the app drawer. */
    fun startMultiSelect(initialComponentKey: String? = null) {
        _isHomescreenMode.value = false
        _selectedItemIds.value = emptyMap()
        _isMultiSelectActive.value = true
        _selectedComponentKeys.value = if (initialComponentKey != null) setOf(initialComponentKey) else emptySet()
    }

    /** Start multi-select from the homescreen. */
    fun startHomescreenMultiSelect(initialComponentKey: String, itemId: Int) {
        _isHomescreenMode.value = true
        _isMultiSelectActive.value = true
        _selectedComponentKeys.value = setOf(initialComponentKey)
        _selectedItemIds.value = mapOf(initialComponentKey to itemId)
    }

    fun toggleSelection(componentKey: String) {
        val current = _selectedComponentKeys.value.toMutableSet()
        if (current.contains(componentKey)) {
            current.remove(componentKey)
        } else {
            current.add(componentKey)
        }
        _selectedComponentKeys.value = current
    }

    /** Toggle homescreen item selection, storing its workspace id. */
    fun toggleHomescreenSelection(componentKey: String, itemId: Int) {
        val currentKeys = _selectedComponentKeys.value.toMutableSet()
        val currentIds = _selectedItemIds.value.toMutableMap()
        if (currentKeys.contains(componentKey)) {
            currentKeys.remove(componentKey)
            currentIds.remove(componentKey)
        } else {
            currentKeys.add(componentKey)
            currentIds[componentKey] = itemId
        }
        _selectedComponentKeys.value = currentKeys
        _selectedItemIds.value = currentIds
    }

    fun isSelected(componentKey: String): Boolean {
        return _selectedComponentKeys.value.contains(componentKey)
    }

    fun exitMultiSelect() {
        _isMultiSelectActive.value = false
        _isHomescreenMode.value = false
        _selectedComponentKeys.value = emptySet()
        _selectedItemIds.value = emptyMap()
    }
}
