package com.wji.meditationplayer

import android.app.Application

class MeditationApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val Application.container: AppContainer
    get() = (this as MeditationApp).container
