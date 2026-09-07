package com.example.fitvisor__demo.ui

import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.example.fitvisor__demo.R
import com.example.fitvisor__demo.settings.AppSettings
import com.example.fitvisor__demo.settings.DebugDataStore

/** Shared controls in Settings and the exercise Summary. */
object RepTrackingPanel {
    fun bind(view: View) {
        val settings = AppSettings(view.context)
        val store = DebugDataStore(view.context)
        view.visibility = if (settings.debugEnabled) View.VISIBLE else View.GONE
        val toggle = view.findViewById<SwitchCompat>(R.id.testRepsSwitch)
        toggle.setOnCheckedChangeListener(null)
        toggle.isChecked = settings.testRepsEnabled
        toggle.setOnCheckedChangeListener { _, enabled -> settings.testRepsEnabled = enabled }
        val report = view.findViewById<TextView>(R.id.testRepsReport)
        report.text = store.repReport()
        view.findViewById<Button>(R.id.resetTestReps).setOnClickListener {
            AlertDialog.Builder(view.context)
                .setTitle(R.string.reset_test_reps)
                .setMessage(R.string.reset_test_reps_confirmation)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.reset_data) { _, _ ->
                    store.resetReps()
                    report.text = store.repReport()
                }.show()
        }
    }
}
