package com.youki.dex.utils

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.Notification
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.os.UserManager
import android.view.Display
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import com.youki.dex.models.App
import com.youki.dex.models.AppTask
import com.youki.dex.models.DockApp
import java.io.File

object AppUtils {
    const val PINNED_LIST = "pinned.lst"
    const val DOCK_PINNED_LIST = "dock_pinned.lst"
    const val DESKTOP_LIST = "desktop.lst"
    var currentApp = ""
    fun getInstalledPackages(context: Context): List<App> {
        val apps = ArrayList<App>()
        val packages = context.packageManager.getInstalledPackages(0)
        packages.forEach { packageInfo ->
            val appInfo = packageInfo.applicationInfo
            apps.add(
                App(
                    appInfo.loadLabel(context.packageManager).toString(),
                    appInfo.packageName,
                    appInfo.loadIcon(context.packageManager)
                )
            )
        }
        return apps.sortedWith(compareBy { it.name })
    }

    fun getInstalledApps(context: Context): ArrayList<App> {
        val apps = ArrayList<App>()
        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        var appsInfo = mutableListOf<LauncherActivityInfo>()
        for (profile in userManager.userProfiles) appsInfo.addAll(
            launcherApps.getActivityList(
                null,
                profile
            )
        )

        appsInfo = appsInfo.sortedWith(compareBy { it.label.toString() }).toMutableList()

        //TODO: Filter Google App
        for (appInfo in appsInfo) {
            apps.add(
                App(
                    appInfo.label.toString(),
                    appInfo.componentName.packageName,
                    appInfo.getIcon(android.util.DisplayMetrics.DENSITY_XXXHIGH),
                    appInfo.componentName,
                    appInfo.user
                )
            )
        }
        return apps
    }

    fun getPinnedApps(context: Context, type: String): ArrayList<App> {
        val file = File(context.filesDir, type)
        val apps = ArrayList<App>()
        val appsInfo = mutableListOf<LauncherActivityInfo>()
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        if (file.exists()) {
            for (line in file.readLines()) {
                if (line.isBlank()) continue
                val info = line.split(" ")
                if (info.size < 2) continue
                val packageName = info[0]
                val serial = info[1].toLongOrNull() ?: continue
                val userHandle = userManager.getUserForSerialNumber(serial)
                val list = launcherApps.getActivityList(packageName, userHandle)
                if (list.isNullOrEmpty()) unpinApp(context, packageName, type)
                appsInfo.addAll(list)
            }
        }

        for (appInfo in appsInfo) {
            apps.add(
                App(
                    appInfo.label.toString(),
                    appInfo.componentName.packageName,
                    appInfo.getIcon(android.util.DisplayMetrics.DENSITY_XXXHIGH),
                    appInfo.componentName,
                    appInfo.user
                )
            )
        }
        return apps
    }

    fun pinApp(context: Context, app: App, type: String) {
        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        val file = File(context.filesDir, type)
        file.appendText("${app.packageName} ${userManager.getSerialNumberForUser(app.userHandle)}\n")
    }

    fun unpinApp(context: Context, packageName: String, type: String) {
        val file = File(context.filesDir, type)
        val updatedList = file.readLines().filter { it.split(" ")[0] != packageName }
        if (updatedList.isNotEmpty()) file.writeText(updatedList.joinToString("\n") + "\n")
        else {
            file.writeText("")
        }
    }

    fun moveApp(context: Context, app: App, type: String, direction: Int) {
        val file = File(context.filesDir, type)
        val lines = file.readLines().toMutableList()

        val lineIndex = lines.indexOfFirst { it.split(" ")[0] == app.packageName }

        if (lineIndex != -1) {
            if (direction == 0 && lineIndex > 0) {
                val line = lines.removeAt(lineIndex)
                lines.add(lineIndex - 1, line)
            } else if (direction == 1 && lineIndex < lines.size - 1) {
                val line = lines.removeAt(lineIndex)
                lines.add(lineIndex + 1, line)
            }

            file.writeText(lines.joinToString("\n") + "\n")
        }
    }

    fun isPinned(context: Context, app: App, type: String): Boolean {
        val file = File(context.filesDir, type)
        if (!file.exists()) return false
        file.readLines().forEach { line ->
            if (line.split(" ")[0] == app.packageName) return true
        }
        return false
    }

