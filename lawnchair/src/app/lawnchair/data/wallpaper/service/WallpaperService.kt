package app.lawnchair.data.wallpaper.service

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import app.lawnchair.data.AppDatabase
import app.lawnchair.data.wallpaper.Wallpaper
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.dagger.LauncherAppComponent
import com.android.launcher3.dagger.LauncherAppSingleton
import com.android.launcher3.util.DaggerSingletonObject
import com.android.launcher3.util.SafeCloseable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@LauncherAppSingleton
class WallpaperService @Inject constructor(
    @ApplicationContext private val context: Context,
) : SafeCloseable {

    val dao = AppDatabase.Companion.INSTANCE.get(context).wallpaperDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveMutex = Mutex()

    @Volatile
    private var hasWallpaperHistory = false

    init {
        scope.launch {
            hasWallpaperHistory = dao.getTopWallpapers().isNotEmpty()
        }
    }

    suspend fun saveWallpaper(wallpaperManager: WallpaperManager) = withContext(Dispatchers.IO) {
        saveMutex.withLock {
            try {
                val wallpaperDrawable = wallpaperManager.drawable ?: return@withLock
                val sourceBitmap = (wallpaperDrawable as? BitmapDrawable)?.bitmap
                    ?: wallpaperDrawable.toBitmap()
                val storedBitmap = sourceBitmap.downscaleForHistory(MAX_WALLPAPER_DIMENSION)
                try {
                    saveWallpaper(storedBitmap)
                } finally {
                    if (storedBitmap !== sourceBitmap) storedBitmap.recycle()
                }
            } catch (e: Exception) {
                Log.e("WallpaperChange", "Error detecting wallpaper change", e)
            }
        }
    }

    private fun calculateChecksum(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun saveWallpaper(bitmap: Bitmap) {
        val timestamp = System.currentTimeMillis()
        val pendingFile = writePendingWallpaper(bitmap)
        val checksum = calculateChecksum(pendingFile)
        val existingWallpapers = dao.getTopWallpapers()

        if (existingWallpapers.any { it.checksum == checksum }) {
            pendingFile.delete()
            hasWallpaperHistory = existingWallpapers.isNotEmpty()
            Log.d("WallpaperService", "Wallpaper already exists with checksum: $checksum")
            return
        }

        val imageFile = File(pendingFile.parentFile, "wallpaper_$checksum.jpg")
        if (!pendingFile.renameTo(imageFile)) {
            pendingFile.copyTo(imageFile, overwrite = true)
            pendingFile.delete()
        }

        if (existingWallpapers.size >= MAX_WALLPAPER_HISTORY) {
            existingWallpapers.minByOrNull { it.timestamp }?.let { oldest ->
                dao.deleteWallpaper(oldest.id)
                deleteWallpaperFile(oldest.imagePath)
            }
        }

        dao.insert(
            Wallpaper(
                imagePath = imageFile.absolutePath,
                rank = 0,
                timestamp = timestamp,
                checksum = checksum,
            ),
        )
        hasWallpaperHistory = true
    }

    suspend fun updateWallpaperRank(selectedWallpaper: Wallpaper) {
        val currentTime = System.currentTimeMillis()
        dao.updateWallpaper(selectedWallpaper.id, rank = 0, timestamp = currentTime)
    }

    suspend fun getTopWallpapers(): List<Wallpaper> = dao.getTopWallpapers()

    fun hasWallpapersCached(): Boolean = hasWallpaperHistory

    private fun deleteWallpaperFile(imagePath: String) {
        val file = File(imagePath)
        if (file.exists()) {
            file.delete()
        }
    }

    private fun writePendingWallpaper(bitmap: Bitmap): File {
        val storageDir = File(context.filesDir, "wallpapers")
        check(storageDir.exists() || storageDir.mkdirs()) { "Unable to create wallpaper history directory" }
        val imageFile = File.createTempFile("wallpaper_pending_", ".jpg", storageDir)
        FileOutputStream(imageFile).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, WALLPAPER_JPEG_QUALITY, output)) {
                "Unable to encode wallpaper history image"
            }
        }
        return imageFile
    }

    override fun close() {
        scope.cancel()
    }

    companion object {
        private const val MAX_WALLPAPER_DIMENSION = 2160
        private const val MAX_WALLPAPER_HISTORY = 4
        private const val WALLPAPER_JPEG_QUALITY = 90

        @JvmField
        val INSTANCE = DaggerSingletonObject(LauncherAppComponent::getWallpaperService)
    }
}

private fun Bitmap.downscaleForHistory(maxDimension: Int): Bitmap {
    val largestDimension = maxOf(width, height)
    if (largestDimension <= maxDimension) return this
    val scale = maxDimension.toFloat() / largestDimension
    return Bitmap.createScaledBitmap(
        this,
        (width * scale).toInt().coerceAtLeast(1),
        (height * scale).toInt().coerceAtLeast(1),
        true,
    )
}
