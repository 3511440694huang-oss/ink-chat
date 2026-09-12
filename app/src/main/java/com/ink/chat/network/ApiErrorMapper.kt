package com.ink.chat.network

import com.ink.chat.domain.model.ErrorAction
import java.io.IOException

/**
 * 状态码 → 文案与动作（§4.5 错误码映射 / §3.5 文案库）。
 * 文案可直接显示在消息块与内联提示条中。
 */
object ApiErrorMapper {

    data class HumanError(val text: String, val action: ErrorAction)

    fun fromHttp(code: Int): HumanError = when (code) {
        400 -> HumanError("请求格式错误。", ErrorAction.RETRY)
        401 -> HumanError("API Key 无效或已过期。", ErrorAction.GO_SETTINGS)
        402 -> HumanError("余额不足，请充值后再试。", ErrorAction.QUERY_BALANCE)
        422 -> HumanError("参数错误。", ErrorAction.RETRY)
        429 -> HumanError("请求较密集，暂停后重试。", ErrorAction.RETRY)
        500 -> HumanError("服务内部错误。", ErrorAction.RETRY)
        503 -> HumanError("服务暂时不可用（503）。", ErrorAction.RETRY)
        else -> HumanError("请求失败（$code）。", ErrorAction.RETRY)
    }

    fun fromIo(e: IOException): HumanError =
        HumanError("连接失败：无法访问 api.deepseek.com", ErrorAction.RETRY)

    fun fromParse(): HumanError = HumanError("响应解析失败。", ErrorAction.RETRY)

    fun noApiKey(): HumanError = HumanError("请先在设置里填写 API Key。", ErrorAction.GO_SETTINGS)
}