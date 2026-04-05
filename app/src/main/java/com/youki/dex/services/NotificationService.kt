package com.youki.dex.services

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Notification
import android.app.PendingIntent.CanceledException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.divider.MaterialDividerItemDecoration
import com.youki.dex.R
import com.youki.dex.activities.LAUNCHER_ACTION
import com.youki.dex.adapters.NotificationAdapter
import com.youki.dex.adapters.NotificationAdapter.OnNotificationClickListener
import com.youki.dex.utils.AppUtils
import com.youki.dex.utils.AppUtils.makeActivityOptions
import com.youki.dex.utils.ColorUtils
import com.youki.dex.utils.DeviceUtils
import com.youki.dex.utils.Utils
import androidx.core.content.edit
import android.provider.Settings

const val ACTION_HIDE_NOTIFICATION_PANEL = "hide_panel"
const val ACTION_SHOW_NOTIFICATION_PANEL = "show_panel"
const val ACTION_HIDE_NOTIFICATION_BAR = "hide_bar"
const val ACTION_SHOW_NOTIFICATION_BAR = "show_bar"
const val NOTIFICATION_COUNT_CHANGED = "count_changed"
const val NOTIFICATION_SERVICE_ACTION = "notification_service_action"
const val ACTION_STOP_NOTIFICATION_SERVICE = "stop_notification_service"

