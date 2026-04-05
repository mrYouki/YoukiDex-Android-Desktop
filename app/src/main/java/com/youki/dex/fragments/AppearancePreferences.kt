package com.youki.dex.fragments

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView.OnItemClickListener
import android.widget.ArrayAdapter
import android.widget.GridView
import android.widget.ViewSwitcher
import androidx.core.widget.addTextChangedListener
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.youki.dex.R
import com.youki.dex.dialogs.DockLayoutDialog
import com.youki.dex.utils.AppUtils
import com.youki.dex.utils.ColorUtils
import androidx.core.content.edit
import androidx.core.graphics.toColorInt

class AppearancePreferences : PreferenceFragmentCompat() {
    private lateinit var mainColorPref: Preference
    private lateinit var bubbleColorPref: Preference

    override fun onCreatePreferences(arg0: Bundle?, arg1: String?) {
        setPreferencesFromResource(R.xml.preferences_appearance, arg1)
        mainColorPref   = findPreference("theme_main_color")!!
        bubbleColorPref = findPreference("bubble_color_picker")!!

        // Bubble mode: show custom controls only when mode = "custom"
        val prefs = preferenceManager.sharedPreferences!!
        fun updateBubbleVisibility(mode: String) {
            val isCustom = mode == "custom"
            bubbleColorPref.isVisible = isCustom
        }
        updateBubbleVisibility(prefs.getString("bubble_mode", "material_u") ?: "material_u")

        findPreference<androidx.preference.ListPreference>("bubble_mode")!!
            .setOnPreferenceChangeListener { _, newValue ->
                updateBubbleVisibility(newValue.toString())
                true
            }

        bubbleColorPref.setOnPreferenceClickListener {
            showBubbleColorPickerDialog(requireContext())
            false
        }

        // Theme: show custom color only when theme = "custom"
        mainColorPref.setOnPreferenceClickListener {
            showColorPickerDialog(requireContext())
            false
        }
        val currentTheme = prefs.getString("theme", "material_u") ?: "material_u"
        mainColorPref.isVisible = currentTheme == "custom"

        findPreference<androidx.preference.ListPreference>("theme")!!.setOnPreferenceChangeListener { _, newValue ->
            mainColorPref.isVisible = newValue.toString() == "custom"
            true
        }
        findPreference<Preference>("tint_indicators")!!.isVisible = AppUtils.isSystemApp(requireContext(), requireContext().packageName)

        findPreference<Preference>("dock_layout")!!.setOnPreferenceClickListener {
            DockLayoutDialog(requireContext())
            false
        }
    }

    private fun showBubbleColorPickerDialog(context: Context) {
        val prefs = preferenceManager.sharedPreferences!!
        val currentHex   = prefs.getString("bubble_color", "#808080")!!
        val currentAlpha = prefs.getInt("bubble_alpha_pct", 45).toFloat()

        val dialog = MaterialAlertDialogBuilder(context)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_color_picker, null)
        val colorPreview = view.findViewById<View>(R.id.color_preview)
        val colorHexEt   = view.findViewById<TextInputEditText>(R.id.color_hex_et)
        val alphaSb      = view.findViewById<Slider>(R.id.color_alpha_sb)
        val redSb        = view.findViewById<Slider>(R.id.color_red_sb)
        val greenSb      = view.findViewById<Slider>(R.id.color_green_sb)
        val blueSb       = view.findViewById<Slider>(R.id.color_blue_sb)
        val viewSwitcher = view.findViewById<ViewSwitcher>(R.id.colors_view_switcher)
        val toggleGroup  = view.findViewById<MaterialButtonToggleGroup>(R.id.colors_btn_toggle)

