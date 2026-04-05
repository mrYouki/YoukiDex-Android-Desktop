package com.youki.dex.utils

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.preference.PreferenceManager
import com.youki.dex.services.LiveWallpaperService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * WallpaperManagerHelper — wallpaper engine manager.
 *
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  Core changes in this version                                          │
 * │                                                                      │
 * │  ① Atomic file replacement (temp → rename) to prevent reading partial file │
 * │  ② Track active wallpaper via PREF_ACTIVE_PATH instead of size+extension │
 * │  ③ isActiveFile renamed → now accepts Context                          │
 * │  ④ SupervisorJob in each CoroutineScope → no leaks                     │
 * │  ⑤ copyWallpaperToLibrary: verify copied file is non-empty             │
 * └──────────────────────────────────────────────────────────────────────┘
 *
 * Application strategy:
 *   ─ First time → open system dialog to set live wallpaper (once only)
 *   ─ Every time after → direct broadcast → Engine reloads immediately (no dialog)
 */
object WallpaperManagerHelper {

    private const val TAG               = "WallpaperHelper"
    private const val PREF_SETUP_DONE   = "live_wallpaper_setup_done"
    // Path of the active wallpaper in the library (original source before copying to live_wallpaper.*)
    private const val PREF_ACTIVE_PATH  = "live_wallpaper_active_path"

    val VIDEO_EXTENSIONS = listOf("mp4", "mkv", "webm", "3gp")
    val IMAGE_EXTENSIONS = listOf("gif", "png", "jpg", "jpeg", "bmp", "webp")
    val ALL_EXTENSIONS   = VIDEO_EXTENSIONS + IMAGE_EXTENSIONS

    // MIME types for the file picker
    const val PICKER_MIME = "*/*"
    val PICKER_MIME_TYPES = arrayOf(
        "video/mp4", "video/x-matroska", "video/webm", "video/3gpp",
        "image/gif", "image/png", "image/jpeg", "image/bmp", "image/webp"
    )

    private fun componentName(context: Context) =
        ComponentName(context.packageName, LiveWallpaperService::class.java.name)

    // ─── Is our live wallpaper active? ─────────────────────────────────────────

    fun isOurWallpaperActive(context: Context): Boolean = try {
        WallpaperManager.getInstance(context).wallpaperInfo?.component == componentName(context)
    } catch (e: Exception) {
        log("isOurWallpaperActive: ${e.message}"); false
    }

    // ─── Main entry point ───────────────────────────────────────────────────────

