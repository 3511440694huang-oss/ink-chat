package com.ink.chat

import android.app.Application
import com.ink.chat.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/**
 * 应用入口。
 * M1：装配 Koin 容器（与 novel-agent 同模式）。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@App)
            modules(appModule)
        }
    }
}