    fun isGame(packageManager: PackageManager, packageName: String): Boolean {
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                info.category == ApplicationInfo.CATEGORY_GAME
            } else {
                info.flags and ApplicationInfo.FLAG_IS_GAME == ApplicationInfo.FLAG_IS_GAME
            }
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun getCurrentLauncher(packageManager: PackageManager): String {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfo?.activityInfo?.packageName ?: ""
    }

    fun setWindowMode(activityManager: ActivityManager, taskId: Int, mode: Int) {
        try {
            val setWindowMode = activityManager.javaClass.getMethod(
                "setTaskWindowingMode",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            )
            setWindowMode.invoke(activityManager, taskId, mode, false)
        } catch (_: Exception) {
        }
    }

    fun getRunningTasks(
        activityManager: ActivityManager, packageManager: PackageManager, max: Int
    ): ArrayList<AppTask> {
        val tasksInfo = activityManager.getRunningTasks(max)
        if (tasksInfo.isNullOrEmpty()) return ArrayList()
        currentApp = tasksInfo[0].baseActivity?.packageName ?: ""
        val appTasks = ArrayList<AppTask>()
        for (taskInfo in tasksInfo) {
            try {
                //Exclude systemui, launcher and other system apps from the tasklist
                if (taskInfo.baseActivity!!.packageName.contains("com.android.systemui") || taskInfo.baseActivity!!.packageName.contains(
                        "com.google.android.packageinstaller"
                    ) || taskInfo.baseActivity!!.className == "com.android.quickstep.RecentsActivity"
                ) continue

                //Hack to save Dock settings activity from being excluded
                if (!(taskInfo.topActivity!!.className == "com.youki.dex.activities.MainActivity" || taskInfo.topActivity!!.className == "com.youki.dex.activities.DebugActivity") && taskInfo.topActivity!!.packageName == getCurrentLauncher(
                        packageManager
                    )
                ) continue
                if (Build.VERSION.SDK_INT > 29) {
                    try {
                        val isRunning = taskInfo.javaClass.getField("isRunning")
                        val running = isRunning.getBoolean(taskInfo)
                        if (!running) continue
                    } catch (_: Exception) {
                    }
                }
                appTasks.add(
                    AppTask(
                        taskInfo.id,
                        packageManager.getActivityInfo(taskInfo.topActivity!!, 0)
                            .loadLabel(packageManager).toString(),
                        taskInfo.topActivity!!.packageName,
                        packageManager.getActivityIcon(taskInfo.topActivity!!)
                    )
                )
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        return appTasks
    }

    fun getRecentTasks(context: Context, max: Int): ArrayList<AppTask> {
        val ignoredApps =
            listOf<String>(context.packageName, getCurrentLauncher(context.packageManager))
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val start = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val usageStats = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_BEST, start, System.currentTimeMillis()
        ).sortedWith(compareByDescending { it.lastTimeUsed })
            .filterNot { ignoredApps.contains(it.packageName) }
        val appTasks = ArrayList<AppTask>()
        if (usageStats.isNotEmpty())
            currentApp = usageStats[0].packageName
        for (stat in usageStats) {
            val app = stat.packageName
            try {
                if (isLaunchable(context, app)) {
                    appTasks.add(
                        AppTask(
                            -1,
                            getPackageLabel(context, app),
                            app,
                            context.packageManager.getApplicationIcon(app)
                        )
                    )
                }
            } catch (_: PackageManager.NameNotFoundException) {
            }
            if (appTasks.size >= max) break
        }
        return appTasks
    }

    fun isSystemApp(context: Context, app: String): Boolean {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(app, 0)
            appInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun isLaunchable(context: Context, app: String): Boolean {
        val resolveInfo = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(app), 0
        )
        return resolveInfo.isNotEmpty()
    }

    fun getPackageLabel(context: Context, packageName: String): String {
        try {
            val packageManager = context.packageManager
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            return packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
        }
        return ""
    }

    fun getAppIcon(context: Context, app: String): Drawable {
        return try {
            context.packageManager.getApplicationIcon(app)
        } catch (_: PackageManager.NameNotFoundException) {
            AppCompatResources.getDrawable(context, android.R.drawable.sym_def_app_icon)!!
        }
    }

    private fun makeLaunchBounds(
        context: Context, mode: String, dockHeight: Int, displayId: Int = Display.DEFAULT_DISPLAY
    ): Rect {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        var left = 0
        var top = 0
        var right = 0
        var bottom = 0
        val deviceWidth = DeviceUtils.getDisplayMetrics(context, displayId).widthPixels
        val deviceHeight = DeviceUtils.getDisplayMetrics(context, displayId).heightPixels
        val statusHeight = DeviceUtils.getStatusBarHeight(context)
        val navHeight = DeviceUtils.getNavBarHeight(context)
        val diff = if (dockHeight - navHeight > 0) dockHeight - navHeight else 0

        val usableHeight =
            if (DeviceUtils.shouldApplyNavbarFix()) deviceHeight - diff - DeviceUtils.getStatusBarHeight(
                context
            )
            else deviceHeight - dockHeight - DeviceUtils.getStatusBarHeight(context)
        val scaleFactor = sharedPreferences.getString("scale_factor", "1.0")!!.toFloat()
        when (mode) {
            "standard" -> {
                left = (deviceWidth / (5 * scaleFactor)).toInt()
                top = ((usableHeight + statusHeight) / (7 * scaleFactor)).toInt()
                right = deviceWidth - left
                bottom = usableHeight + dockHeight - top
            }

            "maximized" -> {
                right = deviceWidth
                bottom = usableHeight
            }

            "portrait" -> {
                left = deviceWidth / 3
                top = usableHeight / 15
                right = deviceWidth - left
                bottom = usableHeight + dockHeight - top
            }

            "tiled-left" -> {
                right = deviceWidth / 2
                bottom = usableHeight
            }

            "tiled-top" -> {
                right = deviceWidth
                bottom = (usableHeight + statusHeight) / 2
            }

            "tiled-right" -> {
                left = deviceWidth / 2
                right = deviceWidth
                bottom = usableHeight
            }

            "tiled-bottom" -> {
                right = deviceWidth
                top = (usableHeight + statusHeight) / 2
                bottom = usableHeight + statusHeight
            }
        }
        return Rect(left, top, right, bottom)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════════════
    // WINDOWING MODE — Android 8 (API 26) to Android 16 (API 36)
    // ═══════════════════════════════════════════════════════════════════════
    // Google did not remove FREEFORM mode (5) in Android 16 — it still exists.
    // The issue: the way to request it changed across API levels:
    //
    //   API 26-27 → setLaunchStackId(2)        [Stack-based, pre-Pie]
    //   API 28-35 → setLaunchWindowingMode(5)   [reflection on public method]
    //   API 36    → same method but Google added setLaunchAdjacentFlagOverride
    //               as an additional hint. setLaunchBounds still works.
    //
    // No Shizuku needed — we try each approach in order until one succeeds.
    // ═══════════════════════════════════════════════════════════════════════

    private const val WINDOWING_MODE_FULLSCREEN = 1
    private const val WINDOWING_MODE_FREEFORM   = 5

    fun makeActivityOptions(
        context: Context, mode: String, dockHeight: Int, displayId: Int
    ): ActivityOptions {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        val secondary = sp.getBoolean("prefer_last_display", false)
        val display = when {
            displayId != Display.DEFAULT_DISPLAY -> displayId
            secondary -> DeviceUtils.getSecondaryDisplay(context)?.displayId ?: displayId
            else -> displayId
        }

        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= 26) options.setLaunchDisplayId(display)

        // FULLSCREEN — simple, works on all API levels
        if (mode == "fullscreen") {
            setWindowingMode(options, WINDOWING_MODE_FULLSCREEN)
            return options
        }

        // FREEFORM — setLaunchBounds must be called before setWindowingMode on all API levels
        val bounds = makeLaunchBounds(context, mode, dockHeight, display)
        options.setLaunchBounds(bounds)
        setWindowingMode(options, WINDOWING_MODE_FREEFORM)

        // Android 16 (API 36): Google added setLaunchAdjacentFlagOverride
        // as an additional hint to the system to open the window in floating mode
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                val m = ActivityOptions::class.java.getDeclaredMethod(
                    "setLaunchAdjacentFlagOverride", Boolean::class.javaPrimitiveType)
                m.isAccessible = true
                m.invoke(options, true)
            } catch (_: Exception) {}
            // Android 16: force freeform via hidden flag as extra guarantee
            try {
                val f = ActivityOptions::class.java.getDeclaredField("mLaunchWindowingMode")
                f.isAccessible = true
                f.set(options, WINDOWING_MODE_FREEFORM)
            } catch (_: Exception) {}
        }

