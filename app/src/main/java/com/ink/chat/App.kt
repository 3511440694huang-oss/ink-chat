package com.ink.chat

import android.app.Application
import com.ink.chat.data.backup.BackupManager
import com.ink.chat.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/**
 * 应用入口。
 * M1：装配 Koin 容器（与 novel-agent 同模式）。
 * M5.7：启动时先完成「备份恢复」收尾（在 Room 初始化之前替换数据库 / 设置 / 字体）。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 待恢复收尾必须在任何 Room 使用之前执行（幂等；无暂存时直接返回）
        BackupManager.applyPendingRestore(this)
        startKoin {
            androidContext(this@App)
            modules(appModule)
        }
    }
}