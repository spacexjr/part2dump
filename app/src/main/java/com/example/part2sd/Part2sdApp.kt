package com.example.part2sd

import android.app.Application
import com.google.android.material.color.DynamicColors

class Part2sdApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Enable Material You dynamic colors on supported devices (Android 12+).
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