        return options
    }

    /**
     * Sets the windowing mode — tries all available approaches in order:
     *
     *   1. setLaunchStackId(2)              → API 26-27
     *   2. getMethod("setLaunchWindowingMode") → API 28+ public
     *   3. getDeclaredMethod(...)              → API 28+ package-private
     *   4. field "mWindowingMode" directly      → last resort on Android 16
     */
    private fun setWindowingMode(options: ActivityOptions, mode: Int) {
        if (Build.VERSION.SDK_INT < 28) {
            try {
                val m = ActivityOptions::class.java.getMethod(
                    "setLaunchStackId", Int::class.javaPrimitiveType)
                m.invoke(options, if (mode == WINDOWING_MODE_FREEFORM) 2 else 1)
            } catch (_: Exception) {}
            return
        }
        // Attempt 1: public getMethod — most compatible, API 28-35
        try {
            val m = ActivityOptions::class.java.getMethod(
                "setLaunchWindowingMode", Int::class.javaPrimitiveType)
            m.invoke(options, mode)
            return
        } catch (_: Exception) {}
        // Attempt 2: getDeclaredMethod — bypasses package-private
        try {
            val m = ActivityOptions::class.java.getDeclaredMethod(
                "setLaunchWindowingMode", Int::class.javaPrimitiveType)
            m.isAccessible = true
            m.invoke(options, mode)
            return
        } catch (_: Exception) {}
        // Attempt 3: internal field — last resort on some OEMs and Android 16
        for (name in listOf("mWindowingMode", "mLaunchWindowingMode", "windowingMode")) {
            try {
                val f = ActivityOptions::class.java.getDeclaredField(name)
                f.isAccessible = true
                f.set(options, mode)
                return
            } catch (_: Exception) {}
        }
    }

    fun buildShellLaunchCommand(
        context: Context, packageName: String,
        mode: String, dockHeight: Int, displayId: Int
    ): String {
        val bounds = makeLaunchBounds(context, mode, dockHeight, displayId)
        val wm = if (mode == "fullscreen") WINDOWING_MODE_FULLSCREEN else WINDOWING_MODE_FREEFORM
        val b = "${bounds.left} ${bounds.top} ${bounds.right} ${bounds.bottom}"
        val component = context.packageManager
            .getLaunchIntentForPackage(packageName)
            ?.component?.flattenToString() ?: return ""
        return "am start -n $component --windowingMode $wm --display $displayId --launch-bounds \"$b\""
    }

        fun resizeTask(context: Context, mode: String, taskId: Int, dockHeight: Int) {
        if (taskId < 0) return
        val bounds = makeLaunchBounds(context, mode, dockHeight)
        DeviceUtils.runAsRoot(
            "am task resize " + taskId + " " + bounds.left + " " + bounds.top + " " + bounds.right + " " + bounds.bottom
        )
    }

    fun containsTask(apps: ArrayList<DockApp>, task: AppTask): Int {
        for (i in apps.indices) {
            if (apps[i].packageName == task.packageName) return i
        }
        return -1
    }

    fun isMediaNotification(notification: Notification) =
        notification.extras[Notification.EXTRA_TEMPLATE].toString() == "android.app.Notification\$MediaStyle"

    fun uninstallApp(context: Context, packageName: String) {
        if (isSystemApp(context, packageName))
            DeviceUtils.runAsRoot("pm uninstall --user 0 $packageName")
        else context.startActivity(
            Intent(
                Intent.ACTION_UNINSTALL_PACKAGE,
                "package:$packageName".toUri()
            )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    //  Dock Size Presets 
    data class DockSizeConfig(
        val dockHeightDp: Int,
        val iconSizeDp: Int,
        val gridSizeDp: Int,
 /** true (PC mode — ) */
        val useSystemDensity: Boolean
    ) {
        fun toHeightPx(context: Context) =
            if (useSystemDensity) Utils.dpToPxSystem(dockHeightDp)
            else Utils.dpToPx(context, dockHeightDp)

        fun toGridSizePx(context: Context) =
            if (useSystemDensity) Utils.dpToPxSystem(gridSizeDp)
            else Utils.dpToPx(context, gridSizeDp)

        fun toIconSizePx(context: Context) =
            if (useSystemDensity) Utils.dpToPxSystem(iconSizeDp)
            else Utils.dpToPx(context, iconSizeDp)
    }

    fun getDockSizeConfig(
        context: Context,
        prefs: android.content.SharedPreferences
    ): DockSizeConfig = when (prefs.getString("dock_size_preset", "normal")) {
        "small" -> DockSizeConfig(40, 34, 42, false)
        "pc"    -> DockSizeConfig(30, 24, 32, true)   // Display Size
        else    -> DockSizeConfig(
            prefs.getString("dock_height", "56")!!.toInt(),
            50, 52, false
        )
    }
}
