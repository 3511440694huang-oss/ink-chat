package com.ink.chat.data.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "ink_settings")

/** 设置项全量（§4.4 DataStore 键表） */
data class AppSettings(
    val apiKeyEnc: String = "",
    val model: String = "deepseek-flash",
    val effort: String = "low",
    val thinkingEnabled: Boolean = true,
    val stream: Boolean = false,
    val webSearch: Boolean = false,
    val fontScale: Float = 0.9f,
    val lineSpacing: String = "normal",
    val theme: String = "paper",
    val systemPrompt: String = "",
    val balanceJson: String = "",
    /** 模板短语库（M5 §2.1 A13；JSON 字符串数组） */
    val phrasesJson: String = "",
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val API_KEY = stringPreferencesKey("api_key_enc")
        val MODEL = stringPreferencesKey("model")
        val EFFORT = stringPreferencesKey("effort")
        val THINKING = booleanPreferencesKey("thinking_enabled")
        val STREAM = booleanPreferencesKey("stream")
        val WEB_SEARCH = booleanPreferencesKey("web_search")
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val LINE_SPACING = stringPreferencesKey("line_spacing")
        val THEME = stringPreferencesKey("theme")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val BALANCE = stringPreferencesKey("balance_json")
        val PHRASES = stringPreferencesKey("phrases")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            apiKeyEnc = p[Keys.API_KEY] ?: "",
            model = p[Keys.MODEL] ?: "deepseek-flash",
            effort = p[Keys.EFFORT] ?: "low",
            thinkingEnabled = p[Keys.THINKING] ?: true,
            stream = p[Keys.STREAM] ?: false,
            webSearch = p[Keys.WEB_SEARCH] ?: false,
            fontScale = p[Keys.FONT_SCALE] ?: 0.9f,
            lineSpacing = p[Keys.LINE_SPACING] ?: "normal",
            theme = p[Keys.THEME] ?: "paper",
            systemPrompt = p[Keys.SYSTEM_PROMPT] ?: "",
            balanceJson = p[Keys.BALANCE] ?: "",
            phrasesJson = p[Keys.PHRASES] ?: "",
        )
    }

    suspend fun current(): AppSettings = settings.first()

    /** 写入 Keystore AES-GCM 密文（base64） */
    suspend fun setApiKey(plain: String) {
        val enc = CryptoManager.encrypt(plain.trim())
        context.dataStore.edit { it[Keys.API_KEY] = enc }
    }

    /** 解密后的明文 key；未设置或密钥失效返回 null */
    suspend fun plainApiKey(): String? = CryptoManager.decrypt(current().apiKeyEnc)

    suspend fun setModel(v: String) = update { it[Keys.MODEL] = v }

    suspend fun setThinking(enabled: Boolean, effort: String) = update {
        it[Keys.THINKING] = enabled
        it[Keys.EFFORT] = effort
    }

    suspend fun setStream(v: Boolean) = update { it[Keys.STREAM] = v }

    suspend fun setWebSearch(v: Boolean) = update { it[Keys.WEB_SEARCH] = v }

    suspend fun setBalance(json: String) = update { it[Keys.BALANCE] = json }

    suspend fun setPhrases(json: String) = update { it[Keys.PHRASES] = json }

    // —— 显示设置（M4 §2.1 D6）——
    suspend fun setFontScale(v: Float) = update { it[Keys.FONT_SCALE] = v }

    suspend fun setLineSpacing(v: String) = update { it[Keys.LINE_SPACING] = v }

    suspend fun setTheme(v: String) = update { it[Keys.THEME] = v }

    private suspend fun update(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}