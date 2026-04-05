package com.youki.dex.adapters

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.youki.dex.R
import com.youki.dex.models.App
import com.youki.dex.utils.ColorUtils
import com.youki.dex.utils.IconPackUtils
import com.youki.dex.utils.Utils
import java.util.Locale

class AppAdapter(
    private val context: Context,
    private var apps: List<App>,
    private val listener: OnAppClickListener,
    private val large: Boolean,
    private val iconPackUtils: IconPackUtils?
) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {
    private val allApps: ArrayList<App> = ArrayList(apps)
    private var iconBackground = 0
    private val iconPadding: Int
    private val singleLine: Boolean
    private val isWinStyle: Boolean
    private val dominantColorCache = HashMap<String, Int>()
    private lateinit var query: String

    interface OnAppClickListener {
        fun onAppClicked(app: App, item: View)
        fun onAppLongClicked(app: App, item: View)
    }

    init {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        iconPadding =
            Utils.dpToPx(context, sharedPreferences.getString("icon_padding", "5")!!.toInt())
        singleLine = sharedPreferences.getBoolean("single_line_labels", true)
        val shape = sharedPreferences.getString("icon_shape", "circle")
        isWinStyle = shape == "win"
        when (shape) {
            "circle" -> iconBackground = R.drawable.circle
            "round_rect" -> iconBackground = R.drawable.round_square
            "win" -> iconBackground = R.drawable.win_square
            "default" -> iconBackground = -1
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, arg1: Int): ViewHolder {
        val itemLayoutView = LayoutInflater.from(context)
            .inflate(if (large) R.layout.app_entry_large else R.layout.app_entry, null)
        return ViewHolder(itemLayoutView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
        val app = apps[position]
        val name = app.name
        if (::query.isInitialized) {
            val spanStart =
                name.lowercase(Locale.getDefault()).indexOf(query.lowercase(Locale.getDefault()))
            val spanEnd = spanStart + query.length
            if (spanStart != -1) {
                val spannable = SpannableString(name)
                spannable.setSpan(
                    StyleSpan(Typeface.BOLD),
                    spanStart,
                    spanEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                viewHolder.nameTv.text = spannable
            } else {
                viewHolder.nameTv.text = name
            }
        } else {
            viewHolder.nameTv.text = name
        }

        val iconDrawable = if (iconPackUtils != null)
            iconPackUtils.getAppThemedIcon(app.packageName)
        else
            app.icon
        viewHolder.iconIv.setImageDrawable(iconDrawable)

        if (iconBackground != -1) {
            val pad = if (isWinStyle) Utils.dpToPx(context, 7) else iconPadding
            viewHolder.iconIv.setPadding(pad, pad, pad, pad)
            viewHolder.iconIv.setBackgroundResource(iconBackground)
            if (isWinStyle) {
                viewHolder.iconIv.background?.clearColorFilter()
            } else if (iconDrawable != null) {
                viewHolder.iconIv.background?.setColorFilter(
                    dominantColorCache.getOrPut(app.packageName) {
                        ColorUtils.getDrawableDominantColor(iconDrawable)
                    },
                    android.graphics.PorterDuff.Mode.SRC_ATOP
                )
            }
        }
        viewHolder.bind(app, listener)
    }

    override fun getItemCount(): Int {
        return apps.size
    }

    fun updateApps(newApps: List<App>) {
        this.apps = newApps
        this.allApps.clear()
        this.allApps.addAll(newApps)
        // If a query is active, re-apply it
        if (::query.isInitialized && query.isNotEmpty()) {
            filter(query)
        } else {
            this.apps = newApps
            notifyDataSetChanged()
        }
    }

    fun filter(query: String) {
        val results = ArrayList<App>()
        if (query.isEmpty()) {
            this.query = ""
        }
        if (query.length > 1) {
            if (query.matches("^[0-9]+(\\.[0-9]+)?[-+/*][0-9]+(\\.[0-9]+)?".toRegex())) {
                results.add(
                    App(
                        Utils.solve(query).toString() + "", context.packageName + ".calc",
                        ResourcesCompat.getDrawable(
                            context.resources,
                            R.drawable.ic_calculator,
                            context.theme
                        )!!
                    )
                )
            } else {
                for (app in allApps) {
                    if (app.name.lowercase(Locale.getDefault())
                            .contains(query.lowercase(Locale.getDefault()))
                    ) results.add(app)
                }
            }
            apps = results
            this.query = query
        } else {
            apps = allApps
        }
        notifyDataSetChanged()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        var iconIv: ImageView = itemView.findViewById(R.id.app_icon_iv)
        var nameTv: TextView = itemView.findViewById(R.id.app_name_tv)

        init {
            nameTv.maxLines = if (singleLine) 1 else 2
        }

        fun bind(app: App, listener: OnAppClickListener) {
            itemView.setOnClickListener { view -> listener.onAppClicked(app, view) }
            itemView.setOnLongClickListener { view ->
                listener.onAppLongClicked(app, view)
                true
            }
            itemView.setOnTouchListener { view, event ->
                if (event.buttonState == MotionEvent.BUTTON_SECONDARY) {
                    listener.onAppLongClicked(app, view)
                    return@setOnTouchListener true
                }
                false
            }
        }
    }
}