package com.youki.dex.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * ShellManager V2 — Youki DEX
 * .
 */
class ShellManager private constructor(private val context: Context) {

    companion object {
        private const val HOST = "127.0.0.1"
        private const val PORT = 7171
        private const val MAGIC = "YOUKI_SHELL_V1"
        private const val TIMEOUT = 3000 // Timeout

        @Volatile private var instance: ShellManager? = null
        fun getInstance(ctx: Context) =
            instance ?: synchronized(this) {
                instance ?: ShellManager(ctx.applicationContext).also { instance = it }
            }
    }

    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null

    val isConnected get() = socket?.isConnected == true && !socket!!.isClosed

    // Callbacks
    var onLog: ((String) -> Unit)? = null
    var onConnected: ((String) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    // " "
    private val serverReadyReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
 log(" ! ...")
            connect()
        }
    }

    init {
        val filter = IntentFilter("com.youki.dex.SHELL_SERVER_READY")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(serverReadyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(serverReadyReceiver, filter)
        }
    }

    /**
 * 
     */
    fun connect() {
        if (isConnected) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
 log(" $PORT...")
                val s = Socket()
                s.connect(InetSocketAddress(HOST, PORT), TIMEOUT)
                
                val w = PrintWriter(s.getOutputStream(), true)
                val r = BufferedReader(InputStreamReader(s.getInputStream()))

                // (Handshake)
                val hello = r.readLine()
 if (hello != MAGIC) throw Exception(" !")

                //
                w.println("PING")
 if (r.readLine() != "PONG") throw Exception(" !")

                socket = s
                writer = w
                reader = r

 log(" ! .")
                withContext(Dispatchers.Main) { onConnected?.invoke("$HOST:$PORT") }

            } catch (e: Exception) {
 log(" (Connection Refused)")
 log(" : ADB .")
                withContext(Dispatchers.Main) { onDisconnected?.invoke() }
            }
        }
    }

    /**
 * ( )
     */
    fun runShell(cmd: String, onResult: (String) -> Unit) {
        if (!isConnected) {
 onResult(" !")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                writer?.println("CMD:$cmd")
                val result = buildString {
                    while (true) {
                        val line = reader?.readLine() ?: break
                        if (line == "__DONE__") break
                        if (isNotEmpty()) append("\n")
                        append(line)
                    }
                }
 withContext(Dispatchers.Main) { onResult(result.ifBlank { " " }) }
            } catch (e: Exception) {
 log(" : ${e.message}")
                disconnect()
            }
        }
    }

    /**
 * ()
     */
    fun getStartCommand(): String {
        val apkPath = context.packageManager.getApplicationInfo(context.packageName, 0).sourceDir
        return "CLASSPATH=$apkPath app_process /system/bin com.youki.dex.server.ShellServer &"
    }

    fun disconnect() {
        CoroutineScope(Dispatchers.IO).launch {
            try { writer?.println("EXIT") } catch (_: Exception) {}
            socket?.close()
            socket = null
            withContext(Dispatchers.Main) { onDisconnected?.invoke() }
        }
    }

    private fun log(msg: String) {
        CoroutineScope(Dispatchers.Main).launch { onLog?.invoke(msg) }
    }
}