class NotificationService : NotificationListenerService(), OnNotificationClickListener,
    SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var windowManager: WindowManager
    private lateinit var notificationLayout: LinearLayout
    private lateinit var notificationTitleTv: TextView
    private lateinit var notificationTextTv: TextView
    private lateinit var notificationIconIv: ImageView
    private lateinit var notificationCloseBtn: ImageView
    private lateinit var handler: Handler
    private lateinit var sharedPreferences: SharedPreferences
    private var notificationPanel: View? = null
    private var notificationsLv: RecyclerView? = null
    private var cancelAllBtn: ImageButton? = null
    private lateinit var notificationActionsLayout: LinearLayout
    private lateinit var context: Context
    private var notificationArea: LinearLayout? = null
    private var preferLastDisplay = false
    private var dockReceiver: DockServiceReceiver? = null
    private var y = 0
    private var margins = 0
    private var dockHeight: Int = 0
    private lateinit var notificationLayoutParams: WindowManager.LayoutParams
    private var actionsHeight = 0
    override fun onCreate() {
        super.onCreate()
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        sharedPreferences.registerOnSharedPreferenceChangeListener(this)
        preferLastDisplay = sharedPreferences.getBoolean("prefer_last_display", false)
        context = DeviceUtils.getDisplayContext(this, preferLastDisplay)
        actionsHeight = Utils.dpToPx(context, 20)
        windowManager = context.getSystemService(WINDOW_SERVICE) as WindowManager
        notificationLayoutParams = Utils.makeWindowParams(
            Utils.dpToPx(context, 300), LinearLayout.LayoutParams.WRAP_CONTENT, context,
            preferLastDisplay
        )
        // FIX: Add FLAG_NOT_TOUCH_MODAL so this window (which is always present in the
        // WindowManager, even when visibility=GONE) doesn't swallow touch events outside
        // its 300dp bounds. Without this flag, the window acts as a modal overlay and
        // blocks taps on whatever sits behind its bounding rectangle.
        notificationLayoutParams.flags = notificationLayoutParams.flags or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        margins = Utils.dpToPx(context, 2)
        dockHeight =
            Utils.dpToPx(context, sharedPreferences.getString("dock_height", "56")!!.toInt())
        y = (if (DeviceUtils.shouldApplyNavbarFix())
            dockHeight - DeviceUtils.getNavBarHeight(context)
        else
            dockHeight) + margins
        notificationLayoutParams.x = margins
        notificationLayoutParams.gravity = Gravity.BOTTOM or if (sharedPreferences.getInt(
                "dock_layout",
                -1
            ) == 0
        ) Gravity.CENTER_HORIZONTAL else Gravity.END
        notificationLayoutParams.y = y
        notificationLayout = LayoutInflater.from(this).inflate(
            R.layout.notification_entry,
            null
        ) as LinearLayout
        val padding = Utils.dpToPx(context, 10)
        notificationLayout.setPadding(padding, padding, padding, padding)
        notificationLayout.setBackgroundResource(R.drawable.round_square)
        // Apply dock theme color to the popup banner — same as dock
        val initColors = ColorUtils.getMainColors(sharedPreferences, this)
        notificationLayout.background?.setColorFilter(initColors[0], android.graphics.PorterDuff.Mode.SRC_ATOP)
        notificationLayout.background?.alpha = initColors[1]
        notificationLayout.visibility = View.GONE
        notificationTitleTv = notificationLayout.findViewById(R.id.notification_title_tv)
        notificationTextTv = notificationLayout.findViewById(R.id.notification_text_tv)
        notificationIconIv = notificationLayout.findViewById(R.id.notification_icon_iv)
        notificationCloseBtn = notificationLayout.findViewById(R.id.notification_close_btn)
        notificationCloseBtn.alpha = 1f
        notificationActionsLayout =
            notificationLayout.findViewById(R.id.notification_actions_layout)
        // FIX: TYPE_APPLICATION_OVERLAY requires runtime permission check.
        // SYSTEM_ALERT_WINDOW in the manifest is necessary but NOT sufficient —
        // the user must also grant it via Settings. Without this guard, the
        // service crashes with BadTokenException (window type 2038 denied).
        if (Settings.canDrawOverlays(this)) {
            windowManager.addView(notificationLayout, notificationLayoutParams)
        }
        handler = Handler(Looper.getMainLooper())
        notificationLayout.alpha = 0f
        notificationLayout.setOnHoverListener { _, event ->
            if (event.action == MotionEvent.ACTION_HOVER_ENTER) {
                handler.removeCallbacksAndMessages(null)
            } else if (event.action == MotionEvent.ACTION_HOVER_EXIT) {
                hideNotification()
            }
            false
        }

        dockReceiver = DockServiceReceiver()
        ContextCompat.registerReceiver(
            this,
            dockReceiver,
            IntentFilter(DOCK_SERVICE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // If DockService already started before us, activate immediately.
        // Guard with canDrawOverlays — permission may not be granted yet.
        if (sharedPreferences.getBoolean("dex_mode_active", false)
            && Settings.canDrawOverlays(this)
        ) {
            try {
                if (notificationLayout.windowToken == null)
                    windowManager.addView(notificationLayout, notificationLayoutParams)
                notificationLayout.visibility = View.VISIBLE
            } catch (_: Exception) {
                notificationLayout.visibility = View.GONE
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        updateNotificationCount()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
        updateNotificationCount()
        if (Utils.notificationPanelVisible)
            updateNotificationPanel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        // DEX mode — notifications only active in desktop mode
        if (!sharedPreferences.getBoolean("dex_mode_active", false)) return
        updateNotificationCount()
        if (Utils.notificationPanelVisible) {
            updateNotificationPanel()
        } else {
            if (sharedPreferences.getBoolean("show_notifications", true)) {
                val notification = sbn.notification
                val isForegroundService = (notification.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
                if ((sbn.isOngoing && !sharedPreferences.getBoolean("show_ongoing", false))
                    || isForegroundService
                    || (sbn.packageName == AppUtils.currentApp && sharedPreferences.getBoolean("silence_current", true))
                    || notification.contentView != null
                    || isBlackListed(sbn.packageName)
                )
                    return
                val extras = notification.extras
                var notificationTitle = extras.getString(Notification.EXTRA_TITLE)
                if (notificationTitle == null) notificationTitle =
                    AppUtils.getPackageLabel(context, sbn.packageName)
                val notificationText = extras.getCharSequence(Notification.EXTRA_TEXT)
                // Refresh color on each notification — stays in sync with dock theme
                val notifColors = ColorUtils.getMainColors(sharedPreferences, this@NotificationService)
                notificationLayout.background?.setColorFilter(notifColors[0], android.graphics.PorterDuff.Mode.SRC_ATOP)
                notificationLayout.background?.alpha = notifColors[1]

                if (AppUtils.isMediaNotification(notification) && notification.getLargeIcon() != null) {
                    val padding = Utils.dpToPx(context, 0)
                    notificationIconIv.setPadding(padding, padding, padding, padding)
                    notificationIconIv.setImageIcon(notification.getLargeIcon())
                    notificationIconIv.background = null
                } else {
                    notification.smallIcon.setTint(Color.WHITE)
                    notificationIconIv.setBackgroundResource(R.drawable.circle)
                    ColorUtils.applySecondaryColor(
                        context, sharedPreferences,
                        notificationIconIv
                    )
                    val padding = Utils.dpToPx(context, 14)
                    notificationIconIv.setPadding(padding, padding, padding, padding)
                    notificationIconIv.setImageIcon(notification.smallIcon)
                }

                val progress = extras.getInt(Notification.EXTRA_PROGRESS)
                val p = if (progress != 0) " $progress%" else ""
                notificationTitleTv.text = notificationTitle + p
                notificationTextTv.text = notificationText
                val actions = notification.actions
                notificationActionsLayout.removeAllViews()
                if (actions != null) {
                    val actionLayoutParams = LinearLayout.LayoutParams(0, actionsHeight)
                    actionLayoutParams.weight = 1f
                    if (AppUtils.isMediaNotification(notification)) {
                        for (action in actions) {
                            val actionIv = ImageView(this@NotificationService)
                            try {
                                val resources = packageManager
                                    .getResourcesForApplication(sbn.packageName)
                                val drawable = resources.getDrawable(
                                    resources.getIdentifier(
                                        action.icon.toString() + "",
                                        "drawable",
                                        sbn.packageName
                                    )
                                )
                                drawable.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_ATOP)
                                actionIv.setImageDrawable(drawable)
                                actionIv.setOnClickListener {
                                    try {
                                        action.actionIntent.send()
                                    } catch (_: CanceledException) {
                                    }
                                }
                                notificationTextTv.isSingleLine = true
                                notificationActionsLayout.addView(actionIv, actionLayoutParams)
                            } catch (_: PackageManager.NameNotFoundException) {
                            }
                        }
                    } else {
                        // PERF: cache getMainColors — called once instead of per-action
                        val actionTextColor = ColorUtils.manipulateColor(
                            ColorUtils.getMainColors(sharedPreferences, this)[0], 1.6f
                        )
                        for (action in actions) {
                            val actionTv = TextView(context)
                            actionTv.isSingleLine = true
                            actionTv.text = action.title
                            actionTv.setTextColor(actionTextColor)
                            actionTv.setOnClickListener {
                                try {
                                    action.actionIntent.send()
                                    notificationLayout.visibility = View.GONE
                                    notificationLayout.alpha = 0f
                                } catch (_: CanceledException) {
                                }
                            }
                            notificationActionsLayout.addView(actionTv, actionLayoutParams)
                        }
                    }
                }
                notificationCloseBtn.setOnClickListener {
                    notificationLayout.visibility = View.GONE
                    if (sbn.isClearable)
                        cancelNotification(sbn.key)
                }
                notificationLayout.setOnClickListener {
                    notificationLayout.visibility = View.GONE
                    notificationLayout.alpha = 0f
                    val intent = notification.contentIntent
                    if (intent != null) {
                        try {
                            val options = makeActivityOptions(
                                context, "standard", dockHeight,
                                Display.DEFAULT_DISPLAY
                            )
                            intent.send(context, 0, null, null, null, null, options.toBundle())
                            if (sbn.isClearable) cancelNotification(sbn.key)
                        } catch (_: CanceledException) {
                        }
                    }
                }
                notificationLayout.setOnLongClickListener {
                    val ignoredApps = buildSet {
                        addAll(sharedPreferences.getStringSet("ignored_notifications_popups", emptySet())!!)
                        add(sbn.packageName)
                    }
                    sharedPreferences.edit {
                        putStringSet("ignored_notifications_popups", ignoredApps)
                    }
                    notificationLayout.visibility = View.GONE
                    notificationLayout.alpha = 0f
                    Toast.makeText(
                        this@NotificationService,
                        R.string.silenced_notifications,
                        Toast.LENGTH_LONG
                    )
                        .show()
                    if (sbn.isClearable) cancelNotification(sbn.key)
                    true
                }
                // PERF/FIX: Cancel previous animation safely.
                // setListener(null) BEFORE cancel() prevents the stale hide-animation's
                // onAnimationEnd from firing and resetting visibility/scale mid-flight,
                // which was the root cause of the "jittery quick-press" glitch.
                notificationLayout.animate().setListener(null).cancel()
                notificationLayout.scaleX = 0.88f
                notificationLayout.scaleY = 0.88f
                notificationLayout.alpha = 0f
                notificationLayout.visibility = View.VISIBLE
                // withLayer() replaces manual LAYER_TYPE_HARDWARE management:
                // it enables GPU compositing for the duration and cleans up
                // automatically on both natural end AND cancellation — no leaks.
                notificationLayout.animate()
                    .scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(240)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .withLayer()
                    .setListener(null)
                if (sharedPreferences.getBoolean(
                        "enable_notification_sound",
                        false
                    )
                ) DeviceUtils.playEventSound(this, "notification_sound")
                hideNotification()
            }
        }
    }

    private fun hideNotification() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            // Clear listener before cancel so it won't fire during the cancel call itself
            notificationLayout.animate().setListener(null).cancel()
            notificationLayout.animate()
                .scaleX(0.88f).scaleY(0.88f).alpha(0f)
                .setDuration(180)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withLayer()
                // FIX: 'cancelled' flag prevents onAnimationEnd from resetting
                // visibility/scale when a new show-animation interrupts this one.
                // Without this, the reset (scaleX=1f, GONE) fires mid-flight and
                // corrupts the incoming animation's start state → jitter/glitch.
                .setListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }
                    override fun onAnimationEnd(animation: Animator) {
                        if (cancelled) return
                        notificationLayout.visibility = View.GONE
                        notificationLayout.scaleX = 1f
                        notificationLayout.scaleY = 1f
                        notificationLayout.alpha = 1f
                    }
                })
        }, sharedPreferences.getString("notification_display_time", "5")!!.toInt() * 1000L)
    }

    private fun isBlackListed(packageName: String): Boolean {
        val ignoredPackages =
            sharedPreferences.getStringSet("ignored_notifications_popups", setOf("android"))
        return ignoredPackages!!.contains(packageName)
    }

    private val countHandler = Handler(Looper.getMainLooper())
    private val countRunnable = Runnable { doUpdateNotificationCount() }

    private fun updateNotificationCount() {
        countHandler.removeCallbacks(countRunnable)
        countHandler.postDelayed(countRunnable, 300)
    }

    private fun doUpdateNotificationCount() {
        var count = 0
        var cancelableCount = 0
        val notifications = try {
            activeNotifications
        } catch (e: SecurityException) {
            // Service token may be invalid — happens when the listener is not
            // yet fully bound or loses its token mid-flight. Skip silently.
            return
        } ?: return
        for (notification in notifications) {
            if (notification != null && notification.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0) {
                count++
                if (notification.isClearable) cancelableCount++
            }
            if (Utils.notificationPanelVisible) cancelAllBtn?.visibility =
                if (cancelableCount > 0) View.VISIBLE else View.INVISIBLE
        }
        sendBroadcast(
            Intent(NOTIFICATION_SERVICE_ACTION)
                .setPackage(packageName)
                .putExtra("action", NOTIFICATION_COUNT_CHANGED)
                .putExtra("count", count)
        )
    }

    fun showNotificationPanel() {
        // DEX mode — panel only shows in desktop mode
        if (!sharedPreferences.getBoolean("dex_mode_active", false)) return
        if (Utils.notificationPanelVisible) return  // already open — don't stack
        val layoutParams = Utils.makeWindowParams(
            Utils.dpToPx(context, 400), -2, context,
            preferLastDisplay
        )
        layoutParams.gravity = Gravity.BOTTOM or Gravity.END
        layoutParams.y = y
        layoutParams.x = margins
        layoutParams.flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or  // FIX: was missing — caused panel to block taps outside its bounds
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        notificationPanel = LayoutInflater.from(context).inflate(R.layout.notification_panel, null)
        cancelAllBtn = notificationPanel!!.findViewById(R.id.cancel_all_n_btn)
        notificationsLv = notificationPanel!!.findViewById(R.id.notification_lv)
        notificationsLv!!.layoutManager =
            LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
        notificationArea = notificationPanel!!.findViewById(R.id.notification_area)
        val qsArea = notificationPanel!!.findViewById<LinearLayout>(R.id.qs_area)
        val notificationsBtn = notificationPanel!!.findViewById<ImageView>(R.id.notifications_btn)
        val orientationBtn = notificationPanel!!.findViewById<ImageView>(R.id.btn_orientation)
        val touchModeBtn = notificationPanel!!.findViewById<ImageView>(R.id.btn_touch_mode)
        val screenshotBtn = notificationPanel!!.findViewById<ImageView>(R.id.btn_screenshot)
        val screencapBtn = notificationPanel!!.findViewById<ImageView>(R.id.btn_screencast)
        val settingsBtn = notificationPanel!!.findViewById<ImageView>(R.id.btn_settings)
        // QS circles — same color as dock bubbles (mainColor from user's chosen theme)
        val qsColors = ColorUtils.getMainColors(sharedPreferences, this)
        val qsBubbleColor = qsColors[0]
        val qsBubbleAlpha = qsColors[1]
        for (btn in listOf(notificationsBtn, orientationBtn, touchModeBtn, screencapBtn, screenshotBtn, settingsBtn)) {
            btn.background?.setColorFilter(qsBubbleColor, android.graphics.PorterDuff.Mode.SRC_ATOP)
            btn.background?.alpha = qsBubbleAlpha
        }
        touchModeBtn.setOnClickListener {
            hideNotificationPanel()
            if (sharedPreferences.getBoolean("tablet_mode", false)) {
                Utils.toggleBuiltinNavigation(sharedPreferences.edit(), false)
                sharedPreferences.edit {
                    putBoolean("app_menu_fullscreen", false)
                    putBoolean("tablet_mode", false)
                }
                Toast.makeText(context, R.string.tablet_mode_off, Toast.LENGTH_SHORT).show()
            } else {
                Utils.toggleBuiltinNavigation(sharedPreferences.edit(), true)
                sharedPreferences.edit {
                    putBoolean("app_menu_fullscreen", true)
                    putBoolean("tablet_mode", true)
                }
                Toast.makeText(context, R.string.tablet_mode_on, Toast.LENGTH_SHORT).show()
            }
        }
        orientationBtn.setImageResource(
            if (sharedPreferences.getBoolean(
                    "lock_landscape",
                    true
                )
            ) R.drawable.ic_screen_rotation_off else R.drawable.ic_screen_rotation_on
        )
        orientationBtn.setOnClickListener {
            sharedPreferences.edit {
                putBoolean("lock_landscape", !sharedPreferences.getBoolean("lock_landscape", true))
            }
            orientationBtn
                .setImageResource(
                    if (sharedPreferences.getBoolean(
                            "lock_landscape",
                            true
                        )
                    ) R.drawable.ic_screen_rotation_off else R.drawable.ic_screen_rotation_on
                )
        }
        screenshotBtn.setOnClickListener {
            hideNotificationPanel()
            sendBroadcast(
                Intent(NOTIFICATION_SERVICE_ACTION)
                    .setPackage(packageName)
                    .putExtra("action", ACTION_TAKE_SCREENSHOT)
            )
        }
        screencapBtn.setOnClickListener {
            hideNotificationPanel()
            launchApp("standard", sharedPreferences.getString("app_rec", "")!!)
        }
        settingsBtn.setOnClickListener {
            hideNotificationPanel()
            launchApp("standard", packageName)
        }
        cancelAllBtn!!.setOnClickListener { cancelAllNotifications() }
        notificationsBtn.setImageResource(
            if (sharedPreferences.getBoolean(
                    "show_notifications",
                    true
                )
            ) R.drawable.ic_notifications else R.drawable.ic_notifications_off
        )
        notificationsBtn.setOnClickListener {
            val showNotifications = sharedPreferences.getBoolean("show_notifications", true)
            sharedPreferences.edit { putBoolean("show_notifications", !showNotifications) }
            notificationsBtn.setImageResource(
                if (!showNotifications) R.drawable.ic_notifications else R.drawable.ic_notifications_off
            )
            if (showNotifications) Toast.makeText(
                context,
                R.string.popups_disabled,
                Toast.LENGTH_LONG
            ).show()
        }
        // separator try/catch
        val separator = MaterialDividerItemDecoration(
            ContextThemeWrapper(context, R.style.AppTheme_Dock), LinearLayoutManager.VERTICAL
        )
        separator.isLastItemDecorated = false

        // Panel colors = same as dock (follows Material You / user theme)
        val dockColors  = ColorUtils.getMainColors(sharedPreferences, this)
        val panelColor  = dockColors[0]  // mainColor
        val panelColor2 = dockColors[2]  // secondaryColor for qs area

        // Always fully opaque so the panel looks solid like the dock
        notificationArea?.background?.setColorFilter(panelColor, android.graphics.PorterDuff.Mode.SRC_ATOP)
        notificationArea?.background?.alpha = 255
        qsArea?.background?.setColorFilter(panelColor2, android.graphics.PorterDuff.Mode.SRC_ATOP)
        qsArea?.background?.alpha = 255

        // Divider lines: Material You accent when material_u, white otherwise
        val isMatU = sharedPreferences.getString("theme", "material_u") == "material_u"
        val dividerColor = if (isMatU && com.google.android.material.color.DynamicColors.isDynamicColorAvailable()) {
            val accent = ColorUtils.getThemeColors(this, false)[0] // light colorPrimary
            android.graphics.Color.argb(180,
                android.graphics.Color.red(accent),
                android.graphics.Color.green(accent),
                android.graphics.Color.blue(accent))
        } else {
            android.graphics.Color.argb(80, 255, 255, 255) // subtle white for other themes
        }
        separator.dividerColor = dividerColor

        windowManager.addView(notificationPanel, layoutParams)
        notificationsLv!!.addItemDecoration(separator)
        updateNotificationPanel()
        notificationPanel!!.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE
                && (event.y < notificationPanel!!.measuredHeight || event.x < notificationPanel!!.x)
            ) {
                hideNotificationPanel()
            }
            false
        }
        Utils.notificationPanelVisible = true
        updateNotificationCount()
    }

    private fun launchApp(mode: String, app: String) {
        sendBroadcast(
            Intent(LAUNCHER_ACTION)
                .setPackage(packageName)
                .putExtra("action", ACTION_LAUNCH_APP)
                .putExtra("mode", mode)
                .putExtra("app", app)
        )
    }

    fun hideNotificationPanel() {
        Utils.notificationPanelVisible = false
        val panel = notificationPanel ?: return
        notificationPanel = null
        notificationsLv = null
        cancelAllBtn = null
        try { windowManager.removeView(panel) } catch (_: Exception) {}
    }

    private fun updateNotificationPanel() {
        val ignoredApps = sharedPreferences.getStringSet("ignored_notifications_panel", setOf())!!
        val notifications =
            activeNotifications.filterNot { ignoredApps.contains(it.packageName) }.sortedWith(
                compareByDescending { AppUtils.isMediaNotification(it.notification) && it.isOngoing })
                .toTypedArray<StatusBarNotification>()
        var adapter = notificationsLv!!.adapter
        if (adapter is NotificationAdapter)
            adapter.updateNotifications(notifications)
        else {
            adapter = NotificationAdapter(
                context,
                notifications,
                this
            )
            notificationsLv!!.adapter = adapter
        }
        val layoutParams = notificationsLv!!.layoutParams
        val count = adapter.itemCount
        if (count > 3) {
            layoutParams.height = Utils.dpToPx(context, 232)
        } else layoutParams.height = -2
        notificationArea!!.visibility = if (count == 0) View.GONE else View.VISIBLE
        notificationsLv!!.layoutParams = layoutParams
    }

    internal inner class DockServiceReceiver : BroadcastReceiver() {
        override fun onReceive(p1: Context, intent: Intent) {
            when (intent.getStringExtra("action")) {
                ACTION_SHOW_NOTIFICATION_PANEL -> showNotificationPanel()
                ACTION_HIDE_NOTIFICATION_PANEL -> hideNotificationPanel()
                ACTION_HIDE_NOTIFICATION_BAR -> {
                    // Mark desktop mode as inactive so no new popups appear
                    sharedPreferences.edit { putBoolean("dex_mode_active", false) }
                    // Hide any popup currently on screen
                    hideNotification()
                    // Remove the notification bar window
                    try {
                        if (notificationLayout.windowToken != null)
                            windowManager.removeView(notificationLayout)
                    } catch (_: Exception) {
                        notificationLayout.visibility = android.view.View.GONE
                    }
                }
                ACTION_SHOW_NOTIFICATION_BAR -> {
                    // Mark desktop mode as active so popups are allowed
                    sharedPreferences.edit { putBoolean("dex_mode_active", true) }
                    // FIX: guard addView — overlay permission may be revoked at runtime
                    if (!Settings.canDrawOverlays(p1)) return
                    try {
                        if (notificationLayout.windowToken == null)
                            windowManager.addView(notificationLayout, notificationLayoutParams)
                        notificationLayout.visibility = View.VISIBLE
                    } catch (_: Exception) {
                        notificationLayout.visibility = View.GONE
                    }
                }
                ACTION_STOP_NOTIFICATION_SERVICE -> {
                    // DockService is shutting down — disconnect the notification listener too
                    try { requestUnbind() } catch (_: Exception) {}
                }
            }
        }
    }

    override fun onNotificationClicked(sbn: StatusBarNotification, item: View) {
        val notification = sbn.notification
        if (notification.contentIntent != null) {
            hideNotificationPanel()
            try {
                val options = makeActivityOptions(
                    context, "standard", dockHeight,
                    Display.DEFAULT_DISPLAY
                )
                notification.contentIntent.send(context, 0, null, null, null, null, options.toBundle())
                if (sbn.isClearable) cancelNotification(sbn.key)
            } catch (_: CanceledException) {
            }
        }
    }

    override fun onNotificationLongClicked(notification: StatusBarNotification, item: View) {
        val savedApps = sharedPreferences.getStringSet(
            "ignored_notifications_panel",
            setOf()
        )!!
        val ignoredApps = mutableSetOf<String>()
        ignoredApps.addAll(savedApps)
        ignoredApps.add(notification.packageName)
        sharedPreferences.edit { putStringSet("ignored_notifications_panel", ignoredApps) }
        item.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        Toast.makeText(
            this@NotificationService,
            R.string.silenced_notifications,
            Toast.LENGTH_LONG
        ).show()
        updateNotificationPanel()
    }

    override fun onNotificationCancelClicked(notification: StatusBarNotification, item: View) {
        cancelNotification(notification.key)
    }

    override fun onSharedPreferenceChanged(p0: SharedPreferences?, preference: String?) {
        if (preference == "dock_height")
            updateLayoutParams()
    }

    private fun updateLayoutParams() {
        dockHeight =
            Utils.dpToPx(context, sharedPreferences.getString("dock_height", "56")!!.toInt())
        y = (if (DeviceUtils.shouldApplyNavbarFix())
            dockHeight - DeviceUtils.getNavBarHeight(context)
        else
            dockHeight) + margins

        notificationLayoutParams.y = y
        // Guard: updateViewLayout crashes if the view is not currently attached
        if (notificationLayout.windowToken != null) {
            try {
                windowManager.updateViewLayout(notificationLayout, notificationLayoutParams)
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        try { dockReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        dockReceiver = null
        handler.removeCallbacksAndMessages(null)
        try { windowManager.removeView(notificationLayout) } catch (_: Exception) {}
        super.onDestroy()
    }
}