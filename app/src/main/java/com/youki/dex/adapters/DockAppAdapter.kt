package com.youki.dex.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.youki.dex.R
import com.youki.dex.models.DockApp
import com.youki.dex.utils.AppUtils
import com.youki.dex.utils.ColorUtils
import com.youki.dex.utils.IconPackUtils
import com.youki.dex.utils.Utils

class DockAppAdapter(
    private val context: Context, private var apps: ArrayList<DockApp>,
    private val listener: OnDockAppClickListener, private val iconPackUtils: IconPackUtils?,
    private val dockHeight: Int = 0
) : RecyclerView.Adapter<DockAppAdapter.ViewHolder>() {
    private var iconBackground = 0
    private val iconPadding: Int
    private val iconTheming: Boolean
    private val tintIndicators: Boolean
    private val isWinStyle: Boolean
    // bind
    private val dominantColorCache = HashMap<String, Int>()

    interface OnDockAppClickListener {
        fun onDockAppClicked(app: DockApp, view: View)
        fun onDockAppLongClicked(app: DockApp, view: View)
    }

    init {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        iconTheming = sharedPreferences.getString("icon_pack", "") != ""
        iconPadding =
            Utils.dpToPx(context, sharedPreferences.getString("icon_padding", "5")!!.toInt())
        tintIndicators = sharedPreferences.getBoolean("tint_indicators", false)
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
        val itemLayoutView =
            LayoutInflater.from(parent.context).inflate(R.layout.app_task_entry, null)
        // Set explicit height so items don't collapse in WRAP_CONTENT RecyclerView
        if (dockHeight > 0) {
            itemLayoutView.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dockHeight
            )
        }
        return ViewHolder(itemLayoutView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
        val app = apps[position]
        val size = app.tasks.size
        if (size > 0) {
            // Win11: bitmap
            if (tintIndicators && !isWinStyle)
                ColorUtils.applyColor(
                    viewHolder.runningIndicator,
                    dominantColorCache.getOrPut(app.packageName) {
                        ColorUtils.manipulateColor(ColorUtils.getDrawableDominantColor(app.icon), 2f)
                    }
                )

            viewHolder.runningIndicator.alpha = if (app.tasks[0].id != -1) 1f else 0f
            // Win11: indicator (6dp) alpha active
            if (isWinStyle) {
                viewHolder.runningIndicator.layoutParams.width = Utils.dpToPx(context, 6)
                viewHolder.runningIndicator.alpha =
                    if (app.tasks[0].id != -1)
                        if (app.packageName == AppUtils.currentApp) 1f else 0.5f
                    else 0f
            } else {
                viewHolder.runningIndicator.layoutParams.width =
                    Utils.dpToPx(context, if (app.packageName == AppUtils.currentApp) 16 else 8)
            }

            if (size > 1) {
                viewHolder.taskCounter.text = size.toString()
                viewHolder.taskCounter.alpha = 1f
            } else
                viewHolder.taskCounter.alpha = 0f
        } else {
            viewHolder.runningIndicator.alpha = 0f
            viewHolder.taskCounter.alpha = 0f
        }
        val iconDrawable = if (iconPackUtils != null)
            iconPackUtils.getAppThemedIcon(app.packageName)
        else
            app.icon

        viewHolder.iconIv.setImageDrawable(iconDrawable)

        if (iconBackground != -1) {
            // Win11: padding frosted glass
            val pad = if (isWinStyle) Utils.dpToPx(context, 6) else iconPadding
            viewHolder.iconIv.setPadding(pad, pad, pad, pad)
            viewHolder.iconIv.setBackgroundResource(iconBackground)
            if (isWinStyle) {
                // frosted glass win_square.xml (#26FFFFFF)
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

    fun updateApps(newApps: ArrayList<DockApp>) {
        apps = newApps
        notifyDataSetChanged()
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        var iconIv: ImageView = itemView.findViewById(R.id.icon_iv)
        var taskCounter: TextView = itemView.findViewById(R.id.task_count_badge)
        var runningIndicator: View = itemView.findViewById(R.id.running_indicator)

        fun bind(app: DockApp, listener: OnDockAppClickListener) {
            itemView.setOnClickListener { view -> listener.onDockAppClicked(app, view) }
            itemView.setOnLongClickListener { view ->
                listener.onDockAppLongClicked(app, view)
                true
            }
            itemView.setOnTouchListener { view, event ->
                if (event.buttonState == MotionEvent.BUTTON_SECONDARY) {
                    listener.onDockAppLongClicked(app, view)
                    return@setOnTouchListener true
                }
                false
            }
        }
    }
}