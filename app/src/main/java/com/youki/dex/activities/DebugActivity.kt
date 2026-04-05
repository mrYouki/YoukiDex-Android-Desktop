package com.youki.dex.activities

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.youki.dex.R
import com.youki.dex.utils.Utils


class DebugActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dialog = MaterialAlertDialogBuilder(this)
        dialog.setTitle(R.string.something_wrong)
        val report = intent.getStringExtra("report") ?: run { finish(); return }
        dialog.setMessage(report)
        dialog.setPositiveButton(R.string.ok) { _, _ -> finish() }
        dialog.setNeutralButton(R.string.save_log) { _, _ ->
            Utils.saveLog(this, "crash_log", report)
            finish()
        }
        dialog.setNegativeButton(R.string.open_again) { _, _ ->
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
        dialog.setCancelable(false)
        dialog.create().show()
    }
}
