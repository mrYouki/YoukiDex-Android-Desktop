package com.youki.dex.server

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

/**
 * ShellServer — privileged process app_process
 *
 * Shizuku:
 *  adb shell CLASSPATH=/data/app/com.youki.dex-.../base.apk \
 *    app_process /system/bin com.youki.dex.server.ShellServer
 *
 * uid=2000 (shell) — ADB
 * Unix socket port 7171 
 */
object ShellServer {

    private const val PORT = 7171
    private const val MAGIC = "YOUKI_SHELL_V1"

    @JvmStatic
    fun main(args: Array<String>) {
        println("[$MAGIC] ShellServer starting... uid=${getUid()}")

        //
        val uid = getUid()
        if (uid != 0 && uid != 2000) {
            System.err.println("[$MAGIC] ERROR: Must run as root (0) or shell (2000), got uid=$uid")
            System.exit(1)
        }

        println("[$MAGIC] Running as uid=$uid ")
        println("[$MAGIC] Listening on port $PORT...")

        try {
            val server = ServerSocket(PORT)
            println("[$MAGIC] Ready! Waiting for connections...")

            // connections
            Runtime.getRuntime().exec(arrayOf(
                "am", "broadcast",
                "-a", "com.youki.dex.SHELL_SERVER_READY",
                "--receiver-include-background"
            ))

            while (true) {
                try {
                    val client = server.accept()
                    println("[$MAGIC] Client connected: ${client.inetAddress}")
                    Thread { handleClient(client) }.start()
                } catch (e: Exception) {
                    System.err.println("[$MAGIC] Accept error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            System.err.println("[$MAGIC] Fatal: ${e.message}")
            System.exit(1)
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)

            // Handshake
            writer.println(MAGIC)

            while (!socket.isClosed) {
                val line = reader.readLine() ?: break

                when {
                    line == "PING" -> writer.println("PONG")
                    line == "UID"  -> writer.println("uid=${getUid()}")
                    line == "EXIT" -> { writer.println("BYE"); break }
                    line.startsWith("CMD:") -> {
                        val cmd = line.removePrefix("CMD:")
                        val result = runCommand(cmd)
                        // DONE
                        writer.println(result)
                        writer.println("__DONE__")
                    }
                    else -> writer.println("ERR:Unknown command")
                }
            }
        } catch (e: Exception) {
            System.err.println("[$MAGIC] Client error: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun runCommand(cmd: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            process.waitFor()
            buildString {
                if (stdout.isNotBlank()) append(stdout.trim())
                if (stderr.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append(stderr.trim())
                }
                if (isEmpty()) append("(no output)")
            }
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    private fun getUid(): Int {
        return try {
            Runtime.getRuntime().exec(arrayOf("id", "-u"))
                .inputStream.bufferedReader().readText().trim().toInt()
        } catch (_: Exception) { -1 }
    }
}