        colorHexEt.addTextChangedListener { text ->
            val hex = text.toString()
            var color = -1
            if (hex.length == 7 && ColorUtils.toColor(hex).also { color = it } != -1) {
                colorPreview.background.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_ATOP)
                redSb.value   = Color.red(color).toFloat()
                greenSb.value = Color.green(color).toFloat()
                blueSb.value  = Color.blue(color).toFloat()
            }
        }
        alphaSb.valueTo   = 100f
        alphaSb.valueFrom = 0f
        alphaSb.value     = currentAlpha
        alphaSb.addOnChangeListener { _, value, _ ->
            colorPreview.background.alpha = (value * 255 / 100).toInt()
        }
        val onRgbChange = Slider.OnChangeListener { _, _, fromUser ->
            if (fromUser) colorHexEt.setText(ColorUtils.toHexColor(
                Color.rgb(redSb.value.toInt(), greenSb.value.toInt(), blueSb.value.toInt())))
        }
        redSb.addOnChangeListener(onRgbChange)
        greenSb.addOnChangeListener(onRgbChange)
        blueSb.addOnChangeListener(onRgbChange)
        colorHexEt.setText(currentHex)

        val presetsGv = view.findViewById<android.widget.GridView>(R.id.presets_gv)
        presetsGv.adapter = HexColorAdapter(context, context.resources.getStringArray(R.array.default_color_values))
        presetsGv.onItemClickListener = android.widget.AdapterView.OnItemClickListener { adapterView, _, position, _ ->
            colorHexEt.setText(adapterView.getItemAtPosition(position).toString())
            toggleGroup.check(R.id.custom_button)
            viewSwitcher.showNext()
        }
        view.findViewById<View>(R.id.custom_button).setOnClickListener { viewSwitcher.showPrevious() }
        view.findViewById<View>(R.id.presets_button).setOnClickListener { viewSwitcher.showNext() }

        dialog.setNegativeButton(R.string.cancel, null)
        dialog.setPositiveButton(R.string.ok) { _, _ ->
            val hex = colorHexEt.text.toString()
            if (ColorUtils.toColor(hex) != -1) {
                prefs.edit()
                    .putString("bubble_color", hex)
                    .putInt("bubble_alpha_pct", alphaSb.value.toInt())
                    .apply()
            }
        }
        dialog.setView(view)
        dialog.show()
    }
    private fun showColorPickerDialog(context: Context) {
        val dialog = MaterialAlertDialogBuilder(context)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_color_picker, null)
        val colorPreview = view.findViewById<View>(R.id.color_preview)
        val colorHexEt = view.findViewById<TextInputEditText>(R.id.color_hex_et)
        val alphaSb = view.findViewById<Slider>(R.id.color_alpha_sb)
        val redSb = view.findViewById<Slider>(R.id.color_red_sb)
        val greenSb = view.findViewById<Slider>(R.id.color_green_sb)
        val blueSb = view.findViewById<Slider>(R.id.color_blue_sb)
        val viewSwitcher = view.findViewById<ViewSwitcher>(R.id.colors_view_switcher)
        val toggleGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.colors_btn_toggle)
        colorHexEt.addTextChangedListener { text ->
            val hexColor = text.toString()
            var color = -1
            if (hexColor.length == 7 && ColorUtils.toColor(hexColor).also { color = it } != -1) {
                colorPreview.background.setColorFilter(color, PorterDuff.Mode.SRC_ATOP)
                redSb.value = Color.red(color).toFloat()
                greenSb.value = Color.green(color).toFloat()
                blueSb.value = Color.blue(color).toFloat()
            } else colorHexEt.error = getString(R.string.invalid_color)

        }
        alphaSb.addOnChangeListener { _, value, _ -> colorPreview.background.alpha = value.toInt() }
        val onChangeListener = Slider.OnChangeListener { _, _, fromUser ->
            if (fromUser) colorHexEt.setText(ColorUtils.toHexColor(
                    Color.rgb(redSb.value.toInt(), greenSb.value.toInt(), blueSb.value.toInt())))
        }
        redSb.addOnChangeListener(onChangeListener)
        greenSb.addOnChangeListener(onChangeListener)
        blueSb.addOnChangeListener(onChangeListener)
        dialog.setNegativeButton(R.string.cancel, null)
        dialog.setPositiveButton(R.string.ok) { _, _ ->
            val color = colorHexEt.text.toString()
            if (ColorUtils.toColor(color) != -1) {
                mainColorPref.sharedPreferences!!.edit {
                    putString(mainColorPref.key, color)
                    putInt("theme_main_alpha", alphaSb.value.toInt())
                }
            }
        }
        alphaSb.value = mainColorPref.sharedPreferences!!.getInt("theme_main_alpha", 255).toFloat()
        val hexColor = mainColorPref.sharedPreferences!!.getString(mainColorPref.key, "#212121")
        colorHexEt.setText(hexColor)
        val presetsGv = view.findViewById<GridView>(R.id.presets_gv)
        presetsGv.adapter = HexColorAdapter(context, context.resources.getStringArray(R.array.default_color_values))
        presetsGv.onItemClickListener = OnItemClickListener { adapterView, _, position, _ ->
            colorHexEt.setText(adapterView.getItemAtPosition(position).toString())
            toggleGroup.check(R.id.custom_button)
            viewSwitcher.showNext()
        }
        view.findViewById<View>(R.id.custom_button).setOnClickListener { viewSwitcher.showPrevious() }
        view.findViewById<View>(R.id.presets_button).setOnClickListener { viewSwitcher.showNext() }
        dialog.setView(view)
        dialog.show()
    }

    internal class HexColorAdapter(private val context: Context, colors: Array<String>) : ArrayAdapter<String>(context, R.layout.color_entry, colors) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            var convertView = convertView
            if (convertView == null) convertView = LayoutInflater.from(context).inflate(R.layout.color_entry, null)
            convertView!!.findViewById<View>(R.id.color_entry_iv).background
                    .setColorFilter(getItem(position)!!.toColorInt(), PorterDuff.Mode.SRC_ATOP)
            return convertView
        }
    }
}
