package com.youki.dex.fragments

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import com.youki.dex.R


class DefaultAppsPreferences : PreferenceFragmentCompat() {
    override fun onCreatePreferences(arg0: Bundle?, arg1: String?) {
        setPreferencesFromResource(R.xml.preferences_default_apps, arg1)
    }
}