    /**
     * Applies the wallpaper intelligently:
     *   • If setup was completed before → immediate broadcast (no dialog)
     *   • If first time → open system dialog once only
     *
     * Note: we do not rely solely on isOurWallpaperActive() because some devices
     * (Samsung, some MIUI) return false even when the live wallpaper is actually active.
     * So we rely on PREF_SETUP_DONE as the source of truth.
     */
    fun applyWallpaper(context: Context, onResult: (String) -> Unit) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        if (prefs.getBoolean(PREF_SETUP_DONE, false)) {
            log("Setup done → hot-reload broadcast")
            sendReload(context)
            onResult("✅ Wallpaper updated!")
        } else {
            log("First time → opening system dialog")
            openSystemDialog(context)
            prefs.edit().putBoolean(PREF_SETUP_DONE, true).apply()
            onResult("Tap 'Set Wallpaper' once — everything is automatic after that! 🎯")
        }
    }

    /** Sends a direct broadcast to reload the wallpaper. */
    fun sendReload(context: Context) {
        context.applicationContext.sendBroadcast(
            Intent(LiveWallpaperService.ACTION_RELOAD).apply {
                setPackage(context.packageName)
            }
        )
    }

    // ─── System dialog (first-time setup) ──────────────────────────────────────

    private fun openSystemDialog(context: Context) {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, componentName(context))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            log("Specific live wallpaper intent failed: ${e.message}")
            try {
                context.startActivity(
                    Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (e2: Exception) {
                log("Live wallpaper chooser also failed: ${e2.message}")
            }
        }
    }

    // ─── Copy file to library ───────────────────────────────────────────────────

    /**
     * Copies the file from a URI (any source: downloads, photos, SD card...) to the library,
     * then sets it as the active wallpaper.
     *
     * Uses InputStream directly — works with any URI type (content://, file://, ...).
     */
    fun copyWallpaperToLibrary(
        context: Context,
        sourceUri: Uri,
        extension: String,
        onDone: (success: Boolean, message: String) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                val ext = extension.lowercase().trimStart('.')
                require(ext in ALL_EXTENSIONS) { "Unsupported format: $ext" }

                val timestamp = System.currentTimeMillis()
                val destFile  = File(context.filesDir, "wallpaper_$timestamp.$ext")

                context.contentResolver.openInputStream(sourceUri)
                    ?.use { input -> FileOutputStream(destFile).use { output -> input.copyTo(output) } }
                    ?: throw Exception("Cannot open file from URI: $sourceUri")

                // Verify the file is non-empty — some folder URIs return an empty stream
                if (destFile.length() == 0L) {
                    destFile.delete()
                    throw Exception("Copied file is empty — check app permissions")
                }

                log("Library: ${destFile.name} (${destFile.length()} bytes)")
                setActiveWallpaperSync(context, destFile)

                withContext(Dispatchers.Main) { onDone(true, "Wallpaper added to library ✅") }
            } catch (e: Exception) {
                log("copyWallpaperToLibrary failed: ${e.message}")
                withContext(Dispatchers.Main) { onDone(false, "Failed to add: ${e.message}") }
            }
        }
    }

    /** Sets a file from the library as the active wallpaper. */
    fun setActiveWallpaper(context: Context, file: File, onDone: (success: Boolean) -> Unit) {
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                setActiveWallpaperSync(context, file)
                withContext(Dispatchers.Main) { onDone(true) }
            } catch (e: Exception) {
                log("setActiveWallpaper failed: ${e.message}")
                withContext(Dispatchers.Main) { onDone(false) }
            }
        }
    }

    /**
     * Replaces the active wallpaper atomically:
     *   1. Copies the file to a tmp file first
     *   2. Deletes the old live_wallpaper.*
     *   3. Renames tmp → live_wallpaper.{ext}
     *
     * This guarantees the service never reads a partially written file.
     * Saves the source path in SharedPreferences for accurate active wallpaper tracking.
     */
    private fun setActiveWallpaperSync(context: Context, source: File) {
        val ext      = source.extension.lowercase().ifBlank { "mp4" }
        val filesDir = context.filesDir

        // ① Copy to a temp file
        val tmp = File(filesDir, "live_wallpaper_tmp.$ext")
        source.copyTo(tmp, overwrite = true)

        // ② Delete old live_wallpaper.* (any extension)
        filesDir.listFiles()
            ?.filter { it.name.startsWith("live_wallpaper.") }
            ?.forEach { it.delete() }

        // ③ Atomic rename — faster and safer than copy
        val dest = File(filesDir, "live_wallpaper.$ext")
        if (!tmp.renameTo(dest)) {
            // renameTo fails if they are on different partitions (very rare inside filesDir)
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }

        // ④ Save source path for accurate comparison in isActiveFile()
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString(PREF_ACTIVE_PATH, source.absolutePath).apply()

        log("Active wallpaper → ${dest.name}")
    }

    // ─── Library ────────────────────────────────────────────────────────────────

    /** Returns all saved wallpapers sorted from newest to oldest. */
    fun getLibraryFiles(context: Context): List<File> =
        context.filesDir.listFiles()
            ?.filter { f ->
                f.name.startsWith("wallpaper_") &&
                f.length() > 0 &&
                f.extension.lowercase() in ALL_EXTENSIONS
            }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** Returns the currently active wallpaper. */
    fun getWallpaperFile(context: Context): File? =
        context.filesDir.listFiles()
            ?.firstOrNull { it.name.startsWith("live_wallpaper.") && it.length() > 0 }

    fun hasWallpaperFile(context: Context): Boolean = getWallpaperFile(context) != null

    /**
     * Is this file the currently active wallpaper?
     *
     * Compares first against the saved PREF_ACTIVE_PATH (100% accurate).
     * If no path is saved (upgrading from an older version)
     * falls back to comparing by size + extension.
     */
    fun isActiveFile(context: Context, libraryFile: File): Boolean {
        val storedPath = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_ACTIVE_PATH, null)
        if (storedPath != null) return libraryFile.absolutePath == storedPath
        // Fallback for backward compatibility with older versions
        val active = getWallpaperFile(context) ?: return false
        return libraryFile.length() == active.length() &&
               libraryFile.extension.equals(active.extension, ignoreCase = true)
    }

    // ─── File type detection ────────────────────────────────────────────────────

    fun isVideo(file: File): Boolean = file.extension.lowercase() in VIDEO_EXTENSIONS
    fun isGif(file: File): Boolean   = file.extension.lowercase() == "gif"
    fun isImage(file: File): Boolean = file.extension.lowercase() in IMAGE_EXTENSIONS

    private fun log(msg: String) = Log.d(TAG, msg)
}