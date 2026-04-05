package com.youki.dex.preferences

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.button.MaterialButton
import com.youki.dex.R

class FileChooserPreference(private val context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    init {
        setupPreference()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val resetButton = holder.findViewById(R.id.fs_preference_reset_btn) as MaterialButton
        resetButton.setOnClickListener { setFile("default") }
    }

    private fun setupPreference() {
        widgetLayoutResource = R.layout.preference_file_chooser
    }

    fun setFile(file: String) {
        persistString(file)
        notifyChanged() // ← updates the UI immediately
    }

    override fun onAttached() {
        super.onAttached()
        updateSummary()
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        super.onSetInitialValue(defaultValue)
        updateSummary()
    }

    override fun notifyChanged() {
        super.notifyChanged()
        updateSummary()
    }

    private fun updateSummary() {
        val file = try {
            sharedPreferences?.getString(key, "default") ?: "default"
        } catch (_: Exception) { "default" }

        summary = if (file == "default") {
            context.getString(R.string.tap_to_set)
        } else {
            // Extract the file name from the URI instead of displaying the full URI
            getFileNameFromUri(file) ?: context.getString(R.string.tap_to_set)
        }
    }

    private fun getFileNameFromUri(uriString: String): String? {
        return try {
            val uri = Uri.parse(uriString)
            // Try to get the file name from ContentResolver
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) cursor.getString(nameIndex) else null
                } else null
            } ?: uri.lastPathSegment // fallback if the query fails
        } catch (_: Exception) {
            null
        }
    }
}