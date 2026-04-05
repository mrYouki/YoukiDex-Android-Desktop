package com.youki.dex.utils

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.lang.reflect.Method

/**
 * ShizukoManager - Shizuku-based privileged shell bridge.
 * Replaces wireless ADB. User runs Shizuku once via ADB, then everything is automatic.
 */
class ShizukoManager private constructor(private val context: Context) {

    companion object {
        const val REQUEST_CODE = 0x5317

        @Volatile private var instance: ShizukoManager? = null
        fun getInstance(ctx: Context) =
            instance ?: synchronized(this) {
                instance ?: ShizukoManager(ctx.applicationContext).also { instance = it }
            }
    }

    var onLog:     ((String) -> Unit)? = null
    var onGranted: (() -> Unit)?       = null
    var onDenied:  (() -> Unit)?       = null
    var onBound:   (() -> Unit)?       = null
    var onUnbound: (() -> Unit)?       = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        log("Shizuku available")
        CoroutineScope(Dispatchers.Main).launch { onBound?.invoke() }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        log("Shizuku disconnected")
        CoroutineScope(Dispatchers.Main).launch { onUnbound?.invoke() }
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, result ->
            if (result == PackageManager.PERMISSION_GRANTED) {
                log("Shizuku permission granted")
                CoroutineScope(Dispatchers.Main).launch { onGranted?.invoke() }
            } else {
                log("Shizuku permission denied")
                CoroutineScope(Dispatchers.Main).launch { onDenied?.invoke() }
            }
        }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
    }

    val isAvailable: Boolean
        get() = try { Shizuku.pingBinder() } catch (_: Exception) { false }

    val hasPermission: Boolean
        get() = try {
            isAvailable && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { false }

    fun requestPermission() {
        when {
            !isAvailable  -> log("Shizuku is not installed or not running")
            hasPermission -> {
                log("Permission already granted")
                CoroutineScope(Dispatchers.Main).launch { onGranted?.invoke() }
            }
            else -> {
                log("Requesting Shizuku permission...")
                try { Shizuku.requestPermission(REQUEST_CODE) }
                catch (e: Exception) { log("Error: ${e.message}") }
            }
        }
    }

    // newProcess became private in newer Shizuku versions - use reflection
    @Suppress("DiscouragedPrivateApi")
    private fun newProcessViaReflection(cmd: String): ShizukuRemoteProcess {
        val method: Method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as ShizukuRemoteProcess
    }

    fun runShell(cmd: String, onResult: (String) -> Unit) {
        if (!hasPermission) { onResult("No Shizuku permission. Start Shizuku first."); return }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val process = newProcessViaReflection(cmd)
                val stdout  = process.inputStream.bufferedReader().readText()
                val stderr  = process.errorStream.bufferedReader().readText()
                process.waitFor()
                val result = buildString {
                    if (stdout.isNotBlank()) append(stdout.trim())
                    if (stderr.isNotBlank()) { if (isNotEmpty()) append("\n"); append(stderr.trim()) }
                    if (isEmpty()) append("(no output)")
                }
                withContext(Dispatchers.Main) { onResult(result) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult("Execution error: ${e.message}") }
            }
        }
    }

    fun grantWriteSecureSettings(packageName: String, onResult: (String) -> Unit) {
        log("Granting WRITE_SECURE_SETTINGS to $packageName")
        runShell("pm grant $packageName android.permission.WRITE_SECURE_SETTINGS") { result ->
            log(result); onResult(result)
        }
    }

    fun grantWriteSettings(packageName: String, onResult: (String) -> Unit) {
        log("Granting WRITE_SETTINGS to $packageName")
        runShell("pm grant $packageName android.permission.WRITE_SETTINGS") { result ->
            log(result); onResult(result)
        }
    }

    fun destroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
    }

    private fun log(msg: String) {
        CoroutineScope(Dispatchers.Main).launch { onLog?.invoke(msg) }
    }
}
