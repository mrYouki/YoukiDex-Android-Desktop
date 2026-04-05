package com.youki.dex.activities

import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewSwitcher
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.youki.dex.R
import com.youki.dex.dialogs.DockLayoutDialog
import com.youki.dex.fragments.PreferencesFragment
import com.youki.dex.services.NotificationService
import com.youki.dex.utils.ShizukoManager
import com.youki.dex.utils.RootManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.youki.dex.utils.ColorUtils
import com.youki.dex.utils.DeviceUtils
import kotlin.reflect.KFunction0
import androidx.core.net.toUri
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon

class MainActivity : AppCompatActivity() {

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var permissionsDialog: AlertDialog
    private lateinit var overlayBtn:         MaterialButton
    private lateinit var storageBtn:         MaterialButton
    private lateinit var adminBtn:           MaterialButton
    private lateinit var notificationsBtn:   MaterialButton
    private lateinit var accessibilityBtn:   MaterialButton
    private lateinit var settingsOverlays:   MaterialButton
    private lateinit var recentAppsBtn:      MaterialButton
    private lateinit var secureBtn:          MaterialButton
    private lateinit var defaultLauncherBtn: MaterialButton
    private lateinit var shizukuBtn:         MaterialButton

    private var canDrawOverOtherApps       = false
    private var hasStoragePermission       = false
    private var isDeviceAdminEnabled       = false
    private var settingsOverlaysAllowed    = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        supportFragmentManager.beginTransaction()
            .replace(R.id.settings_container, PreferencesFragment())
            .commit()
        if (!DeviceUtils.hasStoragePermission(this))
            DeviceUtils.requestStoragePermissions(this)
        if (!DeviceUtils.canDrawOverOtherApps(this) || !DeviceUtils.isAccessibilityServiceEnabled(this))
            showPermissionsDialog()
        if (sharedPreferences.getInt("dock_layout", -1) == -1)
            DockLayoutDialog(this)

