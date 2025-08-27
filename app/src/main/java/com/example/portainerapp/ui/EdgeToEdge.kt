package com.example.portainerapp.ui

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

object EdgeToEdge {
    fun apply(activity: Activity, topInsetView: View, bottomInsetView: View? = null) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val topInitial = intArrayOf(
            topInsetView.paddingLeft,
            topInsetView.paddingTop,
            topInsetView.paddingRight,
            topInsetView.paddingBottom
        )
        val bottomInitial = bottomInsetView?.let {
            intArrayOf(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom)
        }
        ViewCompat.setOnApplyWindowInsetsListener(topInsetView) { _, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topInsetView.setPadding(topInitial[0], topInitial[1] + sb.top, topInitial[2], topInitial[3])
            if (bottomInsetView != null && bottomInitial != null) {
                bottomInsetView.setPadding(bottomInitial[0], bottomInitial[1], bottomInitial[2], bottomInitial[3] + sb.bottom)
            }
            insets
        }
        // Request insets now
        topInsetView.requestApplyInsets()
        bottomInsetView?.requestApplyInsets()
    }
}

