package app.lawnchair.backup.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.lawnchair.backup.LawnchairBackup
import app.lawnchair.util.removeFlag
import app.lawnchair.util.restartLauncher
import com.android.launcher3.R
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface RestoreBackupUiState {
    val isLoading: Boolean

    data class Success(val backup: LawnchairBackup) : RestoreBackupUiState {
        override val isLoading: Boolean = false
    }

    data object Loading : RestoreBackupUiState {
        override val isLoading: Boolean = true
    }

    data object Error : RestoreBackupUiState {
        override val isLoading: Boolean = true
    }
}

private data class RestoreBackupViewModelState(
    val backup: LawnchairBackup? = null,
    val hasError: Boolean = false,
) {
    fun toUiState(): RestoreBackupUiState = when {
        hasError -> RestoreBackupUiState.Error
        backup != null -> RestoreBackupUiState.Success(backup)
        else -> RestoreBackupUiState.Loading
    }
}

class RestoreBackupViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private var initialized = false
    private lateinit var backupUri: Uri

    private val viewModelState = MutableStateFlow(RestoreBackupViewModelState())
    val uiState = viewModelState
        .map { it.toUiState() }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            viewModelState.value.toUiState(),
        )

    val backupContents = savedStateHandle.getStateFlow("contents", 0)
    private val _restoring = MutableStateFlow(false)
    val restoring = _restoring.asStateFlow()

    fun restore(contents: Int) {
        val backup = (uiState.value as? RestoreBackupUiState.Success)?.backup ?: return
        if (_restoring.value) return
        _restoring.value = true
        viewModelScope.launch {
            // Finish the commit and restart even if the screen rotates or is disposed meanwhile.
            withContext(NonCancellable) {
                val context = getApplication<Application>()
                try {
                    backup.restore(contents)
                    Toast.makeText(context, R.string.backup_restore_success, Toast.LENGTH_SHORT).show()
                    restartLauncher(context)
                } catch (failure: Throwable) {
                    Log.e("RestoreBackupViewModel", "Failed to restore backup", failure)
                    Toast.makeText(context, R.string.backup_restore_error, Toast.LENGTH_SHORT).show()
                } finally {
                    _restoring.value = false
                }
            }
        }
    }

    fun init(backupUri: Uri) {
        if (initialized) return
        initialized = true
        this.backupUri = backupUri
        viewModelScope.launch {
            try {
                val backup = LawnchairBackup(getApplication(), backupUri)
                backup.readInfoAndPreview()
                setBackupContents(backup.info.contents.removeFlag(LawnchairBackup.INCLUDE_WALLPAPER))
                viewModelState.update { it.copy(backup = backup) }
            } catch (t: Throwable) {
                Log.e("RestoreBackupViewModel", "failed to parse backup", t)
                viewModelState.update { it.copy(hasError = true) }
            }
        }
    }

    fun setBackupContents(contents: Int) {
        savedStateHandle["contents"] = contents
    }
}
