package com.youki.dex.services

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Notification
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.ActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.content.res.Configuration
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.core.widget.addTextChangedListener
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.youki.dex.R
import com.youki.dex.activities.LAUNCHER_ACTION
import com.youki.dex.activities.LAUNCHER_RESUMED
import com.youki.dex.activities.MainActivity
import com.youki.dex.adapters.AppActionsAdapter
import com.youki.dex.adapters.AppAdapter
import com.youki.dex.adapters.AppAdapter.OnAppClickListener
import com.youki.dex.adapters.AppShortcutAdapter
import com.youki.dex.adapters.AppTaskAdapter
import com.youki.dex.adapters.DisplaysAdapter
import com.youki.dex.adapters.DockAppAdapter
import com.youki.dex.adapters.DockAppAdapter.OnDockAppClickListener
import com.youki.dex.db.DBHelper
import com.youki.dex.models.Action
import com.youki.dex.models.App
import com.youki.dex.models.AppTask
import com.youki.dex.models.DockApp
import com.youki.dex.preferences.NAV_LONG_ACTIONS
import com.youki.dex.receivers.BatteryStatsReceiver
import com.youki.dex.receivers.SoundEventsReceiver
import com.youki.dex.utils.AppUtils
import com.youki.dex.utils.ColorUtils
import com.youki.dex.utils.DeepShortcutManager
import com.youki.dex.utils.DeviceUtils
import com.youki.dex.utils.IconPackUtils
import com.youki.dex.utils.OnSwipeListener
import com.youki.dex.utils.Utils
import com.youki.dex.widgets.HoverInterceptorLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.UnsupportedEncodingException
import java.net.URLEncoder

const val DOCK_SERVICE_CONNECTED = "service_connected"
const val ACTION_TAKE_SCREENSHOT = "take_screenshot"
const val ACTION_LAUNCH_APP = "launch_app"
const val DESKTOP_APP_PINNED = "desktop_app_pinned"
const val DOCK_SERVICE_ACTION = "dock_service_action"

