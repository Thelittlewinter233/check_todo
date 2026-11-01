# 每日打卡 App — 开发交接文档

> 交接目的：让新的开发 session 无需上下文即可继续开发。
> 配套文档：`README.md`（用户视角）、`NATIVE_ANDROID_REPORT.md`（逐轮开发详录）、`TIMETABLE_ANALYSIS.md`（WakeUp 6.1.35 反编译剖析）。
> 最后更新：2026-10-26（教务导入 frame 转储通道 + 小卡片快照机制完成后）

## 1. 项目概述

「每日打卡」是一款**纯原生 Android 应用**（Java + Android 框架 API，**零第三方库**），由 Vue3 Web 版迁移而来。当前功能：

| 模块 | 功能 |
|---|---|
| 打卡任务 | 多任务 CRUD（周一~日多选+打卡时间窗）、今日/全部视图、连续打卡 streak、打卡日历（白/绿/灰/黑=请假/今天描边）、请假（当天+未来预请，打断 streak）、取消打卡（防误点）、统计（打卡天数/总天数+打卡率+进度条）、紧急置顶排序 |
| 待办 | 开始/截止时间（日历+24h TimePicker）、花费时长、未开始/进行中/已过期状态、待完成/已完成折叠分组、勾选+撤销、按截止时间排序 |
| 课表 | 多课表（独立设置：时间刻度/学期开始日/总周数/节数/周末显示）、周视图网格（自绘 Canvas）、课程增删改、CSV/HTML/教务在线导入、手动编辑、默认作息=华农15节 |
| 桌面小卡片 | 三视图切换卡（打卡⇄待办⇄课表，按 widgetId 记忆模式）、今日课程独立卡；均支持快捷勾选/打卡，数据变更即时刷新 |
| 通知提醒 | 打卡任务可设「完成所需时间」；打卡+待办在「截止−所需−1h」提醒；AlarmManager 精确/降级双路径；被动捕获等全数据变更重调度 |
| 主题 | 亮/暗/跟随系统三态手动切换（`attachBaseContext`+`createConfigurationContext`） |
| 教务导入 | 五通道：**frame 转储（主推）**、被动捕获（shouldInterceptRequest 旁路）、fetch 代理（GET+会话 Cookie）、Cookie 直取、深度扫描（几何，最后手段）；3570 校清单映射 5 大厂商家族 parser |

**不在范围**（用户明确裁剪）：登录云端同步、iOS、后端。

## 2. 环境与工具链

- **JDK 21**（Microsoft OpenJDK 21.0.9，PATH 可用）
- **Android 工具链**：`tools/sdk/`（官方 build-tools **35.0.1** + platform-34 android.jar；注意 build-tools 34 的 d8 不支持 Java 11 class 文件）。`native-android/prepare.bat` 负责下载+SHA1 校验+解压，可随时删除重下；`tools/test-lib/`（Nashorn+ASM，仅测试执行注入脚本用，不进 APK）由 `native-android/prepare-test-libs.bat` 下载+SHA1 校验
- **无 Gradle / AndroidX / Compose / 任何第三方库**——所有功能用框架 API 自实现（含 JSON 用 `org.json`、HTML 解析自写 `SimpleHtmlParser`、Jsoup 垫片 `jshim/`）
- **minSdk 24 / targetSdk 34 / 包名 com.checktodo.app**；debug 签名（keytool 生成，apksigner v2+v3）
- 反编译工具：`tools/jadx-out/sources/`（WakeUp 6.1.35 全量反编译，16169 类，仅作参考）

## 3. 构建与测试

```bat
native-android\build.bat     REM 一键 7 步全链路（含全量测试，测试失败即中断）
native-android\prepare.bat   REM 工具链缺失时先跑
```

构建链：`aapt2 compile/link`（生成 R.java）→ `javac --release 11 -Xlint:all` → **纯 Java 测试** → 打包 classes.jar → `d8 --min-api 24` → zipalign → apksigner 签名 → 自动 verify。

**产物**：`native-android/out/checktodo-debug.apk`（约 394KB / 403,573 字节，随功能增长）。

