package app.lawnchair.allapps

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object MultiSelectManager {
    private val _isMultiSelectActive = MutableStateFlow(false)
    val isMultiSelectActive: StateFlow<Boolean> = _isMultiSelectActive.asStateFlow()

    private val _selectedComponentKeys = MutableStateFlow<Set<String>>(emptySet())
    val selectedComponentKeys: StateFlow<Set<String>> = _selectedComponentKeys.asStateFlow()

    fun startMultiSelect(initialComponentKey: String? = null) {
        _isMultiSelectActive.value = true
        _selectedComponentKeys.value = if (initialComponentKey != null) setOf(initialComponentKey) else emptySet()
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

    fun isSelected(componentKey: String): Boolean {
        return _selectedComponentKeys.value.contains(componentKey)
    }

    fun exitMultiSelect() {
        _isMultiSelectActive.value = false
        _selectedComponentKeys.value = emptySet()
    }
}
