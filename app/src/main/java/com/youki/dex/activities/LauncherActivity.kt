package com.youki.dex.activities

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.ShortcutInfo
import android.os.Bundle
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.youki.dex.R
import com.youki.dex.adapters.AppActionsAdapter
import com.youki.dex.adapters.AppShortcutAdapter
import com.youki.dex.models.Action
import com.youki.dex.models.App
import com.youki.dex.services.ACTION_LAUNCH_APP
import com.youki.dex.services.DESKTOP_APP_PINNED
import com.youki.dex.services.DOCK_SERVICE_ACTION
import com.youki.dex.services.DOCK_SERVICE_CONNECTED
import com.youki.dex.utils.AppUtils
import com.youki.dex.utils.ColorUtils
import com.youki.dex.utils.DeepShortcutManager
import com.youki.dex.utils.DeviceUtils
import com.youki.dex.utils.IconPackUtils
import com.youki.dex.utils.Utils
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import kotlin.math.abs

const val LAUNCHER_ACTION = "launcher_action"
const val LAUNCHER_RESUMED = "launcher_resumed"
const val LAUNCHER_PAUSED = "launcher_paused"

open class LauncherActivity : AppCompatActivity(), OnSharedPreferenceChangeListener {

    private var iconPackUtils: IconPackUtils? = null
    private lateinit var serviceBtn: MaterialButton
    private lateinit var desktopContainer: FrameLayout
    private lateinit var notesEt: EditText
    private lateinit var sharedPreferences: SharedPreferences
    private var bgTouchX = 0f
    private var bgTouchY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // PERF FIX: packageManager.resolveActivity() and DisplayManager.getDisplays() are
        // blocking IPC calls that used to run on the main thread every single launch, adding
        // 200–400ms of cold-start latency.
        //
        // New strategy: read orientation decision from a SharedPreference cache that is
        // written once in onResume() (off the critical launch path). On first ever launch the
        // cache is absent, so we fall back to the live IPC calls — the user only pays the
        // cost once, not every time they tap the shortcut.
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val cachedLandscape = prefs.getBoolean("_cached_should_force_landscape", false)
        val cacheReady      = prefs.getBoolean("_cached_landscape_valid", false)

        val shouldForceLandscape = if (cacheReady) {
            // Fast path — no IPC at all ✅
            cachedLandscape
        } else {
            // First launch: do the IPC once and store the result
            val hasExternalDisplay = (getSystemService(DISPLAY_SERVICE)
                    as android.hardware.display.DisplayManager)
                .getDisplays(android.hardware.display.DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                .isNotEmpty()
            val isDefaultHome = run {
                val homeIntent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_HOME)
                packageManager.resolveActivity(homeIntent, 0)?.activityInfo?.packageName == packageName
            }
            (hasExternalDisplay || isDefaultHome).also { result ->
                prefs.edit {
                    putBoolean("_cached_should_force_landscape", result)
                    putBoolean("_cached_landscape_valid", true)
                }
            }
        }

        if (shouldForceLandscape) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        // else: leave SCREEN_ORIENTATION_UNSPECIFIED — phone portrait mode works fine

        setContentView(R.layout.activity_launcher)

        val backgroundLayout = findViewById<FrameLayout>(R.id.ll_background)
        serviceBtn = findViewById(R.id.service_btn)
        desktopContainer = findViewById(R.id.desktop_icons_container)

