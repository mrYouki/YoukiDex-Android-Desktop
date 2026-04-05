package com.youki.dex.fragments

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.youki.dex.R
import com.youki.dex.preferences.SoundFilePreference
import com.youki.dex.utils.DeviceUtils
import com.youki.dex.utils.ShizukoManager
import java.io.File
import java.io.IOException

/**
 * SoundsPreferences — fully rewritten
 *
 * Core difference from the old version:
 *  ┌─────────────────────────────────────────────────────────────────┐
 *  │ Old:  saves content URI → BroadcastReceiver → MediaPlayer          │
 *  │ New:  copies file → absolute path → Settings.System / SharedPrefs  │
 *  └─────────────────────────────────────────────────────────────────┘
 *
 * System sounds (sys_*):
 *   ✅ Written directly to Settings.System
 *   ✅ System plays them automatically at the right time (lock, dock, battery…)
 *   ✅ No BroadcastReceiver or MediaPlayer needed
 *
 * Custom YoukiDex sounds (startup, usb, charge…):
 *   ✅ Saved as a path in SharedPreferences
 *   ✅ DockService plays them via the fixed MediaPlayer (with GC fix)
 */
class SoundsPreferences : PreferenceFragmentCompat() {

    // ══════════════════════════════════════════════════════
    // Map: preference key → Settings.System key
    // ══════════════════════════════════════════════════════
    private val systemSoundMap = mapOf(
        "sys_lock_sound"              to "lock_sound",
        "sys_unlock_sound"            to "unlock_sound",
        "sys_low_battery_sound"       to "low_battery_sound",
        "sys_wireless_charging_sound" to "wireless_charging_started_sound",
        "sys_car_dock_sound"          to "car_dock_sound",
        "sys_car_undock_sound"        to "car_undock_sound",
        "sys_desk_dock_sound"         to "desk_dock_sound",
        "sys_desk_undock_sound"       to "desk_undock_sound"
    )

    // YoukiDex sounds — stored in SharedPrefs only
    private val customSoundKeys = setOf(
        "startup_sound", "usb_sound", "charge_sound",
        "charge_complete_sound", "notification_sound"
    )

    // The preference the user tapped most recently
    private var pendingPref: SoundFilePreference? = null

    // ──── File Picker ────
    private val pickAudio = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        handleFilePicked(uri)
    }

    // ══════════════════════════════════════════════════════
    //  Lifecycle
    // ══════════════════════════════════════════════════════

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_sounds, rootKey)
        setupChargingToggle()
        setupAllSoundPreferences()
    }

    // ══════════════════════════════════════════════════════
    //  Setup
    // ══════════════════════════════════════════════════════

    private fun setupChargingToggle() {
        findPreference<SwitchPreferenceCompat>("charging_sounds_enabled")
            ?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                val success = DeviceUtils.putSystemSetting(
                    requireContext(),
                    "charging_sounds_enabled",
                    if (enabled) "1" else "0"
                )
                if (!success) showPermissionToast()
                true
            }
    }

    private fun setupAllSoundPreferences() {
        val allKeys = systemSoundMap.keys + customSoundKeys
        allKeys.forEach { prefKey ->
            findPreference<SoundFilePreference>(prefKey)?.setOnPreferenceClickListener { pref ->
                pendingPref = pref as SoundFilePreference
                pickAudio.launch(arrayOf("audio/*"))
                true
            }
        }
    }

    // ══════════════════════════════════════════════════════
    // Handle file selection result
    // ══════════════════════════════════════════════════════

    private fun handleFilePicked(uri: Uri) {
        val pref = pendingPref ?: return
        pendingPref = null

        // ① Copy the file to app-private storage
        val destFile = copyAudioFile(uri, pref.key) ?: run {
            showToast(getString(R.string.sound_copy_failed))
            return
        }

        val absolutePath = destFile.absolutePath

        // ② Save path in SharedPreferences (shows file name in UI)
        pref.setSound(absolutePath)

        // ③ If a system sound → write to Settings.System via Shizuku or WRITE_SETTINGS
        val systemKey = systemSoundMap[pref.key]
        if (systemKey != null) {
            applySystemSound(systemKey, absolutePath)
        }
        // Custom YoukiDex sounds: setSound() is enough — DockService reads from SharedPrefs
    }

    // ══════════════════════════════════════════════════════
    // Write to Settings.System
    // ══════════════════════════════════════════════════════

    private fun applySystemSound(systemKey: String, filePath: String) {
        val ctx = requireContext()
        val shizuku = ShizukoManager.getInstance(ctx)

        when {
            shizuku.isAvailable && shizuku.hasPermission -> {
                // ① chmod so SystemUI can read the file
                shizuku.runShell(buildChmodCommands(filePath)) { chmodResult ->
                    Log.d("YoukiDex", "chmod: $chmodResult")
                    // ② Write the path to Settings.System
                    val ok = DeviceUtils.putSystemSetting(ctx, systemKey, filePath)
                    if (!ok) showPermissionToast()
                    else showToast(getString(R.string.sound_applied))
                }
            }

            DeviceUtils.hasWriteSettingsPermission(ctx) -> {
                DeviceUtils.putSystemSetting(ctx, systemKey, filePath)
                showToast(getString(R.string.sound_applied))
            }

            else -> showPermissionToast()
        }
    }

    /**
     * Builds chmod commands to make the file readable by SystemUI.
     * SystemUI runs as the "system" user → needs execute on parent dirs + read on the file.
     */
    private fun buildChmodCommands(filePath: String): String {
        val file     = File(filePath)
        val soundsDir = file.parent ?: return "chmod 644 \"$filePath\""
        val filesDir  = File(soundsDir).parent ?: return "chmod 644 \"$filePath\""
        val dataDir   = File(filesDir).parent  ?: return "chmod 644 \"$filePath\""

        return """
            chmod 711 "$dataDir"
            chmod 711 "$filesDir"
            chmod 711 "$soundsDir"
            chmod 644 "$filePath"
        """.trimIndent()
    }

    // ══════════════════════════════════════════════════════
    // Copy file
    // ══════════════════════════════════════════════════════

    /**
     * Copies the audio file from a content URI to:
     *   [filesDir]/sounds/<prefKey>.<ext>
     *
     * Example: /data/data/com.youki.dex/files/sounds/startup_sound.mp3
     *
     * No takePersistableUriPermission needed — the copy happens immediately
     * before exiting the result callback.
     */
    private fun copyAudioFile(uri: Uri, prefKey: String): File? {
        return try {
            val ctx = requireContext()
            val ext = getFileExtension(uri) ?: "ogg"
            val soundsDir = File(ctx.filesDir, "sounds").apply { mkdirs() }
            val destFile = File(soundsDir, "$prefKey.$ext")

            ctx.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.d("YoukiDex", "Audio copied → ${destFile.absolutePath}")
            destFile
        } catch (e: IOException) {
            Log.e("YoukiDex", "copyAudioFile failed: ${e.message}")
            null
        }
    }

    private fun getFileExtension(uri: Uri): String? {
        return try {
            requireContext().contentResolver
                .query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(
                            android.provider.OpenableColumns.DISPLAY_NAME
                        )
                        if (idx != -1) cursor.getString(idx)?.substringAfterLast(".", "")
                        else null
                    } else null
                }
        } catch (_: Exception) {
            null
        }
    }

    // ══════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════

    private fun showToast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    private fun showPermissionToast() =
        Toast.makeText(
            requireContext(),
            getString(R.string.permission_write_settings_required),
            Toast.LENGTH_LONG
        ).show()
}