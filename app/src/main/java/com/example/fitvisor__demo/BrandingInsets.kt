package com.example.fitvisor__demo

import android.app.Activity
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Small helpers to keep the redesigned screens edge-to-edge friendly:
 * a branded navy for the system bars and padding that respects the status/nav
 * bar (and display-cutout) insets so content never sits under system icons.
 *
 * On Android 15+ (edge-to-edge by default) the bars are transparent and the
 * padding keeps content clear of them. On older versions the bars simply take
 * the navy tint and the dispatched insets are typically zero.
 */
object BrandingInsets {

    @Suppress("DEPRECATION")
    fun applyNavySystemBars(activity: Activity) {
        val navy = ContextCompat.getColor(activity, R.color.bg_primary)
        activity.window.statusBarColor = navy
        activity.window.navigationBarColor = navy
    }

    /**
     * Pads [view] by the system-bar and display-cutout insets, preserving any
     * padding already declared in XML.
     */
    fun padForSystemBars(view: View) {
        val basePaddingLeft = view.paddingLeft
        val basePaddingTop = view.paddingTop
        val basePaddingRight = view.paddingRight
        val basePaddingBottom = view.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(
                basePaddingLeft + bars.left,
                basePaddingTop + bars.top,
                basePaddingRight + bars.right,
                basePaddingBottom + bars.bottom
            )
            windowInsets
        }
    }
}
