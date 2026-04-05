package com.youki.dex.fragments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.youki.dex.R

class HelpAboutPreferences : PreferenceFragmentCompat() {
    override fun onCreatePreferences(arg0: Bundle?, arg1: String?) {
        setPreferencesFromResource(R.xml.preferences_help_about, arg1)

        // Tap developer photo → opens GitHub profile
        findPreference<Preference>("open_github_profile")?.setOnPreferenceClickListener {
            openUrl("https://github.com/mrYouki")
            true
        }

        // Tap GitHub in About Project → opens project link
        findPreference<Preference>("open_github_project")?.setOnPreferenceClickListener {
            openUrl("https://github.com/mrYouki/YoukiDex-Android-Desktop")
            true
        }

        // Tap Discord → opens Discord server
        findPreference<Preference>("open_discord")?.setOnPreferenceClickListener {
            openUrl("https://discord.gg/r7sG6fthJj")
            true
        }

        // Help button
        findPreference<Preference>("show_help")?.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.help)
                .setView(R.layout.dialog_help)
                .setPositiveButton(R.string.ok, null)
                .show()
            false
        }
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }
}