**测试体系**（当前 **1646 项，0 失败**）：纯 Java runner（main + System.exit，无 JUnit），`build.bat` 第 4 步自动执行全部 `test/*.java`。**新增测试必须登记进 build.bat 的测试文件清单**。测试夹具在 `test/fixtures/`（真实课表页：kingosoft_kb.htm、kingosoft_shell.htm、scau_grid.htm）。

**开发纪律（历史验证有效）**：
1. 改前先 Read 相关源码；2. 修 bug 先写失败测试复现再修（TDD 红绿）；3. 每阶段实际写盘+跑 build.bat 自验；4. 最终报告必须含测试/构建/签名输出与 APK 路径，禁止只回规划文字；5. Web 版（根目录 Vue 工程）与 native-android/ 互不干扰，Web 版零改动。

## 4. 目录结构与核心文件地图

```
native-android/
  AndroidManifest.xml        # 权限：INTERNET/通知/电池优化/开机；receivers；usesCleartextTraffic
  build.bat / prepare.bat
  src/com/checktodo/app/
    MainActivity.java        # 单 Activity：三标签（打卡/待办/课表）+ 主题切换 + 所有对话框编排
    domain/                  # 纯 Java 域逻辑（不依赖 Android 类，全部可 JVM 测试）
      Task/TaskLogic/TaskStatus/TaskStats   # 打卡：状态机/streak/统计（口径见 TaskStats 注释）
      Todo/TodoLogic/TodoStatus             # 待办：状态机/filterWidgetTodos(48h窗口)/排序
      TaskRepository.java     # 持久化总入口（SharedPreferences+org.json，key=check-todo.v1）
      TimetableRepository.java# 多课表存储（key=timetable.v2，含 v1→v2 迁移）
      CourseBase/CourseDetail/TimeNode/TimetableConfig/TimetableLogic
      TimetableCsvParser      # CSV 导入（列序：课程名,星期,开始节,结束节,老师,教室,周次）
      SimpleHtmlParser        # 手写容错 HTML 解析（rowspan/colspan 展开、实体解码、UTF-8/GBK 回落）
      HtmlTimetableParser     # 通用课表网格解析（解析链总入口，见 §6）
      WeekExpression          # ★统一周次文法：1-16/7/1-5,7-16逗号分段/(单)(双)/[1-3]节后缀；三处解析共用
      KingoReportParser       # 青果 xskcb*.jsp 报表网格定点解析（真实页面夹具）
      ScauGridParser          # 华农新版正方 kbgrid_table 定点解析（id=天-节坐标、span.title1、单行式）
      TimeTableSettings/TimeTableDraft # 时间表默认值(华农15节)+草稿单一数据源+validateForSave
      OnlineExtraction/OnlineImportLogic/OnlineDivParser/GeometryGridSolver # 教务导入五通道
      PassiveCapture/PassiveCapturePolicy    # 被动捕获池(Tee/LRU8MB)+安全拦截决策(纯函数)
      ReminderLogic           # 提醒时刻=截止−所需−1h
      ai/（域）                # AI 判定：VerifyMode/VerificationPolicy/ImageHash/ExifFreshness/AiPromptBuilder/AiVerdictParser/GlmRequest（GLM-4.6V-Flash，BYOK）
      ThemeMode               # 亮/暗/跟随系统
    data/                    # 仓库层（TaskRepository/TimetableRepository；保存校验用 validateForSave 同口径）
    timetable/               # TimetableView(自绘Canvas)/TimetableController(课表+教务导入编排)/CapsuleToast
    widget/                  # CheckTodoWidget(三视图)/CheckTodoWidgetService/TodayCourseWidget(今日课程)
                             # WidgetFactorySnapshot/WidgetModes/WidgetModeStore(快照三不变量,见§6)
                             # CourseWidgetItems(课表条目纯函数,两卡共用)/PassiveCaptureClient/ChunkBridge
                             # /OnlineExtractionSession(单线程executor+30s预算+看门狗)
    notification/            # ReminderScheduler(RTC_WAKEUP+FetchBudget预算)/ReminderReceiver
    jshim/                   # Jsoup 兼容垫片（Element/Elements/select 子集，支撑 wakeup_parser）
    wakeup_parser/           # WakeUp 家族 parser：正方zf/金智jz/青果kingo_new/URP/元素UI(5家族)
                             # +SchoolInfoDecoder(解密3570校清单:reverse→B64→AES-CBC→B64→GZIP)
  res/                       # values + values-night（全部颜色资源化）；layout；xml(widget_info)
  test/                      # 31 个纯 Java 测试文件 1646 项；fixtures/ 真实课表页夹具
  out/checktodo-debug.apk    # 产物
tools/sdk/                   # Android 构建工具链（不入库可重下）
tools/jadx-out/              # WakeUp 反编译源码（参考用）
TIMETABLE_ANALYSIS.md        # WakeUp 剖析报告
NATIVE_ANDROID_REPORT.md     # 逐轮开发详录（15+ 轮，每轮含根因/修复/测试证据）
```

