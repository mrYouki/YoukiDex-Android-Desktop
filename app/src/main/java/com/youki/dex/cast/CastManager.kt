package com.youki.dex.cast

import android.content.Context
import android.util.Log
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.CastStateListener
import com.google.android.gms.cast.framework.SessionManagerListener
import java.util.concurrent.Executors

/**
 * CastManager
 *
 * Dock Cast manager — works without an Activity (from a Service).
 *
 * Usage:
 *   val castManager = CastManager(context)
 *   castManager.init()
 *   castManager.onStateChanged = { isConnected -> updateCastButton(isConnected) }
 *   castManager.toggleCastDialog(activity)   // opens the device picker dialog
 *
 * Note: CastContext requires the main thread for initialization.
 */
class CastManager(private val context: Context) {

    companion object {
        private const val TAG = "CastManager"
    }

    private var castContext: CastContext? = null
    private var currentSession: CastSession? = null

    /** callback — invoked when the connection state changes */
    var onStateChanged: ((isConnected: Boolean) -> Unit)? = null

    private val castStateListener = CastStateListener { newState ->
        val connected = newState == CastState.CONNECTED
        Log.d(TAG, "Cast state → $newState (connected=$connected)")
        onStateChanged?.invoke(connected)
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            currentSession = session
            onStateChanged?.invoke(true)
            Log.d(TAG, "Session started: $sessionId")
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            currentSession = null
            onStateChanged?.invoke(false)
            Log.d(TAG, "Session ended (error=$error)")
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            currentSession = session
            onStateChanged?.invoke(true)
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            currentSession = null
            onStateChanged?.invoke(false)
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            onStateChanged?.invoke(false)
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            currentSession = null
            onStateChanged?.invoke(false)
            Log.w(TAG, "Session start failed (error=$error)")
        }

        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
    }

    /** Must be called on the main thread */
    fun init() {
        try {
            castContext = CastContext.getSharedInstance(context, Executors.newSingleThreadExecutor())
                .result
            castContext?.addCastStateListener(castStateListener)
            castContext?.sessionManager?.addSessionManagerListener(sessionListener, CastSession::class.java)
            Log.d(TAG, "CastContext initialized ✅")
        } catch (e: Exception) {
            // Device does not support Google Play Services — Cast unavailable
            Log.w(TAG, "CastContext init failed (no Play Services?): ${e.message}")
            castContext = null
        }
    }

    /** Is Cast currently connected? */
    val isConnected: Boolean
        get() = castContext?.castState == CastState.CONNECTED

    /** Is a Cast device nearby? */
    val isAvailable: Boolean
        get() = castContext?.castState?.let {
            it == CastState.NOT_CONNECTED || it == CastState.CONNECTING || it == CastState.CONNECTED
        } ?: false

    /**
     * Opens the Cast dialog to select a device.
     * Requires an Activity — we use startActivity from Service context with FLAG_ACTIVITY_NEW_TASK.
     */
    fun showCastPicker(context: Context) {
        try {
            val intent = android.content.Intent(context,
                com.youki.dex.cast.CastPickerActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open cast picker: ${e.message}")
        }
    }

    /** Disconnects from the current device */
    fun disconnect() {
        castContext?.sessionManager?.endCurrentSession(true)
    }

    fun destroy() {
        castContext?.removeCastStateListener(castStateListener)
        castContext?.sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
    }
}
