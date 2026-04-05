package com.youki.dex.cast

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.mediarouter.app.MediaRouteChooserDialog
import com.google.android.gms.cast.framework.CastButtonFactory

/**
 * CastPickerActivity
 *
 * Fully transparent Activity — its sole purpose is to show the Cast device picker dialog.
 * Needed because MediaRouteChooserDialog requires an Activity context,
 * and dialogs cannot be opened directly from a Service.
 *
 * Closes itself as soon as the dialog is dismissed.
 */
class CastPickerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fully transparent — no layout needed
        window.setBackgroundDrawableResource(android.R.color.transparent)

        showCastDialog()
    }

    private fun showCastDialog() {
        try {
            val dialog = MediaRouteChooserDialog(this)
            dialog.routeSelector = com.google.android.gms.cast.framework.CastContext
                .getSharedInstance(this)
                .mergedSelector ?: run { finish(); return }

            dialog.setOnDismissListener { finish() }
            dialog.show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                this,
                "Cast not available on this device",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            finish()
        }
    }
}
