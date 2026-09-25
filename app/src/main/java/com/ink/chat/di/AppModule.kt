package com.ink.chat.di

import com.google.gson.Gson
import com.ink.chat.data.backup.BackupManager
import com.ink.chat.data.datastore.SettingsStore
import com.ink.chat.data.fonts.FontManager
import com.ink.chat.data.repo.ChatRepository
import com.ink.chat.data.repo.ConversationHolder
import com.ink.chat.data.repo.SettingsRepository
import com.ink.chat.data.repo.TextFileManager
import com.ink.chat.data.repo.UploadManager
import com.ink.chat.data.room.InkDatabase
import com.ink.chat.network.DeepSeekApi
import com.ink.chat.ui.balance.BalanceViewModel
import com.ink.chat.ui.chat.ChatViewModel
import com.ink.chat.ui.sessions.SessionsViewModel
import com.ink.chat.ui.settings.SettingsViewModel
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

/** Koin 装配（与 novel-agent 同模式，§4.8） */
val appModule = module {

    single { Gson() }

    single {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)   // 连接 10s（§4.5）
            .readTimeout(120, TimeUnit.SECONDS)     // 读取 120s（流式场景另行禁用）
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    single { InkDatabase.build(androidContext()) }
    single { get<InkDatabase>().conversationDao() }
    single { get<InkDatabase>().messageDao() }
    single { get<InkDatabase>().usageDao() }
    single { get<InkDatabase>().modelCacheDao() }

    single { SettingsStore(androidContext()) }

    single { DeepSeekApi(get()) }

    single { ConversationHolder() }

    single { UploadManager(androidContext(), get(), get()) }

    single { TextFileManager(androidContext()) }

    single { FontManager(androidContext()) }

    single { BackupManager(androidContext(), get(), get(), get()) }

    single { ChatRepository(get(), get(), get(), get(), get(), get()) }
    single { SettingsRepository(get(), get(), get(), get()) }

    viewModel { ChatViewModel(get(), get(), get(), get(), get()) }
    viewModel { SessionsViewModel(get(), get()) }
    viewModel { SettingsViewModel(get(), get(), get(), get()) }
    viewModel { BalanceViewModel(get(), get()) }
}