**持久化 key 清单**（SharedPreferences，模式均为 JSON 字符串）：`check-todo.v1`（打卡+待办+leaveDates+requiredMinutes）、`timetable.v2`（多课表，含 v1 迁移）、`check-todo-widget`（三视图模式 mode.<widgetId>）、`check-todo-online-import`（URL 历史）、`passive_capture_enabled`、`theme_mode`、`ai-verify.v1`（AI 判定 BYOK：base_url/model/api key）。

## 5. 教务导入架构（最复杂子系统，重点交接）

### 五通道流水线（点「提取课表」触发，全部后台线程）

1. **frame 转储（主推）**：轻发现（只列 frame 树，10s 预算，textContent 摘要不排版）→ 框架列表 UI（URL/标题/表数/摘要，默认勾选课表特征帧）→ 用户选中帧注入 `documentElement.outerHTML` 单行脚本 → ChunkBridge 分块回传（256KB/块，8MB/帧上限）→ **走 HTML 导入同款解析链**（Kingo→Scau→通用启发）。原理=「控制台 copy 指定区域」自动化，拿的是渲染后 DOM，绕开 POST/动态渲染问题
2. **被动捕获**：`PassiveCaptureClient.shouldInterceptRequest` 旁路 Tee 所有响应（仅 GET、仅 2xx、认证 URL 黑名单 17 关键词、30x 放行、响应头含 Set-Cookie 逐条透传、2MB/条 LRU 8MB 池）——**覆盖 POST 型系统**（新版正方）。安全决策全部在 `PassiveCapturePolicy` 纯函数（136 项测试）
3. **fetch 代理**：页内 `fetch(候选,{credentials:'include'})` 分块回传，失败回落 CookieManager+HttpURLConnection 直取（仅 GET，对 POST 型系统无效）
4. **深度扫描**：几何网格（GeometryGridSolver：X 最近邻→星期、Y 重叠→节次）+ 表格 + div 格子，默认不跑
5. 合并去重：课程名+星期+起止节次+周次范围+单双周

### 安全红线（登录回归的教训，勿回退）

- 只拦 GET；**永不拦 30x**（CAS/OAuth 跳转链）；认证 URL 黑名单 17 关键词；Origin/Referer/UA 头保真；**响应头必须透传 Set-Cookie**（否则会话断裂报「应用未注册」）；两层 catch(Throwable) failsafe；对话框有「被动捕获」开关可自救

### 超时与取消

无全局硬上限 + 「停止」按钮（状态机 IDLE→RUNNING→STOPPED/COMPLETED，停止保留结果可直接导入）；看门狗仅防死锁（180s 无活动）。通道级预算：发现 10s / 单帧信息 2s / 单帧 dump 8s / 深扫 12s。`evaluateJavascript` 回调可能永不触发是已知坑——所有等待必须有预算+看门狗双保险。

## 6. 关键设计决策（含踩坑记录）