class DockService : AccessibilityService(), OnSharedPreferenceChangeListener, OnTouchListener,
    OnAppClickListener, OnDockAppClickListener {

    // Service-scoped coroutine scope — cancelled automatically in onDestroy()
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    // PERF: Cache interpolators as class-level vals — avoids instantiating a new
    // object on every animation call. On budget CPUs (T606, Helio G35) this alone
    // shaves ~2-4ms per frame by eliminating repeated GC pressure from short-lived
    // interpolator allocations inside tight animation loops.
    private val interpSpringIn   by lazy { android.view.animation.PathInterpolator(0.22f, 1.0f, 0.36f, 1.0f) }
    private val interpEmphasized by lazy { android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f) }
    private val interpExit       by lazy { android.view.animation.PathInterpolator(0.3f, 0f, 0.8f, 0.15f) }
    private val interpAccel      by lazy { android.view.animation.AccelerateInterpolator(1.5f) }
    private val interpSpringBtn  by lazy { android.view.animation.PathInterpolator(0.34f, 1.56f, 0.64f, 1f) }
    private val interpAccelDecel by lazy { android.view.animation.AccelerateDecelerateInterpolator() }

    private var orientation = -1
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var activityManager: ActivityManager
    private lateinit var appsBtn: ImageView
    private lateinit var appsBtnCenter: ImageView
    private lateinit var backBtn: ImageView
    private lateinit var homeBtn: ImageView
    private lateinit var recentBtn: ImageView
    private lateinit var assistBtn: ImageView
    private lateinit var powerBtn: ImageView
    private lateinit var bluetoothBtn: ImageView
    private lateinit var wifiBtn: ImageView
    private lateinit var batteryBtn: TextView
    private lateinit var volumeBtn: ImageView
    private lateinit var pinBtn: ImageView
    private lateinit var wallpaperBtn: ImageView

    private lateinit var castBtn: android.widget.ImageView
    private var castManager: com.youki.dex.cast.CastManager? = null

    private lateinit var notificationBtn: TextView
    private lateinit var searchTv: TextView
    private lateinit var topRightCorner: Button
    private lateinit var bottomRightCorner: Button
    private lateinit var dockHandle: Button
    private lateinit var appMenu: LinearLayout
    private lateinit var searchLayout: LinearLayout
    private var powerMenu: LinearLayout? = null
    private var audioPanel: LinearLayout? = null
    private lateinit var searchEntry: LinearLayout
    private lateinit var dockLayout: RelativeLayout
    private lateinit var windowManager: WindowManager
    private lateinit var appsSeparator: View
    private var appMenuVisible = false
    private var powerMenuVisible = false
    private var isPinned = false
    private var audioPanelVisible = false
    private var systemApp = false
    private var secondary = false
    private lateinit var dockLayoutParams: WindowManager.LayoutParams
    private lateinit var searchEt: EditText
    private lateinit var tasksGv: RecyclerView
    private lateinit var favoritesGv: RecyclerView
    private lateinit var appsGv: RecyclerView
    private lateinit var wifiManager: WifiManager
    private lateinit var batteryReceiver: BatteryStatsReceiver
    private lateinit var soundEventsReceiver: SoundEventsReceiver
    private var launcherReceiver: BroadcastReceiver? = null
    private var dockActionReceiver: BroadcastReceiver? = null
    private var notificationServiceReceiver: BroadcastReceiver? = null
    private var wallpaperReceiver: BroadcastReceiver? = null
    private var packageReceiver: BroadcastReceiver? = null
    private lateinit var gestureDetector: GestureDetector
    private lateinit var db: DBHelper
    private lateinit var dockHandler: Handler
    private lateinit var dock: HoverInterceptorLayout
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var pinnedApps: ArrayList<App>
    private lateinit var dateTv: TextClock
    private var maxApps = 0
    private var maxAppsLandscape = 0
    private lateinit var context: Context
    private lateinit var tasks: ArrayList<AppTask>
    @Volatile private var lastUpdate: Long = 0 // FIX #12: @Volatile prevents race condition between Main and IO threads
    private var dockHeight: Int = 0
    private var dockMargin: Int = 0
    private lateinit var handleLayoutParams: WindowManager.LayoutParams
    private lateinit var launcherApps: LauncherApps
    private var iconPackUtils: IconPackUtils? = null
    // Quick Settings Panel
    private var qsPanel: LinearLayout? = null
    private var qsPanelVisible = false
    private var qsPanelAnimating = false
    // Resource Monitor
    private var resourceMonitorTv: TextView? = null
    private var resourceHandler: Handler? = null
    private var prevCpuTotal = 0L
    private var prevCpuIdle = 0L
    private var lastCpuValue = 0
    override fun onCreate() {
        super.onCreate()
        db = DBHelper(this)
        activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        sharedPreferences.registerOnSharedPreferenceChangeListener(this)
        secondary = sharedPreferences.getBoolean("prefer_last_display", false)
        context = DeviceUtils.getDisplayContext(this, secondary)
        windowManager = context.getSystemService(WINDOW_SERVICE) as WindowManager
        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        launcherApps = getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps
        dockHandler = Handler(Looper.getMainLooper())
        if (sharedPreferences.getString("icon_pack", "").orEmpty().isNotEmpty()) {
            iconPackUtils = IconPackUtils(this)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Utils.startupTime = System.currentTimeMillis()
        systemApp = AppUtils.isSystemApp(context, packageName)
        // Enable freeform windowing system-wide so ALL apps open as windows
        // regardless of which profile (phone/tablet/PC) the user chose
        enableFreeformWindowing()
        maxApps = sharedPreferences.getString("max_running_apps", "10")?.toDoubleOrNull()?.toInt() ?: 10
        maxAppsLandscape = sharedPreferences.getString("max_running_apps_landscape", "10")?.toDoubleOrNull()?.toInt() ?: 10
        orientation = resources.configuration.orientation

        //Create the dock
        dock = LayoutInflater.from(
            androidx.appcompat.view.ContextThemeWrapper(
                context,
                R.style.AppTheme_Dock
            )
        ).inflate(R.layout.dock, null) as HoverInterceptorLayout
        dockLayout = dock.findViewById(R.id.dock_layout)
        dockHandle = LayoutInflater.from(context).inflate(R.layout.dock_handle, null) as Button
        appsBtn = dock.findViewById(R.id.apps_btn)
        appsBtnCenter = dock.findViewById(R.id.apps_btn_center)
        tasksGv = dock.findViewById(R.id.apps_lv)
        val layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        tasksGv.layoutManager = layoutManager
        backBtn = dock.findViewById(R.id.back_btn)
        homeBtn = dock.findViewById(R.id.home_btn)
        recentBtn = dock.findViewById(R.id.recents_btn)
        assistBtn = dock.findViewById(R.id.assist_btn)
        notificationBtn = dock.findViewById(R.id.notifications_btn)
        pinBtn   = dock.findViewById(R.id.pin_btn)
        wallpaperBtn = dock.findViewById(R.id.wallpaper_btn)
        castBtn = dock.findViewById(R.id.cast_btn)
        bluetoothBtn = dock.findViewById(R.id.bluetooth_btn)
        wifiBtn = dock.findViewById(R.id.wifi_btn)
        volumeBtn = dock.findViewById(R.id.volume_btn)
        batteryBtn = dock.findViewById(R.id.battery_btn)
        dateTv = dock.findViewById(R.id.date_btn)
        resourceMonitorTv = dock.findViewById(R.id.resource_monitor_tv)
        // Show/hide resource monitor based on preference
        if (sharedPreferences.getBoolean("show_resource_monitor", false)) {
            resourceMonitorTv?.visibility = View.VISIBLE
            startResourceMonitor()
        }
        dock.setOnHoverListener { _, event ->
            if (event.action == MotionEvent.ACTION_HOVER_ENTER) {
                if (dockLayout.isGone) showDock()
            } else if (event.action == MotionEvent.ACTION_HOVER_EXIT) if (dockLayout.isVisible) {
                hideDock(500)
            }
            false
        }
        gestureDetector = GestureDetector(context, object : OnSwipeListener() {
            override fun onSwipe(direction: Direction): Boolean {
                if (direction == Direction.UP) {
                    if (!isPinned) pinDock() else if (!appMenuVisible) showAppMenu()
                } else if (direction == Direction.DOWN) {
                    if (appMenuVisible) hideAppMenu() else unpinDock()
                } else if (direction == Direction.LEFT) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
                return true
            }
        })
        dock.setOnTouchListener(this)
        dockLayout.setOnTouchListener(this)
        dockHandle.alpha = sharedPreferences.getString("handle_opacity", "0.5")?.toFloatOrNull() ?: 0.5f
        dockHandle.setOnClickListener { pinDock() }

        // Central animation function for all buttons
        // POLISH: ViewPropertyAnimator runs on the RenderThread → zero jank.
        // XML-based Animation runs on the main thread and chains two separate listeners
        // which introduces a visible gap between press and release.
        // We now do press + action + release in one smooth Animator chain.
        // Haptic feedback makes every tap feel physical and premium.
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
            context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
        else
            @Suppress("DEPRECATION") context.getSystemService(android.os.Vibrator::class.java)

        fun haptic(view: View) {
            view.performHapticFeedback(
                android.view.HapticFeedbackConstants.VIRTUAL_KEY,
                android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        }

        fun animateBtn(view: View, action: () -> Unit) {
            haptic(view)
            view.animate().cancel()
            // PERF: reduced 60→40ms press, 280→180ms release for budget CPUs (T606/G35).
            // Uses cached interpolators — no per-call object allocation.
            view.animate()
                .scaleX(0.82f).scaleY(0.82f).alpha(0.65f)
                .setDuration(40)
                .setInterpolator(interpAccel)
                .withEndAction {
                    action()
                    view.animate()
                        .scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(180)
                        .setInterpolator(interpSpringBtn)
                        .start()
                }.start()
        }

        appsBtn.setOnClickListener { animateBtn(it) { toggleAppMenu() } }
        appsBtn.setOnLongClickListener {
            launchApp(null, null, Intent(Settings.ACTION_APPLICATION_SETTINGS))
            true
        }
        appsBtnCenter.setOnClickListener { animateBtn(it) { toggleAppMenu() } }
        appsBtnCenter.setOnLongClickListener {
            launchApp(null, null, Intent(Settings.ACTION_APPLICATION_SETTINGS))
            true
        }
        assistBtn.setOnClickListener { animateBtn(it) { launchAssistant() } }

        backBtn.setOnClickListener { animateBtn(it) { performGlobalAction(GLOBAL_ACTION_BACK) } }
        backBtn.setOnLongClickListener {
            performNavAction("enable_nav_back")
            true
        }
        homeBtn.setOnClickListener { animateBtn(it) { performGlobalAction(GLOBAL_ACTION_HOME) } }
        homeBtn.setOnLongClickListener {
            performNavAction("enable_nav_home")
            true
        }
        recentBtn.setOnClickListener { animateBtn(it) { performGlobalAction(GLOBAL_ACTION_RECENTS) } }
        recentBtn.setOnLongClickListener {
            performNavAction("enable_nav_recents")
            true
        }

        notificationBtn.setOnClickListener {
            animateBtn(it) {
                if (sharedPreferences.getBoolean("enable_qs_notif", true)) {
                    if (audioPanelVisible) hideAudioPanel()
                            toggleNotificationPanel(!Utils.notificationPanelVisible)
                } else performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            }
        }
        pinBtn.setOnClickListener { animateBtn(it) { togglePin() } }

        // ── Wallpaper button ──────────────────────────────────────────────────
        // Opens the app chosen by the user in settings
        // If none is set, opens the default wallpaper picker
        wallpaperBtn.setOnClickListener {
            animateBtn(it) {
                val pkg = sharedPreferences.getString("app_wallpaper", "").orEmpty()
                if (pkg.isNotEmpty()) {
                    launchApp(null, pkg)
                } else {
                    launchApp(null, null, Intent(Intent.ACTION_SET_WALLPAPER)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
        wallpaperBtn.setOnLongClickListener {
            // Long press → open wallpaper button settings directly
            launchApp("standard", null, Intent(this, com.youki.dex.activities.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
            true
        }

        // ── Cast button ───────────────────────────────────────────────────────
        castManager = com.youki.dex.cast.CastManager(context).also { mgr ->
            mgr.init()
            mgr.onStateChanged = { isConnected ->
                // Show the cast button whenever a device is available or connected
                castBtn.visibility = if (mgr.isAvailable) View.VISIBLE else View.GONE
                castBtn.setImageResource(
                    if (isConnected) R.drawable.ic_cast_connected else R.drawable.ic_screen
                )
                castBtn.alpha = if (isConnected) 1f else 0.6f
            }
        }
        castBtn.setOnClickListener {
            animateBtn(it) {
                if (castManager?.isConnected == true) {
                    castManager?.disconnect()
                } else {
                    castManager?.showCastPicker(context)
                }
            }
        }

        // Quick-launch shortcut button removed
        bluetoothBtn.setOnClickListener { animateBtn(it) { launchApp(null, null, Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } }
        bluetoothBtn.setOnLongClickListener {
            openBluetoothSettings()
            true
        }
        wifiBtn.setOnClickListener { animateBtn(it) { toggleWifi() } }
        wifiBtn.setOnLongClickListener {
            launchApp(null, null, Intent(Settings.ACTION_WIFI_SETTINGS))
            true
        }
        volumeBtn.setOnClickListener { animateBtn(it) { toggleVolume() } }
        volumeBtn.setOnLongClickListener {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                launchApp(null, null, Intent(Settings.ACTION_SOUND_SETTINGS))
            } else
                startActivity(Intent(Settings.Panel.ACTION_VOLUME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }
        batteryBtn.setOnClickListener {
            animateBtn(it) {
                launchApp(null, null, Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
            }
        }
        dateTv.setOnClickListener { toggleQsPanel() }
        dateTv.setOnLongClickListener {
            launchApp(null, null, Intent(Settings.ACTION_DATE_SETTINGS))
            true
        }

        dockHeight =
            Utils.dpToPx(context, sharedPreferences.getString("dock_height", "56")?.toIntOrNull() ?: 56)
        val isRoundOnStartup = sharedPreferences.getBoolean("round_dock", false)
        val displayIdStartup = if (secondary)
            DeviceUtils.getSecondaryDisplay(context)?.displayId ?: Display.DEFAULT_DISPLAY
        else
            Display.DEFAULT_DISPLAY
        val displayWidthStartup = DeviceUtils.getDisplayMetrics(context, displayIdStartup).widthPixels
        dockMargin = Utils.dpToPx(context, 8)

        dockLayoutParams = Utils.makeWindowParams(
            if (isRoundOnStartup) displayWidthStartup - 2 * dockMargin else -1,
            dockHeight, context, secondary
        )
        dockLayoutParams.screenOrientation =
            if (sharedPreferences.getBoolean("lock_landscape", true))
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        // KDE-style floating: centered with bottom margin; Windows-style: pinned to edge
        if (isRoundOnStartup) {
            dockLayoutParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            dockLayoutParams.y = dockMargin
        } else {
            dockLayoutParams.gravity = Gravity.BOTTOM or Gravity.START
            dockLayoutParams.y = 0
        }
        // Hide status bar via WindowManager flags — stronger than policy_control
        // FLAG_HARDWARE_ACCELERATED must be set BEFORE addView() — required for blur to work
        // Android 16+: FLAG_FULLSCREEN causes touch occlusion — use Shizuku instead
        dockLayoutParams.flags = dockLayoutParams.flags or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (Build.VERSION.SDK_INT >= 35) {
            dockLayoutParams.flags = dockLayoutParams.flags or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            dockLayoutParams.setFitInsetsTypes(0)
        } else {
            dockLayoutParams.flags = dockLayoutParams.flags or
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        }
        // Prevent dock from moving/resizing when keyboard appears
        dockLayoutParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        // blur requires TRANSLUCENT format — already set in makeWindowParams
        // Dock appears immediately when service starts
        windowManager.addView(dock, dockLayoutParams)

        //Hot corners
        topRightCorner = Button(context)
        topRightCorner.setBackgroundResource(R.drawable.corner_background)
        bottomRightCorner = Button(context)
        bottomRightCorner.setBackgroundResource(R.drawable.corner_background)
        topRightCorner.setOnHoverListener(HotCornersHoverListener("enable_corner_top_right"))
        bottomRightCorner.setOnHoverListener(HotCornersHoverListener("enable_corner_bottom_right"))
        updateCorners()
        val cornersLayoutParams = Utils.makeWindowParams(
            Utils.dpToPx(context, 2), -2, context,
            secondary
        )
        cornersLayoutParams.gravity = Gravity.TOP or Gravity.END
        windowManager.addView(topRightCorner, cornersLayoutParams)
        cornersLayoutParams.gravity = Gravity.BOTTOM or Gravity.END
        windowManager.addView(bottomRightCorner, cornersLayoutParams)

        //App menu
        appMenu = LayoutInflater.from(ContextThemeWrapper(context, R.style.AppTheme_Dock))
            .inflate(R.layout.apps_menu, null) as LinearLayout
        searchEntry = appMenu.findViewById(R.id.search_entry)
        searchEt = appMenu.findViewById(R.id.menu_et)
        powerBtn = appMenu.findViewById(R.id.power_btn)
        appsGv = appMenu.findViewById(R.id.menu_applist_lv)
        appsGv.setHasFixedSize(true)
        appsGv.layoutManager = GridLayoutManager(context, 5)
        favoritesGv = appMenu.findViewById(R.id.fav_applist_lv)
        favoritesGv.layoutManager = GridLayoutManager(context, 5)
        searchLayout = appMenu.findViewById(R.id.search_layout)
        searchTv = appMenu.findViewById(R.id.search_tv)
        appsSeparator = appMenu.findViewById(R.id.apps_separator)
        powerBtn.setOnClickListener {
            hideAppMenu()
            // Show confirmation dialog: Restart DEX or Force Stop
            val dialogView = LayoutInflater.from(context).inflate(R.layout.task_list, null)
            val dialogParams = Utils.makeWindowParams(-2, -2, context, secondary)
            dialogParams.gravity = Gravity.CENTER
            dialogParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            ColorUtils.applyMainColor(context, sharedPreferences, dialogView)
            val actions = ArrayList<Action>().apply {
                add(Action(R.drawable.ic_soft_restart, getString(R.string.restart_dex)))
                add(Action(R.drawable.ic_hide, getString(R.string.force_stop_dex)))
            }
            val actionsLv = dialogView.findViewById<android.widget.ListView>(R.id.tasks_lv)
            actionsLv.adapter = AppActionsAdapter(context, actions)
            dialogView.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE)
                    try { windowManager.removeView(dialogView) } catch (_: Exception) {}
                false
            }
            actionsLv.setOnItemClickListener { _, _, position, _ ->
                try { windowManager.removeView(dialogView) } catch (_: Exception) {}
                if (position == 0) {
                    // Restart DEX — schedule relaunch then kill
                    val restartIntent = android.content.Intent(context, com.youki.dex.activities.LauncherActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    val pending = android.app.PendingIntent.getActivity(
                        context, 22222, restartIntent,
                        android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                    val am = context.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
                    am[android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, android.os.SystemClock.elapsedRealtime() + 1000] = pending
                    stopSelf()
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }, 400)
                } else {
                    // Force Stop — kill everything immediately
                    com.youki.dex.App.cancelRestartAlarm(context)
                    com.youki.dex.App.intentionalShutdown = true
                    try { disableSelf() } catch (_: Exception) {}
                    stopSelf()
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }, 400)
                }
            }
            windowManager.addView(dialogView, dialogParams)
        }
        searchTv.setOnClickListener {
            try {
                launchApp(
                    null, null,
                    Intent(
                        Intent.ACTION_VIEW,
                        ("https://www.google.com/search?q="
                                + URLEncoder.encode(searchEt.text.toString(), "UTF-8")).toUri()
                    )
                )
            } catch (e: UnsupportedEncodingException) {
                throw RuntimeException(e)
            }
        }

        searchEt.addTextChangedListener { text ->
            if (text != null) {
                val appAdapter = appsGv.adapter as? AppAdapter ?: return@addTextChangedListener
                appAdapter.filter(text.toString())
                if (text.length > 1) {
                    searchLayout.visibility = View.VISIBLE
                    searchTv.text =
                        getString(R.string.search_for) + " \"" + text + "\" " + getString(R.string.on_google)
                    toggleFavorites(false)
                } else {
                    searchLayout.visibility = View.GONE
                    toggleFavorites(
                        AppUtils.getPinnedApps(
                            context,
                            AppUtils.PINNED_LIST
                        ).isNotEmpty()
                    )
                }
            }
        }

        searchEt.setOnKeyListener { _, code, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (code == KeyEvent.KEYCODE_ENTER && searchEt.text.toString().length > 1) {
                    try {
                        launchApp(
                            null, null,
                            Intent(
                                Intent.ACTION_VIEW,
                                ("https://www.google.com/search?q="
                                        + URLEncoder.encode(
                                    searchEt.text.toString(),
                                    "UTF-8"
                                )).toUri()
                            )
                        )
                    } catch (e: UnsupportedEncodingException) {
                        throw RuntimeException(e)
                    }
                    true
                } else if (code == KeyEvent.KEYCODE_DPAD_DOWN)
                    appsGv.requestFocus()
            }
            false
        }

        updateAppMenu()

        //TODO: Filter app button menu click only
        appMenu.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE
                && (event.y < appMenu.measuredHeight || event.x > appMenu.measuredWidth)
            ) {
                hideAppMenu()
            }
            false
        }

        //Dock handle
        handleLayoutParams = Utils.makeWindowParams(
            Utils.dpToPx(context, 22), -2, context,
            secondary
        )
        updateHandlePositionValues()

        //Listen for launcher messages
        launcherReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.getStringExtra("action")) {
                    LAUNCHER_RESUMED -> pinDock()
                    ACTION_LAUNCH_APP -> {
                        val pkg = intent.getStringExtra("app") ?: return@onReceive
                        launchApp(intent.getStringExtra("mode"), pkg)
                    }
                }
            }
        }
        ContextCompat.registerReceiver(this, launcherReceiver, IntentFilter(LAUNCHER_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)

        // Receiver for DeX mode enable/disable commands — register BEFORE broadcasting CONNECTED
        dockActionReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.getStringExtra("action")) {
                    "enable_dock" -> {
                        try {
                            if (dock.windowToken == null)
                                windowManager.addView(dock, dockLayoutParams)
                        } catch (_: Exception) {}
                        dock.visibility = android.view.View.VISIBLE
                        // FIX: pinDock() must be called to also show dockLayout (the actual
                        // visible bar inside the outer dock container). Without this, the
                        // outer HoverInterceptorLayout is VISIBLE but the inner bar stays GONE,
                        // so the dock appears invisible on desktop entry / service reconnect.
                        pinDock()
                        sendBroadcast(Intent(DOCK_SERVICE_ACTION)
                            .setPackage(packageName)
                            .putExtra("action", ACTION_SHOW_NOTIFICATION_BAR))
                    }
                    "disable_self" -> {
                        try { if (appMenuVisible) hideAppMenu() } catch (_: Exception) {}
                        try { if (qsPanelVisible) toggleQsPanel() } catch (_: Exception) {}
                        dock.visibility = android.view.View.GONE
                        sendBroadcast(Intent(DOCK_SERVICE_ACTION)
                            .setPackage(packageName)
                            .putExtra("action", ACTION_HIDE_NOTIFICATION_BAR))
                    }
                }
            }
        }
        ContextCompat.registerReceiver(this, dockActionReceiver, IntentFilter(DOCK_SERVICE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)

        // Tell the launcher the service has connected + activate notifications immediately
        sendBroadcast(
            Intent(DOCK_SERVICE_ACTION)
                .setPackage(packageName)
                .putExtra("action", DOCK_SERVICE_CONNECTED)
        )
        // Activate notifications right away — don't wait for LauncherActivity
        sendBroadcast(Intent(DOCK_SERVICE_ACTION)
            .setPackage(packageName)
            .putExtra("action", ACTION_SHOW_NOTIFICATION_BAR))

        //Register receivers
        notificationServiceReceiver = object : BroadcastReceiver() {
            override fun onReceive(p1: Context, intent: Intent) {
                when (intent.getStringExtra("action")) {
                    NOTIFICATION_COUNT_CHANGED -> {
                        val count = intent.getIntExtra("count", 0)
                        if (count > 0) {
                            notificationBtn.setBackgroundResource(R.drawable.circle)
                            notificationBtn.text = count.toString()
                        } else {
                            notificationBtn.setBackgroundResource(R.drawable.ic_expand_up_circle)
                            notificationBtn.text = ""
                        }
                        // Re-apply bubble color — setBackgroundResource resets the colorFilter
                        val c = getBubbleColor()
                        notificationBtn.background?.setColorFilter(c, android.graphics.PorterDuff.Mode.SRC_ATOP)
                        notificationBtn.background?.alpha = 255
                    }

                    ACTION_TAKE_SCREENSHOT -> takeScreenshot()
                }
            }
        }
        ContextCompat.registerReceiver(this, notificationServiceReceiver,
            IntentFilter(NOTIFICATION_SERVICE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        batteryReceiver = BatteryStatsReceiver(
            context,
            batteryBtn,
            sharedPreferences.getBoolean("show_battery_level", false)
        )
        // FIX #3: ACTION_BATTERY_CHANGED is a sticky broadcast — must use RECEIVER_EXPORTED
        ContextCompat.registerReceiver(
            this, batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
        )
        updateBatteryBtn()
        soundEventsReceiver = SoundEventsReceiver()
        val soundEventsFilter = IntentFilter()
        soundEventsFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        soundEventsFilter.addAction(Intent.ACTION_POWER_CONNECTED)
        ContextCompat.registerReceiver(
            this,
            soundEventsReceiver,
            soundEventsFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        wallpaperReceiver = object : BroadcastReceiver() {
            override fun onReceive(p1: Context, intent: Intent) {
                // Delay lets Material You finish computing new palette before we read it
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    // Invalidate the DynamicColors cache so applyTheme() picks up the new palette
                    com.youki.dex.utils.ColorUtils.invalidateDynamicColorCache()
                    applyTheme()
                    applyBubbleColors()
                }, 800)
            }
        }
        val wallpaperFilter = IntentFilter().apply {
            addAction(Intent.ACTION_WALLPAPER_CHANGED)
            // Material You regenerates palette on these events
            addAction("android.intent.action.OVERLAY_CHANGED")
            addAction("com.android.server.wm.theme.THEME_CHANGED")
        }
        ContextCompat.registerReceiver(this, wallpaperReceiver, wallpaperFilter, ContextCompat.RECEIVER_NOT_EXPORTED)

        val filter = IntentFilter(Intent.ACTION_PACKAGE_FULLY_REMOVED)
        filter.addDataScheme("package")
        packageReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                loadPinnedApps()
                updateRunningTasks()
            }
        }
        ContextCompat.registerReceiver(this, packageReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        //Play startup sound
        DeviceUtils.playEventSound(context, "startup_sound")

        updateNavigationBar()
        updateQuickSettings()
        updateDockShape()
        applyTheme()
        updateMenuIcon()
        loadPinnedApps()
        placeRunningApps()
        windowManager.addView(dockHandle, handleLayoutParams)
        if (sharedPreferences.getBoolean("pin_dock", true))
            pinDock()
        else
            Toast.makeText(context, R.string.start_message, Toast.LENGTH_LONG).show()
        // Always hide status bar with YoukiDex — restored on close
        DeviceUtils.hideStatusBar(context, true)
    }

    private fun getAppActions(app: App): ArrayList<Action> {
        val actions = ArrayList<Action>()
        if (DeepShortcutManager.hasHostPermission(context)) {
            if (!DeepShortcutManager.getShortcuts(app.packageName, context).isNullOrEmpty())
                actions.add(Action(R.drawable.ic_shortcuts, getString(R.string.shortcuts)))
        }
        actions.add(Action(R.drawable.ic_manage, getString(R.string.manage)))
        actions.add(Action(R.drawable.ic_launch_mode, getString(R.string.open_as)))
        if (DeviceUtils.getDisplays(this).size > 1)
            actions.add(Action(R.drawable.ic_add_to_desktop, getString(R.string.launch_in)))
        if (AppUtils.isPinned(context, app, AppUtils.PINNED_LIST))
            actions.add(Action(R.drawable.ic_remove_favorite, getString(R.string.remove)))
        if (getPinActions(app).isNotEmpty())
            actions.add(Action(R.drawable.ic_pin, getString(R.string.add_to)))

        return actions
    }

    private fun getPinActions(app: App): ArrayList<Action> {
        val actions = ArrayList<Action>()
        if (!AppUtils.isPinned(context, app, AppUtils.PINNED_LIST))
            actions.add(Action(R.drawable.ic_add_favorite, getString(R.string.favorites)))
        if (!AppUtils.isPinned(context, app, AppUtils.DESKTOP_LIST))
            actions.add(Action(R.drawable.ic_add_to_desktop, getString(R.string.desktop)))
        if (!AppUtils.isPinned(context, app, AppUtils.DOCK_PINNED_LIST))
            actions.add(Action(R.drawable.ic_pin, getString(R.string.dock)))

        return actions
    }

    override fun onDockAppClicked(app: DockApp, anchor: View) {
        val tasks = app.tasks
        if (tasks.size == 1) {
            val taskId = tasks[0].id
            if (taskId == -1) {
                launchApp(null, app.packageName)
            } else {
                // Toggle minimize: if app is in foreground → hide it, otherwise → show it
                val runningTasks = activityManager.getRunningTasks(1)
                val foregroundPackage = runningTasks.firstOrNull()?.topActivity?.packageName
                if (foregroundPackage == app.packageName) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                } else {
                    activityManager.moveTaskToFront(taskId, 0)
                }
            }
            // auto-pin/unpin after direct launch only
            if (getDefaultLaunchMode(app.packageName) == "fullscreen") {
                if (isPinned && sharedPreferences.getBoolean("auto_unpin", true)) unpinDock()
            } else {
                if (!isPinned && sharedPreferences.getBoolean("auto_pin", true)) pinDock()
            }
        } else if (tasks.size > 1) {
            val view = LayoutInflater.from(context).inflate(R.layout.task_list, null)
            val layoutParams = Utils.makeWindowParams(-2, -2, context, secondary)
            ColorUtils.applyMainColor(context, sharedPreferences, view)
            layoutParams.gravity = Gravity.BOTTOM or Gravity.START
            layoutParams.flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL   // FIX: prevent blocking touches outside this popup
                    or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
            layoutParams.y = Utils.dpToPx(context, 2) + dockHeight
            val location = IntArray(2)
            anchor.getLocationOnScreen(location)
            layoutParams.x = location[0]
            view.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    windowManager.removeView(view)
                }
                false
            }
            val tasksLv = view.findViewById<ListView>(R.id.tasks_lv)
            tasksLv.adapter = AppTaskAdapter(context, tasks)
            tasksLv.setOnItemClickListener { adapterView, _, position, _ ->
                activityManager.moveTaskToFront(
                    (adapterView.getItemAtPosition(position) as AppTask).id, 0
                )
                windowManager.removeView(view)
            }
            windowManager.addView(view, layoutParams)
        } else {
            launchApp(getDefaultLaunchMode(app.packageName), app.packageName)
            // auto-pin/unpin after launch only
            if (getDefaultLaunchMode(app.packageName) == "fullscreen") {
                if (isPinned && sharedPreferences.getBoolean("auto_unpin", true)) unpinDock()
            } else {
                if (!isPinned && sharedPreferences.getBoolean("auto_pin", true)) pinDock()
            }
        }
    }

    override fun onDockAppLongClicked(app: DockApp, view: View) {
        showDockAppContextMenu(app, view)
    }

    override fun onAppClicked(app: App, item: View) {
        if (app.packageName == "$packageName.calc") {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("results", app.name))
            Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
        } else launchApp(null, app.packageName, null, app)
    }

    override fun onAppLongClicked(app: App, view: View) {
        if (app.packageName != "$packageName.calc") {
            showAppContextMenu(app, view)
        }
    }

    // FIX: Debounce TYPE_WINDOWS_CHANGED — fires dozens of times per second during
    // animations. Without this, updateRunningTasks() is hammered even though its
    // own 500ms guard only skips adapter updates, NOT the wifi/bluetooth icon refresh
    // and DockAppAdapter creation path (when recreateAdapter=true). Tracking the
    // last event time here gives a true event-level gate.
    private var lastWindowChangeTime = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            // Re-apply rotation lock on every window change
            if (sharedPreferences.getBoolean("lock_landscape", true)) {
                DeviceUtils.freezeRotation(true)
            }
            val now = System.currentTimeMillis()
            if (now - lastWindowChangeTime < 250) return  // debounce: skip if < 250ms since last event
            lastWindowChangeTime = now
            if (Build.VERSION.SDK_INT >= 28) {
                if (event.windowChanges.and(AccessibilityEvent.WINDOWS_CHANGE_REMOVED) == AccessibilityEvent.WINDOWS_CHANGE_REMOVED ||
                    event.windowChanges.and(AccessibilityEvent.WINDOWS_CHANGE_ADDED) == AccessibilityEvent.WINDOWS_CHANGE_ADDED
                )
                    updateRunningTasks()
            } else {
                updateRunningTasks()
            }
        } else if (sharedPreferences.getBoolean(
                "custom_toasts",
                false
            ) && event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED && event.parcelableData !is Notification && event.text.isNotEmpty()
        ) {
            val text = event.text[0].toString()
            val app = event.packageName.toString()
            showToast(app, text)
        }
    }

    private fun showToast(app: String, text: String) {
        val layoutParams = Utils.makeWindowParams(-2, -2, context, secondary)
        layoutParams.gravity = Gravity.BOTTOM or Gravity.CENTER
        layoutParams.y = dock.measuredHeight + Utils.dpToPx(context, 4)
        val toast = LayoutInflater.from(context).inflate(R.layout.toast, null)
        ColorUtils.applyMainColor(context, sharedPreferences, toast)
        val textTv = toast.findViewById<TextView>(R.id.toast_tv)
        val iconIv = toast.findViewById<ImageView>(R.id.toast_iv)
        textTv.text = text
        val notificationIcon = AppUtils.getAppIcon(context, app)
        iconIv.setImageDrawable(notificationIcon)
        ColorUtils.applyColor(iconIv, ColorUtils.getDrawableDominantColor(notificationIcon))
        toast.alpha = 0f
        toast.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        toast.animate().alpha(1f).setDuration(150)
            .setInterpolator(AccelerateDecelerateInterpolator())
        // FIX #13: Reuse dockHandler instead of creating a new Handler per toast
        dockHandler.postDelayed({
            toast.animate().alpha(0f).setDuration(600)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        toast.setLayerType(View.LAYER_TYPE_NONE, null)
                        windowManager.removeView(toast)
                    }
                })
        }, 5000)
        windowManager.addView(toast, layoutParams)
    }

    override fun onInterrupt() {}

    //Handle keyboard shortcuts
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            if (event.isAltPressed) {
                if (event.keyCode == KeyEvent.KEYCODE_L && sharedPreferences.getBoolean(
                        "enable_lock_desktop",
                        true
                    )
                )
                    lockScreen()
                else if (event.keyCode == KeyEvent.KEYCODE_P && sharedPreferences.getBoolean(
                        "enable_open_settings",
                        true
                    )
                )
                    launchApp(
                        null, null,
                        Intent(Settings.ACTION_SETTINGS)
                    )
                else if (event.keyCode == KeyEvent.KEYCODE_T && sharedPreferences.getBoolean(
                        "enable_open_terminal",
                        false
                    )
                )
                    launchApp(null, sharedPreferences.getString("app_terminal", "com.termux").orEmpty())
                else if (event.keyCode == KeyEvent.KEYCODE_Q && sharedPreferences.getBoolean(
                        "enable_expand_notifications",
                        true
                    )
                )
                    performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                else if (event.keyCode == KeyEvent.KEYCODE_W && sharedPreferences.getBoolean(
                        "enable_toggle_pin",
                        true
                    )
                )
                    togglePin()
                else if (event.keyCode == KeyEvent.KEYCODE_M && sharedPreferences.getBoolean(
                        "enable_open_music",
                        true
                    )
                )
                    launchApp(null, sharedPreferences.getString("app_music", "").orEmpty())
                else if (event.keyCode == KeyEvent.KEYCODE_B && sharedPreferences.getBoolean(
                        "enable_open_browser",
                        true
                    )
                )
                    launchApp(null, sharedPreferences.getString("app_browser", "").orEmpty())
                else if (event.keyCode == KeyEvent.KEYCODE_A && sharedPreferences.getBoolean(
                        "enable_open_assist",
                        true
                    )
                )
                    launchApp(null, sharedPreferences.getString("app_assistant", "").orEmpty())
                else if (event.keyCode == KeyEvent.KEYCODE_R && sharedPreferences.getBoolean(
                        "enable_open_rec",
                        true
                    )
                )
                    launchApp(null, sharedPreferences.getString("app_rec", "").orEmpty())
                else if (event.keyCode == KeyEvent.KEYCODE_D)
                    startActivity(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                else if (event.keyCode == KeyEvent.KEYCODE_O) {
                    toggleSoftKeyboard()
                } else if (event.keyCode == KeyEvent.KEYCODE_F12)
                    DeviceUtils.softReboot()
                //Window management
                else if (event.keyCode == KeyEvent.KEYCODE_F3) {
                    if (tasks.isNotEmpty()) {
                        val task = tasks[0]
                        AppUtils.resizeTask(
                            context, "portrait", task.id, dockHeight
                        )
                    }
                } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (tasks.isNotEmpty()) {
                        val task = tasks[0]
                        if (event.isShiftPressed)
                            launchApp(
                                "maximized",
                                task.packageName,
                                newInstance = true,
                                rememberMode = false
                            )
                        else
                            AppUtils.resizeTask(
                                context, "maximized", task.id, dockHeight
                            )
                    }
                } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (tasks.isNotEmpty()) {
                        val task = tasks[0]
                        if (event.isShiftPressed)
                            launchApp(
                                "tiled-left",
                                task.packageName,
                                newInstance = true,
                                rememberMode = false
                            )
                        else
                            AppUtils.resizeTask(
                                context, "tiled-left", task.id, dockHeight
                            )
                        return true
                    }
                } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (tasks.isNotEmpty()) {
                        val task = tasks[0]
                        if (event.isShiftPressed)
                            launchApp(
                                "tiled-right",
                                task.packageName,
                                newInstance = true,
                                rememberMode = false
                            )
                        else
                            AppUtils.resizeTask(
                                context, "tiled-right", task.id, dockHeight
                            )
                        return true
                    }
                } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (tasks.isNotEmpty()) {
                        val task = tasks[0]
                        if (event.isShiftPressed)
                            launchApp(
                                "standard",
                                task.packageName,
                                newInstance = true,
                                rememberMode = false
                            )
                        else
                            AppUtils.resizeTask(
                                context, "standard", task.id, dockHeight
                            )
                    }
                } else if (event.isShiftPressed) {
                    val index = when (event.keyCode) {
                        KeyEvent.KEYCODE_1 -> 0
                        KeyEvent.KEYCODE_2 -> 1
                        KeyEvent.KEYCODE_3 -> 2
                        KeyEvent.KEYCODE_4 -> 3
                        KeyEvent.KEYCODE_N -> 4
                        else -> -1
                    }
                    if (index == 4 && sharedPreferences.getBoolean("enable_new_instance", true)) {
                        if (tasks.isNotEmpty()) {
                            val task = tasks[0]
                            launchApp(null, task.packageName, newInstance = true)
                        }
                    } else if (index != -1 && sharedPreferences.getBoolean("enable_tiling", true)) {
                        val displays = DeviceUtils.getDisplays(this)
                        if (tasks.isNotEmpty() && displays.size > index) {
                            val task = tasks[0]
                            launchApp(null, task.packageName, displayId = displays[index].displayId)
                        }
                    }
                }
            } else {
                if (event.keyCode == KeyEvent.KEYCODE_CTRL_RIGHT && sharedPreferences.getBoolean(
                        "enable_ctrl_back",
                        true
                    )
                ) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    return true
                } else if (event.keyCode == KeyEvent.KEYCODE_MENU && sharedPreferences.getBoolean(
                        "enable_menu_recents",
                        false
                    )
                ) {
                    performGlobalAction(GLOBAL_ACTION_RECENTS)
                    return true
                } else if (event.keyCode == KeyEvent.KEYCODE_F10 && sharedPreferences.getBoolean(
                        "enable_f10",
                        true
                    )
                ) {
                    performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
                    return true
                } else if ((event.keyCode == KeyEvent.KEYCODE_HOME || event.keyCode == KeyEvent.KEYCODE_META_LEFT) && sharedPreferences.getBoolean(
                        "enable_open_menu",
                        true
                    )
                ) {
                    toggleAppMenu()
                    return true
                }
            }
        }

        return super.onKeyEvent(event)
    }

    private fun toggleSoftKeyboard() {
        if (Build.VERSION.SDK_INT < 30) {
            val im = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            im.showInputMethodPicker()
        } else {
            //TODO
            val kc = softKeyboardController
            val mode = kc.showMode
            if (mode == SHOW_MODE_AUTO || mode == SHOW_MODE_HIDDEN) kc.setShowMode(
                SHOW_MODE_IGNORE_HARD_KEYBOARD
            ) else kc.setShowMode(SHOW_MODE_HIDDEN)
        }
    }

    private fun togglePin() {
        if (isPinned) unpinDock() else pinDock()
    }

    private fun showDock() {
        dock.visibility = View.VISIBLE
        dockHandle.visibility = View.GONE

        if (dockLayoutParams.height != dockHeight) {
            dockLayoutParams.height = dockHeight
            windowManager.updateViewLayout(dock, dockLayoutParams)
        }

        dockHandler.removeCallbacksAndMessages(null)
        updateRunningTasks()
        dockLayout.visibility = View.VISIBLE
        dockLayout.animate().cancel()
        dockLayout.scaleX = 0.88f
        dockLayout.scaleY = 0.88f
        dockLayout.alpha = 0f
        dockLayout.translationY = com.youki.dex.utils.Utils.dpToPx(context, 12).toFloat()
        dockLayout.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        // POLISH: Material Design spring curve — snappy entry matches Samsung DeX feel
        dockLayout.animate()
            .scaleX(1f).scaleY(1f).alpha(1f).translationY(0f)
            .setDuration(200)
            .setInterpolator(interpSpringIn)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    dockLayout.setLayerType(View.LAYER_TYPE_NONE, null)
                }
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    dockLayout.scaleX = 1f; dockLayout.scaleY = 1f
                    dockLayout.alpha = 1f; dockLayout.translationY = 0f
                    dockLayout.setLayerType(View.LAYER_TYPE_NONE, null)
                }
            }).start()
    }

    fun pinDock() {
        isPinned = true
        pinBtn.setImageResource(R.drawable.ic_pin)
        if (dockLayout.isGone)
            showDock()
        // Android 16+: hide status bar via Shizuku since FLAG_FULLSCREEN is blocked
        if (Build.VERSION.SDK_INT >= 35) {
            val shizuku = com.youki.dex.utils.ShizukoManager.getInstance(context)
            if (shizuku.hasPermission) {
                shizuku.runShell("service call StatusBar 1") {}
            }
        }
    }

    private fun unpinDock() {
        pinBtn.setImageResource(R.drawable.ic_unpin)
        isPinned = false
        if (dockLayout.isVisible)
            hideDock(500)
        // Android 16+: restore status bar
        if (Build.VERSION.SDK_INT >= 35) {
            val shizuku = com.youki.dex.utils.ShizukoManager.getInstance(context)
            if (shizuku.hasPermission) {
                shizuku.runShell("service call StatusBar 2") {}
            }
        }
    }

    private fun hideDock(delay: Int) {
        dockHandler.removeCallbacksAndMessages(null)
        dockHandler.postDelayed({
            if (!isPinned) {
                dockLayout.animate().cancel()
                dockLayout.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                // POLISH: fast exit — scale + slide down + fade
                dockLayout.animate()
                    .scaleX(0.90f).scaleY(0.90f).alpha(0f)
                    .translationY(com.youki.dex.utils.Utils.dpToPx(context, 8).toFloat())
                    .setDuration(180)
                    .setInterpolator(android.view.animation.AccelerateInterpolator(2f))
                    .withEndAction {
                        dockLayout.visibility = View.GONE
                        dockLayout.scaleX = 1f; dockLayout.scaleY = 1f; dockLayout.alpha = 1f; dockLayout.translationY = 0f
                        dockLayout.setLayerType(View.LAYER_TYPE_NONE, null)
                        if (sharedPreferences.getString("activation_method", "swipe") == "swipe") {
                            val height = sharedPreferences.getString("dock_activation_area", "10")
                                ?.toDoubleOrNull()?.toInt() ?: 10
                            dockLayoutParams.height = Utils.dpToPx(context, height)
                            windowManager.updateViewLayout(dock, dockLayoutParams)
                        } else {
                            dock.visibility = View.GONE
                            dockHandle.visibility = View.VISIBLE
                        }
                    }.start()
            }
        }, delay.toLong())
    }

    private fun getDefaultLaunchMode(app: String?): String {
        if (app == null) return "standard"
        // If user remembered a specific mode for this app, respect it
        val remembered: String? = db.getLaunchMode(app)
        if (sharedPreferences.getBoolean("remember_launch_mode", true) && remembered != null)
            return remembered
        // Games: only go fullscreen if user explicitly enabled it (default OFF now)
        if (AppUtils.isGame(packageManager, app)
            && sharedPreferences.getBoolean("launch_games_fullscreen", false))
            return "fullscreen"
        // Default is always windowed — "standard" = freeform window
        return sharedPreferences.getString("launch_mode", "standard") ?: "standard"
    }

    private fun launchApp(
        mode: String?,
        packageName: String?,
        intent: Intent? = null,
        app: App? = null,
        displayId: Int = Display.DEFAULT_DISPLAY,
        newInstance: Boolean = false,
        rememberMode: Boolean = true
    ) {
        var launchMode = mode
        if (launchMode == null)
            launchMode = getDefaultLaunchMode(packageName)
        else
            if (rememberMode && sharedPreferences.getBoolean(
                    "remember_launch_mode",
                    true
                ) && packageName != null
            )
                db.saveLaunchMode(packageName, launchMode)

        val options = AppUtils.makeActivityOptions(context, launchMode, dockHeight, displayId)

        //Used only for work apps
        if (app != null && app.userHandle != Process.myUserHandle())
            launcherApps.startMainActivity(
                app.componentName,
                app.userHandle,
                null,
                options.toBundle()
            )
        else {
            val launchIntent: Intent? = if (intent == null && packageName != null)
                packageManager.getLaunchIntentForPackage(packageName)
            else
                intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            if (launchIntent == null)
                return

            if (newInstance)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)

            context.startActivity(launchIntent, options.toBundle())
        }

        if (appMenuVisible)
            hideAppMenu()

        // If lock_landscape is enabled, lock rotation at system level
        if (sharedPreferences.getBoolean("lock_landscape", true)) {
            DeviceUtils.freezeRotation(true)
        }

        if (launchMode == "fullscreen" && sharedPreferences.getBoolean("auto_unpin", true)) {
            if (isPinned)
                unpinDock()
        } else {
            if (!isPinned && sharedPreferences.getBoolean("auto_pin", true))
                pinDock()
        }
        //Hack to ensure the launched app is already on the top of the stack
        dockHandler.postDelayed({ updateRunningTasks() }, 1200)

        if (Utils.notificationPanelVisible)
            toggleNotificationPanel(false)
    }

    private fun setOrientation() {
        val lockLandscape = sharedPreferences.getBoolean("lock_landscape", true)
        DeviceUtils.freezeRotation(lockLandscape)
        dockLayoutParams.screenOrientation =
            if (lockLandscape)
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        windowManager.updateViewLayout(dock, dockLayoutParams)
    }

    private fun toggleAppMenu() {
        if (appMenuVisible)
            hideAppMenu()
        else
            showAppMenu()
    }

    fun showAppMenu() {
        val layoutParams: WindowManager.LayoutParams?
        val displayId =
            if (secondary) DeviceUtils.getSecondaryDisplay(context)?.displayId ?: Display.DEFAULT_DISPLAY else Display.DEFAULT_DISPLAY
        val deviceWidth = DeviceUtils.getDisplayMetrics(context, displayId).widthPixels
        val deviceHeight = DeviceUtils.getDisplayMetrics(context, displayId).heightPixels
        val margins = Utils.dpToPx(context, 2)
        val navHeight = DeviceUtils.getNavBarHeight(context)
        val diff = if (dockHeight - navHeight > 0) dockHeight - navHeight else 0
        val usableHeight =
            if (DeviceUtils.shouldApplyNavbarFix())
                deviceHeight - margins - diff - DeviceUtils.getStatusBarHeight(context)
            else
                deviceHeight - dockHeight - dockMargin - DeviceUtils.getStatusBarHeight(context) - margins
        if (sharedPreferences.getBoolean("app_menu_fullscreen", false)) {
            layoutParams = Utils.makeWindowParams(-1, usableHeight + margins, context, secondary)
            layoutParams.y = dockHeight + dockMargin
            if (sharedPreferences.getInt("dock_layout", -1) != 0) {
                val padding = Utils.dpToPx(context, 24)
                appMenu.setPadding(padding, padding, padding, padding)
                searchEntry.gravity = Gravity.CENTER
                searchLayout.gravity = Gravity.CENTER
                appsGv.layoutManager = GridLayoutManager(context, 10)
                favoritesGv.layoutManager = GridLayoutManager(context, 10)
            } else {
                appsGv.layoutManager = GridLayoutManager(context, 5)
                favoritesGv.layoutManager = GridLayoutManager(context, 5)
            }
            appMenu.setBackgroundResource(R.drawable.rect)
        } else {
            val width = Utils.dpToPx(
                context,
                sharedPreferences.getString("app_menu_width", "700")?.toIntOrNull() ?: 700
            )
            val height = Utils.dpToPx(
                context,
                sharedPreferences.getString("app_menu_height", "580")?.toIntOrNull() ?: 580
            )
            layoutParams = Utils.makeWindowParams(
                width.coerceAtMost(deviceWidth - margins * 2), height.coerceAtMost(usableHeight),
                context, secondary
            )
            layoutParams.x = margins
            layoutParams.y = dockMargin + dockHeight + margins
            appsGv.layoutManager = GridLayoutManager(
                context,
                sharedPreferences.getString("num_columns", "5")?.toIntOrNull() ?: 5
            )
            favoritesGv.layoutManager = GridLayoutManager(
                context,
                sharedPreferences.getString("num_columns", "5")?.toIntOrNull() ?: 5
            )
            val padding = Utils.dpToPx(context, 10)
            appMenu.setPadding(padding, padding, padding, padding)
            searchEntry.gravity = Gravity.START
            searchLayout.gravity = Gravity.START
            appMenu.setBackgroundResource(R.drawable.round_rect)
        }
        layoutParams.flags = (WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        layoutParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        val halign = if (sharedPreferences.getBoolean(
                "center_app_menu",
                false
            )
        ) Gravity.CENTER_HORIZONTAL else Gravity.START
        layoutParams.gravity = Gravity.BOTTOM or halign
        ColorUtils.applyMainColor(context, sharedPreferences, appMenu)
        ColorUtils.applyColor(appsSeparator, ColorUtils.getMainColors(sharedPreferences, this)[4])
        windowManager.addView(appMenu, layoutParams)

        //Load apps
        updateAppMenu()
        loadFavoriteApps()

        //Load user info
        val avatarIv = appMenu.findViewById<ImageView>(R.id.avatar_iv)
        val userNameTv = appMenu.findViewById<TextView>(R.id.user_name_tv)
        avatarIv.setOnClickListener {
            if (AppUtils.isSystemApp(context, packageName))
                launchApp(null, null, Intent("android.settings.USER_SETTINGS"))
            else
                launchApp("standard", null, Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                        or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                        or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT))
        }
        if (AppUtils.isSystemApp(context, packageName)) {
            val name = DeviceUtils.getUserName(context)
            if (name != null) userNameTv.text = name
            val icon = DeviceUtils.getUserIcon(context)
            if (icon != null) avatarIv.setImageBitmap(icon)
        } else {
            val name = sharedPreferences.getString("user_name", "")
            if (!name.isNullOrEmpty()) userNameTv.text = name
            val iconUri = sharedPreferences.getString("user_icon_uri", "default")
            if (iconUri != "default") {
                val bitmap = Utils.getBitmapFromUri(context, Uri.parse(iconUri))
                val icon = Utils.getCircularBitmap(bitmap)
                if (icon != null)
                    avatarIv.setImageBitmap(icon)
            } else avatarIv.setImageResource(R.drawable.ic_user)
        }
        appMenu.animate().cancel()
        appMenu.scaleX = 0.92f
        appMenu.scaleY = 0.92f
        appMenu.alpha = 0f
        appMenu.translationY = com.youki.dex.utils.Utils.dpToPx(context, 16).toFloat()
        appMenu.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        // PERF: 350→220ms — budget CPUs can't sustain 60fps for 350ms compositing.
        // Uses cached interpEmphasized — no new PathInterpolator per open.
        appMenu.animate()
            .scaleX(1f).scaleY(1f).alpha(1f).translationY(0f)
            .setDuration(220)
            .setInterpolator(interpEmphasized)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    appMenu.setLayerType(View.LAYER_TYPE_NONE, null)
                }
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    appMenu.scaleX = 1f; appMenu.scaleY = 1f
                    appMenu.alpha = 1f; appMenu.translationY = 0f
                    appMenu.setLayerType(View.LAYER_TYPE_NONE, null)
                }
            }).start()

        //Work around android showing the ime system ui bar
        val softwareKeyboard =
            context.resources.configuration.keyboard == Configuration.KEYBOARD_NOKEYS
        val tabletMode = sharedPreferences.getInt("dock_layout", -1) == 1

        searchEt.showSoftInputOnFocus = softwareKeyboard || tabletMode
        searchEt.requestFocus()

        appMenuVisible = true
    }

    fun hideAppMenu() {
        searchEt.setText("")
        val adapter = appsGv.adapter
        if (adapter is AppAdapter) {
            adapter.filter("")
        }
        appMenu.animate().cancel()
        // PERF: use cached exit interpolator — no new PathInterpolator per close
        appMenu.animate()
            .scaleX(0.92f).scaleY(0.92f).alpha(0f)
            .translationY(com.youki.dex.utils.Utils.dpToPx(context, 12).toFloat())
            .setDuration(150)
            .setInterpolator(interpExit)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    try { windowManager.removeView(appMenu) } catch (_: Exception) {}
                    appMenu.scaleX = 1f; appMenu.scaleY = 1f
                    appMenu.alpha = 1f; appMenu.translationY = 0f
                    appMenuVisible = false
                }
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    try { windowManager.removeView(appMenu) } catch (_: Exception) {}
                    appMenu.scaleX = 1f; appMenu.scaleY = 1f
                    appMenu.alpha = 1f; appMenu.translationY = 0f
                    appMenuVisible = false
                }
            }).start()
    }

    private suspend fun fetchInstalledApps(): ArrayList<App> = withContext(Dispatchers.Default) {
        return@withContext AppUtils.getInstalledApps(context)
    }

    private fun updateAppMenu(recreateAdapter: Boolean = false) {
        serviceScope.launch(Dispatchers.Default) {
            val hiddenApps = sharedPreferences.getStringSet(
                "hidden_apps_grid",
                setOf()
            ).orEmpty()
            val apps = fetchInstalledApps().filterNot { hiddenApps.contains(it.packageName) }

            withContext(Dispatchers.Main) {
                val menuFullscreen = sharedPreferences.getBoolean("app_menu_fullscreen", false)
                val phoneLayout = sharedPreferences.getInt("dock_layout", -1) == 0
                //TODO: Implement efficient adapter
                val existingAdapter = appsGv.adapter
                if (existingAdapter is AppAdapter && !recreateAdapter) {
                    existingAdapter.updateApps(apps)
                } else {
                    appsGv.adapter = AppAdapter(
                        context, apps, this@DockService,
                        menuFullscreen && !phoneLayout, iconPackUtils
                    )
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showAppContextMenu(app: App, anchor: View) {
        val view = LayoutInflater.from(context).inflate(R.layout.task_list, null)
        val layoutParams = Utils.makeWindowParams(-2, -2, context, secondary)
        ColorUtils.applyMainColor(context, sharedPreferences, view)
        layoutParams.gravity = Gravity.START or Gravity.TOP
        layoutParams.flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        layoutParams.x = location[0]
        layoutParams.y = location[1] + Utils.dpToPx(context, anchor.measuredHeight / 2)
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE)
                windowManager.removeView(view)

            false
        }
        val actionsLv = view.findViewById<ListView>(R.id.tasks_lv)
        actionsLv.adapter = AppActionsAdapter(context, getAppActions(app))
        actionsLv.setOnItemClickListener { adapterView, _, position, _ ->
            if (adapterView.getItemAtPosition(position) is Action) {
                val action = adapterView.getItemAtPosition(position) as Action
                if (action.text == getString(R.string.manage)) {
                    val actions = ArrayList<Action>()
                    actions.add(Action(R.drawable.ic_arrow_back, ""))
                    actions.add(Action(R.drawable.ic_info, getString(R.string.app_info)))
                    if (sharedPreferences.getBoolean("enable_app_hiding_grid", false))
                        actions.add(
                            Action(
                                R.drawable.ic_hide,
                                getString(R.string.hide)
                            )
                        )
                    if (!AppUtils.isSystemApp(
                            context,
                            app.packageName
                        ) || sharedPreferences.getBoolean("allow_sysapp_uninstall", false)
                    ) actions.add(Action(R.drawable.ic_uninstall, getString(R.string.uninstall)))
                    if (sharedPreferences.getBoolean("allow_app_freeze", false))
                        actions.add(
                            Action(
                                R.drawable.ic_freeze,
                                getString(R.string.freeze)
                            )
                        )
                    actionsLv.adapter = AppActionsAdapter(context, actions)
                } else if (action.text == getString(R.string.shortcuts)) {
                    actionsLv.adapter = AppShortcutAdapter(
                        context,
                        DeepShortcutManager.getShortcuts(app.packageName, context) ?: emptyList()
                    )
                } else if (action.text == "") {
                    actionsLv.adapter = AppActionsAdapter(context, getAppActions(app))
                } else if (action.text == getString(R.string.open_as)) {
                    val actions = ArrayList<Action>()
                    actions.add(Action(R.drawable.ic_arrow_back, ""))
                    actions.add(Action(R.drawable.ic_standard, getString(R.string.standard)))
                    actions.add(Action(R.drawable.ic_maximized, getString(R.string.maximized)))
                    actions.add(Action(R.drawable.ic_portrait, getString(R.string.portrait)))
                    actions.add(Action(R.drawable.ic_fullscreen, getString(R.string.fullscreen)))
                    actionsLv.adapter = AppActionsAdapter(context, actions)
                } else if (action.text == getString(R.string.add_to)) {
                    val actions = ArrayList<Action>()
                    actions.add(Action(R.drawable.ic_arrow_back, ""))
                    actions.addAll(getPinActions(app))
                    actionsLv.adapter = AppActionsAdapter(context, actions)
                } else if (action.text == getString(R.string.launch_in)) {
                    actionsLv.adapter = DisplaysAdapter(context, DeviceUtils.getDisplays(this))
                } else if (action.text == getString(R.string.app_info)) {
                    launchApp(
                        null, null, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData("package:${app.packageName}".toUri())
                    )
                    windowManager.removeView(view)
                } else if (action.text == getString(R.string.hide)) {
                    val savedApps = sharedPreferences.getStringSet(
                        "hidden_apps_grid",
                        setOf()
                    ).orEmpty()
                    val hiddenApps = mutableSetOf<String>()
                    hiddenApps.addAll(savedApps)
                    hiddenApps.add(app.packageName)

                    sharedPreferences.edit {
                        putStringSet("hidden_apps_grid", hiddenApps)
                    }

                    if (AppUtils.isPinned(this, app, AppUtils.PINNED_LIST))
                        AppUtils.unpinApp(this, app.packageName, AppUtils.PINNED_LIST)
                    if (AppUtils.isPinned(this, app, AppUtils.DOCK_PINNED_LIST))
                        AppUtils.unpinApp(this, app.packageName, AppUtils.DOCK_PINNED_LIST)
                    if (AppUtils.isPinned(this, app, AppUtils.DESKTOP_LIST))
                        AppUtils.unpinApp(this, app.packageName, AppUtils.DESKTOP_LIST)
                    updateAppMenu()
                    loadFavoriteApps()
                    windowManager.removeView(view)
                } else if (action.text == getString(R.string.uninstall)) {
                    AppUtils.uninstallApp(this, app.packageName)
                    if (appMenuVisible)
                        hideAppMenu()
                    windowManager.removeView(view)
                } else if (action.text == getString(R.string.freeze)) {
                    val status = DeviceUtils.runAsRoot("pm disable ${app.packageName}")
                    if (status != "error") Toast.makeText(
                        context,
                        R.string.app_frozen,
                        Toast.LENGTH_SHORT
                    ).show() else Toast.makeText(
                        context,
                        R.string.something_wrong,
                        Toast.LENGTH_SHORT
                    ).show()
                    windowManager.removeView(view)
                    if (appMenuVisible) hideAppMenu()
                } else if (action.text == getString(R.string.favorites)) {
                    AppUtils.pinApp(context, app, AppUtils.PINNED_LIST)
                    windowManager.removeView(view)
                    loadFavoriteApps()
                } else if (action.text == getString(R.string.remove)) {
                    AppUtils.unpinApp(context, app.packageName, AppUtils.PINNED_LIST)
                    windowManager.removeView(view)
                    loadFavoriteApps()
                } else if (action.text == getString(R.string.desktop)) {
                    AppUtils.pinApp(context, app, AppUtils.DESKTOP_LIST)
                    sendBroadcast(
                        Intent(DOCK_SERVICE_ACTION)
                            .setPackage(packageName)
                            .putExtra("action", DESKTOP_APP_PINNED)
                    )
                    windowManager.removeView(view)
                } else if (action.text == getString(R.string.dock)) {
                    AppUtils.pinApp(context, app, AppUtils.DOCK_PINNED_LIST)
                    loadPinnedApps()
                    updateRunningTasks()
                    windowManager.removeView(view)
                } else if (action.text == getString(R.string.standard)) {
                    windowManager.removeView(view)
                    launchApp("standard", app.packageName, null, app, newInstance = true)
                } else if (action.text == getString(R.string.maximized)) {
                    windowManager.removeView(view)
                    launchApp("maximized", app.packageName, null, app, newInstance = true)
                } else if (action.text == getString(R.string.portrait)) {
                    windowManager.removeView(view)
                    launchApp("portrait", app.packageName, null, app, newInstance = true)
                } else if (action.text == getString(R.string.fullscreen)) {
                    windowManager.removeView(view)
                    launchApp("fullscreen", app.packageName, null, app, newInstance = true)
                }
            } else if (Build.VERSION.SDK_INT > 24 && adapterView.getItemAtPosition(position) is ShortcutInfo) {
                val shortcut = adapterView.getItemAtPosition(position) as ShortcutInfo
                windowManager.removeView(view)
                DeepShortcutManager.startShortcut(shortcut, context)
            } else if (Build.VERSION.SDK_INT > 28 && adapterView.getItemAtPosition(position) is Display) {
                val display = adapterView.getItemAtPosition(position) as Display
                windowManager.removeView(view)
                launchApp(
                    null,
                    app.packageName,
                    null,
                    app,
                    display.displayId,
                    sharedPreferences.getBoolean("launch_new_instance_secondary", true)
                )
            }
        }
        windowManager.addView(view, layoutParams)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showDockAppContextMenu(app: App, anchor: View) {
        val view = LayoutInflater.from(context).inflate(R.layout.pin_entry, null)
        val pinLayout = view.findViewById<LinearLayout>(R.id.pin_entry_pin)
        val layoutParams = Utils.makeWindowParams(-2, -2, context, secondary)
        view.setBackgroundResource(R.drawable.round_rect)
        ColorUtils.applyMainColor(context, sharedPreferences, view)
        layoutParams.gravity = Gravity.BOTTOM or Gravity.START
        layoutParams.flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        layoutParams.y = Utils.dpToPx(context, 2) + dockHeight
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        layoutParams.x = location[0]
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE)
                windowManager.removeView(view)

            false
        }
        val icon = view.findViewById<ImageView>(R.id.pin_entry_iv)
        ColorUtils.applySecondaryColor(context, sharedPreferences, icon)
        val text = view.findViewById<TextView>(R.id.pin_entry_tv)
        if (AppUtils.isPinned(context, app, AppUtils.DOCK_PINNED_LIST)) {
            icon.setImageResource(R.drawable.ic_unpin)
            text.setText(R.string.unpin)
            val moveLayout = view.findViewById<LinearLayout>(R.id.pin_entry_move)
            moveLayout.visibility = View.VISIBLE
            val moveLeft = view.findViewById<ImageView>(R.id.pin_entry_left)
            val moveRight = view.findViewById<ImageView>(R.id.pin_entry_right)
            ColorUtils.applySecondaryColor(context, sharedPreferences, moveLeft)
            ColorUtils.applySecondaryColor(context, sharedPreferences, moveRight)
            moveLeft.setOnClickListener {
                AppUtils.moveApp(this, app, AppUtils.DOCK_PINNED_LIST, 0)
                loadPinnedApps()
                updateRunningTasks()
            }
            moveRight.setOnClickListener {
                AppUtils.moveApp(this, app, AppUtils.DOCK_PINNED_LIST, 1)
                loadPinnedApps()
                updateRunningTasks()
            }
        }
        pinLayout.setOnClickListener {
            if (AppUtils.isPinned(context, app, AppUtils.DOCK_PINNED_LIST))
                AppUtils.unpinApp(
                    context,
                    app.packageName,
                    AppUtils.DOCK_PINNED_LIST
                ) else
                AppUtils.pinApp(context, app, AppUtils.DOCK_PINNED_LIST)
            loadPinnedApps()
            if (isPinned)
                updateRunningTasks()
            windowManager.removeView(view)
        }
        windowManager.addView(view, layoutParams)
    }

    // FIX (Lifecycle): Track all floating popup windows so we can clean them up
    // properly in onDestroy(). Without this, popups can outlive their creator and
    // cause "View not attached to window manager" crashes or ghost windows.
    private val activePopups = mutableListOf<View>()

    /** Add a popup to WindowManager and track it for proper lifecycle cleanup */
    private fun addPopup(view: View, params: WindowManager.LayoutParams) {
        windowManager.addView(view, params)
        activePopups.add(view)
    }

    /** Remove a popup from WindowManager and stop tracking it */
    private fun removePopup(view: View) {
        activePopups.remove(view)
        try { windowManager.removeView(view) } catch (_: Exception) {}
    }

    private fun cleanupAllPopups() {
        activePopups.toList().forEach { v ->
            try { windowManager.removeView(v) } catch (_: Exception) {}
        }
        activePopups.clear()
    }

    /**
     * FIX (Flags): Context menus are overlays that detect outside touches to
     * dismiss themselves but DON'T need to steal focus from whatever app is
     * running. FLAG_NOT_FOCUSABLE is correct here.
     *
     * However, FLAG_WATCH_OUTSIDE_TOUCH ONLY works when paired with
     * FLAG_NOT_TOUCH_MODAL on API 26+. Without FLAG_NOT_TOUCH_MODAL, outside
     * touches are consumed by the window and apps below never receive them.
     *
     * This helper builds consistent params for all dismissible context menus.
     */
    private fun makeContextMenuParams(): WindowManager.LayoutParams {
        val p = Utils.makeWindowParams(-2, -2, context, secondary)
        p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or   // ← was missing
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        return p
    }

    override fun onSharedPreferenceChanged(p1: SharedPreferences, preference: String?) {
        if (preference == null)
            return
        if (preference.startsWith("theme") || preference == "bubble_color"
            || preference == "bubble_alpha_pct" || preference == "bubble_mode"
            || preference == "round_dock" || preference == "dock_height"
)
            applyTheme()
        else if (preference == "menu_icon_uri")
            updateMenuIcon()
        else if (preference.startsWith("icon_")) {
            val iconPack = sharedPreferences.getString("icon_pack", "").orEmpty()
            iconPackUtils = if (iconPack.isNotEmpty()) {
                IconPackUtils(this)
            } else
                null

            updateRunningTasks(true)
            updateAppMenu(true)
            loadFavoriteApps()
        } else if (preference == "tint_indicators") {
            updateRunningTasks(true)
        } else if (preference == "lock_landscape")
            setOrientation()
        else if (preference == "hide_status_bar")
            DeviceUtils.hideStatusBar(context, sharedPreferences.getBoolean("hide_status_bar", false))
        else if (preference == "center_running_apps") {
            placeRunningApps()
            updateRunningTasks()
        } else if (preference == "center_apps_btn") {
            updateCenterAppsBtn()
        } else if (preference == "dock_activation_area")
            updateDockTrigger()
        else if (preference.startsWith("enable_corner_"))
            updateCorners()
        else if (preference.startsWith("enable_nav_")) {
            updateNavigationBar()
        } else if (preference.startsWith("enable_qs_")) {
            updateQuickSettings()
        } else if (preference == "round_dock")
            updateDockShape()
        else if (preference.startsWith("max_running_apps")) {
            maxApps = sharedPreferences.getString("max_running_apps", "10")?.toDoubleOrNull()?.toInt() ?: 10
            maxAppsLandscape =
                sharedPreferences.getString("max_running_apps_landscape", "10")?.toDoubleOrNull()?.toInt() ?: 10
            updateRunningTasks()
        } else if (preference == "activation_method") {
            updateActivationMethod()
        } else if (preference == "handle_opacity")
            dockHandle.alpha = sharedPreferences.getString("handle_opacity", "0.5")?.toFloatOrNull() ?: 0.5f
        else if (preference == "dock_height")
            updateDockHeight()
        else if (preference == "handle_position")
            updateHandlePosition()
        else if (preference == "show_battery_level")
            updateBatteryBtn()
        else if (preference == "show_resource_monitor") {
            if (sharedPreferences.getBoolean("show_resource_monitor", false)) {
                resourceMonitorTv?.visibility = View.VISIBLE
                startResourceMonitor()
            } else {
                resourceMonitorTv?.visibility = View.GONE
                stopResourceMonitor()
            }
        }
    }

    private fun updateDockTrigger() {
        if (!isPinned) {
            val height = sharedPreferences.getString("dock_activation_area", "10")?.toIntOrNull() ?: 10
            dockLayoutParams.height = Utils.dpToPx(context, height)
            windowManager.updateViewLayout(dock, dockLayoutParams)
        }
    }

    private fun updateActivationMethod() {
        if (!isPinned) {
            val method = sharedPreferences.getString("activation_method", "swipe")
            if (method == "swipe") {
                dockHandle.visibility = View.GONE
                updateDockTrigger()
                dock.visibility = View.VISIBLE
            } else {
                dock.visibility = View.GONE
                dockHandle.visibility = View.VISIBLE
            }
        }
    }

    private fun updateDockHeight() {
        dockHeight = Utils.dpToPx(context, sharedPreferences.getString("dock_height", "56")?.toIntOrNull() ?: 56)
        if (isPinned) {
            dockLayoutParams.height = dockHeight
            windowManager.updateViewLayout(dock, dockLayoutParams)
        }
    }

    private fun placeRunningApps() {
        // apps_lv is now inside LinearLayout (center_group), not directly in RelativeLayout
        val layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        tasksGv.layoutParams = layoutParams

        // Update center_group position inside RelativeLayout
        val groupParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        )
        if (sharedPreferences.getBoolean("center_running_apps", true)) {
            groupParams.addRule(RelativeLayout.CENTER_IN_PARENT)
        } else {
            groupParams.addRule(RelativeLayout.END_OF, R.id.nav_panel)
            groupParams.addRule(RelativeLayout.START_OF, R.id.system_tray)
            groupParams.addRule(RelativeLayout.CENTER_VERTICAL)
        }
        val centerGroup = dock.findViewById<LinearLayout>(R.id.center_group)
        centerGroup.layoutParams = groupParams
        centerGroup.requestLayout()
        dockLayout.requestLayout()
        updateCenterAppsBtn()
    }

    private fun updateCenterAppsBtn() {
        val centerMode = sharedPreferences.getBoolean("center_apps_btn", false)
        if (centerMode) {
            // Hide button from nav_panel and show it in center
            appsBtn.visibility = View.GONE
            appsBtnCenter.visibility = if (
                sharedPreferences.getBoolean("enable_nav_apps", true)
            ) View.VISIBLE else View.GONE
            // Maximum 5 apps in center mode
            maxApps = minOf(
                (sharedPreferences.getString("max_running_apps", "10") ?: "10")
                    .toDoubleOrNull()?.toInt() ?: 10,
                5
            )
            maxAppsLandscape = minOf(
                (sharedPreferences.getString("max_running_apps_landscape", "10") ?: "10")
                    .toDoubleOrNull()?.toInt() ?: 10,
                5
            )
        } else {
            // Return button to its original position
            appsBtnCenter.visibility = View.GONE
            appsBtn.visibility = if (
                sharedPreferences.getBoolean("enable_nav_apps", true)
            ) View.VISIBLE else View.GONE
            maxApps = (sharedPreferences.getString("max_running_apps", "10") ?: "10")
                .toDoubleOrNull()?.toInt() ?: 10
            maxAppsLandscape = (sharedPreferences.getString("max_running_apps_landscape", "10") ?: "10")
                .toDoubleOrNull()?.toInt() ?: 10
        }
        updateRunningTasks(true)
    }

    private fun loadPinnedApps() {
        pinnedApps = AppUtils.getPinnedApps(context, AppUtils.DOCK_PINNED_LIST)
    }

    private fun updateRunningTasks(recreateAdapter: Boolean = false) {
        val now = System.currentTimeMillis()
        if (now - lastUpdate < 500 && !recreateAdapter)
            return
        lastUpdate = now

        // PERF FIX: getRunningTasks() / getRecentTasks() are blocking IPC calls.
        // Moving them to Dispatchers.IO eliminates main-thread stalls (the 300-500ms
        // jank visible when the dock first appears or apps are switched).
        // Only the RecyclerView update is posted back to Main.
        serviceScope.launch {
            val pinnedSnapshot = if (::pinnedApps.isInitialized) ArrayList(pinnedApps) else ArrayList()
            val apps = ArrayList<DockApp>()
            pinnedSnapshot.forEach { pinnedApp ->
                apps.add(DockApp(pinnedApp.name, pinnedApp.packageName, pinnedApp.icon))
            }

            val nApps =
                if (orientation == Configuration.ORIENTATION_PORTRAIT) maxApps else maxAppsLandscape

            // heavy IPC happens here, off the main thread
            val fetchedTasks: ArrayList<AppTask> = withContext(Dispatchers.IO) {
                if (systemApp)
                    AppUtils.getRunningTasks(activityManager, packageManager, nApps)
                else
                    AppUtils.getRecentTasks(context, nApps)
            }

            // Build the dock app list (pure in-memory, fast)
            if (systemApp) {
                for (j in 1..fetchedTasks.size) {
                    val task = fetchedTasks[fetchedTasks.size - j]
                    val index = AppUtils.containsTask(apps, task)
                    if (index != -1) apps[index].addTask(task)
                    else apps.add(DockApp(task))
                }
            } else {
                fetchedTasks.reversed().forEach { task ->
                    if (AppUtils.containsTask(apps, task) == -1)
                        apps.add(DockApp(task))
                }
            }

            // Back to Main for all UI updates
            withContext(Dispatchers.Main) {
                tasks = fetchedTasks
                // Let RecyclerView size itself naturally
                tasksGv.layoutParams?.width = ViewGroup.LayoutParams.WRAP_CONTENT
                val adapter = tasksGv.adapter
                if (adapter is DockAppAdapter && !recreateAdapter)
                    adapter.updateApps(apps)
                else
                    tasksGv.adapter = DockAppAdapter(context, apps, this@DockService, iconPackUtils, dockHeight)

                // TODO: wifiManager.isWifiEnabled is deprecated on API 29+ — use WifiManager.isWifiEnabled
                // via ConnectivityManager on future refactor. Suppressed for now: no safe alternative
                // that works on all API levels without requesting location permission.
                @Suppress("DEPRECATION")
                wifiBtn.setImageResource(
                    if (wifiManager.isWifiEnabled) R.drawable.ic_wifi_on else R.drawable.ic_wifi_off)
                val bluetoothAdapter = bluetoothManager.adapter
                if (bluetoothAdapter != null)
                    bluetoothBtn.setImageResource(
                        if (bluetoothAdapter.isEnabled) R.drawable.ic_bluetooth else R.drawable.ic_bluetooth_off)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        orientation = newConfig.orientation
        // Display or DPI changed — recalculate sizes
        updateDockHeight()
        updateDockShape()
        // FIX: Re-apply theme on ANY config change (orientation, uiMode dark/light,
        // density). Without this, colors stay stale when night mode or display
        // settings change at runtime. applyTheme() uses 'this' (live service context)
        // so DynamicColors always picks up the freshest Material You palette.
        applyTheme()
        if (::tasksGv.isInitialized) updateRunningTasks(true)
    }

    private fun updateDockShape() {
        val isRound = sharedPreferences.getBoolean("round_dock", false)
        dockLayout.setBackgroundResource(if (isRound) R.drawable.round_rect else R.drawable.rect)
        ColorUtils.applyMainColor(context, sharedPreferences, dockLayout)

        // KDE-style floating dock when round_dock is ON:
        // shrink window width + add bottom margin so rounded corners are fully visible
        val margin = Utils.dpToPx(context, 8)
        val displayId = if (secondary)
            DeviceUtils.getSecondaryDisplay(context)?.displayId ?: Display.DEFAULT_DISPLAY
        else
            Display.DEFAULT_DISPLAY
        val displayWidth = DeviceUtils.getDisplayMetrics(context, displayId).widthPixels

        if (isRound) {
            dockLayoutParams.width  = displayWidth - 2 * margin
            dockLayoutParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            dockLayoutParams.y      = margin
        } else {
            dockLayoutParams.width  = WindowManager.LayoutParams.MATCH_PARENT
            dockLayoutParams.gravity = Gravity.BOTTOM or Gravity.START
            dockLayoutParams.y      = 0
        }
        windowManager.updateViewLayout(dock, dockLayoutParams)
    }

    private fun updateNavigationBar() {
        val centerMode = sharedPreferences.getBoolean("center_apps_btn", false)
        val appsEnabled = sharedPreferences.getBoolean("enable_nav_apps", true)
        // Prevent two buttons showing at once — only one visible based on state
        if (centerMode) {
            appsBtn.visibility = View.GONE
            appsBtnCenter.visibility = if (appsEnabled) View.VISIBLE else View.GONE
        } else {
            appsBtnCenter.visibility = View.GONE
            appsBtn.visibility = if (appsEnabled) View.VISIBLE else View.GONE
        }
        backBtn.visibility =
            if (sharedPreferences.getBoolean("enable_nav_back", true)) View.VISIBLE else View.GONE
        homeBtn.visibility =
            if (sharedPreferences.getBoolean("enable_nav_home", true)) View.VISIBLE else View.GONE
        recentBtn.visibility =
            if (sharedPreferences.getBoolean("enable_nav_recents", true)) View.VISIBLE else View.GONE
        assistBtn.visibility =
            if (sharedPreferences.getBoolean("enable_nav_assist", true)) View.VISIBLE else View.GONE
    }

    private fun updateQuickSettings() {
        notificationBtn.visibility =
            if (sharedPreferences.getBoolean("enable_qs_notif", true)) View.VISIBLE else View.GONE
        bluetoothBtn.visibility = if (sharedPreferences.getBoolean(
                "enable_qs_bluetooth",
                false
            )
        ) View.VISIBLE else View.GONE
        batteryBtn.visibility = if (sharedPreferences.getBoolean(
                "enable_qs_battery",
                false
            )
        ) View.VISIBLE else View.GONE
        wifiBtn.visibility =
            if (sharedPreferences.getBoolean("enable_qs_wifi", true)) View.VISIBLE else View.GONE
        pinBtn.visibility =
            if (sharedPreferences.getBoolean("enable_qs_pin", true)) View.VISIBLE else View.GONE
        volumeBtn.visibility =
            if (sharedPreferences.getBoolean("enable_qs_vol", true)) View.VISIBLE else View.GONE
        wallpaperBtn.visibility =
            if (sharedPreferences.getBoolean("enable_qs_wallpaper", false)) View.VISIBLE else View.GONE
        dateTv.visibility =
            if (sharedPreferences.getBoolean("enable_qs_date", true)) View.VISIBLE else View.GONE
    }

    private fun launchAssistant() {
        // Google Assistant directly — no customization
        val assistIntent = packageManager.getLaunchIntentForPackage("com.google.android.googlequicksearchbox")
        if (assistIntent != null) {
            assistIntent.action = Intent.ACTION_ASSIST
            assistIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { context.startActivity(assistIntent) } catch (_: Exception) {}
        } else {
            try {
                startActivity(Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {}
        }
    }

    private fun openBluetoothSettings() {
        launchApp(null, null, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }


    @Suppress("DEPRECATION")
    private fun toggleWifi() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val enabled = wifiManager.isWifiEnabled
            val icon = if (!enabled) R.drawable.ic_wifi_on else R.drawable.ic_wifi_off
            wifiBtn.setImageResource(icon)
            wifiManager.isWifiEnabled = !enabled
        } else
            startActivity(Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun toggleVolume() {
        if (!audioPanelVisible) showAudioPanel() else hideAudioPanel()
    }

    private fun hideAudioPanel() {
        if (!audioPanelVisible || audioPanel == null) return
        audioPanelVisible = false
        val panelToRemove = audioPanel
        audioPanel = null
        try { windowManager.removeView(panelToRemove) } catch (_: Exception) {}
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showAudioPanel() {
        //  guard — prevents stacking multiple menus on rapid volume button press
        if (audioPanelVisible) return
        audioPanelVisible = true

        if (Utils.notificationPanelVisible)
            toggleNotificationPanel(false)

        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val layoutParams = Utils.makeWindowParams(
            Utils.dpToPx(context, 340), -2, context,
            secondary
        )
        layoutParams.flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or   // FIX: prevent audio panel from blocking taps outside its bounds
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        layoutParams.y = Utils.dpToPx(context, 2) + dockHeight
        layoutParams.x = Utils.dpToPx(context, 2)
        layoutParams.gravity = Gravity.BOTTOM or Gravity.END
        audioPanel = LayoutInflater.from(ContextThemeWrapper(context, R.style.AppTheme_Dock))
            .inflate(R.layout.audio_panel, null) as LinearLayout
        audioPanel?.setOnTouchListener(null)
        val musicIcon = audioPanel?.findViewById<ImageView>(R.id.ap_music_icon)
        val musicSb = audioPanel?.findViewById<SeekBar>(R.id.ap_music_sb)
        musicSb?.max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        musicSb?.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        musicSb?.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
            }
            override fun onStartTrackingTouch(p1: SeekBar) {}
            override fun onStopTrackingTouch(p1: SeekBar) {}
        })
        // Audio panel — same color as dock background (mainColor from user's theme)
        val dockColors = ColorUtils.getMainColors(sharedPreferences, context)
        audioPanel?.setBackgroundResource(R.drawable.btn_bubble_audio)
        audioPanel?.background?.setColorFilter(dockColors[0], android.graphics.PorterDuff.Mode.SRC_ATOP)
        audioPanel?.background?.alpha = dockColors[1]
        musicIcon?.clearColorFilter()
        // Slider color: Material You primary when material_u theme, white otherwise
        val isMatU = sharedPreferences.getString("theme", "material_u") == "material_u"
        val sliderCol = if (isMatU && com.google.android.material.color.DynamicColors.isDynamicColorAvailable()) {
            ColorUtils.getThemeColors(context, true)[0]
        } else {
            android.graphics.Color.WHITE
        }
        musicSb?.progressDrawable?.setColorFilter(sliderCol, android.graphics.PorterDuff.Mode.SRC_ATOP)
        musicSb?.thumb?.setColorFilter(sliderCol, android.graphics.PorterDuff.Mode.SRC_ATOP)
        windowManager.addView(audioPanel, layoutParams)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showPowerMenu() {
        //  guard — prevents two menus from appearing
        if (powerMenuVisible) return
        powerMenuVisible = true

        val layoutParams = Utils.makeWindowParams(
            Utils.dpToPx(context, 400),
            Utils.dpToPx(context, 120), context, secondary
        )
        layoutParams.gravity = Gravity.CENTER
        layoutParams.x = Utils.dpToPx(context, 10)
        layoutParams.flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL   // FIX: prevent power menu from blocking taps outside it
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        powerMenu = LayoutInflater.from(context).inflate(R.layout.power_menu, null) as LinearLayout
        powerMenu?.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE)
                hidePowerMenu()

            false
        }
        val powerOffBtn = powerMenu?.findViewById<ImageButton>(R.id.power_off_btn)
        val restartBtn = powerMenu?.findViewById<ImageButton>(R.id.restart_btn)
        val softRestartBtn = powerMenu?.findViewById<ImageButton>(R.id.soft_restart_btn)
        val lockBtn = powerMenu?.findViewById<ImageButton>(R.id.lock_btn)
        powerOffBtn?.let { ColorUtils.applySecondaryColor(context, sharedPreferences, it) }
        restartBtn?.let { ColorUtils.applySecondaryColor(context, sharedPreferences, it) }
        softRestartBtn?.let { ColorUtils.applySecondaryColor(context, sharedPreferences, it) }
        lockBtn?.let { ColorUtils.applySecondaryColor(context, sharedPreferences, it) }
        powerOffBtn?.setOnClickListener {
            hidePowerMenu()
            DeviceUtils.shutdown()
        }
        restartBtn?.setOnClickListener {
            hidePowerMenu()
            DeviceUtils.reboot()
        }
        softRestartBtn?.setOnClickListener {
            hidePowerMenu()
            DeviceUtils.softReboot()
        }
        lockBtn?.setOnClickListener {
            hidePowerMenu()
            lockScreen()
        }
        // Button to fully close YoukiDex
        val closeYoukiBtn = powerMenu?.findViewById<android.widget.ImageButton>(R.id.close_youki_btn)
        closeYoukiBtn?.let { ColorUtils.applySecondaryColor(context, sharedPreferences, it) }
        closeYoukiBtn?.setOnClickListener {
            hidePowerMenu()
            disableSelf()
        }
        val restartYoukiBtn = powerMenu?.findViewById<android.widget.ImageButton>(R.id.restart_youki_btn)
        restartYoukiBtn?.let { ColorUtils.applySecondaryColor(context, sharedPreferences, it) }
        restartYoukiBtn?.setOnClickListener {
            hidePowerMenu()
            // Fix: launch MainActivity (not DebugActivity which requires a "report" extra
            // and immediately calls finish() when none is provided, making the button a no-op)
            val restartIntent = android.content.Intent(context, com.youki.dex.activities.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val pending = android.app.PendingIntent.getActivity(
                context, 11111, restartIntent,
                android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val am = context.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
            am[android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, android.os.SystemClock.elapsedRealtime() + 1000] = pending
            try { disableSelf() } catch (_: Exception) {}
            stopSelf()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                android.os.Process.killProcess(android.os.Process.myPid())
            }, 400)
        }
        powerMenu?.let { ColorUtils.applyMainColor(context, sharedPreferences, it) }
        windowManager.addView(powerMenu, layoutParams)
        topRightCorner.visibility = if (sharedPreferences.getBoolean(
                "enable_corner_top_right",
                false
            )
        ) View.VISIBLE else View.GONE
    }

    private fun hidePowerMenu() {
        if (!powerMenuVisible || powerMenu == null) return
        powerMenuVisible = false
        val menuToRemove = powerMenu
        powerMenu = null
        try { windowManager.removeView(menuToRemove) } catch (_: Exception) {}
    }

    /** Reads bubble color and transparency ratio from preferences.
     *  bubble_mode = "custom"     → user-picked hex color
     *  bubble_mode = "material_u" → Material You primary color (default)
     */
    private fun getBubbleColor(): Int {
        val alphaPct = sharedPreferences.getInt("bubble_alpha_pct", 45).coerceIn(0, 100)
        val alphaInt = (alphaPct * 255 / 100)
        val mode = sharedPreferences.getString("bubble_mode", "material_u") ?: "material_u"

        if (mode == "material_u" && com.google.android.material.color.DynamicColors.isDynamicColorAvailable()) {
            return try {
                // Use 'this' (live service context) so Material You colors are always fresh
                val base = ColorUtils.getThemeColors(this, true)[0] // colorPrimary dark
                val darkened = ColorUtils.manipulateColor(base, 0.7f)
                android.graphics.Color.argb(
                    alphaInt,
                    android.graphics.Color.red(darkened),
                    android.graphics.Color.green(darkened),
                    android.graphics.Color.blue(darkened)
                )
            } catch (_: Exception) {
                android.graphics.Color.argb(alphaInt, 128, 128, 128)
            }
        }

        // custom mode (or Material You not available)
        val baseHex = sharedPreferences.getString("bubble_color", "#808080") ?: "#808080"
        return try {
            val base = android.graphics.Color.parseColor(baseHex)
            android.graphics.Color.argb(
                alphaInt,
                android.graphics.Color.red(base),
                android.graphics.Color.green(base),
                android.graphics.Color.blue(base)
            )
        } catch (_: Exception) {
            android.graphics.Color.argb(alphaInt, 128, 128, 128)
        }
    }

    /** Applies dynamic bubble color to all dock buttons */
    private fun applyBubbleColors() {
        val color = getBubbleColor()
        for (btn in listOf(backBtn, homeBtn, recentBtn, assistBtn, bluetoothBtn, wifiBtn, volumeBtn, pinBtn)) {
            btn.background?.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_ATOP)
            btn.background?.alpha = 255 // The color itself carries the alpha
        }
        notificationBtn.background?.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_ATOP)
        notificationBtn.background?.alpha = 255
        batteryBtn.background?.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_ATOP)
        batteryBtn.background?.alpha = 255
        resourceMonitorTv?.background?.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_ATOP)
        resourceMonitorTv?.background?.alpha = 255
        dateTv.background?.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_ATOP)
        dateTv.background?.alpha = 255
        // Sync wallpaper button bubble color with dock
        wallpaperBtn.background?.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_ATOP)
        wallpaperBtn.background?.alpha = 255
    }

    fun applyTheme() {
        // Reset dock background drawable so setColorFilter starts clean (no stale tint)
        val isDockRound = sharedPreferences.getBoolean("round_dock", false)
        dockLayout.setBackgroundResource(if (isDockRound) R.drawable.round_rect else R.drawable.rect)
        appMenu.setBackgroundResource(if (isDockRound) R.drawable.round_rect else R.drawable.rect)

        // Sync floating/fullscreen window position whenever theme is applied
        updateDockShape()

        // Pass 'this' (the live Service context) NOT the cached 'context' field —
        // DynamicColors.wrapContextIfAvailable needs a fresh context to pick up new Material You colors
        ColorUtils.applyMainColor(this, sharedPreferences, dockLayout)
        ColorUtils.applyMainColor(this, sharedPreferences, appMenu)
        ColorUtils.applySecondaryColor(this, sharedPreferences, searchEntry)
        ColorUtils.applySecondaryColor(this, sharedPreferences, powerBtn)

        // White icons in XML + transparent bubble — no filter needed
        for (btn in listOf(backBtn, homeBtn, recentBtn, assistBtn, bluetoothBtn, wifiBtn, volumeBtn))
            btn.clearColorFilter()
        pinBtn.clearColorFilter()

        // Notification button — white text on transparent bubble
        notificationBtn.setTextColor(android.graphics.Color.WHITE)

        // Apps button (Windows 11 logo) — tint with Material You primary color
        val winLogoColor = if (sharedPreferences.getString("theme", "material_u") == "material_u"
            && com.google.android.material.color.DynamicColors.isDynamicColorAvailable()) {
            ColorUtils.getThemeColors(this, false)[0] // light colorPrimary — visible on dark bg
        } else {
            android.graphics.Color.WHITE // white for non-Material themes
        }
        appsBtn.setColorFilter(winLogoColor, android.graphics.PorterDuff.Mode.SRC_ATOP)
        appsBtnCenter.setColorFilter(winLogoColor, android.graphics.PorterDuff.Mode.SRC_ATOP)

        // Apply dynamic bubble color
        applyBubbleColors()
    }





    private fun updateCorners() {
        topRightCorner.visibility = if (sharedPreferences.getBoolean(
                "enable_corner_top_right",
                false
            )
        ) View.VISIBLE else View.GONE
        bottomRightCorner.visibility = if (sharedPreferences.getBoolean(
                "enable_corner_bottom_right",
                false
            )
        ) View.VISIBLE else View.GONE
    }

    private fun updateMenuIcon() {
        val iconUri = sharedPreferences.getString("menu_icon_uri", "default")
        if (iconUri == "default") appsBtn.setImageResource(R.drawable.ic_apps_menu) else {
            try {
                val icon = iconUri?.toUri()
                if (icon != null)
                    appsBtn.setImageURI(icon)
            } catch (_: Exception) {
            }
        }
    }

    private fun updateBatteryBtn() {
        val pad = Utils.dpToPx(context, 5)
        batteryBtn.setPadding(pad, pad, pad, pad)
        batteryBtn.setBackgroundResource(R.drawable.btn_bubble_rect)
        batteryBtn.setTextColor(android.graphics.Color.WHITE)
        if (sharedPreferences.getBoolean("show_battery_level", false)) {
            batteryReceiver.showLevel = true
            batteryBtn.text = "${batteryReceiver.level}%"
        } else {
            batteryReceiver.showLevel = false
            batteryBtn.text = ""
        }
    }

    private fun toggleFavorites(visible: Boolean) {
        favoritesGv.visibility = if (visible) View.VISIBLE else View.GONE
        appsSeparator.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun loadFavoriteApps() {
        val apps = AppUtils.getPinnedApps(context, AppUtils.PINNED_LIST)
        toggleFavorites(apps.isNotEmpty())
        val menuFullscreen = sharedPreferences.getBoolean("app_menu_fullscreen", false)
        val phoneLayout = sharedPreferences.getInt("dock_layout", -1) == 0
        favoritesGv.adapter =
            AppAdapter(context, apps, this, menuFullscreen && !phoneLayout, iconPackUtils)
    }

    fun takeScreenshot() {
        if (Build.VERSION.SDK_INT >= 28)
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        else
            DeviceUtils.sendKeyEvent(KeyEvent.KEYCODE_SYSRQ)
    }

    private fun lockScreen() {
        if (Build.VERSION.SDK_INT >= 28)
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        else
            DeviceUtils.lockScreen(context)
    }

    private fun updateHandlePositionValues() {
        val position = sharedPreferences.getString("handle_position", "start")
        handleLayoutParams.gravity =
            Gravity.BOTTOM or if (position == "start") Gravity.START else Gravity.END
        if (position == "end") {
            dockHandle.setBackgroundResource(R.drawable.dock_handle_bg_end)
            dockHandle.setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.ic_expand_left,
                0,
                0,
                0
            )
        } else {
            dockHandle.setBackgroundResource(R.drawable.dock_handle_bg_start)
            dockHandle.setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.ic_expand_right,
                0,
                0,
                0
            )
        }
    }

    private fun updateHandlePosition() {
        updateHandlePositionValues()
        windowManager.updateViewLayout(dockHandle, handleLayoutParams)
    }

    private fun toggleNotificationPanel(show: Boolean) {
        sendBroadcast(
            Intent(DOCK_SERVICE_ACTION)
                .setPackage(packageName)
                .putExtra(
                    "action",
                    if (show) ACTION_SHOW_NOTIFICATION_PANEL else ACTION_HIDE_NOTIFICATION_PANEL
                )
        )
    }

    override fun onTouch(view: View, motionEvent: MotionEvent): Boolean {
        // Close QS panel if open when dock is touched
        if (motionEvent.action == MotionEvent.ACTION_DOWN && qsPanelVisible) {
            toggleQsPanel()
        }
        gestureDetector.onTouchEvent(motionEvent)
        return false
    }

    // ── Freeform Windowing ───────────────────────────────────────────────────
    // Enables freeform (floating windows) at system level via Shizuku.
    // Without this, Android ignores WINDOWING_MODE_FREEFORM requests in phone mode.
    private fun enableFreeformWindowing() {
        val shizuku = com.youki.dex.utils.ShizukoManager.getInstance(context)
        serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                if (shizuku.hasPermission) {
                    // FIX #14: Check current value before writing to avoid redundant DB writes on every service start
                    val current = android.provider.Settings.Global.getInt(contentResolver, "enable_freeform_support", 0)
                    if (current != 1)
                        shizuku.runShell("settings put global enable_freeform_support 1") {}
                    // Force desktop mode — makes freeform work in phone layout too
                    shizuku.runShell("settings put global force_desktop_mode_on_external_displays 0") {}
                    // Android 12+: additional freeform flag
                    if (Build.VERSION.SDK_INT >= 31) {
                        shizuku.runShell("wm set-multi-window-config --freeformWindowManagement true") {}
                    }
                    android.util.Log.d("DockService", "Freeform windowing enabled ✅")
                } else if (com.youki.dex.utils.DeviceUtils.hasWriteSettingsPermission(context)) {
                    // Fallback: WRITE_SECURE_SETTINGS direct
                    android.provider.Settings.Global.putInt(contentResolver, "enable_freeform_support", 1)
                    android.util.Log.d("DockService", "Freeform enabled via WRITE_SECURE_SETTINGS ✅")
                }
            } catch (e: Exception) {
                android.util.Log.e("DockService", "enableFreeformWindowing error: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel() // Cancel all running coroutines to prevent leaks
        castManager?.destroy()
        DeviceUtils.hideStatusBar(this, false)
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        // Unregister all receivers
        try { launcherReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        try { dockActionReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        try { notificationServiceReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        try { wallpaperReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        try { packageReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        if (::batteryReceiver.isInitialized)
            try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        if (::soundEventsReceiver.isInitialized)
            try { unregisterReceiver(soundEventsReceiver) } catch (_: Exception) {}
        stopResourceMonitor()
        // FIX (Lifecycle): Remove all tracked popup windows first so nothing
        // outlives the service. Then remove core windows in order.
        cleanupAllPopups()
        qsPanel?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        audioPanel?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        powerMenu?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        try { windowManager.removeView(dockHandle) } catch (_: Exception) {}
        try { windowManager.removeView(topRightCorner) } catch (_: Exception) {}
        try { windowManager.removeView(bottomRightCorner) } catch (_: Exception) {}
        try { windowManager.removeView(dock) } catch (_: Exception) {}
        super.onDestroy()
    }

    fun performNavAction(key: String) {
        val action = sharedPreferences.getString("${key}_long_action", "none")
        when (action) {
            NAV_LONG_ACTIONS[0] -> return
            NAV_LONG_ACTIONS[1] -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            NAV_LONG_ACTIONS[2] -> launchAssistant()
            NAV_LONG_ACTIONS[3] -> lockScreen()
            NAV_LONG_ACTIONS[4] -> performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
        }
    }

    private fun toggleQsPanel() {
        //  Prevent repetition during animation — this is the cause of stacking
        if (qsPanelAnimating) return

        if (qsPanelVisible) {
            val panelToClose = qsPanel ?: run {
                qsPanelVisible = false
                qsPanelAnimating = false
                return
            }
            qsPanelVisible = false
            qsPanelAnimating = true
            panelToClose.animate().cancel()
            panelToClose.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            panelToClose.animate()
                .scaleX(0.88f).scaleY(0.88f).alpha(0f)
                .translationY(com.youki.dex.utils.Utils.dpToPx(context, 8).toFloat())
                .setDuration(140)
                .setInterpolator(interpExit)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        try { windowManager.removeView(panelToClose) } catch (_: Exception) {}
                        qsPanel = null; qsPanelAnimating = false
                    }
                    override fun onAnimationCancel(animation: android.animation.Animator) {
                        try { windowManager.removeView(panelToClose) } catch (_: Exception) {}
                        qsPanel = null; qsPanelAnimating = false
                    }
                }).start()
            return
        }
        //  set flags immediately before any async work
        qsPanelVisible = true
        qsPanelAnimating = true
        val panel = LayoutInflater.from(context).inflate(R.layout.quick_settings_panel, null) as LinearLayout
        qsPanel = panel

        val brightnessSb = panel.findViewById<SeekBar>(R.id.qs_brightness_sb)
        try {
            brightnessSb.progress = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Exception) {}
        brightnessSb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, v: Int, user: Boolean) {
                if (user) Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Volume slider setup
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val volumeSb = panel.findViewById<SeekBar>(R.id.qs_volume_sb)
        volumeSb.max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volumeSb.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        volumeSb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, v: Int, user: Boolean) {
                if (user) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Both rows — dock mainColor (follows Material You / user theme)
        val qsDockColors  = ColorUtils.getMainColors(sharedPreferences, context)
        val brightnessRow = panel.findViewById<android.view.View>(R.id.qs_brightness_row)
        val volumeRow     = panel.findViewById<android.view.View>(R.id.qs_volume_row)
        panel.background  = null
        for (row in listOf(brightnessRow, volumeRow)) {
            row.background?.setColorFilter(qsDockColors[0], android.graphics.PorterDuff.Mode.SRC_ATOP)
            row.background?.alpha = 255
        }

        // Sliders: Material You accent when material_u, white otherwise
        val isMatU = sharedPreferences.getString("theme", "material_u") == "material_u"
        val sliderColor = if (isMatU && com.google.android.material.color.DynamicColors.isDynamicColorAvailable()) {
            ColorUtils.getThemeColors(context, false)[0] // light colorPrimary — visible on dark bg
        } else {
            android.graphics.Color.WHITE
        }
        for (sb in listOf(brightnessSb, volumeSb)) {
            sb.progressDrawable?.setColorFilter(sliderColor, android.graphics.PorterDuff.Mode.SRC_ATOP)
            sb.thumb?.setColorFilter(sliderColor, android.graphics.PorterDuff.Mode.SRC_ATOP)
        }

        val layoutParams = Utils.makeWindowParams(-2, -2, context, secondary)
        layoutParams.gravity = Gravity.BOTTOM or Gravity.END
        layoutParams.y = dockHeight + Utils.dpToPx(context, 4)
        layoutParams.x = Utils.dpToPx(context, 8)
        layoutParams.flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL   // FIX: prevent QS panel from blocking taps outside its bounds
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        panel.setOnTouchListener(null)
        windowManager.addView(panel, layoutParams)
        panel.scaleX = 0.88f
        panel.scaleY = 0.88f
        panel.alpha = 0f
        panel.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        panel.translationY = com.youki.dex.utils.Utils.dpToPx(context, 10).toFloat()
        panel.animate().cancel()
        panel.animate()
            .scaleX(1f).scaleY(1f).alpha(1f).translationY(0f)
            .setDuration(220)
            .setInterpolator(interpEmphasized)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    panel.setLayerType(View.LAYER_TYPE_NONE, null)
                    qsPanelAnimating = false
                }
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    panel.scaleX = 1f; panel.scaleY = 1f; panel.alpha = 1f; panel.translationY = 0f
                    panel.setLayerType(View.LAYER_TYPE_NONE, null)
                    qsPanelAnimating = false
                }
            }).start()
    }
    private fun showLauncherPicker() {
        // Fetch all installed launchers
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launchers = packageManager.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != packageName } // Exclude YoukiDEX itself

        if (launchers.isEmpty()) {
            Toast.makeText(context, "No other launcher found", Toast.LENGTH_SHORT).show()
            return
        }

        val view = LayoutInflater.from(context).inflate(R.layout.task_list, null)
        val layoutParams = Utils.makeWindowParams(-2, -2, context, secondary)
        layoutParams.gravity = Gravity.CENTER
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

        val actionsLv = view.findViewById<android.widget.ListView>(R.id.tasks_lv)
        val actions = ArrayList<com.youki.dex.models.Action>()
        launchers.forEach { info ->
            val label = info.loadLabel(packageManager).toString()
            actions.add(com.youki.dex.models.Action(R.drawable.ic_launch_mode, label))
        }

        try { ColorUtils.applyMainColor(context, sharedPreferences, view) } catch (_: Exception) {}

        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) windowManager.removeView(view)
            false
        }

        actionsLv.adapter = com.youki.dex.adapters.AppActionsAdapter(context, actions)
        actionsLv.setOnItemClickListener { _, _, position, _ ->
            val selectedInfo = launchers[position]
            windowManager.removeView(view)

            // Fully hide YoukiDEX before opening launcher
            try { dock.visibility = android.view.View.GONE } catch (_: Exception) {}
            try { unpinDock() } catch (_: Exception) {}
            // Hide notification bar
            sendBroadcast(Intent(com.youki.dex.services.DOCK_SERVICE_ACTION)
                .setPackage(packageName)
                .putExtra("action", com.youki.dex.services.ACTION_HIDE_NOTIFICATION_BAR))

            val launchIntent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setPackage(selectedInfo.activityInfo.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            try {
                context.startActivity(launchIntent)
            } catch (e: Exception) {
                // If failed, restore dock
                dock.visibility = android.view.View.VISIBLE
                Toast.makeText(context, "Failed to open app", Toast.LENGTH_SHORT).show()
            }

            // Re-show dock when user returns to YoukiDEX
            dockHandler.postDelayed({
                if (dock.windowToken != null) {
                    dock.visibility = android.view.View.VISIBLE
                    sendBroadcast(Intent(com.youki.dex.services.DOCK_SERVICE_ACTION)
                        .setPackage(packageName)
                        .putExtra("action", com.youki.dex.services.ACTION_SHOW_NOTIFICATION_BAR))
                }
            }, 1000)
        }

        windowManager.addView(view, layoutParams)
    }

    private fun getCpuUsage(): Int {
        // Use /proc/stat with fallback for Debug
        return try {
            // FIX #15: use{} ensures file is always closed even on exception
            val line = java.io.RandomAccessFile("/proc/stat", "r").use { it.readLine() }
            val parts = line.trim().split("\\s+".toRegex()).drop(1)
            if (parts.size < 4) return lastCpuValue
            val user    = parts[0].toLong()
            val nice    = parts[1].toLong()
            val system  = parts[2].toLong()
            val idle    = parts[3].toLong()
            val iowait  = parts.getOrNull(4)?.toLong() ?: 0L
            val irq     = parts.getOrNull(5)?.toLong() ?: 0L
            val softirq = parts.getOrNull(6)?.toLong() ?: 0L
            val total   = user + nice + system + idle + iowait + irq + softirq
            val diffTotal = total - prevCpuTotal
            val diffIdle  = (idle + iowait) - prevCpuIdle
            prevCpuTotal  = total
            prevCpuIdle   = idle + iowait
            if (diffTotal <= 0L) lastCpuValue
            else {
                lastCpuValue = ((100L * (diffTotal - diffIdle)) / diffTotal).toInt().coerceIn(0, 100)
                lastCpuValue
            }
        } catch (_: Exception) {
            // Fallback: calculate CPU from pids
            try {
                val pid = android.os.Process.myPid()
                val statFile = java.io.File("/proc/$pid/stat")
                if (statFile.exists()) {
                    val parts = statFile.readText().trim().split("\\s+".toRegex())
                    if (parts.size > 15) {
                        val utime = parts[13].toLong()
                        val stime = parts[14].toLong()
                        val total = utime + stime
                        val diff = (total - prevCpuTotal).coerceAtLeast(0L)
                        prevCpuTotal = total
                        // clock ticks per second ≈ 100; clamp to 0..100
                        lastCpuValue = (diff).toInt().coerceIn(0, 100)
                        lastCpuValue
                    } else lastCpuValue
                } else lastCpuValue
            } catch (_: Exception) { lastCpuValue }
        }
    }

    private fun startResourceMonitor() {
        val h = Handler(mainLooper)
        resourceHandler = h
        val runnable = object : Runnable {
            override fun run() {
                // Read RAM on main thread normally
                val mi = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(mi)
                val usedRam = (mi.totalMem - mi.availMem) / (1024 * 1024)
                val ramDisplay = if (usedRam >= 1024) "%.1fG".format(usedRam / 1024f) else "${usedRam}M"
                // CPU on background thread
                serviceScope.launch(Dispatchers.IO) {
                    val cpu = getCpuUsage()
                    withContext(Dispatchers.Main) {
                        resourceMonitorTv?.text = "CPU $cpu%  RAM $ramDisplay"
                    }
                }
                h.postDelayed(this, 2000)
            }
        }
        // baseline reads
        serviceScope.launch(Dispatchers.IO) {
            getCpuUsage()
            Thread.sleep(500)
            getCpuUsage()
        }
        h.postDelayed(runnable, 1200)
    }

    private fun stopResourceMonitor() {
        resourceHandler?.removeCallbacksAndMessages(null)
        resourceHandler = null
    }

    inner class HotCornersHoverListener(val key: String) : View.OnHoverListener {
        // PERF/FIX: single Handler instance — old code created a new Handler on every
        // ACTION_HOVER_ENTER, which allocates a Looper reference and accumulates
        // uncancelled callbacks if the pointer moves in/out rapidly (e.g. hovering
        // along the corner edge). A reused handler with removeCallbacks prevents leaks.
        private val handler = Handler(mainLooper)
        private val triggerRunnable = Runnable {
            performNavAction(key)
        }

        override fun onHover(v: View?, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_HOVER_ENTER -> {
                    if (v?.isHovered == true) {
                        val delay = sharedPreferences
                            .getString("hot_corners_delay", "300")
                            ?.toLongOrNull() ?: 300L
                        handler.removeCallbacks(triggerRunnable)
                        handler.postDelayed(triggerRunnable, delay)
                    }
                }
                MotionEvent.ACTION_HOVER_EXIT -> handler.removeCallbacks(triggerRunnable)
            }
            return false
        }
    }
}