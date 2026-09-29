package app.lawnchair.data.wallpaper.model

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lawnchair.data.wallpaper.Wallpaper
import app.lawnchair.wallpaper.WallpaperManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WallpaperViewModel(application: Application) : AndroidViewModel(application) {
    private val wallpaperManagerCompat = WallpaperManagerCompat.INSTANCE.get(application)

    val wallpapers: StateFlow<List<Wallpaper>>
        field = MutableStateFlow<List<Wallpaper>>(emptyList())

    private val mutex = Mutex()
    private var disposed = false

    private val listener = object : WallpaperManagerCompat.OnColorsChangedListener {
        override fun onColorsChanged() {
            viewModelScope.launch {
                mutex.withLock {
                    refreshWallpapers()
                }
            }
        }
    }

    init {
        loadTopWallpapers()
        wallpaperManagerCompat.addOnChangeListener(listener)
    }

    private suspend fun refreshWallpapers() {
        val topWallpapers = wallpaperManagerCompat.service.dao.getTopWallpapers()
        wallpapers.value = topWallpapers
    }

    private fun loadTopWallpapers() {
        viewModelScope.launch {
            mutex.withLock {
                refreshWallpapers()
            }
        }
    }

    suspend fun updateWallpaperRank(wallpaper: Wallpaper) {
        wallpaperManagerCompat.service.updateWallpaperRank(wallpaper)
        loadTopWallpapers()
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        wallpaperManagerCompat.removeOnChangeListener(listener)
        viewModelScope.coroutineContext.cancelChildren()
    }

    override fun onCleared() {
        dispose()
        super.onCleared()
    }
}
