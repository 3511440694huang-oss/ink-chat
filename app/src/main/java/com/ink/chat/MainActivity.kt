package com.ink.chat

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ink.chat.data.datastore.AppSettings
import com.ink.chat.data.repo.SettingsRepository
import com.ink.chat.ui.balance.BalanceScreen
import com.ink.chat.ui.chat.ChatHomeScreen
import com.ink.chat.ui.sessions.SessionsScreen
import com.ink.chat.ui.settings.SettingsScreen
import com.ink.chat.ui.theme.InkTheme
import com.ink.chat.ui.theme.THEME_INVERSE
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * 单 Activity 入口。
 * M0.5：三页导航（对话 / 会话列表 / 设置），全部无转场动画（纸面六则 · 静止之美）。
 * M4：主题 / 字号 / 行距由设置流实时驱动；启动静默刷新余额与模型列表（§2.1 D2/D3）。
 */
class MainActivity : ComponentActivity() {

    private val settingsRepo: SettingsRepository by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings by settingsRepo.settings.collectAsState(initial = AppSettings())

            // 启动静默刷新：余额 + 模型列表（有 Key 时；失败静默，离线可用）
            LaunchedEffect(Unit) {
                if (settingsRepo.current().apiKeyEnc.isNotBlank()) {
                    launch { settingsRepo.refreshBalance() }
                    launch { settingsRepo.refreshModels() }
                }
            }

            // 窗口层（状态栏 / 导航栏 / 背景）随主题切换
            LaunchedEffect(settings.theme) {
                applyWindowTheme(settings.theme == THEME_INVERSE)
            }

            InkTheme(
                theme = settings.theme,
                fontScale = settings.fontScale,
                lineSpacing = settings.lineSpacing,
            ) {
                AppNav()
            }
        }
    }

    private fun applyWindowTheme(inverse: Boolean) {
        val color = if (inverse) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        window.setBackgroundDrawable(ColorDrawable(color))
        window.statusBarColor = color
        window.navigationBarColor = color
        window.decorView.systemUiVisibility = if (inverse) {
            0 // 深底 → 浅色图标
        } else {
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR // 浅底 → 深色图标
        }
    }
}

object Routes {
    const val CHAT = "chat"
    const val SESSIONS = "sessions"
    const val SETTINGS = "settings"
    const val BALANCE = "balance"
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(
        navController = nav,
        startDestination = Routes.CHAT,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None }
    ) {
        composable(Routes.CHAT) {
            ChatHomeScreen(
                onOpenSessions = { nav.navigate(Routes.SESSIONS) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) }
            )
        }
        composable(Routes.SESSIONS) {
            SessionsScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenBalance = { nav.navigate(Routes.BALANCE) }
            )
        }
        composable(Routes.BALANCE) {
            BalanceScreen(onBack = { nav.popBackStack() })
        }
    }
}