1. **课表解析链**：`HtmlTimetableParser.parse()` 入口按优先级尝试结构化 parser（Kingo→Scau→通用启发），第一个产出课程者胜。新增学校 = 加 parser 类 + 注册 + 真实页面夹具断言（参考 KingoReportParserTest/ScauGridParserTest 的做法：从真实 HTML 提取夹具，断言课程门数与抽查字段）
2. **周次文法必须走 `WeekExpression`**：先剥方括号节次再匹配周次（否则 `[3-4]` 被误读为周次）；支持逗号分段（拆多条 CourseDetail）；班级名单如 `26种子科学1-2` 会伪造周次——周次行必须含「周」字
3. **小卡片三不变量+世代权威**（WidgetFactorySnapshot）：构造即冻结 (widgetId,mode)；行数与内容同源；fillInIntent 与**切换按钮**都带 EXTRA_MODE 校验（切换以「所见模式 +1」，旧代点击自愈，见 WidgetSwitchLogic）。**正文世代只跟服务 URI（与标题/切换 PI 同刻同源），onDataSetChanged 禁读实时存储**——存储在「标题上屏」与「正文重建」之间漂移曾致「标题课表对上打卡正文」（2026-10-26，needsRebuild 已删）。`getViewAt` 禁止懒加载仓库；targetSdk 34 下集合视图 template 必须 `FLAG_MUTABLE`
4. **提取重活全部后台**（onlineExecutor 单线程）：UI 线程只碰按钮/Toast/evaluateJavascript 投递；`evaluateJavascript` 回调可能永不触发——一切等待必须带预算
5. **节数单一数据源**（TimeTableDraft）：节数=nodes.size()，不单独存 int（三副本曾经脱节）；保存校验用 `validateForSave(nodes, targetCount)` 且落库同步写回 config.nodes（曾因旧节数校验新草稿导致永远保存不了）
6. **时间隐藏字符**：`stripWhitespace` 处理 `\u3000\u00A0\u200B\uFEFF` 等（trim 只去 ≤U+0020，曾致合法数据误报）
7. **手动构建链的坑**：d8 需 classes.jar 打包（Windows 通配符不可靠）；aapt2 只认 UTF-8 无 BOM 的 XML；中文 XML 属性注意编码
8. **WebVPN 页面**：华农经 `vpn.scau.edu.cn` 代理，页面被 sf-webproxy 改写——frame 发现曾因 `performance.getEntriesByType` 每文档执行+innerText 强制排版而超时，现拆为轻发现（无重活）+ 逐帧 2s 预算

## 7. 当前状态与待办

### 真机已验证
- 北师大珠海（青果）教务导入成功
- 登录回归（被动捕获破坏 CAS）已修复并验证
- 打卡/待办/多课表/通知/主题/小卡片基础功能

### 真机待验证（最近两轮的改动）
- 小卡片快速连点切换不串（快照机制）
- 华农：框架列表 10s 内列出 → 勾选课表帧 → 提取 → 14 门课预览
- 时间表保存（改节数后）、编辑零打扰+保存胶囊
- 提取进度文字在 WebView 上方不被遮挡

### 已知限制/潜在待办
- 新版正方（jwglxt）POST 型系统依赖被动捕获命中查询响应；`NewZhengFang` JSON 字段候选集未用华农真实响应体验证——解析 0 时从诊断证据包取响应体补定点规则（注册表已留插口）
- WakeUp 云端 JS 插件无法迁移（不在 APK，反编译 Java 残片 51 个不可恢复）；自建插件体系=家族 parser+校清单映射+被动捕获，覆盖率可持续增补
- debug 签名；16-20 节默认留空需手填；跨域 frame 受同源策略限制（标 [跨域] 不可 dump）
- 无 adb 设备：所有「不卡/不串」类体感结论来自代码审查+JVM 测试，需真机最终确认

## 8. 协作约定（给新 session 的执行纪律）

- 主模型：**cbc / deepseek-v4.1-flash**（codex [free]gpt-6-astra 额度已耗尽，403 确定性失败）；兜底 hy4-preview
- worker 报 error 时先查磁盘实际落盘（常见：规划完成但未写盘 / 写盘一半中断），从断点续跑不重做
- 两个 worker 严禁并行写同一工作目录（无 git）
- 文档更新义务：每轮改动必须同步 NATIVE_ANDROID_REPORT.md（新增章节）与 README.md（功能/测试数）
