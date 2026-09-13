package com.matiasnl.hakiosk

import android.app.Application

class HaKioskApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
