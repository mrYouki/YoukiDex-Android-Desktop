package com.youki.dex.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.preference.PreferenceManager
import com.youki.dex.R
import com.youki.dex.models.Action
import com.youki.dex.utils.ColorUtils


class AppActionsAdapter(private val context: Context, actions: ArrayList<Action>) : ArrayAdapter<Action>(context, R.layout.context_menu_entry, actions) {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        var convertView = convertView
        val action = getItem(position)
        if (convertView == null) convertView = LayoutInflater.from(context).inflate(R.layout.context_menu_entry, null)
        val icon = convertView!!.findViewById<ImageView>(R.id.menu_entry_iv)
        val text = convertView.findViewById<TextView>(R.id.menu_entry_tv)
        ColorUtils.applySecondaryColor(context, PreferenceManager.getDefaultSharedPreferences(context), icon)
        text.text = action!!.text
        icon.setImageResource(action.icon)
        return convertView
    }
}
