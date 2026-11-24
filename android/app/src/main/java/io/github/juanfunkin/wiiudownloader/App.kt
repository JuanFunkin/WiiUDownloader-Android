package io.github.juanfunkin.wiiudownloader

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
    }
}
