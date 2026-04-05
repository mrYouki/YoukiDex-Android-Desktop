package com.youki.dex.utils

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import android.os.Build
import java.util.Collections
import android.os.UserHandle
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.accessibility.AccessibilityManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.youki.dex.services.DockService
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import android.os.UserManager
import androidx.core.net.toUri

object DeviceUtils {
    const val DISPLAY_SIZE = "display_density_forced"
    const val ICON_BLACKLIST = "icon_blacklist"
    const val HEADS_UP_ENABLED = "heads_up_notifications_enabled"
    const val ENABLE_TASKBAR = "enable_taskbar"
    const val SETTING_OVERLAYS = "secure_overlay_settings"
    private const val SERVICE_NAME = "com.youki.dex/com.youki.dex.services.DockService"
    private const val ENABLED_ACCESSIBILITY_SERVICES = "enabled_accessibility_services"

    /**
     * Returns a root shell Process.
     *
     * Modern Magisk (v20+) and KernelSU inject `su` into PATH at runtime
     * and do NOT place it at a static path. We therefore prefer the
     * PATH-based lookup first, falling back to static paths for older roots.
     */
    @get:Throws(IOException::class)
    val rootAccess: Process
        get() {
            // 1. PATH-based su — works with Magisk v20+, KernelSU, APatch
            try {
                val test = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val out = test.inputStream.bufferedReader().readText()
                test.waitFor()
                if (out.contains("uid=0")) return Runtime.getRuntime().exec("su")
            } catch (_: Exception) {}

            // 2. Static paths — legacy roots (Magisk ≤ v15, SuperSU, etc.)
            val paths = arrayOf(
                "/sbin/su", "/system/sbin/su", "/system/bin/su",
                "/system/xbin/su", "/su/bin/su", "/magisk/.core/bin/su"
            )
            for (path in paths) {
                if (File(path).canExecute()) return Runtime.getRuntime().exec(path)
            }
            // Last resort — will throw if not present
            return Runtime.getRuntime().exec("su")
        }

    fun runAsRoot(command: String): String {
        val output = StringBuilder()
        try {
            val process = rootAccess
            val os = DataOutputStream(process.outputStream)
            os.writeBytes(command + "\n")
            os.writeBytes("exit\n")
            os.flush()
            os.close()
            val br = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (br.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            br.close()
            process.waitFor()
        } catch (_: IOException) {
            return "error"
        } catch (_: Exception) {
            return "error"
        }
        return output.toString().trimEnd('\n')
    }

    fun hideStatusBar(context: android.content.Context, hide: Boolean) {
        try {
            android.provider.Settings.Global.putString(
                context.contentResolver,
                "policy_control",
                if (hide) "immersive.full=*" else "null"
            )
        } catch (_: Exception) {}
    }

    fun freezeRotation(landscape: Boolean) {
        try {
            val rotation = if (landscape) android.view.Surface.ROTATION_90 else android.view.Surface.ROTATION_0
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "window")
            val stub = Class.forName("android.view.IWindowManager${"$"}Stub")
            val asInterface = stub.getMethod("asInterface", android.os.IBinder::class.java)
            val wm = asInterface.invoke(null, binder)
            if (landscape) {
                val freeze = wm.javaClass.getMethod("freezeRotation", Int::class.javaPrimitiveType)
                freeze.invoke(wm, rotation)
            } else {
                val thaw = wm.javaClass.getMethod("thawRotation")
                thaw.invoke(wm)
            }
        } catch (_: Exception) {}
    }

    //Device control
    fun lockScreen(context: Context) {
        val devicePolicyManager =
            context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        try {
            devicePolicyManager.lockNow()
        } catch (_: SecurityException) {
        }
    }

    fun sendKeyEvent(keycode: Int) {
        runAsRoot("input keyevent $keycode")
    }

    fun softReboot() {
        runAsRoot("setprop ctl.restart zygote")
    }

    fun reboot() {
        runAsRoot("am start -a android.intent.action.REBOOT")
    }

