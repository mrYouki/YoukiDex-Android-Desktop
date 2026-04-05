package com.youki.dex.fragments

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import com.youki.dex.R

class KeyboardPreferences : PreferenceFragmentCompat() {
    override fun onCreatePreferences(arg0: Bundle?, arg1: String?) {
        setPreferencesFromResource(R.xml.preferences_keyboard, arg1)
    }
}
