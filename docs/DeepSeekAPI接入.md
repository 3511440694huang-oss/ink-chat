# DeepSeek API 接入（ink-chat 实测口径）

> 用途：ink-chat 及后续项目的 DeepSeek API 接入参考。
> 口径：以项目实际代码与真机实测为准（2026-09-25）；官方文档：[api-docs.deepseek.com](https://api-docs.deepseek.com)。
> 相关：《软件框架.md》附录 A（速查）、`app/src/main/java/com/ink/chat/network/`（源码）。

---

## 一、基础信息

- **Base URL**：`https://api.deepseek.com`
- **认证**：`Authorization: Bearer <API Key>`（Anthropic 兼容端点额外双写 `x-api-key`，见 §五）
- **API Key**：官方平台申请；本项目存于设置页（加密存储）
- **格式**：OpenAI 兼容（`messages` 入参、`choices` 出参）；另有 Anthropic 兼容 Messages 端点（§五）
- **接入方式**：OkHttp 直连拼 JSON（不经 SDK——`thinking` 等扩展字段在 SDK 里需绕 `extra_body`，直连无此坑）
- **超时**（`di/AppModule.kt`）：连接 10s / 读取 120s / 写入 60s；流式请求读取超时禁用（0）；文件上传写超时放宽至 5min

## 二、端点总览（实测）

| 端点 | 方法 | 用途 |
|---|---|---|
| `/chat/completions` | POST | 对话（流式 / 非流式） |
| `/anthropic/v1/messages` | POST | 联网搜索（Anthropic 兼容端点，替代已下线的 `/responses`） |
| `/models` | GET | 模型列表 |
| `/user/balance` | GET | 账户余额 |
| `/files` | POST | 上传文件（仅图片） |

> 联网搜索迁移：`/responses` 的 `web_search` 已于 2026-09-10 随 V4.1 下线，改走 Anthropic 兼容端点（§五）。

## 三、对话接入（POST /chat/completions）

请求体（本项目装配口径）：

```json
{
  "model": "deepseek-v4-flash",
  "messages": [
    {"role": "system", "content": "..."},
    {"role": "user", "content": "..."}
  ],
  "stream": true,
  "stream_options": {"include_usage": true},
  "thinking": {"type": "enabled"},
  "reasoning_effort": "low"
}
```

要点：

- **无状态**：`messages` 必须携带完整历史（不含历史思维链——仅联网 tools 场景例外）；
- **思考模式**：`thinking.type = enabled / disabled`；开启时 `reasoning_effort` 取 `low / high / max`（服务端默认 high，本项目默认 **low** 省费用）；
- 思考模式下 **不支持** `temperature / top_p / presence_penalty / frequency_penalty`——传了不报错、不生效，直接省略；
- **响应解析**：`choices[0].message.content`（正文）/ `reasoning_content`（思维链）/ `usage`（tokens）；
- **缓存统计**：`usage.prompt_cache_hit_tokens` / `prompt_cache_miss_tokens`（未命中缺省 = 输入 − 命中，兜底推导）。

## 四、流式（SSE）

- 帧格式：逐行 `data: {...}`；结束 `data: [DONE]`；无效帧容错跳过；
- `delta` 区分 `content` 与 `reasoning_content`；
- 请求带 `stream_options.include_usage=true` → 末段帧携带 `usage`；
- **墨水屏按段刷新**：批量回调阈值 200 字符或段落边界（`\n\n`），不逐字刷新；
- **中断不丢内容**：流中断 / 用户中止时保留已收内容。

## 五、联网搜索（POST /anthropic/v1/messages）

非流式。请求要点：

- 头：`x-api-key` + `Authorization: Bearer` 双写、`anthropic-version: 2023-06-01`；
- `system` 提升为顶层字段；消息转 Anthropic 格式（`content: [{type:"text", ...}]`）；
- `max_tokens` 必填（本实现 4096）；
- 工具：`tools: [{"type": "web_search_20250305", "name": "web_search", "max_uses": 5}]`，服务端执行。

响应解析：`content[]` 块 → `text`（正文）/ `thinking`（思维链）/ `web_search_tool_result`（来源）。
来源随正文附「引用块」（M5.7 版式）：`——参考来源——` + `[n] 标题` + URL 两行/条；同 URL 去重、标题缺省回退域名、上限 5 条。

## 六、辅助端点

- **余额** `GET /user/balance`：`is_available` + `balance_infos[]`（`currency / total_balance / granted_balance / topped_up_balance`）；失败静默返回 null；
- **模型列表** `GET /models`：取 `data[].id`，**模糊过滤**含 `deepseek` 的 ID（防 ID 变更导致下拉为空）；结果缓存；
- **文件上传** `POST /files`：multipart（`purpose=user_data` + `file`）；单文件 ≤ 64MiB、10 分钟内传完；仅图片（JPEG / PNG / GIF / WebP）；返回 `id` / `file_id`。

## 七、容错、重试与中止

错误码 → 文案（`ApiErrorMapper`）：

| 码 | 文案 | 动作 |
|---|---|---|
| 400 | 请求格式错误 | 重试 |
| 401 | API Key 无效或已过期 | 去设置 |
| 402 | 余额不足，请充值 | 查余额 |
| 422 | 参数错误 | 重试 |
| 429 | 请求较密集，暂停后重试 | 重试 |
| 500 | 服务内部错误 | 重试 |
| 503 | 服务暂时不可用 | 重试 |

- **重试**（`RetryPolicy`）：429 → 最多 3 次（1s → 2s → 4s，±50% 抖动）；5xx / IO → 最多 2 次；服务端 `Retry-After` 优先（上限 60s）；
- **客户端限速**：两次请求间隔 ≥ 500ms（≤ 2 次/秒）；
- **中止**：协程取消 → `call.cancel()` 立即打断阻塞 IO；半截内容随 `ChatCanceledException` 带出（落库不丢）；**用户中止优先于重试**（取消当场退出退避循环）。

## 八、实现映射（ink-chat）

| 文件 | 职责 |
|---|---|
| `network/DeepSeekApi.kt` | 请求装配 / 各端点调用 / 流式与非流式解析 / 取消 |
| `network/StreamParser.kt` | SSE 单行解析（content / reasoning / usage，容错） |
| `network/RetryPolicy.kt` | 退避与重试预算 |
| `network/ApiErrorMapper.kt` | 状态码 → 文案与动作 |
| `data/repo/ChatRepository.kt` | 消息装配与调用编排（对话 / 总结 / 联网分支） |
| `data/datastore/SettingsStore.kt` + `domain/model/ThinkLevel.kt` | 偏好：stream / thinking / effort（关闭·低·高·最大） |

调用参数惯例：

- 对话：`stream / thinking / effort` 取用户设置（思考档位映射：关闭→disabled、低→low、高→high、最大→max）；
- 会话总结等辅助调用：非流式、关思考（省费用提速）。

## 九、踩坑备忘

- 思考模式与 `temperature` 系参数互斥（静默不生效，装配时省略）；
- 流式不传 `stream_options.include_usage` 则末段无 `usage`（缓存统计拿不到数）；
- `/models` 不要写死模型 ID（服务端会变；模糊匹配 + 缓存）；
- 流式必须禁用读取超时——长思考 / 长生成期间无数据会被 120s 读超时切断；
- 联网来源要防重（同 URL 去重）并限条数（墨水屏阅读友好）；
- 联网走 Anthropic 端点时**非流式**（响应为完整 JSON），别按 SSE 解析。

---

> 更新记录：2026-09-25 首版（依据 ink-chat v0.8.x 实测代码整理）。
