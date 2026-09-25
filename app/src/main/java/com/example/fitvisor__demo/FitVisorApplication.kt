package com.example.fitvisor__demo

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.WindowManager

/**
 * Keeps the screen on for as long as any FitVisor screen is in the
 * foreground, so the phone doesn't dim and lock mid-workout while the user
 * is exercising in front of the camera instead of touching it.
 *
 * Done here, once, for every activity (including future ones) rather than in
 * each activity's onCreate. [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON]
 * needs no permission and only applies while the window is visible: leaving
 * the app (home, recents, power button) restores the normal screen timeout.
 */
class FitVisorApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
