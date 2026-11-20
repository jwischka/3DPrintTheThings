package com.u1.slicer

import android.app.Application

class U1SlicerApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // The standalone preset workflow has no printer connections or shared
        // settings database. Its native scene belongs solely to its own screen.
        if (BuildConfig.FULL_PRESET_EXPERIMENTAL) return
        AppForegroundTracker.register()
        container = AppContainer(this)
    }
}
