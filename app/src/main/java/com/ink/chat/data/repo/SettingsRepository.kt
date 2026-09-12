package com.ink.chat.data.repo

import com.google.gson.Gson
import com.ink.chat.data.datastore.AppSettings
import com.ink.chat.data.datastore.SettingsStore
import com.ink.chat.data.room.dao.ModelCacheDao
import com.ink.chat.data.room.entity.ModelCacheEntity
import com.ink.chat.domain.model.BalanceSnapshot
import com.ink.chat.domain.model.ThinkLevel
import com.ink.chat.network.DeepSeekApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 设置与账户（§4.3 repo 层）：ThinkLevel 映射、显示设置、余额缓存、模型列表缓存 */
class SettingsRepository(
    private val store: SettingsStore,
    private val api: DeepSeekApi,
    private val gson: Gson,
    private val modelCacheDao: ModelCacheDao,
) {

    val settings: Flow<AppSettings> = store.settings

    suspend fun current(): AppSettings = store.current()

    /** 解密后的明文 key（仅用于提示末四位；调用网络请用 store 内部解密路径） */
    suspend fun plainApiKey(): String? = store.plainApiKey()

    suspend fun setApiKey(plain: String) = store.setApiKey(plain)

    suspend fun setModel(model: String) = store.setModel(model)

    suspend fun setStream(v: Boolean) = store.setStream(v)

    suspend fun setWebSearch(v: Boolean) = store.setWebSearch(v)

    // —— 显示设置（M4 §2.1 D6）——
    suspend fun setFontScale(v: Float) = store.setFontScale(v)

    suspend fun setLineSpacing(v: String) = store.setLineSpacing(v)

    suspend fun setTheme(v: String) = store.setTheme(v)

    /** UI 四档 → (thinking_enabled, reasoning_effort) */
    suspend fun setThinkLevel(level: ThinkLevel) {
        when (level) {
            ThinkLevel.OFF -> store.setThinking(false, "low")
            else -> store.setThinking(true, level.effort ?: "low")
        }
    }

    fun thinkLevelOf(s: AppSettings): ThinkLevel = when {
        !s.thinkingEnabled -> ThinkLevel.OFF
        s.effort == "high" -> ThinkLevel.HIGH
        s.effort == "max" -> ThinkLevel.MAX
        else -> ThinkLevel.LOW
    }

    /** 刷新余额并缓存；失败返回 null */
    suspend fun refreshBalance(): BalanceSnapshot? {
        val key = store.plainApiKey() ?: return null
        val snap = api.queryBalance(key) ?: return null
        store.setBalance(gson.toJson(snap))
        return snap
    }

    suspend fun cachedBalance(): BalanceSnapshot? = runCatching {
        store.current().balanceJson
            .takeIf { it.isNotBlank() }
            ?.let { gson.fromJson(it, BalanceSnapshot::class.java) }
    }.getOrNull()

    /** 余额缓存流（DataStore → UI；任一页面刷新后全局可见） */
    fun balanceFlow(): Flow<BalanceSnapshot?> = store.settings.map { s ->
        runCatching {
            s.balanceJson.takeIf { it.isNotBlank() }?.let { gson.fromJson(it, BalanceSnapshot::class.java) }
        }.getOrNull()
    }

    // —— 模型列表（M4 §2.1 D3：动态拉取 + 本地缓存）——

    /** 刷新模型列表并写缓存（失败 / 无 Key 返回 null——调用方静默处理） */
    suspend fun refreshModels(): List<String>? {
        val key = store.plainApiKey() ?: return null
        val list = api.listModels(key) ?: return null
        if (list.isNotEmpty()) {
            val now = System.currentTimeMillis()
            modelCacheDao.upsertAll(list.map { ModelCacheEntity(it, now) })
        }
        return list
    }

    /** 模型列表本地缓存（首次联网前可能为空） */
    suspend fun cachedModels(): List<String> = modelCacheDao.all().map { it.id }

    // —— 模板短语（M5 §2.1 A13：常用提示词片段库）——

    /** 短语库流（DataStore JSON → List） */
    fun phrasesFlow(): Flow<List<String>> = store.settings.map { parsePhrases(it.phrasesJson) }

    /** 追加一条短语（去首尾空白后存回） */
    suspend fun addPhrase(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val list = parsePhrases(store.current().phrasesJson) + t
        store.setPhrases(gson.toJson(list))
    }

    /** 按索引删除一条短语 */
    suspend fun removePhrase(index: Int) {
        val list = parsePhrases(store.current().phrasesJson).toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            store.setPhrases(gson.toJson(list))
        }
    }

    private fun parsePhrases(json: String): List<String> =
        if (json.isBlank()) emptyList()
        else runCatching {
            gson.fromJson(json, Array<String>::class.java)?.toList() ?: emptyList()
        }.getOrDefault(emptyList())
}