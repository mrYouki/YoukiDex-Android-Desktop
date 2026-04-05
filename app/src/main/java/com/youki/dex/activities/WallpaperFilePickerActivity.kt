package com.youki.dex.activities

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.webkit.MimeTypeMap
import com.youki.dex.utils.WallpaperManagerHelper

/**
 * WallpaperFilePickerActivity — Trampoline activity for the file picker.
 *
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  Why ACTION_OPEN_DOCUMENT and not ACTION_GET_CONTENT?                  │
 * │                                                                      │
 * │  • ACTION_GET_CONTENT is sometimes hijacked by Google Photos/Drive      │
 * │    showing a media grid instead of the real file browser              │
 * │  • ACTION_OPEN_DOCUMENT always opens DocumentsUI                       │
 * │    and allows browsing downloads, internal storage, SD card...         │
 * │                                                                      │
 * │  Main fix:                                                             │
 * │  • Removed EXTRA_LOCAL_ONLY = true — it was blocking some folders like  │
 * │    Downloads and cross-app sharing                                     │
 * └──────────────────────────────────────────────────────────────────────┘
 */
class WallpaperFilePickerActivity : Activity() {

    companion object {
        private const val TAG                    = "WallpaperFilePicker"
        private const val REQUEST_PICK           = 1001
        private const val REQUEST_ALL_FILES_PERM = 1002
        const val ACTION_WALLPAPER_FILE_PICKED   = "com.youki.dex.WALLPAPER_FILE_PICKED"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)

        // Android 11+: request MANAGE_EXTERNAL_STORAGE so the file browser shows
        // restricted paths such as /Android/data and /Android/obb
        if (needsAllFilesPermission()) {
            requestAllFilesPermission()
        } else {
            openFilePicker()
        }
    }

    // ─── Permissions ─────────────────────────────────────────────────────────

    private fun needsAllFilesPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return !Environment.isExternalStorageManager()
    }

    private fun requestAllFilesPermission() {
        try {
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                },
                REQUEST_ALL_FILES_PERM
            )
        } catch (e: Exception) {
            Log.w(TAG, "App-specific page unavailable: ${e.message}")
            try {
                startActivityForResult(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                    REQUEST_ALL_FILES_PERM
                )
            } catch (e2: Exception) {
                Log.w(TAG, "All-files screen unavailable: ${e2.message}")
                openFilePicker() // ROM does not support it — open picker directly
            }
        }
    }

    // ─── File picker ─────────────────────────────────────────────────────────

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                WallpaperManagerHelper.PICKER_MIME_TYPES
            )
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)

            // Note: EXTRA_LOCAL_ONLY = true was intentionally removed.
            // It was blocking file selection from Downloads and some other folders
            // on certain devices. Without it the picker works with all sources.
        }
        try {
            startActivityForResult(intent, REQUEST_PICK)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open file picker: ${e.message}")
            finish()
        }
    }

    // ─── Results ─────────────────────────────────────────────────────────────

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {

            REQUEST_ALL_FILES_PERM -> {
                // Whether the permission was granted or denied, open the picker
                // The picker works even without this permission, but with fewer paths
                openFilePicker()
            }

            REQUEST_PICK -> {
                if (resultCode != RESULT_OK || data?.data == null) { finish(); return }
                val uri = data.data!!

                // Persist the read permission after the picker closes
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    // Non-fatal — some URIs do not support persistable permissions
                    Log.w(TAG, "takePersistableUriPermission failed (non-fatal): ${e.message}")
                }

                val ext = resolveExtension(uri)
                if (ext == null || ext.lowercase() !in WallpaperManagerHelper.ALL_EXTENSIONS) {
                    Log.w(TAG, "Unsupported or unresolved extension for URI: $uri (ext=$ext)")
                    finish(); return
                }

                WallpaperManagerHelper.copyWallpaperToLibrary(
                    context  = this,
                    sourceUri = uri,
                    extension = ext
                ) { success, message ->
                    Log.d(TAG, "copy → success=$success msg=$message")
                    if (success) {
                        sendBroadcast(Intent(ACTION_WALLPAPER_FILE_PICKED).apply {
                            `package` = packageName
                        })
                    }
                    finish()
                }
            }

            else -> finish()
        }
    }

    // ─── Extension resolver ──────────────────────────────────────────────────

    /**
     * Determines the file extension from a URI using three fallback approaches:
     *   1. MIME type from ContentResolver (we ignore application/octet-stream)
     *   2. File name from the DISPLAY_NAME column
     *   3. Last segment of the URI path
     *
     * This covers:
     *   • content://media/external/...      → accurate MIME type
     *   • content://downloads/...           → DISPLAY_NAME
     *   • content://com.google.android.apps.photos.contentprovider/... → MIME type
     *   • file:///sdcard/...                → path segment
     */
    private fun resolveExtension(uri: Uri): String? {
        // 1. MIME type (most reliable + fastest)
        val mime = contentResolver.getType(uri)
        if (!mime.isNullOrBlank() && mime != "application/octet-stream") {
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            if (!ext.isNullOrBlank()) {
                Log.d(TAG, "Extension from MIME '$mime' → '$ext'")
                return ext
            }
        }

        // 2. DISPLAY_NAME from ContentResolver
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getString(0) ?: ""
                val i = name.lastIndexOf('.')
                if (i >= 0 && i < name.length - 1) {
                    val ext = name.substring(i + 1)
                    Log.d(TAG, "Extension from display name '$name' → '$ext'")
                    return ext
                }
            }
        }

        // 3. Last path segment in the URI
        val seg = uri.lastPathSegment ?: return null
        val i   = seg.lastIndexOf('.')
        return if (i >= 0 && i < seg.length - 1) {
            val ext = seg.substring(i + 1)
            Log.d(TAG, "Extension from URI segment '$seg' → '$ext'")
            ext
        } else {
            Log.w(TAG, "Cannot resolve extension from URI: $uri")
            null
        }
    }
}