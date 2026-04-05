package com.youki.dex.preferences

import android.content.Context
import android.util.AttributeSet
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.button.MaterialButton
import com.youki.dex.R

/**
 * SoundFilePreference
 *
 * Custom Preference for sounds. Saves the absolute file path in SharedPreferences
 * and displays the file name as the summary.
 *
 * Differs from the old FileChooserPreference in:
 *  - Saves the absolute path (String) instead of a content URI
 *  - No takePersistableUriPermission needed
 *  - Path is read directly by MediaPlayer or Settings.System
 */
class SoundFilePreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    init {
        widgetLayoutResource = R.layout.preference_file_chooser
    }

    // ───────────────────────── View ─────────────────────────

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val resetBtn = holder.findViewById(R.id.fs_preference_reset_btn) as? MaterialButton
        resetBtn?.setOnClickListener { setSound(null) }
    }

    // ───────────────────────── API ─────────────────────────

    /**
     * Saves the path in SharedPreferences and updates the UI.
     * @param filePath Absolute path to the file, or null to reset to default.
     */
    fun setSound(filePath: String?) {
        val value = filePath ?: ""
        if (shouldPersist()) persistString(value)
        notifyChanged()
    }

    /**
     * Returns the current path, or null if not yet set.
     */
    fun getCurrentPath(): String? {
        val stored = try {
            sharedPreferences?.getString(key, "") ?: ""
        } catch (_: Exception) {
            ""
        }
        return stored.ifBlank { null }
    }

    // ───────────────────────── Summary ─────────────────────────

    override fun onSetInitialValue(defaultValue: Any?) {
        super.onSetInitialValue(defaultValue)
        refreshSummary()
    }

    override fun notifyChanged() {
        super.notifyChanged()
        refreshSummary()
    }

    private fun refreshSummary() {
        val path = getCurrentPath()
        summary = if (path == null) {
            context.getString(R.string.tap_to_set)
        } else {
            // Show only the file name instead of the full path
            path.substringAfterLast("/").ifBlank {
                context.getString(R.string.tap_to_set)
            }
        }
    }
}