        // Show "Add desktop shortcut" button if it has not been added yet
        if (!sharedPreferences.getBoolean("shortcut_pinned", false)) {
            pinShortcutToHomeScreen()
        }
    }

    private fun pinShortcutToHomeScreen() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val sm = getSystemService(ShortcutManager::class.java) ?: return
        if (!sm.isRequestPinShortcutSupported) return

        val shortcutIntent = Intent(this, DesktopOverlayActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        val shortcutInfo = ShortcutInfo.Builder(this, "youki_desktop_shortcut")
            .setShortLabel("Youki DEX")
            .setLongLabel("Youki Desktop Mode")
            .setIcon(Icon.createWithResource(this, R.mipmap.ic_youki))
            .setIntent(shortcutIntent)
            .build()

        sm.requestPinShortcut(shortcutInfo, null)
        sharedPreferences.edit().putBoolean("shortcut_pinned", true).apply()
    }

    override fun onResume() {
        super.onResume()
        if (::permissionsDialog.isInitialized && permissionsDialog.isShowing)
            updatePermissionsStatus()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_grant_permissions -> showPermissionsDialog()
        }
        return super.onOptionsItemSelected(item)
    }

    // Permissions Dialog
    private fun showPermissionsDialog() {
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle(R.string.manage_permissions)
        val view        = layoutInflater.inflate(R.layout.dialog_permissions, null)
        val viewSwitcher = view.findViewById<ViewSwitcher>(R.id.permissions_view_switcher)
        val requiredBtn  = view.findViewById<Button>(R.id.show_required_button)
        val optionalBtn  = view.findViewById<Button>(R.id.show_optional_button)
        overlayBtn         = view.findViewById(R.id.btn_grant_overlay)
        storageBtn         = view.findViewById(R.id.btn_grant_storage)
        adminBtn           = view.findViewById(R.id.btn_grant_admin)
        notificationsBtn   = view.findViewById(R.id.btn_grant_notifications)
        accessibilityBtn   = view.findViewById(R.id.btn_manage_service)
        settingsOverlays   = view.findViewById(R.id.btn_manage_settings_overlays)
        recentAppsBtn      = view.findViewById(R.id.btn_manage_recent_apps)
        secureBtn          = view.findViewById(R.id.btn_manage_secure)
        defaultLauncherBtn = view.findViewById(R.id.btn_set_default_launcher)
        shizukuBtn         = view.findViewById(R.id.btn_manage_shizuku)
        builder.setView(view)
        permissionsDialog = builder.create()

        overlayBtn.setOnClickListener {
            showPermissionInfoDialog(R.string.display_over_other_apps,
                R.string.display_over_other_apps_desc,
                ::grantOverlayPermissions, canDrawOverOtherApps)
        }
        storageBtn.setOnClickListener {
            showPermissionInfoDialog(R.string.storage, R.string.storage_desc,
                ::requestStoragePermissions, hasStoragePermission)
        }
        adminBtn.setOnClickListener {
            showPermissionInfoDialog(R.string.device_administrator,
                R.string.device_administrator_desc,
                ::requestDeviceAdminPermissions, isDeviceAdminEnabled)
        }
        notificationsBtn.setOnClickListener  { showNotificationsDialog() }
        accessibilityBtn.setOnClickListener  { showAccessibilityDialog() }
        settingsOverlays.setOnClickListener  {
            showPermissionInfoDialog(R.string.overlays_in_settings,
                R.string.overlays_in_settings_desc, null, true)
        }
        recentAppsBtn.setOnClickListener {
            showPermissionInfoDialog(R.string.recent_apps, R.string.recent_apps_desc,
                ::requestRecentAppsPermission, DeviceUtils.hasRecentAppsPermission(this))
        }
        // WRITE_SECURE_SETTINGS - auto-grant if Shizuku ready, else show dialog
        secureBtn.setOnClickListener { handleWriteSecureSettings() }
        // Shizuku - request permission
        shizukuBtn.setOnClickListener { showShizukuDialog() }
        defaultLauncherBtn.setOnClickListener { showDefaultLauncherDialog() }

        requiredBtn.setOnClickListener { viewSwitcher.showPrevious() }
        optionalBtn.setOnClickListener { viewSwitcher.showNext() }
        updatePermissionsStatus()

        permissionsDialog.show()
    }

    // Auto-grant WRITE_SECURE_SETTINGS — priority: Root → Shizuku
    private fun handleWriteSecureSettings() {
        val root    = RootManager.getInstance(this)
        val shizuko = ShizukoManager.getInstance(this)
        val pkg     = packageName
        when {
            DeviceUtils.hasWriteSettingsPermission(this) -> {
                Toast.makeText(this, "WRITE_SECURE_SETTINGS already granted", Toast.LENGTH_SHORT).show()
                updatePermissionsStatus()
            }
            root.isAvailable -> {
                root.grantWriteSecureSettings(pkg) { result ->
                    runOnUiThread {
                        Toast.makeText(this, result.take(80), Toast.LENGTH_LONG).show()
                        updatePermissionsStatus()
                    }
                }
            }
            shizuko.hasPermission -> {
                shizuko.grantWriteSecureSettings(pkg) { result ->
                    runOnUiThread {
                        Toast.makeText(this, result.take(80), Toast.LENGTH_LONG).show()
                        updatePermissionsStatus()
                    }
                }
            }
            else -> showSetupDialog()
        }
    }

        // WRITE_SECURE_SETTINGS setup dialog — Root → Shizuku

    private lateinit var setupLogTv:      TextView
    private lateinit var setupScrollView: ScrollView
    private lateinit var setupStatusTv:   TextView
    private lateinit var setupActionBtn:  MaterialButton

    private fun showSetupDialog() {
        val shizuko = ShizukoManager.getInstance(this)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 24)
        }

        setupStatusTv = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, 16)
        }

        val logLabel = TextView(this).apply {
            text = "Log:"
            textSize = 12f
            setTextColor(0xFF888888.toInt())
        }

        setupLogTv = TextView(this).apply {
            textSize = 12f
            setPadding(16, 8, 16, 8)
            setTextColor(0xFF00E676.toInt())
            setBackgroundColor(0xFF1A1A1A.toInt())
        }
        setupScrollView = ScrollView(this).apply {
            minimumHeight = 280
            addView(setupLogTv)
        }

        setupActionBtn = MaterialButton(this).apply {
            setPadding(0, 24, 0, 0)
        }

        layout.apply {
            addView(setupStatusTv)
            addView(setupActionBtn)
            addView(logLabel)
            addView(setupScrollView)
        }

        fun appendLog(msg: String) {
            runOnUiThread {
                setupLogTv.append("$msg\n")
                setupScrollView.post { setupScrollView.fullScroll(android.view.View.FOCUS_DOWN) }
            }
        }

        // Check Root first (async so UI doesn't freeze)
        val root = RootManager.getInstance(this)
        root.onLog = { msg -> appendLog(msg) }
        CoroutineScope(Dispatchers.IO).launch {
            val hasRoot = root.isAvailable
            withContext(Dispatchers.Main) {
                if (hasRoot) {
                    setupStatusTv.text = "Root available (Magisk/KernelSU) ✓"
                    setupStatusTv.setTextColor(0xFF4CAF50.toInt())
                    setupActionBtn.text = "Grant WRITE_SECURE_SETTINGS via Root"
                    setupActionBtn.isEnabled = true
                    setupActionBtn.setOnClickListener {
                        root.grantWriteSecureSettings(packageName) { result ->
                            appendLog(result); updatePermissionsStatus()
                        }
                    }
                } else {
                    refreshSetupState(shizuko)
                }
            }
        }

        refreshSetupState(shizuko)

        // Shizuku callbacks
        shizuko.onLog     = { msg -> appendLog(msg) }
        shizuko.onGranted = {
            runOnUiThread {
                if (!root.isAvailable) {
                    setupStatusTv.text = "Shizuku ready"
                    setupStatusTv.setTextColor(0xFFFFB300.toInt())
                    setupActionBtn.text = "Grant WRITE_SECURE_SETTINGS"
                    setupActionBtn.isEnabled = true
                    setupActionBtn.setOnClickListener {
                        shizuko.grantWriteSecureSettings(packageName) { result ->
                            appendLog(result); updatePermissionsStatus()
                        }
                    }
                }
            }
            updatePermissionsStatus()
        }
        shizuko.onDenied = {
            runOnUiThread {
                if (!root.isAvailable) {
                    setupStatusTv.text = "Shizuku permission denied"
                    setupStatusTv.setTextColor(0xFFEF5350.toInt())
                    setupActionBtn.isEnabled = true
                    setupActionBtn.text = "Retry"
                    setupActionBtn.setOnClickListener { shizuko.requestPermission() }
                }
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("WRITE_SECURE_SETTINGS")
            .setView(layout)
            .setNegativeButton(R.string.ok) { _, _ ->
                shizuko.onLog     = null
                shizuko.onGranted = null
                shizuko.onDenied  = null
            }
            .show()
    }

    private fun refreshSetupState(shizuko: ShizukoManager) {
        val root = RootManager.getInstance(this)
        when {
            root.isAvailable -> {
                setupStatusTv.text = "Root available (Magisk/KernelSU) ✓"
                setupStatusTv.setTextColor(0xFF4CAF50.toInt())
                setupActionBtn.text = "Grant WRITE_SECURE_SETTINGS via Root"
                setupActionBtn.isEnabled = true
                setupActionBtn.setOnClickListener {
                    root.onLog = { msg -> runOnUiThread { setupLogTv.append("$msg\n") } }
                    root.grantWriteSecureSettings(packageName) { result ->
                        runOnUiThread { setupLogTv.append("$result\n"); updatePermissionsStatus() }
                    }
                }
            }
            shizuko.hasPermission -> {
                setupStatusTv.text = "Shizuku ready - tap to grant"
                setupStatusTv.setTextColor(0xFFFFB300.toInt())
                setupActionBtn.text = "Grant WRITE_SECURE_SETTINGS"
                setupActionBtn.isEnabled = true
                setupActionBtn.setOnClickListener {
                    shizuko.grantWriteSecureSettings(packageName) { result ->
                        runOnUiThread { setupLogTv.append("$result\n"); updatePermissionsStatus() }
                    }
                }
            }
            else -> {
                setupStatusTv.text = "No Root or Shizuku found"
                setupStatusTv.setTextColor(0xFFEF5350.toInt())
                setupActionBtn.text = "Request Shizuku Permission"
                setupActionBtn.isEnabled = true
                setupActionBtn.setOnClickListener { shizuko.requestPermission() }
            }
        }
    }

    // Shizuku Dialog
    private fun showShizukuDialog() {
        val shizuko = ShizukoManager.getInstance(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.shizuku)
            .setMessage(R.string.shizuku_desc)
            .setPositiveButton(
                if (shizuko.hasPermission) R.string.ok else R.string.grant
            ) { _, _ ->
                if (!shizuko.hasPermission) shizuko.requestPermission()
                else updatePermissionsStatus()
            }
            .setNeutralButton(R.string.shizuku_open) { _, _ ->
                try {
                    startActivity(
                        packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                            ?: Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = android.net.Uri.parse("package:moe.shizuku.privileged.api")
                            }
                    )
                } catch (_: Exception) {}
            }
            .show()
    }

    // Default Launcher
    private fun showDefaultLauncherDialog() {
        val isDefault = isDefaultLauncher()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.default_launcher)
            .setMessage(R.string.default_launcher_desc)
            .setPositiveButton(if (isDefault) R.string.ok else R.string.grant) { _, _ ->
                if (!isDefault) openDefaultLauncherSettings()
            }
            .show()
    }

    private fun isDefaultLauncher(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val info = packageManager.resolveActivity(intent, 0)
        return info?.activityInfo?.packageName == packageName
    }

    private fun openDefaultLauncherSettings() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val roleManager = getSystemService(android.app.role.RoleManager::class.java)
                if (roleManager.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME) &&
                    !roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)) {
                    startActivityForResult(roleManager.createRequestRoleIntent(
                        android.app.role.RoleManager.ROLE_HOME), 99)
                    return
                }
            }
        } catch (_: Exception) {}
        startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // Permission Helpers
    private fun grantOverlayPermissions()        = DeviceUtils.grantOverlayPermissions(this)
    private fun requestStoragePermissions()      = DeviceUtils.requestStoragePermissions(this)
    private fun requestDeviceAdminPermissions()  = DeviceUtils.requestDeviceAdminPermissions(this)
    private fun requestRecentAppsPermission()    = startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))

    private fun updatePermissionsStatus() {
        canDrawOverOtherApps = DeviceUtils.canDrawOverOtherApps(this)
        accessibilityBtn.isEnabled = canDrawOverOtherApps
        val accent = ColorStateList.valueOf(ColorUtils.getThemeColors(this, false)[0])
        val warn   = ColorStateList.valueOf(ColorUtils.getThemeColors(this, false)[2])

        if (canDrawOverOtherApps) {
            overlayBtn.setIconResource(R.drawable.ic_granted); overlayBtn.iconTint = accent
        }
        if (DeviceUtils.isAccessibilityServiceEnabled(this)) {
            accessibilityBtn.setIconResource(R.drawable.ic_settings); accessibilityBtn.iconTint = accent
        } else {
            accessibilityBtn.setIconResource(R.drawable.ic_alert); accessibilityBtn.iconTint = warn
        }
        if (DeviceUtils.hasRecentAppsPermission(this)) {
            recentAppsBtn.setIconResource(R.drawable.ic_granted); recentAppsBtn.iconTint = accent
        }
        if (DeviceUtils.isServiceRunning(this, NotificationService::class.java)) {
            notificationsBtn.setIconResource(R.drawable.ic_settings); notificationsBtn.iconTint = accent
        }
        isDeviceAdminEnabled = DeviceUtils.isDeviceAdminEnabled(this)
        if (isDeviceAdminEnabled) {
            adminBtn.setIconResource(R.drawable.ic_granted); adminBtn.iconTint = accent
        }
        hasStoragePermission = DeviceUtils.hasStoragePermission(this)
        if (hasStoragePermission) {
            storageBtn.setIconResource(R.drawable.ic_granted); storageBtn.iconTint = accent
        }
        settingsOverlaysAllowed = DeviceUtils.getSettingsOverlaysAllowed(this)
        if (settingsOverlaysAllowed) {
            settingsOverlays.setIconResource(R.drawable.ic_granted); settingsOverlays.iconTint = accent
        }
        if (DeviceUtils.hasWriteSettingsPermission(this)) {
            secureBtn.setIconResource(R.drawable.ic_granted); secureBtn.iconTint = accent
        }
        val shizuko = ShizukoManager.getInstance(this)
        if (::shizukuBtn.isInitialized) {
            if (shizuko.hasPermission) {
                shizukuBtn.setIconResource(R.drawable.ic_granted); shizukuBtn.iconTint = accent
            } else {
                shizukuBtn.setIconResource(R.drawable.ic_alert); shizukuBtn.iconTint = warn
            }
        }
        if (::defaultLauncherBtn.isInitialized) {
            if (isDefaultLauncher()) {
                defaultLauncherBtn.setIconResource(R.drawable.ic_granted); defaultLauncherBtn.iconTint = accent
            } else {
                defaultLauncherBtn.setIconResource(R.drawable.ic_alert); defaultLauncherBtn.iconTint = warn
            }
        }
    }

    private fun showPermissionInfoDialog(permission: Int, description: Int,
        grantMethod: KFunction0<Unit>?, granted: Boolean) {
        val db = MaterialAlertDialogBuilder(this)
        db.setTitle(permission); db.setMessage(description)
        if (!granted) db.setPositiveButton(R.string.grant) { _, _ -> grantMethod!!.invoke() }
        else db.setPositiveButton(R.string.ok, null)
        db.show()
    }

    private fun showAccessibilityDialog() {
        val db = MaterialAlertDialogBuilder(this)
        db.setTitle(R.string.accessibility_service)
        db.setMessage(R.string.accessibility_service_desc)
        if (DeviceUtils.hasWriteSettingsPermission(this)) {
            db.setPositiveButton(R.string.enable) { _, _ ->
                DeviceUtils.enableService(this)
                Handler(mainLooper).postDelayed({ updatePermissionsStatus() }, 500)
            }
            db.setNegativeButton(R.string.disable) { _, _ ->
                DeviceUtils.disableService(this)
                Handler(mainLooper).postDelayed({ updatePermissionsStatus() }, 500)
            }
        } else {
            db.setPositiveButton(R.string.manage) { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this, R.string.enable_access_help, Toast.LENGTH_LONG).show()
            }
        }
        db.setNeutralButton(R.string.help) { _, _ ->
            startActivity(Intent(Intent.ACTION_VIEW,
                "https://github.com/axel358/smartdock#grant-restricted-permissions".toUri()))
        }
        db.show()
    }

    private fun showNotificationsDialog() {
        val db = MaterialAlertDialogBuilder(this)
        db.setTitle(R.string.notification_access)
        db.setMessage(R.string.notification_access_desc)
        db.setPositiveButton(R.string.manage) { _, _ ->
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            Toast.makeText(this, R.string.enable_access_help, Toast.LENGTH_LONG).show()
        }
        db.setNeutralButton(R.string.help) { _, _ ->
            startActivity(Intent(Intent.ACTION_VIEW,
                "https://github.com/mrYouki/YoukiDex-Android-Desktop#grant-restricted-permissions".toUri()))
        }
        db.show()
    }
}