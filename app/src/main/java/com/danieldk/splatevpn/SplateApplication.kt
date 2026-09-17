package com.danieldk.splatevpn

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SplateApplication : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
