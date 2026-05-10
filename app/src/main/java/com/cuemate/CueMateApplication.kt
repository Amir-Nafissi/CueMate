package com.cuemate

import android.app.Application
import com.cuemate.app.AppContainer

class CueMateApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