        // Apply bottom padding so desktop icons don't hide under the dock overlay
        val dockHeightDp = prefs.getString("dock_height", "56")!!.toInt()
        val dockHeightPx = (dockHeightDp * resources.displayMetrics.density + 0.5f).toInt()
        desktopContainer.setPadding(0, 0, 0, dockHeightPx)
        notesEt = findViewById(R.id.notes_et)
        hideSystemBars()

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        serviceBtn.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }

        backgroundLayout.setOnLongClickListener {
            val view = LayoutInflater.from(this).inflate(R.layout.task_list, null)
            val layoutParams = Utils.makeWindowParams(-2, -2, this)
            ColorUtils.applyMainColor(this, sharedPreferences, view)
            layoutParams.gravity = Gravity.TOP or Gravity.START
            layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            layoutParams.x = bgTouchX.toInt()
            layoutParams.y = bgTouchY.toInt()
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            view.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) windowManager.removeView(view)
                false
            }
            val actionsLv = view.findViewById<ListView>(R.id.tasks_lv)
            val actions = ArrayList<Action>()
            actions.add(Action(R.drawable.ic_wallpaper, getString(R.string.change_wallpaper)))
            actions.add(Action(R.drawable.ic_fullscreen, getString(R.string.display_settings)))
            actionsLv.adapter = AppActionsAdapter(this, actions)
            actionsLv.setOnItemClickListener { adapterView, _, position, _ ->
                val action = adapterView.getItemAtPosition(position) as Action
                if (action.text == getString(R.string.change_wallpaper))
                    startActivityForResult(Intent.createChooser(
                        Intent(Intent.ACTION_SET_WALLPAPER), getString(R.string.change_wallpaper)), 18)
                else if (action.text == getString(R.string.display_settings))
                    startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS))
                windowManager.removeView(view)
            }
            windowManager.addView(view, layoutParams)
            true
        }
        backgroundLayout.setOnTouchListener { _, event ->
            bgTouchX = event.x; bgTouchY = event.y; false
        }

        ContextCompat.registerReceiver(
            this, object : BroadcastReceiver() {
                override fun onReceive(p1: Context, intent: Intent) {
                    when (intent.getStringExtra("action")) {
                        DOCK_SERVICE_CONNECTED -> {
                            serviceBtn.visibility = View.GONE
                            // Service just started — re-trigger enable_dock to activate notifications
                            sendBroadcast(Intent(DOCK_SERVICE_ACTION).setPackage(packageName).putExtra("action", "enable_dock"))
                        }
                        DESKTOP_APP_PINNED -> loadDesktopApps()
                    }
                }
            }, IntentFilter(DOCK_SERVICE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED
        )

        if (sharedPreferences.getString("icon_pack", "")!!.isNotEmpty())
            iconPackUtils = IconPackUtils(this)

        // FIX: loadDesktopApps() uses desktopContainer.width/height for grid snapping.
        // At onCreate() time, the container has not been measured yet (width = 0), so
        // snapToGrid() and findFreeCell() produce wrong positions and icons land at 0,0.
        // We must wait for the first layout pass before loading icons.
        desktopContainer.viewTreeObserver.addOnGlobalLayoutListener(object :
            android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                desktopContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                loadDesktopApps()
            }
        })
    }

    private fun getDesktopIconSizePx(): Int =
        Utils.dpToPx(this, 50)

    private fun getGridSize(): Int = Utils.dpToPx(this, 85)

    /** Snap a pixel value to the nearest grid cell */
    private fun snapToGrid(value: Int): Int {
        val g = getGridSize()
        return ((value + g / 2) / g) * g
    }

    /** Check if two icon positions overlap (within icon bounds) */
    private fun overlaps(x1: Int, y1: Int, x2: Int, y2: Int, iconSizePx: Int): Boolean {
        val margin = (iconSizePx * 0.6f).toInt()
        return Math.abs(x1 - x2) < margin && Math.abs(y1 - y2) < margin
    }

    /** Find a free grid cell starting from (startX, startY) that doesn't overlap existing icons */
    private fun findFreeCell(
        startX: Int, startY: Int,
        occupiedPositions: List<Pair<Int, Int>>,
        iconSizePx: Int,
        containerWidth: Int,
        containerHeight: Int
    ): Pair<Int, Int> {
        val g = getGridSize()
        // Search in expanding rings from the target cell
        for (radius in 0..20) {
            val candidates = mutableListOf<Pair<Int, Int>>()
            if (radius == 0) {
                candidates.add(Pair(startX, startY))
            } else {
                for (col in -radius..radius) {
                    candidates.add(Pair(startX + col * g, startY - radius * g))
                    candidates.add(Pair(startX + col * g, startY + radius * g))
                }
                for (row in -radius + 1 until radius) {
                    candidates.add(Pair(startX - radius * g, startY + row * g))
                    candidates.add(Pair(startX + radius * g, startY + row * g))
                }
            }
            for ((cx, cy) in candidates) {
                if (cx < 0 || cy < 0) continue
                val free = occupiedPositions.none { (ox, oy) -> overlaps(cx, cy, ox, oy, iconSizePx) }
                if (free) return Pair(cx, cy)
            }
        }
        return Pair(startX, startY) // fallback
    }

    fun loadDesktopApps() {
        desktopContainer.removeAllViews()
        val apps = AppUtils.getPinnedApps(this, AppUtils.DESKTOP_LIST)
        val iconSizePx = getDesktopIconSizePx()
        val labelSize = 13f
        val g = getGridSize()
        val occupiedPositions = mutableListOf<Pair<Int, Int>>()

        apps.forEachIndexed { index, app ->
            val savedPos = sharedPreferences.getString("deskpos_${app.packageName}", null)
            val pos = if (savedPos != null) {
                val parts = savedPos.split(",")
                val sx = snapToGrid(parts[0].toIntOrNull() ?: g)
                val sy = snapToGrid(parts[1].toIntOrNull() ?: g)
                findFreeCell(sx, sy, occupiedPositions, iconSizePx,
                    desktopContainer.width, desktopContainer.height)
            } else {
                val col = 0
                val row = index
                findFreeCell(col * g, row * g, occupiedPositions, iconSizePx,
                    desktopContainer.width, desktopContainer.height)
            }
            occupiedPositions.add(pos)
            addDesktopIcon(app, pos.first, pos.second, iconSizePx, labelSize)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addDesktopIcon(app: App, startX: Int, startY: Int, iconSizePx: Int, labelSize: Float) {
        val iconView = LayoutInflater.from(this).inflate(R.layout.app_entry_large, null)
        val iconIv  = iconView.findViewById<ImageView>(R.id.app_icon_iv)
        val nameTv  = iconView.findViewById<TextView>(R.id.app_name_tv)

        iconIv.layoutParams.width  = iconSizePx
        iconIv.layoutParams.height = iconSizePx
        nameTv.textSize = labelSize
        if (sharedPreferences.getBoolean("single_line_labels", true)) nameTv.maxLines = 1

        val iconDrawable = iconPackUtils?.getAppThemedIcon(app.packageName) ?: app.icon
        iconIv.setImageDrawable(iconDrawable)
        nameTv.text = app.name

        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.leftMargin = startX
        lp.topMargin  = startY
        iconView.layoutParams = lp

        var hasMoved = false
        var downRawX = 0f; var downRawY = 0f
        var startLeft = 0;  var startTop  = 0

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!hasMoved) launchApp(null, app.packageName)
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                if (!hasMoved) showAppContextMenu(app, iconView)
            }
        })

        iconView.setOnTouchListener { v, event ->
            gestureDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    hasMoved = false
                    downRawX = event.rawX; downRawY = event.rawY
                    val cur = v.layoutParams as FrameLayout.LayoutParams
                    startLeft = cur.leftMargin; startTop = cur.topMargin
                    v.elevation = 8f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (hasMoved || abs(dx) > 12 || abs(dy) > 12) {
                        hasMoved = true
                        val cur = v.layoutParams as FrameLayout.LayoutParams
                        cur.leftMargin = (startLeft + dx).toInt().coerceAtLeast(0)
                        cur.topMargin  = (startTop  + dy).toInt().coerceAtLeast(0)
                        v.layoutParams = cur
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.elevation = 0f
                    if (hasMoved) {
                        val cur = v.layoutParams as FrameLayout.LayoutParams
                        // Snap to grid
                        val snappedX = snapToGrid(cur.leftMargin).coerceAtLeast(0)
                        val snappedY = snapToGrid(cur.topMargin).coerceAtLeast(0)

                        // Check overlap with other icons
                        val otherPositions = mutableListOf<Pair<Int, Int>>()
                        for (i in 0 until desktopContainer.childCount) {
                            val child = desktopContainer.getChildAt(i)
                            if (child == v) continue
                            val clp = child.layoutParams as FrameLayout.LayoutParams
                            otherPositions.add(Pair(clp.leftMargin, clp.topMargin))
                        }
                        val freePos = findFreeCell(snappedX, snappedY, otherPositions,
                            iconSizePx, desktopContainer.width, desktopContainer.height)

                        cur.leftMargin = freePos.first
                        cur.topMargin  = freePos.second
                        v.layoutParams = cur
                        sharedPreferences.edit {
                            putString("deskpos_${app.packageName}", "${freePos.first},${freePos.second}")
                        }
                    }
                }
            }
            true
        }

        desktopContainer.addView(iconView)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        sendBroadcast(Intent(LAUNCHER_ACTION).setPackage(packageName).putExtra("action", LAUNCHER_RESUMED))
        sendBroadcast(Intent(DOCK_SERVICE_ACTION).setPackage(packageName).putExtra("action", "enable_dock"))
        serviceBtn.visibility = if (DeviceUtils.isAccessibilityServiceEnabled(this)) View.GONE else View.VISIBLE
        loadDesktopApps()

        // PERF: Refresh the orientation cache off the main thread — zero cost on next launch
        // TODO: Replace AsyncTask.THREAD_POOL_EXECUTOR with a coroutine or Executors.newSingleThreadExecutor()
        // when the activity is refactored. Suppressed for now as it only runs an orientation cache update.
        @Suppress("DEPRECATION")
        android.os.AsyncTask.THREAD_POOL_EXECUTOR.execute {
            val dm = getSystemService(DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            val hasExternal = dm
                .getDisplays(android.hardware.display.DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                .isNotEmpty()
            val isHome = run {
                val hi = android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_HOME)
                packageManager.resolveActivity(hi, 0)?.activityInfo?.packageName == packageName
            }
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).edit {
                putBoolean("_cached_should_force_landscape", hasExternal || isHome)
                putBoolean("_cached_landscape_valid", true)
            }
        }
        if (sharedPreferences.getBoolean("show_notes", false)) {
            notesEt.visibility = View.VISIBLE; loadNotes()
        } else notesEt.visibility = View.GONE
    }

    override fun onPause() {
        super.onPause()
        if (sharedPreferences.getBoolean("show_notes", false)) saveNotes()
    }

    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {}

    private fun loadNotes() {
        try {
            val notes = File(getExternalFilesDir(null), "notes.txt")
            val br = BufferedReader(FileReader(notes))
            val sb = StringBuilder()
            var line: String?
            while (br.readLine().also { line = it } != null) sb.append(line).append("\n")
            br.close()
            notesEt.setText(sb.toString())
        } catch (_: IOException) {}
    }

    private fun saveNotes() {
        val content = notesEt.text.toString()
        if (content.isNotEmpty()) {
            try {
                val fw = FileWriter(File(getExternalFilesDir(null), "notes.txt"))
                fw.write(content); fw.close()
            } catch (_: IOException) {}
        }
    }

    private fun launchApp(mode: String?, app: String) {
        sendBroadcast(Intent(LAUNCHER_ACTION).setPackage(packageName)
            .putExtra("action", ACTION_LAUNCH_APP)
            .putExtra("mode", mode)
            .putExtra("app", app))
    }

    private fun getAppActions(app: String): ArrayList<Action> {
        val actions = ArrayList<Action>()
        if (DeepShortcutManager.hasHostPermission(this) &&
            DeepShortcutManager.getShortcuts(app, this)!!.isNotEmpty())
            actions.add(Action(R.drawable.ic_shortcuts, getString(R.string.shortcuts)))
        actions.add(Action(R.drawable.ic_manage, getString(R.string.manage)))
        actions.add(Action(R.drawable.ic_launch_mode, getString(R.string.open_as)))
        actions.add(Action(R.drawable.ic_remove_from_desktop, getString(R.string.remove)))
        return actions
    }

    @SuppressLint("NewApi")
    private fun showAppContextMenu(app: App, anchor: View) {
        val wm   = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = LayoutInflater.from(this).inflate(R.layout.task_list, null)
        val lp   = Utils.makeWindowParams(-2, -2, this)
        ColorUtils.applyMainColor(this, sharedPreferences, view)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.flags   = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        lp.x = loc[0]; lp.y = loc[1] + Utils.dpToPx(this, anchor.measuredHeight / 2)
        view.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) wm.removeView(view); false
        }
        val actionsLv = view.findViewById<ListView>(R.id.tasks_lv)
        actionsLv.adapter = AppActionsAdapter(this, getAppActions(app.packageName))
        actionsLv.setOnItemClickListener { adv, _, pos, _ ->
            when {
                adv.getItemAtPosition(pos) is Action -> {
                    val action = adv.getItemAtPosition(pos) as Action
                    when (action.text) {
                        getString(R.string.manage) -> {
                            val sub = ArrayList<Action>()
                            sub.add(Action(R.drawable.ic_arrow_back, ""))
                            sub.add(Action(R.drawable.ic_info, getString(R.string.app_info)))
                            if (!AppUtils.isSystemApp(this, app.packageName) ||
                                sharedPreferences.getBoolean("allow_sysapp_uninstall", false))
                                sub.add(Action(R.drawable.ic_uninstall, getString(R.string.uninstall)))
                            if (sharedPreferences.getBoolean("allow_app_freeze", false))
                                sub.add(Action(R.drawable.ic_freeze, getString(R.string.freeze)))
                            actionsLv.adapter = AppActionsAdapter(this, sub)
                        }
                        getString(R.string.shortcuts) ->
                            actionsLv.adapter = AppShortcutAdapter(
                                this, DeepShortcutManager.getShortcuts(app.packageName, this)!!)
                        "" -> actionsLv.adapter = AppActionsAdapter(this, getAppActions(app.packageName))
                        getString(R.string.open_as) -> {
                            val sub = ArrayList<Action>()
                            sub.add(Action(R.drawable.ic_arrow_back, ""))
                            sub.add(Action(R.drawable.ic_standard,   getString(R.string.standard)))
                            sub.add(Action(R.drawable.ic_maximized,  getString(R.string.maximized)))
                            sub.add(Action(R.drawable.ic_portrait,   getString(R.string.portrait)))
                            sub.add(Action(R.drawable.ic_fullscreen, getString(R.string.fullscreen)))
                            actionsLv.adapter = AppActionsAdapter(this, sub)
                        }
                        getString(R.string.app_info) -> {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData("package:${app.packageName}".toUri())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            wm.removeView(view)
                        }
                        getString(R.string.uninstall) -> {
                            @Suppress("DEPRECATION")
                            if (AppUtils.isSystemApp(this, app.packageName))
                                DeviceUtils.runAsRoot("pm uninstall --user 0 ${app.packageName}")
                            else startActivity(
                                Intent(Intent.ACTION_UNINSTALL_PACKAGE,
                                    "package:${app.packageName}".toUri())
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            wm.removeView(view)
                        }
                        getString(R.string.freeze) -> {
                            val s = DeviceUtils.runAsRoot("pm disable ${app.packageName}")
                            Toast.makeText(this,
                                if (s != "error") R.string.app_frozen else R.string.something_wrong,
                                Toast.LENGTH_SHORT).show()
                            wm.removeView(view); loadDesktopApps()
                        }
                        getString(R.string.remove) -> {
                            AppUtils.unpinApp(this, app.packageName, AppUtils.DESKTOP_LIST)
                            sharedPreferences.edit { remove("deskpos_${app.packageName}") }
                            wm.removeView(view); loadDesktopApps()
                        }
                        getString(R.string.standard)   -> { wm.removeView(view); launchApp("standard",   app.packageName) }
                        getString(R.string.maximized)  -> { wm.removeView(view); launchApp("maximized",  app.packageName) }
                        getString(R.string.portrait)   -> { wm.removeView(view); launchApp("portrait",   app.packageName) }
                        getString(R.string.fullscreen) -> { wm.removeView(view); launchApp("fullscreen", app.packageName) }
                    }
                }
                adv.getItemAtPosition(pos) is ShortcutInfo -> {
                    wm.removeView(view)
                    DeepShortcutManager.startShortcut(adv.getItemAtPosition(pos) as ShortcutInfo, this)
                }
            }
        }
        wm.addView(view, lp)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, preference: String?) {
        if (preference == null) return
        when (preference) {
            "icon_pack" -> {
                val pack = sharedPreferences.getString("icon_pack", "")!!
                iconPackUtils = if (pack.isNotEmpty()) IconPackUtils(this) else null
                loadDesktopApps()
            }
            "single_line_labels" -> loadDesktopApps()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.systemBars())
                it.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }
}
