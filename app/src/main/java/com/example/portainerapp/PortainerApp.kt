package com.example.portainerapp

import android.app.Application
import com.google.android.material.color.DynamicColors

class PortainerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}

