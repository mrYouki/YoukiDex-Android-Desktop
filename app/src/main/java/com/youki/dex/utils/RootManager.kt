package com.youki.dex.utils

import android.content.Context
import kotlinx.coroutines.*
import java.io.DataOutputStream
import java.io.File

/**
 * RootManager - Handles root access detection and command execution.
 *
 * Fixes Magisk detection: modern Magisk (v20+) doesn't place su at a
 * static path — it injects it into PATH at runtime. We verify root by
 * actually running "su -c id" and checking for uid=0.
 *
 * Priority order in the app:  Root  →  Shizuku
 */
class RootManager private constructor(private val context: Context) {

    companion object {
        @Volatile private var instance: RootManager? = null
        fun getInstance(ctx: Context) =
            instance ?: synchronized(this) {
                instance ?: RootManager(ctx.applicationContext).also { instance = it }
            }
    }

    var onLog: ((String) -> Unit)? = null

    // ──────────────────────────────────────────────────────────────
    //  Root detection
    // ──────────────────────────────────────────────────────────────

    /**
     * Checks whether root is actually available by running "su -c id"
     * and verifying the output contains "uid=0".
     *
     * This is the correct way to detect Magisk root, which no longer
     * places su at a predictable static path.
     */
    val isAvailable: Boolean
        get() {
            // 1. Quick Magisk directory hint (no shell needed)
            if (File("/data/adb/magisk").exists() || File("/data/adb/ksu").exists()) {
                // Magisk/KernelSU present — still verify with a real call below
                log("Magisk/KernelSU directory found")
            }

            // 2. Try PATH-based su (modern Magisk, KernelSU, APatch)
            return try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val output = p.inputStream.bufferedReader().readText()
                p.waitFor()
                val granted = output.contains("uid=0")
                log(if (granted) "Root verified (uid=0)" else "su found but not root: $output")
                granted
            } catch (_: Exception) {
                // 3. Fallback: static paths for older roots
                val paths = arrayOf(
                    "/sbin/su",
                    "/system/sbin/su",
                    "/system/bin/su",
                    "/system/xbin/su",
                    "/su/bin/su",
                    "/magisk/.core/bin/su"  // Magisk ≤ v15 legacy path
                )
                val found = paths.any { File(it).canExecute() }
                log(if (found) "Root found via static path" else "No root found")
                found
            }
        }

    // ──────────────────────────────────────────────────────────────
    //  Command execution
    // ──────────────────────────────────────────────────────────────

    /**
     * Runs [command] as root on a background thread, delivering the
     * trimmed stdout+stderr to [onResult] on the main thread.
     */
    fun runShell(command: String, onResult: (String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val output = executeRoot(command)
            withContext(Dispatchers.Main) { onResult(output) }
        }
    }

    /** Blocking root execution — call only from a background thread. */
    fun executeRoot(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()
            os.close()
            val stdout = process.inputStream.bufferedReader().readText().trim()
            val stderr = process.errorStream.bufferedReader().readText().trim()
            process.waitFor()
            buildString {
                if (stdout.isNotBlank()) append(stdout)
                if (stderr.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append(stderr)
                }
                if (isEmpty()) append("(no output)")
            }
        } catch (e: Exception) {
            log("Root execution error: ${e.message}")
            "error"
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  Convenience helpers
    // ──────────────────────────────────────────────────────────────

    fun grantWriteSecureSettings(packageName: String, onResult: (String) -> Unit) {
        log("Granting WRITE_SECURE_SETTINGS via root to $packageName")
        runShell("pm grant $packageName android.permission.WRITE_SECURE_SETTINGS", onResult)
    }

    private fun log(msg: String) {
        CoroutineScope(Dispatchers.Main).launch { onLog?.invoke(msg) }
    }
}
