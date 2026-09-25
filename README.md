# ink-chat（静墨）— DeepSeek 墨水屏客户端

> 依据 [`docs/软件框架.md`](docs/软件框架.md) v2.0｜**当前：M5.7 完成**（排版与 LaTeX 增强 · 备份恢复 · 提示词 · 自定义字体 · 来源版式）
> 墨屏真机：MiDuoKanReaderPro / Android 8.1（SDK27）/ armeabi-v7a —— 构建 / 安装 / 运行链路已验证
> 代码仓库：[`3511440694huang-oss/ink-chat`](https://github.com/3511440694huang-oss/ink-chat)（Releases 页提供 APK 下载）

## 快速开始（在本机 Ubuntu 终端构建）

```bash
bash build.sh                        # 构建 debug APK
bash build.sh :app:assembleRelease   # 构建 release APK
bash build.sh :app:compileDebugKotlin # 只过 Kotlin 编译（最快暴露代码错误）
```

产物：
- debug：`app/build/outputs/apk/debug/app-debug.apk`
- release：`app/build/outputs/apk/release/app-release.apk`

## 安装与验证（设备端）

```bash
cp app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/ink-debug.apk
pm install -r /data/local/tmp/ink-debug.apk
am start -n com.ink.chat/.MainActivity
pidof com.ink.chat
logcat -d -t 300 | grep -E "FATAL|AndroidRuntime"
```

## 工程结构

```
app/src/main/java/com/ink/chat/
├── App.kt              # Application（Koin 装配）
├── MainActivity.kt     # 单 Activity 入口（三页导航，无转场）
├── data/
│   ├── room/           # InkDatabase · entity/ · dao/ · Converters（四表）
│   ├── datastore/      # CryptoManager（Keystore AES-GCM）· SettingsStore
│   ├── repo/           # ChatRepository · SettingsRepository · ConversationHolder（会话槽位）
│   └── transfer/       # TransferCodec（导出/导入 JSON · 版本校验 · SAF 流）
├── network/            # DeepSeekApi · StreamParser · RetryPolicy · ApiErrorMapper
├── domain/model/       # ChatModels（Message/Conversation/ConversationRow/…）· ThinkLevel
├── di/                 # AppModule（Koin）
├── ui/
│   ├── theme/          # Ink.kt（令牌 · 调色板组合局部）· InkTheme.kt（纸白/反色 · 字号/行距）
│   ├── components/     # InkBasics · InkDialog · InkMessageBlock · InkJumpDialog
│   ├── chat/           # ChatHomeScreen · ChatViewModel（多会话发送）
│   ├── sessions/       # SessionsScreen · SessionsViewModel（列表/搜索/总结/导出）
│   ├── balance/        # BalanceScreen · BalanceViewModel（余额与用量）
│   └── settings/       # SettingsScreen · SettingsViewModel（账户/默认值/显示/数据）
└── util/               # JsonCleaner · TimeFmt
```

## 关键配置（勿动）

- `local.properties`：aapt2 覆盖（arm64 设备必需，详见《构建心得.md》环境层 1）
- `gradle.properties`：JVM 内存 / daemon 关闭
- `keystore/`：与应用一起移动，勿跨项目引用

## 里程碑

- [x] M0 骨架（工程 + 构建链路 + 纸张主题壳）
- [x] M0.5 交互壳（三页导航 · 菜单/设置可点 · 思考强度面板）
- [x] M1 数据与网络地基（Room / Keystore / DeepSeekApi / SSE / 重试）—— ✅ 2026-09-12 真机验证
- [x] M2 对话核心（思考链 / 停止 / 重试 / 复制 / 删除 / 跳转）—— ✅ 2026-09-12 真机验证
- [x] M3 会话管理（列表 / 搜索 / 总结 / 导入导出）—— ✅ 2026-09-12 真机复测通过
- [x] M4 设置与账户（余额 / 模型列表 / 显示三档）—— ✅ 2026-09-12 装机冒烟
- [ ] M5 增强（联网搜索 / 图片输入）
  - [x] M5.6 用量统计（缓存命中率 / 花费估算）· LaTeX 渲染修复 —— ✅ 2026-09-13 真机验证（v0.7.1 已发布）
  - [x] M5.7 细节增强集 —— 排版与 LaTeX 增强 / 备份恢复 / 提示词 / 自定义字体 / 来源版式 —— ✅ 2026-09-25 装机冒烟（v0.8.0）
- [ ] M6 打磨与发布

## 构建记录

### 2026-09-12 · M0 构建验证（本机）

| 项 | 结果 |
|---|---|
| 环境 | Operit Ubuntu 终端（root）+ JDK 17 + Gradle 8.11.1 + SDK 36 / build-tools 35 |
| debug 构建 | `bash build.sh :app:assembleDebug` → BUILD SUCCESSFUL；`app-debug.apk` 15.0MB |
| release 构建 | `bash build.sh :app:assembleRelease` → BUILD SUCCESSFUL；`app-release.apk` 9.8MB（≤12MB 预算内） |
| 安装 | `cp /data/local/tmp` + `pm install -r` → Success |
| 启动 | `am start` → pidof 存活；`logcat` 无 FATAL |
| 界面 | 空对话页渲染正常（[`docs/m0-verify.png`](docs/m0-verify.png)） |

**本轮踩坑（已解决）**：`ChatHomeScreen.kt` 缺 `import androidx.compose.ui.unit.dp` → `Unresolved reference 'dp'`；其余链路（aapt2 override / 资源 / Manifest / Dex / 签名）一次通过。

**待办**：墨水屏真机复验（构建链路已全通）。

### 2026-09-12 · M0.5 交互验证（本机）

| 项 | 结果 |
|---|---|
| release 产物 | `发布/ink-chat-v0.2.0-release.apk` 9.8MB · sha256 `284eff3f…c581c2` |
| 菜单键 | → 「全部对话」页 ✓ |
| 设置键 | → 设置页（五区块骨架）✓ |
| 思考 chip → 面板 | 关闭 / 低（默认）/ 高 / 最大 / 取消 ✓ |
| 选择「高」 | chip 实时更新为「思考 · 高」✓ |

截图：`docs/m05-think-panel.png`、`docs/m05-chip-high.png`、`docs/m05-sessions.png`

### 2026-09-12 · M1 数据与网络地基（真机验证）

| 项 | 结果 |
|---|---|
| 范围 | Room 四表 · Keystore+DataStore · DeepSeekApi（思考档位 / 流式按段 / 429 退避）· SSE 解析 · 错误映射 |
| 构建 | debug 18.5MB / release 12.4MB（`发布/ink-chat-v0.3.1-release.apk`，sha256 `a09e1e8f…2cece4`）；kapt 一次通过 |
| 无 Key 拦截 | 发送 →「请先在设置里填写 API Key」+ [去设置]，不清空输入 ✓ |
| 发送链路 | 用户消息 → 助手占位 → 全量历史装配 → 调用 → 落库展示 —— **用户真机实测通过** ✓ |
| 待专项验证 | 断网 / 429 退避重试（逻辑就绪，建议补测） |

**本轮踩坑**：`InkMessageBlock.kt` 缺 `import androidx.compose.ui.unit.dp`（与 M0 同类问题）；kapt 在 Kotlin 2.0 下回退 1.9 语言级（警告属正常）。

### 2026-09-12 · M2 对话核心（真机验证）

| 项 | 结果 |
|---|---|
| 范围 | 思考链折叠 · 停止（半截保留）· 重试/重新生成 · 复制 · 删除 · 消息跳转弹窗 · ↑↓ 迷你跳转 · 等待计时 · 本次对话 token 统计 |
| 构建 | debug / release 一次通过（`发布/ink-chat-v0.4.1-release.apk` 12.5MB，sha256 `b773befd…a4281`）；0.4.0 真机首验后按反馈迭代至 0.4.1 |
| 真机验证 | 全部 P0 对话功能通过（用户实测）✅ |
| 零动画 | 全局无涟漪 · 滚动禁 fling（松手即停）· 跳转 `scrollToItem`（无动画）✅ |
| 迭代项 | ① ↑↓ 键：48dp 方块组 → 36dp 迷你方块 · 右侧边缘悬浮；② 新增「本次 ↑x ↓y」会话 token 显示（计费口径，随轮次实时刷新） |

**本轮踩坑**：① `inkClickable` 增加 `enabled` 形参后旧的位置传参调用失配 → 改为双重重载；② kotlinx `invokeOnCompletion(onCancelling=true)` 属内部 API → 改用「子协程 `awaitCancellation` + finally 中 `call.cancel()`」公开方案中断阻塞 IO（取消即时生效，不走内建 API）；③ kapt 增量缓存残留旧 `UsageDao_Impl.java` → 清理 `build/generated/source/kapt` 后重建。

### 2026-09-12 · M3 会话管理（装机冒烟）

| 项 | 结果 |
|---|---|
| 范围 | 列表（置顶组/摘要预览/条数·时间）· 搜索（标题+全文，300ms 防抖）· 新建（空闲复用）· 改名 · 置顶 · 删除 · 跳到×无动画定位 · 总结（追加「·摘要」）· 导出（单会话/全部）· 导入（事务追加）· 存储占用 |
| 架构 | 新增 `ConversationHolder` 会话槽位：列表点选/新建/跳转仅更新槽位并弹回对话页（导航栈恒两层）；`TransferCodec` JSON 编解码（format+version 校验，导入前防御性清洗） |
| 构建 | debug / release 通过（`发布/ink-chat-v0.5.0-release.apk` 12.5MB，sha256 `e5e02ff8…78c5`）；**首次编译一次通过** |
| 装机冒烟 | 覆盖安装 0.5.0（versionCode 7）→ 启动无 FATAL → 会话列表页渲染正常（标题/摘要/六键操作行），新 SQL（子查询投影 + LIKE ESCAPE）运行无异常 ✓ |
| 待验 | 用户真机复测：搜索命中、总结、导出→导入回环（"50 会话不卡顿 / JSON 完整回导"） |

**本轮踩坑**：① 再次遇到 kapt 增量缓存残留（新增 DAO 方法 `count()` 未进旧 `_Impl.java`）→ 同款处理：清 `build/generated/source/kapt` + `build/tmp/kapt3` 后重建（已形成固定动作）；② `SessionsScreen` 列表判空不能用 `when { list == null → }` 分支链（跨分支不 smart-cast）→ 改 `if / else if / else`；③ `ensureConversation` 原按 `pinned DESC, updated_at DESC` 取"最近"，置顶上线后会导致启动进入置顶会话 → 改为 `mostRecent()`（仅按 `updated_at`），置顶不影响"上次在聊"语义。

### 2026-09-12 · M4 设置与账户（装机冒烟）

| 项 | 结果 |
|---|---|
| 范围 | D2 余额（启动 + 手动刷新；余额页：赠送/充值构成 · 今日/本月用量 · 10 段静态刻度 · `is_available:false` 框线提示）· D3 模型列表（`GET /models` 拉取 + 模糊匹配 + `models_cache` 缓存 + 内置兜底）· D6 显示三档（字号 0.85/0.9/1.0 · 行距 紧密/标准 · 主题 纸白/反色）· 启动静默刷新（余额 + 模型） |
| 主题架构 | 颜色令牌改为 `LocalInkPalette` 组合局部（反色 = 五级灰数学对偶 255−c）——全应用 80 处 `Ink.*` 引用零改动；字号缩放经 `LocalDensity.fontScale` 覆盖（49 处字号零改动）；行高统一走 `inkLh()` 因子（13 处替换）；窗口层（状态栏/导航栏/背景）随主题切换 |
| 构建 | debug 18.6MB / release 12.5MB（`发布/ink-chat-v0.6.0-release.apk`，sha256 `499a45e0…4f347`）；编译一次通过（1 处遗留引用修复） |
| 装机冒烟 | 覆盖安装 0.6.0（versionCode 8）→ 启动无 FATAL → 设置页全区块渲染 → **启动静默刷新生效（余额实测 ¥17.91）** → 字号/主题对话框交互正常；用户试机确认功能正常 ✓ |
| 实现说明 | ① 设置页「余额」行点击进入余额页（刷新按钮在余额页内，线框④的「刷新」并入⑤）；② 「本月用量」刻度 = 本月 / 上月 token 对比（无月度预算概念，语义于代码注明）；③ 系统提示词（C3）仍为 P1，行文案改「后续版本接入」 |

**本轮踩坑**：① 余额文案改派生流后，`SettingsViewModel.refreshBalance()` 残留引用已删字段 `_balanceText` → 删除该方法（职责移入 `BalanceViewModel`）；② 整文件重写须先 `delete_file` 再 `create_file`（工具约束，记住流程）；③ 墨水屏 `screencap` 不保证实时（可能抓到过渡帧）→ UI 验证以 `uiautomator dump` 语义树为准，像素级验证用 PIL 临时脚本分析。

### 2026-09-13 · M5.6 用量统计与 LaTeX 渲染修复（真机验证 + GitHub 发布）
| 项 | 结果 |
|---|---|
| 范围 | 输入区布局（token 显示独立成行，不再被「图片 / 文件 / 短语」挤压）· 用量行（↑输入 / ↓输出 · 缓存命中率 · 花费估算，点击展开明细）· `Pricing` 峰谷价（北京时间工作日 9–12 / 14–18；命中 / 未命中 / 输出三价）· Room v2→v3（usage 表新增缓存两列，非破坏迁移保数据）· LaTeX：`\(\)` / `\[\]`、cases / aligned / matrix 环境、`\binom` 等组合标记、货币误判修复 |
| 数据口径 | API 多候选键容错解析（`prompt_cache_hit/miss_tokens` 等）；旧记录无缓存列 → 整额按未命中保守估算；无缓存数据时命中率显示「—」而非误报 0% |
| 构建 | debug 18.9MB / release 12.6MB；单元测试 7/7（dollarInline / currencyNotMath / parenInline / bracketBlock / casesUnicode / matrixParse / fracInline）；清 kapt 缓存后 release 一次通过 |
| 装机验证 | 覆盖安装 0.7.1（versionCode 10）→ 启动无 FATAL → `uiautomator dump`：「联网 / 思考 / 图片 / 文件 / 短语」无挤压、用量行「↑933 ↓2.1k · ≈¥0.0092」正常；**旧库数据保留（迁移生效）** ✓；用户实机确认 LaTeX 渲染 ✓ |
| 发布 | GitHub `3511440694huang-oss/ink-chat`（源码 70 文件）＋ [Release v0.7.1](https://github.com/3511440694huang-oss/ink-chat/releases/tag/v0.7.1)（APK 附件，sha256 `ea18a00e…61222`） |
**本轮踩坑**：① 本机 `/storage`（fuse）会破坏 git 写 loose object（`git add` 报 "failed to insert into database"；`/root` 下正常）→ 仓库改用 `--separate-git-dir`（对象库置于 `/root`）规避；② 单测三处小坑：包名写错、Kotlin 字符串模板 `$` 转义、matrix 顶层 Group 断言修正。
### 2026-09-25 · M5.7 细节增强集（装机冒烟 + v0.8.0）

| 项 | 结果 |
|---|---|
| 范围 | ① **排版与 LaTeX**：H1/H2 下细线；符号表扩充约 60 项（implies / iff / mid / parallel / perp / lfloor-rfloor / sqcup-sqcap / bigoplus 等）；新增 `\boxed`（真实框线绘制）、`\overset / \underset / \stackrel`（叠标盒模型）、`\underbrace / \overbrace`（透传 + 尾标）；修复 `\left.` / `\right.`（不再画点）、`\limits` / `\nolimits`（吸收进大运算符，下标不再丢失）；颜色 / 空白 / 旧字体 / `\big` 系命令降级为透明（不再残留原文）。② **备份 / 恢复**：ZIP（DB 三件套 + 设置 + 字体），两阶段恢复（暂存 → 启动时在 Room 之前替换 → 自动重启），不含 API Key。③ **提示词**：系统提示词（system 消息注入）+ 模板库。④ **自定义字体**：ttf / otf / ttc 导入（文件头魔数校验，8MB 上限），全局应用（Typography + LocalInkFontFamily 双通道），删除回退系统。⑤ **联网来源**：引用块版式（——参考来源—— + [n] 标题 + URL 两行/条）、URL 去重、标题缺省回退域名。 |
| 测试 | 单元测试 19/19（新增 `TexCompatTest` 12 项：常见语料零残留扫描 + 关键修复点结构断言 + 行内 Unicode 降级） |
| 构建 | release 12.6MB（`app-release.apk`，versionCode 11 / versionName 0.8.0）；清 kapt 缓存后一次通过 |
| 装机 | 覆盖安装 v0.8.0 → 启动无 FATAL、进程存活 ✓（深度 UI 验收待解锁复核）；APK 归档 `发布/ink-chat-v0.8.0-release.apk`，sha256 `9ab7ae19…18d3c3` |
| 发布 | _待定_（本地已就绪；GitHub 发布待用户确认后执行） |

**本轮踩坑**：① kapt / javac 的「非法 unicode 转义」陷阱——KDoc 里写 `\underset` 会生成 `\u` 序列致 stub 编译失败 → 改为双反斜杠；② Kotlin 块注释支持嵌套——KDoc 里写 `fonts/*` 会吞掉注释结尾（Unclosed comment）→ 改写为「fonts/ 目录下全部字体文件」；③ `pm install` 直读 `/storage` 源路径被 SELinux 拒（system_server 无 fuse 读权）→ 先 `cp /data/local/tmp` 再安装（沿用旧法）。
### 墨屏真机备忘（来自安装堆栈）

- 设备：**MiDuoKanReaderPro** · Android 8.1（SDK27）· armeabi-v7a
- 已知问题：ROM 阉割「安装未知应用」系统页 → MT 管理器唤起报 `Unknown error code -1`
- 安装绕行：系统自带文件管理器打开 APK / `adb install`；不要依赖第三方安装器的跳转入口
