package com.agreenbhm.vibetainer

import android.app.Application
import com.google.android.material.color.DynamicColors

class VibetainerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