    fun shutdown() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) runAsRoot("am start -a android.intent.action.ACTION_REQUEST_SHUTDOWN") else runAsRoot(
            "am start -a com.android.internal.intent.action.REQUEST_SHUTDOWN"
        )
    }

    fun toggleVolume(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_SAME,
            AudioManager.FLAG_SHOW_UI
        )
    }

    // Strong references to prevent GC from collecting MediaPlayer during playback
    private val activePlayers = Collections.synchronizedSet(mutableSetOf<MediaPlayer>())

    fun playEventSound(context: Context, event: String) {
        // Read absolute path from SharedPrefs (new version stores a path, not a URI)
        val filePath = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(event, null)
            ?.takeIf { it.isNotBlank() }
            ?: return

        // Check that the file exists before attempting playback
        val file = File(filePath)
        if (!file.exists() || !file.canRead()) {
            Log.w("YoukiDex", "Sound file not found or not readable: $filePath")
            return
        }

        try {
            val mp = MediaPlayer()
            activePlayers.add(mp) // ← add before prepareAsync to guarantee the reference is held

            mp.apply {
                // AudioAttributes (API 21+) instead of the deprecated setAudioStreamType
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setAudioStreamType(AudioManager.STREAM_MUSIC)
                }

                // Direct path → no ContentResolver or URI permissions needed
                setDataSource(filePath)

                setOnPreparedListener { player ->
                    player.start()
                }

                setOnCompletionListener { player ->
                    activePlayers.remove(player) // ← remove after completion
                    player.release()
                }

                setOnErrorListener { player, what, extra ->
                    Log.e("YoukiDex", "MediaPlayer error [$event]: what=$what extra=$extra")
                    activePlayers.remove(player)
                    player.release()
                    true
                }

                prepareAsync() // ← async: does not block the UI thread
            }
        } catch (e: Exception) {
            Log.e("YoukiDex", "playEventSound failed [$event]: ${e.message}")
        }
    }

    fun putSystemSetting(context: Context, key: String, value: String): Boolean {
        return try {
            Settings.System.putString(context.contentResolver, key, value)
            true
        } catch (e: SecurityException) {
            Log.e("YoukiDex", "putSystemSetting failed [$key]: ${e.message}")
            false
        }
    }

    fun getSystemSetting(context: Context, key: String, default: String = ""): String {
        return try {
            Settings.System.getString(context.contentResolver, key) ?: default
        } catch (_: Exception) {
            default
        }
    }

    fun getSecureSetting(context: Context, setting: String, defaultValue: Int): Int {
        return try {
            Settings.Secure.getInt(context.contentResolver, setting)
        } catch (_: Exception) {
            defaultValue
        }
    }

    fun getSecureSetting(context: Context, setting: String, defaultValue: String): String {
        return try {
            val value = Settings.Secure.getString(context.contentResolver, setting)
            value ?: defaultValue
        } catch (_: Exception) {
            defaultValue
        }
    }

    fun putSecureSetting(context: Context, setting: String, value: String): Boolean {
        return try {
            Settings.Secure.putString(context.contentResolver, setting, value)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun putGlobalSetting(context: Context, setting: String, value: Int): Boolean {
        return try {
            Settings.Global.putInt(context.contentResolver, setting, value)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun getGlobalSetting(context: Context, setting: String, defaultValue: Int): Int {
        return try {
            Settings.Global.getInt(context.contentResolver, setting)
        } catch (_: Exception) {
            defaultValue
        }
    }

    //Device info
    @SuppressLint("InternalInsetResource", "DiscouragedApi")
    fun getStatusBarHeight(context: Context): Int {
        var result = 0
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = context.resources.getDimensionPixelSize(resourceId)
        }
        return result
    }

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    fun getNavBarHeight(context: Context): Int {
        var result = 0
        val resourceId =
            context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = context.resources.getDimensionPixelSize(resourceId)
        }
        return result
    }

    fun getUserName(context: Context): String? {
        val um = context.getSystemService(Context.USER_SERVICE) as UserManager
        try {
            return um.userName
        } catch (_: Exception) {
        }
        return null
    }

    fun getUserIcon(context: Context): Bitmap? {
        val um = context.getSystemService(Context.USER_SERVICE) as UserManager
        var userIcon: Bitmap? = null
        try {
            val getUserIcon = um.javaClass.getMethod("getUserIcon", Int::class.javaPrimitiveType)
            val myUserId = UserHandle::class.java.getMethod("myUserId")
            val id = myUserId.invoke(UserHandle::class.java) as Int
            userIcon = getUserIcon.invoke(um, id) as Bitmap?
            if (userIcon != null) userIcon = Utils.getCircularBitmap(userIcon)
        } catch (_: Exception) {
        }
        return userIcon
    }

    fun getDisplays(context: Context, category: String? = null): Array<Display> {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return dm.getDisplays(category)
    }

    fun getSecondaryDisplay(context: Context): Display? {
        val displays = getDisplays(context, DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        return if (displays.isNotEmpty()) displays[0] else null
    }

    fun getDisplayMetrics(
        context: Context,
        displayId: Int = Display.DEFAULT_DISPLAY
    ): DisplayMetrics {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(displayId)
        val metrics = DisplayMetrics()
        display.getMetrics(metrics)
        return metrics
    }

    fun getDisplayContext(context: Context, secondary: Boolean = false): Context {
        if (!secondary) return context
        val secondaryDisplay = getSecondaryDisplay(context)
        return if (secondaryDisplay != null) context.createDisplayContext(secondaryDisplay) else context
    }

    @SuppressLint("PrivateApi")
    fun getSystemProp(prop: String): String {
        val systemPropertiesClass = Class.forName("android.os.SystemProperties")
        val getMethod = systemPropertiesClass.getMethod("get", String::class.java)

        return getMethod.invoke(null, prop) as String
    }

    fun isBliss(): Boolean {
        return getSystemProp("ro.bliss.version").isNotEmpty()
    }

    fun shouldApplyNavbarFix(): Boolean {
        return Build.VERSION.SDK_INT > 31 && isNavbarEnabled()
    }

    fun isNavbarEnabled(): Boolean {
        return getSystemProp("qemu.hw.mainkeys") != "1"
    }

    //Permissions
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        for (enabledService in enabledServices) {
            val serviceInfo = enabledService.resolveInfo.serviceInfo
            if (serviceInfo.packageName == context.packageName && serviceInfo.name == DockService::class.java.name) {
                return true
            }
        }
        return false
    }

    fun hasStoragePermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun requestStoragePermissions(context: Activity) {
        ActivityCompat.requestPermissions(
            context,
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            8
        )
    }

    fun hasWriteSettingsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun grantPermission(permission: String): Boolean {
        val result = runAsRoot("pm grant com.youki.dex $permission")
        return result.isEmpty()
    }

    fun grantOverlayPermissions(context: Activity) {
        context.startActivityForResult(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                ("package:" + context.packageName).toUri()
            ),
            8
        )
    }

    fun requestDeviceAdminPermissions(context: Activity) {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        intent.putExtra(
            DevicePolicyManager.EXTRA_DEVICE_ADMIN,
            ComponentName(context, DeviceAdminReceiver::class.java)
        )
        context.startActivityForResult(intent, 8)
    }

    fun isDeviceAdminEnabled(context: Context): Boolean {
        val devicePolicyManager =
            context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val deviceAdmins = devicePolicyManager.activeAdmins
        if (deviceAdmins != null) {
            for (deviceAdmin in deviceAdmins) {
                if (deviceAdmin.packageName == context.packageName) {
                    return true
                }
            }
        }
        return false
    }

    fun hasRecentAppsPermission(context: Context): Boolean {
        return AppUtils.isSystemApp(context, context.packageName) || checkAppOpsPermission(
            context,
            AppOpsManager.OPSTR_GET_USAGE_STATS
        )
    }

    private fun checkAppOpsPermission(context: Context, permission: String): Boolean {
        val packageManager = context.packageManager
        val applicationInfo: ApplicationInfo = try {
            packageManager.getApplicationInfo(context.packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        }
        val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOpsManager.checkOpNoThrow(
            permission,
            applicationInfo.uid,
            applicationInfo.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    //Service control
    fun enableService(context: Context) {
        val services = getSecureSetting(context, ENABLED_ACCESSIBILITY_SERVICES, "")
        if (services.contains(SERVICE_NAME)) return
        val newServices: String =
            if (services.isEmpty()) SERVICE_NAME else "$services:$SERVICE_NAME"
        putSecureSetting(context, ENABLED_ACCESSIBILITY_SERVICES, newServices)
    }

    fun disableService(context: Context) {
        val services = getSecureSetting(context, ENABLED_ACCESSIBILITY_SERVICES, "")
        if (!services.contains(SERVICE_NAME)) return
        var newServices = ""
        if (services.contains("$SERVICE_NAME:")) newServices = services.replace(
            "$SERVICE_NAME:",
            ""
        ) else if (services.contains(":$SERVICE_NAME")) newServices = services.replace(
            ":$SERVICE_NAME",
            ""
        ) else if (services.contains(SERVICE_NAME)) newServices = services.replace(SERVICE_NAME, "")
        putSecureSetting(context, ENABLED_ACCESSIBILITY_SERVICES, newServices)
    }

    fun canDrawOverOtherApps(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    @Suppress("DEPRECATION")
    fun isServiceRunning(context: Context, serviceName: Class<*>): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in activityManager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceName.name == service.service.className) {
                return true
            }
        }
        return false
    }

    fun getSettingsOverlaysAllowed(context: Context): Boolean {
        return getSecureSetting(context, SETTING_OVERLAYS, 0) == 1
    }
}
