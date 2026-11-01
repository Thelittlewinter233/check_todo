# 原生 Android 版制作过程与结果报告

日期：2026-10-24 ｜ 执行：codex `[free]gpt-6-astra`（Pan 编排）｜ 验收：meta-agent 独立复验

## 背景与目标

将已验收的 Vue3 Web 打卡应用（多任务自定义、周一~周日多选、打卡时间范围、连续打卡 streak、错过不补签）迁移为**纯原生 Android App**：

- 零第三方库：仅 JDK + Android 框架 API，不用 Gradle / AndroidX / Compose
- 手动构建链：`aapt2 → javac → 纯 Java 测试 → d8 → zipalign → apksigner`
- 产出可直接安装的 APK

## 环境核实（制作前亲测）

| 项目 | 结果 |
|---|---|
| JDK | ✅ openjdk 21.0.9 (Microsoft build)，`java`/`javac`/`keytool` 在 PATH |
| Android SDK | ❌ 本机原无（无 ANDROID_HOME、无 build-tools） |

解决方案：从 Google 官方仓库下载最小工具链（独立 zip，非 Android Studio）：

- `build-tools_r35.0.1_windows.zip`（SHA1 `1cbfa5564b62504a1111352edda35914cf723a20`）
  （注：build-tools 34 的 `d8` 不支持 Java 11 class 文件，故升级 35.0.1）
- `platform-34-ext7_r03.zip`（SHA1 `1f2e9478d6a7601425ceaa553311dc43191f103d`）
- 解压至 `tools/sdk/`，`prepare.bat` 负责下载/校验，可随时删除重下

## 实现结构（`native-android/`）

```
native-android/
  AndroidManifest.xml          # com.checktodo.app，minSdk 24 / targetSdk 34
  prepare.bat                  # 工具链下载 + SHA1 校验 + 解压
  build.bat                    # 一键 7 步构建链
  src/com/checktodo/app/
    MainActivity.java          # 单 Activity，今日/全部任务视图、任务编辑、打卡
    data/TaskRepository.java   # SharedPreferences + org.json 持久化
    domain/Task.java           # 任务模型
    domain/TaskStatus.java     # 未开始/可打卡/已完成/已错过/休息日
    domain/TaskLogic.java      # streak/状态机纯 Java 实现（不依赖 Android 类）
  test/StreakTest.java         # 无依赖纯 Java 测试 runner（main + System.exit）
  res/                         # 布局/shape/自适应图标/中文字符串
  out/checktodo-debug.apk      # 最终产物
```

构建链（build.bat）：`aapt2 compile/link`（含 R.java 生成）→ `javac --release 11 -Xlint:all` → 纯 Java 测试 → `d8 --release --min-api 24` → 打包 classes.dex → `zipalign` → keytool 生成/复用 debug.keystore → `apksigner` 签名。

## 过程记录

1. 首次派发：worker 完成规划（确定官方下载 URL 与图标方案）后提前收工，无任何落盘产出——通过磁盘检查识破，发续跑指令后完成全部实现。
2. 业务逻辑移植：`TaskLogic.java` 与 Web 版 `src/domain/tasks.js` 状态机逐行对应；Web 版 5 个 Vitest 用例全部移植为纯 Java 用例并补充边界用例，共 19 个。
3. 图标：复用 Web 版图标设计（绿底白色卡片 + 红色对勾），API 26+ 自适应图标 + 低版本 vector 回退。

## 验收结果（全部由 meta-agent 亲跑复验）

| 项目 | 结果 |
|---|---|
| `build.bat` 一键构建 | ✅ 完整重跑成功，产出 APK |
| 纯 Java 测试 | ✅ 19/19 通过（错过归零、非重复日跳过、边界时间等） |
| `apksigner verify` | ✅ v2 + v3 签名 Verifies（debug 证书） |
| `aapt2 dump badging` | ✅ `com.checktodo.app` / 每日打卡 / minSdk 24 / targetSdk 34 |
| APK 内容 | ✅ classes.dex、resources.arsc、自适应图标齐全，47.6 KB |
| Web 版回归 | ✅ `npm run build` + 5 测试通过，未受影响 |
| 业务规则 | ✅ 错过不补签、streak 仅按重复日累计 |

## 产物与使用

- **APK**：`native-android/out/checktodo-debug.apk`（47.6 KB，传到手机直接安装）
- 重新出包：`native-android\build.bat`（改代码后一键执行）
- 工具链缺失时：先 `native-android\prepare.bat`

## 已知限制

- 未做真机/模拟器验证（本机无设备），UI 细节需真机确认
- debug 签名，正式发布需换成自己的 release keystore
- 原生版数据存 SharedPreferences，与 Web 版 localStorage 数据互不相通

## 2026-10-24 增量功能：待办与桌面小卡片

### A. 待办功能

- 模型位于 `native-android/src/com/checktodo/app/domain/Todo.java`，字段包括 `title`、`startTime`、`endTime`、`durationMinutes`、`completed`、`createdAt`。
- 纯 Java 规则位于 `native-android/src/com/checktodo/app/domain/TodoLogic.java`，覆盖结束时间升序排序、标题非空、结束晚于开始、花费时长范围校验、待完成/已完成分组，以及完成与撤销的不可变状态转换。
- 主界面新增 `待办` 分页。待办按结束时间升序展示，`待完成` 和 `已完成` 为两个可折叠分组；点击待完成条目可直接勾选，勾选后移入已完成，已完成条目可撤销并完整保留原字段。
- 新建与编辑使用同一对话框，支持修改全部字段。开始时间默认当前时刻；结束时间由 `DatePickerDialog` 选择日期、`TimePickerDialog` 选择 24 小时制时刻，两个控件分开调用。
- 持久化沿用 `check-todo.v1` 的 `SharedPreferences + org.json` 模式，在根 JSON 中新增可选 `todos` 数组。老数据没有该字段时仍按原任务数组和打卡记录加载。

### B. 桌面小卡片

- `native-android/src/com/checktodo/app/widget/CheckTodoWidget.java` 使用 `AppWidgetProvider + RemoteViews`。
- `native-android/src/com/checktodo/app/widget/CheckTodoWidgetService.java` 使用 `RemoteViewsService/RemoteViewsFactory` 提供列表条目，避免自定义 View。
- 卡片标题旁提供 `切换` 与 `刷新` 按钮，可切换显示待完成待办或今天的打卡任务；点击标题打开主 App。
- 待办条目通过 `PendingIntent` 广播直接勾选完成；今日任务仅在状态为 `可打卡` 时可直接打卡。操作后刷新所有已注册卡片。
- `TaskRepository.persist()` 在任务或待办增删改、打卡、完成和撤销后发送显式刷新广播。卡片另有 `updatePeriodMillis=1800000` 的系统周期刷新兜底。

### 验证结果

- `native-android/test/StreakTest.java`：19/19 通过。
- `native-android/test/TodoTest.java`：12/12 通过，覆盖排序、结束早于开始被拒、完成/撤销往返、分组不改变源数据。
- `native-android/build.bat`：完整链路成功，产出 `native-android/out/checktodo-debug.apk`，大小 68,617 字节。
- `apksigner verify --verbose`：`Verifies`，v2 + v3 签名通过。
- `aapt2 dump badging`：`com.checktodo.app`、minSdk 24、targetSdk 34、`provides-component:'app-widget'`。
- 根目录 Web 版未改源码，`npm run build` 成功，`npm test` 5/5 通过。

### 新增已知限制

- 本机没有 Android 真机或模拟器，App 内待办交互与桌面小卡片的系统绑定、列表刷新和 PendingIntent 交互仍需真机验证。
- 小卡片周期刷新为 30 分钟，任务时间窗跨过后最多延迟到下一次系统刷新或数据变更刷新才会重新计算状态（刷新按钮已按需求移除）。

## 2026-10-24 增量修复：时间选择器 / 打卡日历 + 请假 / 小卡片交互

执行：接手 worker（前任 codex 中断后替换）。动手前已 Read 全部相关源码核对磁盘现状：
`Task.leaveDates`、`TaskStatus.LEAVE` 已由前任落盘（沿用，未重做），其余按本报告实现。

### 问题 1：任务时间改为选择器

- `res/layout/dialog_task_editor.xml`：两个 `EditText(input_start/input_end, inputType=time)` 改为 `Button(button_start_time/button_end_time)`，禁止自由文本；`input_start/input_end` 降级为 `visibility="gone"` 的 `TextView`，仅作值载体供校验/保存使用（保留 id，避免 R 常量与既有引用断裂）。
- `MainActivity.showTaskEditor`：时间存在 `String[] times` 中，`pickTime(title, current, callback)` 统一弹 `TimePickerDialog`（构造末参 `true` 即 `setIs24HourView(true)`），回填后同步按钮文案与隐藏值。
- 新建与编辑共用 `showTaskEditor(task)` 同一入口，两条路径都生效；`TaskLogic.validateTask` 仍对 `HH:mm` 再次校验兜底。

### 问题 2：打卡日历 + 请假

- `domain/TaskLogic.java`：新增 `isOnLeave`、`canLeave(task,target,now)`（仅今天及以前的重复日）、`calendarDayState(...)` 与 `CalendarDayState` 枚举（DONE/MISSED/LEAVE/TODAY/TODAY_ACTIVE/OFF/FUTURE）；`taskStatus` 在重复日请假时返回 `LEAVE`（`canCheckIn` 因而为 false，请假日无打卡入口）；`calculateStreak` 在当天与回溯循环中都跳过请假日期，请假不打断 streak。
- `data/TaskRepository.java`：新增 `setLeave(taskId, dateKey, leave)`；`taskToJson` 仅在非空时写 `leaveDates` 数组，`taskFromJson` 缺字段按空集合加载（老数据兼容）；`saveTask` 编辑时保留既有请假记录。
- `res/layout/dialog_task_calendar.xml`（新增）：任务名/时间范围、`‹`/`›` 翻月、`yyyy年M月` 标题、周一到周日表头、`GridLayout(7×6)`、图例、当前连续天数。
- `MainActivity.showTaskCalendar / renderCalendarGrid / showDayActions / applyLeaveChange`：白底、绿=已打卡、灰=错过、黑=请假、淡显=非重复日/未来、今天描边；点日期弹「设为请假 / 取消请假 / 取消」，未来与非重复日不提供请假项，且 `canLeave` 二次拒绝。请假后立即 `render()` 主列表 + 重绘当前月。
- 新增 drawable：`calendar_day_{normal,done,missed,leave,off,today}.xml`、`calendar_legend_*.xml`、`status_leave.xml`；`colors.xml` 增加 `statusLeaveBackground`。

### 问题 3：小卡片交互修复 + 去刷新键 + 实时同步

- `CheckTodoWidget.updateWidget`：`setPendingIntentTemplate(R.id.widget_list, templateIntent(...))`，`templateIntent` 使用 `FLAG_MUTABLE`（`Build.VERSION.SDK_INT >= S` 判定，API 31 以下追加 0），是本次「点击无效」的根因修复（原实现错误使用 `FLAG_IMMUTABLE`，系统无法合入 fillInIntent）。
- 切换按钮改为独立 `setOnClickPendingIntent(widget_switch, switchIntent(...))`（`FLAG_IMMUTABLE`），requestCode 与 `data` 按 `appWidgetId` 区分，避免多实例互相覆盖；标题点击走 `ACTION_OPEN_APP` 广播再启动 Activity。
- `CheckTodoWidgetService.Factory.getViewAt`：`setOnClickFillInIntent(widget_item_root, fillInIntent)`，写入 `itemType/itemId`；不可操作行给空 Intent 防止误触。
- `AndroidManifest.xml`：receiver 增补 `WIDGET_SWITCH / WIDGET_ITEM / WIDGET_CLICK_ITEM / WIDGET_OPEN_APP / WIDGET_REFRESH` 五个 action；`MainActivity` 加 `launchMode="singleTop"`。
- 删除 `widget_checktodo.xml` 的 `widget_refresh` 按钮及 Provider 中对应绑定（`grep -rn widget_refresh src/ res/` 已无命中）。
- 实时同步：`TaskRepository.persist()` → `WidgetRefresh.notifyDataChanged()` → 显式组件广播 → Provider `updateAll()` 立即重刷所有实例；`MainActivity` 动态注册同一 action 的 receiver（API 33+ 用 `RECEIVER_NOT_EXPORTED`），卡片操作即时回流界面。`updatePeriodMillis` 保留 30 分钟兜底。

### 本轮验证结果

- 纯 Java 测试：`StreakTest` 19/19、`TodoTest` 12/12、新增 `LeaveTest` 31/31，合计 62/62 全过（原 31 个无回归）。
- `build.bat` 完整重跑：`EXIT=0`，`javac -Xlint:all` 无警告，产出 `native-android/out/checktodo-debug.apk`（82,056 字节）。
- `apksigner verify --verbose`：`Verifies`，v2 `true`、v3 `true`。
- `aapt2 dump badging`：`com.checktodo.app`、minSdk 24、targetSdk 34、label「每日打卡」。
- 静态自检（无真机）：`grep` 证据见下方「交互链自检」。

### 交互链自检（静态证据）

| 检查点 | 证据 |
|---|---|
| template 存在且 MUTABLE | `CheckTodoWidget.java:138 setPendingIntentTemplate(...)`；`:177 FLAG_MUTABLE`（`>= VERSION_CODES.S`） |
| fillInIntent 存在 | `CheckTodoWidgetService.java:113 setOnClickFillInIntent(R.id.widget_item_root, fillInIntent)`，`:91/:92` 写入 itemType/itemId |
| 切换独立 PendingIntent | `CheckTodoWidget.java:128 setOnClickPendingIntent(R.id.widget_switch, switchIntent(...))` |
| manifest action 注册 | `AndroidManifest.xml:38-42` 五个 `WIDGET_*` action；APK 内 `aapt2 dump xmltree` 已确认 |
| 刷新键彻底移除 | `grep -rn widget_refresh src/ res/` → 无命中 |
| 数据变更出口 | `TaskRepository.java:183 WidgetRefresh.notifyDataChanged(context)` |

### 本轮已知限制

- 仍无真机/模拟器：时间选择器、月历翻月、请假点击、卡片点击与实时回流仅做了代码与静态链路验证，需真机确认。
- `startActivity` 自广播在部分定制 ROM 上仍可能被限制，标题点击已保留为「打开 App」的次要入口（ListView 条目点击为主交互）。
- 请假只能由用户手动设置，不会自动识别法定节假日；请假记录不参与任何统计展示（仅影响状态与 streak）。

## 2026-10-24 真机反馈修复：请假语义变更 / 配色 / 主题 / 卡片点击 / 星期行

执行：codex。动手前已 Read 相关源码核对现状，全部改动已落盘、已跑通完整构建与测试。

### 更正 1：请假语义（需求变更，非 bug）

- `domain/TaskLogic.calculateStreak`：请假改为打断连续打卡——回溯循环遇请假重复日直接 `break`（原为跳过）；今天请假视为未完成，streak 直接归零。请假日之后继续打卡从最近完成日重新累计。
- `domain/TaskLogic.canLeave`：由「今天及以前的重复日」收紧为「仅今天的重复日」（`dateKey(target).equals(dateKey(now))`），昨天及以前不可补请假，未来也不可；已请假的今天仍返回 true 供「取消请假」切换。
- `MainActivity.showDayActions/showLeaveRejected`：「设为请假」入口仅对今天显示（由新 `canLeave` 控制），拒绝提示改为「只能当天请假」；日历 streak 文案改为「当前连续 X 天（请假会打断）」。
- `test/LeaveTest.java`：同步改为新语义并扩充——请假打断 streak（含对照）、请假当天/错过归零、canLeave 仅今天（含 null、已请假今天可取消）。LeaveTest 由 31 个增至 38 个用例，未删减覆盖面。
- 数据结构 `leaveDates` 不变，`TaskRepository` 读写逻辑未动，老数据完全兼容。

### 更正 2：请假图例黑底黑字

- `res/layout/dialog_task_calendar.xml`：图例五项逐个显式指定高对比文字色——已打卡/请假用白字（夜间为浅底时自动切深字），错过/今天用深色字，非重复日用次级色；「请假」chip 文字由 `calendarLeaveText` 控制（亮=白，夜=深）。
- 日历请假日格数字色由 `MainActivity.textColorFor` 改为 `@color/calendarLeaveText`（亮色白字黑底；夜间请假底变浅色、字自动变深色）。
- 状态标签「已请假」文字改用 `@color/statusLeaveText`，不再是硬编码白色，夜间浅底配深字。

### 更正 3：颜色收敛 + DayNight 跟随系统

- 所有硬编码色值收敛到 `res/values/colors.xml`（新增 `onPrimary/headerSubText/segmentedBackground/tabSelectedBackground/tabTextSelected/tabTextNormal/buttonSecondaryBackground/buttonSecondaryText/danger/checkboxBorder/statusLeaveText/calendarDoneText/calendarLeaveText/calendarOff/calendarOffStroke/calendarNormalStroke/calendarTextMissed/calendarTextOff/legendBackground/legendOffStroke` 等）。
- 新增 `res/values-night/colors.xml`：背景 `#101413`、卡片 `#1b2220`、正文/次级文字、primary/accent、状态色、日历格与图例全部有深色变体。
- 主题：`values/styles.xml` parent 改为 `android:Theme.DeviceDefault.Light.NoActionBar`，新增 `values-night/styles.xml`（`android:Theme.DeviceDefault.NoActionBar` 深色变体）。框架夜间 qualifier 自动生效，跟随系统切换，未加手动开关（需求为可选）。
- 16 个 drawable（calendar_day_*、calendar_legend_*、button_secondary/danger、segmented、tab_button）全部改引 colors 资源；7 个布局的硬编码 `#ffffff/#d9e3de/#2b3230` 全部替换为颜色资源。`MainActivity` 的选中/普通 tab 文字色改用 `getColor(R.color.tabTextSelected/tabTextNormal)`。
- 小卡片：widget 背景/文字已走 `cardBackground/textPrimary/textSecondary` 资源引用，夜间自动跟随（`aapt2 dump resources` 已确认 night 配置进包）。
- 常规 UI 一致性：勾选框、圆角、间距未动布局结构，仅统一颜色 token 与对比度。

### 更正 4：小卡片整卡可点

- `CheckTodoWidgetService.Factory.getViewAt`：`setOnClickFillInIntent` 统一绑定到最外层根容器 `R.id.widget_item_root`（原对不可操作行绑空 Intent，行点击无效）。`widget_item.xml` 根容器为 `layout_width=match_parent` + `background=@drawable/card`，子视图（标题/副标题/操作按钮 TextView）均未设 clickable，不拦截事件——整行任意位置均可点。
- 无操作行：fillInIntent 只带 `appWidgetId` 不带条目数据；`CheckTodoWidget.handleItemAction` 在无 `itemId` 时兜底 `openApp(context)` 启动 MainActivity，即无操作行整行点击打开 App。可操作行（待办勾选/今日打卡）行为不变。
- 切换按钮仍为独立 `setOnClickPendingIntent`（IMMUTABLE），不经过集合模板。

### 更正 5：新建任务弹窗星期行文字重叠

- 根因：代码生成的 `CheckBox` 使用系统默认勾选框（约 32dp 宽含内边距）+ 默认 minWidth/padding，7 项等分 360dp 弹窗宽度时每格不足 46dp，文字被挤压裁切。
- 修复：新增 `res/drawable/checkbox_weekday.xml`（16dp 自绘勾选框：选中=primary 圆角块+白色对勾 vector `ic_check_small.xml`，未选=1.5dp 描边）与 `res/drawable/ic_check_small.xml`；`MainActivity.showTaskEditor` 中每个星期 CheckBox `setButtonDrawable(checkbox_weekday)`、`setMinWidth/ setMinimumWidth/setMinHeight/setMinimumHeight(0)`、`setPadding(6dp,8dp,0,8dp)`、`textSize 13sp`、等分权重（`LayoutParams(0, WRAP, 1f)`）不变。每格实际占用约 16+6+14≈36dp < 46dp，7 项完整显示不重叠；勾选框与文字色均走颜色资源，夜间正常。

### 本轮验证结果

- 纯 Java 测试：`StreakTest` 19/19、`TodoTest` 12/12、`LeaveTest` 38/38，合计 **69/69** 全过（原 62 个用例无回归，LeaveTest 净增 7 个）。
- `build.bat` 完整重跑：`EXIT=0`，`javac -Xlint:all` 无警告，apksigner `Verifies`（v2/v3 true）。
- `aapt2 dump badging`：`com.checktodo.app`、每日打卡、minSdk 24、targetSdk 34、`provides-component:'app-widget'`。
- `aapt2 dump resources`：night qualifier 颜色配置已进包。
- 产物：`native-android/out/checktodo-debug.apk`，86,308 字节。
- Web 版未改动（`src/`、`public/`、`android/` 零变更）。

### 本轮已知限制

- 无真机/模拟器：夜间配色对比度、星期行 360dp 实测、整卡点击手感仍需真机确认（静态证据：根容器绑定 + match_parent + card 背景 + 子视图无 clickable）。
- 夜间主题跟随系统（`values-night` qualifier），未提供 App 内手动亮/暗切换入口（需求中标注为可选）。
- 旧数据中「今天之前」的请假记录仍保留并继续打断 streak（语义变更后视为未完成日），属预期行为。

## 2026-10-24 第二轮增量：未来预请假、待办状态机、卡片瘦身与流畅度

### 1. 请假规则：今天 + 未来重复日

- `domain/TaskLogic.java` 的 `canLeave` 改为 `dateKey(target) >= dateKey(now)`：今天与未来的重复日均可设置请假，过去仍拒绝。取消请假不受限制（该日已有请假时 UI 显示「取消请假」）。
- `calendarDayState` 将 `LEAVE` 判定提前到 `FUTURE` 判定之前：未来预请假日直接显示请假色，未到日期无打卡入口；日期到来后 `taskStatus` 返回 `LEAVE`、`canCheckIn` 为 false，不算完成、不算错过。
- `calculateStreak` 逻辑未改：回溯只看过去与今天，未来日期本就不参与；今日请假归零与历史请假打断语义保持原样（原用例零回归）。
- `MainActivity.showDayActions/showLeaveRejected`：日历点今天或未来的重复日均出现「设为请假」；拒绝提示文案改为「过去不能补请」。`TaskRepository.setLeave/leaveDates` 存储结构零改动，旧数据兼容。

### 2. 待办状态 + 小卡片过滤

- 新增 `domain/TodoStatus.java`（未开始/进行中/已过期/已完成）与 `TodoLogic.status(todo, now)` 纯函数：完成态优先，其次按开始/截止时间区间判定；结束时刻等号归入「已过期」。
- 新增 `TodoLogic.filterWidgetTodos(todos, now)`：已过期全部显示、进行中全部显示、未开始仅显示开始时间在未来 48 小时内的（`todo.startTime - now > 48h` 才排除，恰 48 小时含边界显示）、已完成不显示；输出保持截止时间升序，过期条目截止时间最早，自然位于待完成分组顶部。
- `CheckTodoWidgetService` 待办模式改用 `filterWidgetTodos`，条目副标题增加状态标签。App 列表每条待办显示状态标签：未开始=灰、进行中=绿、已过期=红（`status_missed` 背景 + `statusMissedText` 文字，夜间有红色变体）、已完成=绿灰；过期待办标题同步红色强调。

### 3. 待办卡片瘦身

- `res/layout/item_todo.xml` 重写：左侧 30dp 圆形勾选框（`todo_checkbox_empty/done.xml`，未完成=描边空圈、完成=primary 实心+白色对勾，点击即完成/撤销）；中部标题单行省略 + 状态标签 + 第二行「截止时间 + 花费时长」；右侧 36dp「⋮」溢出按钮弹出 `PopupMenu`（编辑/删除）。
- 原右侧竖排四个 44dp 按钮（完成/撤销/编辑/删除）全部移除；卡片 padding 从 14dp 收敛到 10/12dp、间距从 10dp 收敛到 8dp，高度由约 172dp 降至约 64dp。颜色全部走 `values/values-night` 资源，深浅色自动跟随。

### 4. 流畅度优化（实际落地点）

- 勾选/撤销待办：`onTodoCheckClicked` 不再 `render()` 整页重建。单条视图 `bindTodoView` 就地更新，目标分组展开时用 `removeView/addView(index)` 在分组间移动该视图；目标分组收起时直接移除该视图；分组标题计数就地改文本。持久化仍走 `persist()` → widget 广播刷新。
- 打卡：`bindTaskView` 与 `updateTaskItem` 共用同一条绑定路径，打卡成功后只更新该任务卡片（状态标签、连续天数、打卡按钮可见性），不再 `removeAllViews`。
- 30 秒周期刷新：`refreshRunnable` 改走 `refreshTick()`，只对已渲染视图做状态更新（标签/连续天数/勾选外观），视图不增删；仅当跨天（`dateKey` 变化）才整页重渲染。
- 月历复用：42 个 `TextView` 在打开日历时创建一次并绑定一次点击监听（日期存 tag），翻月与请假变更只更新文本/背景/颜色/alpha/tag，不再每次重建 42 个视图与 42 个监听器；复用时 alpha 显式重置。
- 主线程 IO 排查：全部写入走 `SharedPreferences.edit().apply()`（异步落盘），`TaskRepository` 与 `CheckTodoWidget` 均无 `commit()`；`onResume` 的 `repository.reload()` 保留（用于同步暂停期间小卡片的写入，数据量为单键 JSON）。
- 不做的伪优化：小卡片保留 `notifyAppWidgetViewDataChanged`，这是 RemoteViews 集合视图在零依赖下的标准最小刷新粒度，无进一步局部化空间。

### 本轮验证结果

- 纯 Java 测试：`StreakTest` 19/19、`TodoTest` 25/25（净增 13：状态机 6 + 过滤 7）、`LeaveTest` 54/54（净增 16：今天+未来可请、过去拒绝、预请假日到达后 LEAVE 且不可打卡、取消预请假恢复、日历未来请假显示），合计 **98/98** 全过（原 69 个语义用例零回归）。
- `build.bat` 完整重跑：`EXIT=0`，`javac -Xlint:all` 无警告，`apksigner verify --verbose` 输出 `Verifies`，v2=true、v3=true。
- `aapt2 dump badging`：`package: name='com.checktodo.app'`、`application-label:'每日打卡'`、`minSdkVersion:'24'`、`targetSdkVersion:'34'`、launchable-activity 为 `com.checktodo.app.MainActivity`。
- `aapt2 dump resources`：`(night)` 颜色配置在包内；新资源 `drawable/todo_checkbox_done`、`drawable/todo_checkbox_empty`、`id/todo_checkbox` 均已进包。
- 产物：`native-android/out/checktodo-debug.apk`，90,566 字节。
- Web 版零改动（`src/`、`public/`、`android/`、`vite.config.js` 未触碰）。

### 本轮已知限制

- 待办「已过期」判定按渲染时刻计算；后台被杀后重新打开或 30 秒周期刷新时更新。
- 预请假只针对重复日；若之后编辑任务去掉该星期，请假记录保留但该日不再重复，日历按非重复日展示。
- 待办排序整体按截止时间升序（已过期天然在顶部）；未提供单独的「仅过期置顶、其余按开始时间」排序开关。


## 2026-10-24 第三轮真机反馈修复：App 内主题切换 + 重复日期竖排

### 1. 主题切换改为 App 内手动图标（亮色 / 深色 / 跟随系统）

- 新增 `domain/ThemeMode.java`（纯 Java 枚举，无 Android 依赖）：LIGHT/DARK/FOLLOW_SYSTEM，持久化值 light/dark/follow_system；`fromString` 对 null、空串、未知值一律回落 FOLLOW_SYSTEM（旧安装无该键时行为不变）；`next()` 定义点击循环顺序 亮色→深色→跟随系统→亮色。
- `MainActivity.attachBaseContext`：读取 SharedPreferences（沿用 check-todo 文件，新键 theme_mode）后，显式模式通过 `base.createConfigurationContext` 强制 uiMode 夜间位（DARK=UI_MODE_NIGHT_YES、LIGHT=UI_MODE_NIGHT_NO），FOLLOW_SYSTEM 不改配置。这是零 AndroidX 下最稳妥的做法：资源限定符照常生效，显式深色也能命中 values-night/colors.xml 与 values-night/styles.xml 的深色主题父类（Theme.DeviceDefault.NoActionBar），显式亮色命中 values 的 Light 父类；主题仍统一为单一 AppTheme，无需 setTheme 分支。系统配置变化（如系统切换深浅色）会重新走 attachBaseContext，强制位自动重放。
- `activity_main.xml` 标题栏在「新建」按钮左侧新增 44dp `ImageButton(button_theme)`，背景 `drawable/theme_button.xml`（半透明白描边圆 + ripple）。三态图标为自绘 vector（`ic_theme_light` 太阳 / `ic_theme_dark` 月亮 / `ic_theme_auto` 半实心圆），填充与描边用 `@color/onPrimary`，在主色头部上深浅两种模式均可见。
- `MainActivity` 点击处理：`currentThemeMode.next()` → 写回 theme_mode → Toast 提示新模式名 → `recreate()`，新实例 attachBaseContext 重新套用偏好，立即生效；图标与 contentDescription 由 `updateThemeIcon()` 按当前模式刷新。
- 小卡片取舍：RemoteViews 由桌面进程用桌面自己的配置取资源，无法跟随 App 内的强制 uiMode，卡片继续按系统深浅色渲染（values-night 资源照常生效）。要让卡片也手动切换需为深/浅各做一套卡片布局并在刷新时选择，成本高收益低，本轮按需求说明保留跟随系统。

### 2. 重复日期布局：勾选框在上、文字在下

- `MainActivity.showTaskEditor` 星期行重写：每周为一个纵向 `LinearLayout` 列（Gravity.CENTER_HORIZONTAL，minHeight 44dp），列内上=16dp 自绘勾选框（`checkbox_weekday`，去掉 CheckBox 默认 padding/minWidth/minHeight），下=13sp 文字（一~日），7 列等分权重横排。dialog 内容宽约 320dp 时每列约 45dp，竖排后单列仅需容纳 16dp 勾选框与单字标签，360dp 宽度下不拥挤、不重叠。
- 点击热区：整列绑定点击代理（`column.setOnClickListener` → `checkBox.toggle()`，CheckBox 本身 setClickable(false) 避免双重触发），热区为整列 45x44dp；列背景解析 `android.R.attr.selectableItemBackground` 提供点按反馈。
- 选中态强化：勾选时文字变为 `@color/primary` 加粗，未选中为 `textPrimary` 常规，深浅色均走颜色资源（夜间 primary 为更亮的 #2f6b59 变体）。
- 复用检查：`checkbox_weekday` 仅 dialog_task_editor 的星期行使用；打卡日历（dialog_task_calendar）的周表头是独立的 `CalendarWeekLabel` 纯文本样式，不受影响，无需改动。

### 测试与构建

- 新增 `test/ThemeModeTest.java` 12 个纯 Java 用例（已知值解析、null/空串/未知值/大小写回落、循环顺序、循环闭合、value()↔fromString 往返），并入 build.bat 第 4 步。
- 全部测试：StreakTest 19/19、TodoTest 25/25、LeaveTest 54/54、ThemeModeTest 12/12，合计 110/110，原 98 用例零回归。
- `build.bat` 完整重跑 EXIT=0，javac -Xlint:all 无警告；apksigner verify：Verifies，v2=true、v3=true；aapt2 dump badging：com.checktodo.app / 每日打卡 / minSdk 24 / targetSdk 34；aapt2 dump resources 确认 values-night 配置与新增 drawable（ic_theme_light/dark/auto、theme_button）、string（theme_*）、id（button_theme）均已进包。

## 2026-10-24 第三轮需求：打卡统计 + 任务卡瘦身

### 1. 打卡统计（口径与实现）

- 新增 `domain/TaskStats.java`（纯 Java，零 Android 依赖）：`compute(task, completedDates, now)` 返回 totalDays / completedDays / ratePercent。
- 口径：总天数 = 从创建日（含当天）到今天之间属于**当前**重复日的天数（非重复日、未来不计）；打卡天数 = 该区间内已完成打卡且日期 ≤ 今天、且落在当前重复日上的天数（与总天数同一口径，编辑重复日后旧打卡不计入，保证 0..100% 不会溢出）；打卡率 = 打卡天数 ÷ 总天数，四舍五入取整。
- 边界：总天数为 0（创建当天且非重复日）时 totalDays=0、ratePercent=-1，UI 显示「0/0 天 --」且隐藏进度条。
- createdAt 兜底：Task 已有并持久化 `createdAt` 时间戳（`TaskRepository.taskToJson/taskFromJson` 读写，新建任务写入 `System.currentTimeMillis()`，编辑保留旧值）。老数据缺失（optLong 默认 0）时取最早的 ≤今天 打卡日期作为创建日（数据驱动，能还原真实历史区间）；没有任何打卡记录则回落到今天（统计为 0/0 或 0/1）。createdAt 晚于今天的时钟异常会被钳制到今天，避免负区间。
- UI（item_task.xml + MainActivity.bindTaskView）：次行显示「打卡天数/总天数 + 百分比」，如「5/9 天 56%」，右侧下方 3dp 水平细进度条（自绘 `drawable/task_progress.xml`：轨道 segmentedBackground、进度 primary，clip layer，零第三方 View）。
- 用例（`test/TaskStatsTest.java`，19 个）：基本周 2/3=67%、跨周 9 天全打卡 100%、5/9 四舍五入 56%、创建当天重复日 0/1→1/1、创建当天非重复日 0/0 与 rate=-1、编辑重复日后按 TuTh 重算且旧周一打卡不计、未来打卡排除、创建前打卡排除、老数据最早打卡兜底、老数据无打卡回落今天（重复/非重复两种）、未来 createdAt 钳制。

### 2. 任务卡瘦身（item_task.xml + bindTaskView）

- 主行：任务名（16sp 加粗、单行省略）+ 状态标签（11sp chip，沿用五色状态背景）+ 右侧「连续 N 天」（12sp accent）。
- 次行：时间范围 + 重复日缩写（一三五）+ 统计（5/9 天 56%），全部 12sp 次级色，单行省略；下接 3dp 进度条。
- 操作收敛：打卡改为左侧 30dp 圆形勾选区（今日视图可打卡时为 accent 圆环、醒目可点，点击后局部刷新；今日已完成为 primary 实心 + ✓；未开始/已错过/请假/休息为灰环 35% 透明度且不可点）。编辑/删除收进右侧「⋮」36dp 溢出按钮的 PopupMenu，仅「全部任务」视图显示——保持今日视图不可编辑、全部视图不可打卡的原语义。
- 布局参数对齐待办卡：card 背景、padding 12/10/10/10、marginBottom 8dp、minHeight 64dp；对照原版（padding 16 + 右侧竖排 48/44/44 三按钮），高度由约 170dp 收敛到约 72dp。
- 颜色全部走 values/values-night 资源（accent/primary/segmentedBackground/cardBackground/textPrimary/textSecondary），深浅色自动适配。
- 小卡片未改：widget 用独立的 widget_item.xml，交互与渲染零变更。
- 兼容：持久化结构零改动（沿用既有 createdAt 字段），Web 版零改动。

### 验证结果

- 纯 Java 测试：StreakTest 19/19、TodoTest 25/25、LeaveTest 54/54、ThemeModeTest 12/12、TaskStatsTest 19/19，合计 **129/129** 全过（原 110 用例零回归）。
- `build.bat` 完整重跑：EXIT=0，javac -Xlint:all 无警告，产出 `native-android/out/checktodo-debug.apk`（99,218 字节）。
- `apksigner verify --verbose`：Verifies，v2=true、v3=true。
- `aapt2 dump badging`：com.checktodo.app / 每日打卡 / minSdk 24 / targetSdk 34。
- `aapt2 dump resources`：新资源 drawable/task_check_active、drawable/task_progress 与 id/task_check、task_overflow、task_weekdays、task_stats、task_progress 均已进包；values-night 配置仍在包内。

### 本轮已知限制

- 打卡率按「当前重复日」口径：编辑重复日后历史统计会随之变化（旧重复日的打卡不再计入），属口径定义内的预期行为。
- 无真机/模拟器：圆环打卡区手感、进度条视觉与 ⋮ 菜单实测需真机确认。
- 统计中请假日按重复日计入总天数但不计入打卡天数（需求未定义请假豁免，按字面口径执行）。

## 2026-10-24 第四轮真机反馈：取消今日打卡 + 今日排序

### 1. 当天已打卡可取消（防误点）

- `data/TaskRepository.cancelCheckIn(taskId, now)`：只从该任务的 `completedDates` 中移除 `TaskLogic.dateKey(now)`（今天的键），保留历史记录，写回 `SharedPreferences` 后由 `persist()` 触发小卡片广播刷新，返回是否确有移除。历史日期在数据层就没有入口，符合「取消入口仅限今天」。
- `MainActivity`：今日视图下，已完成任务的左侧实心圆形勾选区绑定点击 → `confirmCancelCheckIn(task)` 弹出 `AlertDialog`（标题「取消今日打卡？」，确定/取消）。确认后调用 repository，随后 `updateTaskItem` 就地刷新该卡片的状态标签、连续天数、打卡率与统计文本，再 `refreshTaskItemPosition` 按新排序把卡片移到正确位置。全部视图的勾选区仍不可点，保持「今日视图才可打卡/取消」的原语义；请假状态与流程不变（请假日 `canCheckIn` 为 false，不会进入该分支）。
- 小卡片链路：`CheckTodoWidgetService.Factory.getViewAt` 对 `TaskStatus.ACTIVE` 与 `TaskStatus.DONE` 都写入 `itemType/itemId`，已完成行的动作文案由「已完成」改为「取消」。`CheckTodoWidget.handleItemAction` 收到 `TYPE_TASK` 后先查任务并判断 `TaskLogic.canCheckIn`：可打卡则直接 `repository.checkIn`；否则若今天已在 `completedDates` 中，就以 `EXTRA_CANCEL_TASK_ID` extra 启动 `MainActivity`（`FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_SINGLE_TOP`）。`MainActivity` 在 `onCreate` 与新增的 `onNewIntent` 中调用 `maybeShowCancelConfirm()`，读取并移除该 extra 后走与 App 内完全相同的确认对话框，因此确认口径、持久化与刷新行为两端一致。RemoteViews 无法弹 Dialog，这是零依赖下的标准折中：卡片点击后短暂拉起 App 完成确认。
- 统计/日历自动跟随：取消只改 `completedDates`，`TaskStats.compute`、`TaskLogic.calculateStreak` 与 `calendarDayState` 都以该集合为唯一输入，无需特殊分支；日历视图重新打开即反映取消后的状态。

### 2. 排序：时间更紧的未打卡任务置顶

- 新增 `domain/TaskLogic.sortForToday(List<Task>, Map<String, Set<String>> completedDates, Date now)`（纯 Java，无 Android 依赖），排序键为 `todayRank`：ACTIVE=0、DONE=1、MISSED=2、UPCOMING=3、LEAVE=4、OFF=5；同 rank 内按 `endTime` 升序（越紧急越靠上），同 `endTime` 按任务名字典序，保证结果确定、与输入顺序无关。
- 最终口径说明：「今天未打卡且今天需要打卡」即 ACTIVE（含时间窗内的 MISSED/UPCOMING 按需求分别排在其后）；已完成今天任务紧随其后；之后是已错过、未开始（均按 endTime 升序）；请假与非今日重复日（OFF）排在最后。全部视图同样用该 comparator（OFF 项按 endTime 升序落在末尾），取「确定性 + 与今日视图一致」而非维持数据库插入顺序——插入顺序在编辑任务后会变化，排序结果不可预期。
- `MainActivity`：新增 `visibleTasks(now)` 统一完成「按 mode 过滤 + `completedDatesByTask()` 收集每个任务的完成集合 + `sortForToday`」，`renderTasks` 与 `refreshTaskItemPosition` 共用它。`refreshTaskItemPosition` 只在 TODAY 模式下把目标视图 `removeView` 后 `addView(targetIndex)` 到排序后的位置，属于单条视图移动，不整页重建，无闪屏；勾选与取消打卡成功后都调用它，因此卡片会就地移动到新的分组位置。ALL 模式不做移动（排序对全部视图整体稳定，无需局部搬移）。
- `CheckTodoWidgetService.Factory.onDataSetChanged` 在过滤出今天已排期任务后调用同一个 `sortForToday`，App 与小卡片今日顺序完全一致；待办模式排序逻辑未变。

### 测试与构建

- 新增 `test/TaskOrderTest.java` 7 个用例：两个 ACTIVE 任务按 endTime 升序；ACTIVE 先于 DONE；完整分组顺序 ACTIVE→DONE→MISSED→UPCOMING→LEAVE；OFF 任务排在已排期任务之后、LEAVE 先于 OFF；同 endTime 按名称回退且结果与输入顺序无关。
- 新增 `test/CancelTest.java` 16 个用例：取消后在时间窗内回到 ACTIVE 且可再打卡、过窗后变 MISSED、开窗前变 UPCOMING；streak 从 2 降到 1，且取消历史日不影响今天；`TaskStats` 由 3/3=100% 变 2/3=67%、重新打卡后回到 100%；取消只移除今天的键、历史日期仍在、日历历史仍为 DONE。
- 全部测试：StreakTest 19/19、TodoTest 25/25、LeaveTest 54/54、ThemeModeTest 12/12、TaskStatsTest 19/19、TaskOrderTest 7/7、CancelTest 16/16，合计 **152/152**，原 129 用例零回归。
- `build.bat` 完整重跑：EXIT=0，`javac -Xlint:all` 无警告；`apksigner verify --verbose` 输出 `Verifies`，v2=true、v3=true（v3.1/v4 为 false，与既有产物一致）。
- `aapt2 dump badging`：`package: name='com.checktodo.app'`、`application-label:'每日打卡'`、`minSdkVersion:'24'`、`targetSdkVersion:'34'`、launchable-activity 为 `com.checktodo.app.MainActivity`。
- `aapt2 dump resources`：`(night)` 颜色配置在包内；`drawable/ic_theme_light|dark|auto`、`drawable/theme_button`、`style/AppTheme`、`layout/widget_checktodo`、`layout/widget_item` 与相关 id/string 均已进包。
- 产物：`native-android/out/checktodo-debug.apk`，99,218 字节（本轮只改 Java 与脚本，无新增资源，体积与上一轮一致）。
- Web 版零改动（`src/`、`public/`、`android/`、`vite.config.js` 未触碰）；持久化结构未变，`completedDates` 仍是任务级日期键集合。

### 本轮已知限制

- 小卡片上取消打卡需要拉起 App 弹确认框（RemoteViews 不能展示 Dialog），会有短暂的前台切换；打卡本身仍在卡片内直接完成。
- 无真机/模拟器：确认框文案排版、卡片跳转后的 `singleTop` 复现路径与就地移动的动画观感需真机确认。
- `persist()` 会广播刷新，主界面随后可能收到一次全量 `render()`，与就地移动叠加时最多多一次重绘，功能与顺序结果不受影响。

## 2026-10-24 第五轮需求：统计起算日修正 + 本地通知提醒

### 1. 创建已过当日截止时间：从第二天计数

- 位置：`native-android/src/com/checktodo/app/domain/TaskStats.java` 的 `resolveStatsStartKey(task, completedDates, todayKey)`。
- 规则：先沿用既有 `createdAt` 兜底（缺失时最早打卡日，其次今天；未来 `createdAt` 钳制到今天）。若 `createdAt > 0` 且创建时刻的当日分钟数大于 `endTime`（例如 23:00 创建、截止 22:00），统计起算日推到第二天；创建当天不计入总天数，不会在卡片上立刻出现 0/1 与 0%。截止前创建仍从创建日（含）起算。
- `TaskLogic.minutesOfDay` 与 `TaskLogic.parseTime` 改为 public，供统计层复用；`calculateStreak` 未改，创建日没有历史打卡时 streak 本就从 0 开始。编辑任务不改 `createdAt`，因此不受影响。
- 日历取舍：`calendarDayState` 未做统计起算日特殊分支。若创建当天是重复日且已过截止时间，月历中该天仍按既有状态机显示灰色 MISSED，保留「错过不补签」的直观历史；卡片统计按新规则不把该天计入。
- 测试：`TaskStatsTest` 新增 5 个断言（截止后创建当天 0/0、下一个重复日从 10-26 起算、截止前创建当天计入），总数 19→22。

### 2. 任务完成所需时间与通知提醒

- 模型与持久化：`Task` 新增 `requiredMinutes`（默认常量 60）；`TaskRepository.saveTask` 6 参版本写入，JSON 读写 `requiredMinutes`，老数据 `optInt(..., 60)` 兜底，重新保存后字段补齐。编辑任务保留 `createdAt` 与请假记录。
- 编辑器：`dialog_task_editor.xml` 新增「完成所需时间（分钟）」区域，4 个 40dp 预设 chip（15/30/60/120，选中 primary）+ 64dp 数字自定义输入；`MainActivity.showTaskEditor` 统一维护选择态并在选择时间后实时更新「提醒时刻」预览。输入范围 1~1440，非法值在保存时由 `TaskLogic.validateTask` 返回错误。
- 公式（纯 Java `domain/ReminderLogic.java`）：`提醒时刻 = 截止时间 - 完成所需时间 - 60 分钟缓冲`。打卡任务取当天零点 + `endTime` 分钟数 − `requiredMinutes` − 60；待办取绝对 `endTime` − `durationMinutes` − 60。例：08:00–22:00、所需 60 分钟 → 20:00 提醒。提醒早于当前时间则当天不调度；截止 01:00 时提醒可能落到前一天 23:00，同样不补发。
- 调度条件：任务要求今天为重复日、未完成、未请假、状态为 ACTIVE/UPCOMING 且提醒时刻在未来；待办要求未完成、截止日在今天、截止时间与提醒时刻均在未来。打卡、完成待办、请假、删除、编辑后由 `TaskRepository.persist()` 全量重算并取消不再需要的闹钟；小卡片完成待办同样走 repository 持久化，因此自动取消提醒。
- Android 实现：`notification/ReminderScheduler.java` 创建 `reminders` NotificationChannel（API 26+，IMPORTANCE_HIGH，系统默认声音 + 震动 + 灯），为每个符合条件的条目注册 `AlarmManager.RTC_WAKEUP` 一次性闹钟，并注册次日 00:00 的全量重算兜底。API 31+ 先 `canScheduleExactAlarms()`，有权限用 `setExactAndAllowWhileIdle`，否则用 `setAndAllowWhileIdle`（可能被系统延迟到 Doze 维护窗口，但功能不丢）。所有 PendingIntent 使用 `FLAG_IMMUTABLE`。
- 接收器：`notification/ReminderReceiver.java` 处理 REMINDER/BOOT_COMPLETED/DAILY。触发时重新读取数据并按 `ReminderLogic` 二次校验，避免打卡后旧闹钟仍打扰；通过则发送「打卡提醒/待办提醒」通知，正文含名称、截止时间与完成所需时间，点击打开 MainActivity。`AndroidManifest.xml` 声明 `POST_NOTIFICATIONS`、`RECEIVE_BOOT_COMPLETED` 与 exported=false 的 receiver。
- 权限取舍：MainActivity 首次运行时（API 33+）申请一次 `POST_NOTIFICATIONS`，结果记录在 `notification_permission_asked`，拒绝后不再重复请求；闹钟仍会触发，但系统会拦截通知展示，应用不崩溃。精确闹钟权限不主动跳设置页，系统未授权时自动降级为非精确闹钟。

### 验证结果

- 新增 `test/ReminderTest.java` 23 个用例：默认 60、自定义 15、老数据 0 兜底、任务可调度/已完成/请假/非重复日/已过截止/提醒已过、跨到昨天不调度、待办公式与状态过滤、通知正文、1~1440 边界。
- 全部测试：Streak 19、Todo 25、Leave 54、ThemeMode 12、TaskStats 22、TaskOrder 7、Cancel 16、Reminder 23，合计 **178/178** 通过（原 152 个零回归）。
- `native-android\build.bat` 完整重跑 EXIT=0；`javac --release 11 -Xlint:all` 仅保留 `Notification.Builder(Context)` 的 API 24 兼容 deprecation 提示，非错误。产物 `native-android/out/checktodo-debug.apk`，107,634 字节。
- `apksigner verify --verbose`：`Verifies`，v2=true、v3=true，signers=1。
- `aapt2 dump badging`：`com.checktodo.app` / 每日打卡 / minSdk 24 / targetSdk 34 / launchable MainActivity，`POST_NOTIFICATIONS` 与 `RECEIVE_BOOT_COMPLETED` 在 uses-permission 列表。
- `aapt2 dump xmltree AndroidManifest.xml`：`.notification.ReminderReceiver` 已注册；`aapt2 dump resources`：`drawable/ic_notification`、`drawable/chip_background`、`color/chip_text`、`string/channel_reminders`、`string/notification_task_title`、`string/notification_todo_title` 与既有 `(night)` 资源均在包内。
- Web 版零改动（`src/`、`public/`、`android/` 未触碰）；`requiredMinutes` 为原生 JSON 可选字段，老安装兼容。

### 本轮已知限制

- 本机无真机/模拟器：通知声音/震动、Doze 下精确闹钟触发、API 33 权限弹窗、重启后的 BOOT_COMPLETED 恢复均需真机验证。
- API 31+ 用户未授予「闹钟和提醒」精确闹钟权限时，提醒会延迟到系统允许的窗口；不会丢失，但分钟级精度无法保证。
- 通知权限拒绝后静默降级，应用内不再主动引导；Android 13+ 用户需去系统设置手动开启。
- 日历保留创建日灰色 MISSED 的展示取舍，卡片统计与日历视觉在创建晚于截止的当天可能不同。

## 2026-10-24 第六轮真机反馈：创建日状态 + 通知链路排查

### 1. 截止后创建当天显示「未开始」

- `domain/TaskLogic.java` 新增公开入口 `statusOf(task, completedDates, now)`；原 `taskStatus(...)` 改为委托调用，App 与测试可共用同一状态机。状态判定顺序为：非重复日 OFF、请假日 LEAVE、今天已完成 DONE，然后若「今天是截止后创建日」则直接返回 `UPCOMING`，最后走原来的时间窗状态机。
- 新增共享规则 `createdAfterDailyEndTime(task)` 与 `isDeferredCreationDay(task, now)`：前者比较 `createdAt` 当日分钟数与 `endTime`，后者判断今天是否正好是这个创建日。`TaskStats.resolveStatsStartKey(...)` 改为直接复用 `createdAfterDailyEndTime`，统计起算日与 UI 状态使用同一条规则，不再各自做时间比较。
- 最终口径：创建时刻已过当日截止时，创建当天统计不计入且卡片显示「未开始」；第二天起恢复原状态机（开始前 UPCOMING、窗口内 ACTIVE、截止后 MISSED）；截止前创建的任务不受影响，当天截止后仍显示「已错过」。
- 日历取舍：当前版本的 `calendarDayState` 未把「截止后创建」单独建模。创建当天作为重复日时按普通今天描边渲染，历史创建日仍可能显示灰色 MISSED；本需求只约束卡片/列表状态，不扩大月历规则。
- `test/ReminderTest.java` 新增 7 个断言：截止后创建当天为 UPCOMING；同一任务当天不调度提醒；第二天开始前/窗口内/截止后分别回到 UPCOMING/ACTIVE/MISSED；截止前创建当天截止后仍是 MISSED；截止前创建且提醒已过时不调度。

### 2. 通知链路逐项排查与修复

| 检查点 | 结论与修复 |
|---|---|
| 调度时机 | 四条路径齐全：`MainActivity.onCreate()` 与 `onResume()` 都调用 `ReminderScheduler.rescheduleAll(this)`；数据每次持久化后全量重算；`ReminderReceiver` 收到 `BOOT_COMPLETED` 后重调度；每日零点兜底闹钟触发后重算。安装后只要打开一次 App，即会建立提醒闹钟。 |
| receiver 注册 | `AndroidManifest.xml` 静态注册 `.notification.ReminderReceiver`，监听 `REMINDER`、`REMINDER_DAILY`、`BOOT_COMPLETED`，不依赖动态注册；Alarm PendingIntent 使用显式 `ComponentName`，`requestCode` 由 type+id 派生，测试通知与每日兜底使用独立 code，不冲突。 |
| 精确闹钟 | 统一走 `RTC_WAKEUP`。API 24–30 直接 `setExactAndAllowWhileIdle`；API 31+ 先 `canScheduleExactAlarms()`，可用则精确，否则降级 `setAndAllowWhileIdle`。降级可能被 Doze/系统省电延迟，但路径已确认在用且会触发。 |
| 通知渠道 | `reminders` channel 由 `ensureChannel()` 创建，`IMPORTANCE_HIGH`、默认声音、震动与呼吸灯；`ReminderReceiver.postNotification()` 在每次 notify 前再次 ensure，防止冷进程首次触发时渠道缺失。渠道创建后系统不允许提升既有重要性，若老安装已创建低重要性格，需删除数据或卸载重装才会得到 HIGH 行为。 |
| smallIcon | 使用有效资源 `drawable/ic_notification`，`aapt2 dump resources` 已确认进包，避免部分 ROM 因无效图标静默丢通知。 |
| 通知权限 UX | API 33+ 首次进入先弹说明对话框；点「去开启」调用系统原生 `requestPermissions(...)`；被永久拒绝（`shouldShowRequestPermissionRationale=false`）时引导到系统应用详情页；已授权不弹。授权结果回调成功后立即全量重调度，拒绝仅提示不崩溃。 |
| 进程被杀唤醒 | Receiver manifest 注册，闹钟 `RTC_WAKEUP`，无动态注册依赖；App 强停/进程死亡后系统仍可拉起 receiver。中文 ROM 的激进省电/自启动限制属系统层限制。 |
| 排查日志 | `CheckTodoReminder` 输出通知权限状态、exact/inexact 闹钟模式、每个下一提醒时间与无提醒结论；receiver 输出权限拦截、成功 posting。可用 `adb logcat -s CheckTodoReminder` 直接定位。 |
| 测试通知 | 主界面右上角新增「⋮」按钮，菜单提供「发送测试通知」；点击后 5 秒触发，走与真实提醒相同的 `AlarmManager → PendingIntent → ReminderReceiver → NotificationChannel → notify()` 链路，未授权时先进入权限引导。 |

### 验证结果

- 全部 JVM 测试：Streak 19、Todo 25、Leave 54、ThemeMode 12、TaskStats 22、TaskOrder 7、Cancel 16、Reminder 30，合计 **185/185** 通过（原 178 个零回归，Reminder 23→30）。
- `native-android\build.bat` 完整重跑 EXIT=0，日志见 `native-android\build_verify.log`；`javac` 仅保留 API 24 兼容下 `Notification.Builder(Context)` 的 deprecation 提示，非错误。
- `apksigner verify --verbose`：`Verifies`，v2=true、v3=true、signers=1。
- `aapt2 dump badging`：`com.checktodo.app` / 每日打卡 / minSdk 24 / targetSdk 34，`POST_NOTIFICATIONS` 与 `RECEIVE_BOOT_COMPLETED` 在包内。
- `aapt2 dump xmltree AndroidManifest.xml`：`.notification.ReminderReceiver` 与三个 action 已确认；`aapt2 dump resources`：`drawable/ic_more`、`drawable/ic_notification`、测试/权限字符串及既有 `(night)` 资源均进包。
- 产物：`native-android\out\checktodo-debug.apk`，111,801 字节。Web 版零改动（`src/`、`public/`、`android/` 未触碰）。

### 本轮已知限制

- 无真机/模拟器：通知声音、震动、厂商省电策略、BOOT 后恢复与 API 33 权限弹窗仍需真机验证；「发送测试通知」就是为这条链路提供的一键自验入口。
- API 31+ 未授权精确闹钟时降级为 inexact，可能延迟几分钟或进入系统维护窗口；OEM 激进省电还可能进一步延迟。
- Android 13+ 通知权限被永久拒绝后，每次打开 App 会引导到设置页，直到授权为止；这是可发现性与打扰之间的折中。
- 打开 App 时提醒时刻已经过去的条目当天不补发；下一天由每日兜底/数据变更重新调度。

## 2026-10-24 第七轮真机复测：短窗口真实提醒修复 + 电池优化引导

### 1. 根因结论：1 分钟没有被钳制成 60，接收端把准点触发的提醒丢弃

- 逐项读取 `ReminderLogic`、`ReminderScheduler`、`ReminderReceiver` 与 `TaskRepository.persist()` 后确认：`MIN_REQUIRED_MINUTES = 1`，`requiredMinutes(task) = 1`，`computeTaskReminderTime()` 对「15:01 创建、16:04 截止、所需 1 分钟」计算出的确是 **15:03**；任务保存后的 `persist()` 也确实调用了 `rescheduleAll()`，闹钟已注册在 15:03。
- 真实根因在接收端二次校验：`ReminderReceiver.notifyTask()` 复用 `shouldScheduleTask(task, completedDates, new Date())`，该方法要求 `reminder > now`。闹钟触发时 `now` 已等于或略晚于 15:03，于是 15:03 的真实提醒被判定「提醒时刻已过」并静默 return，通知从未进入 `notify()`。测试通知正常是因为走 `TYPE_TEST` 分支，没有这条校验。
- 修复口径拆成两层：`isTaskReminderEligible()/isTodoReminderEligible()` 只判断「今天需要、未完成、未请假、截止时间仍在未来」；`resolveTaskScheduleTime()/resolveTodoScheduleTime()` 负责选择时刻。计算出的提醒在未来则按公式调度；提醒已过但截止未到则改为 `now + 5 秒` 立即提醒；截止已过不调度。接收端改为只做触发时资格校验，不再要求提醒时刻仍在未来。
- 调度日志升级：`CheckTodoReminder` 现在输出条目名称、ID、调度时刻、exact/inexact，以及是否 immediate；可用 `adb logcat -s CheckTodoReminder` 直接核对。精确闹钟降级链路保持：API 24–30 exact，API 31+ 有 `canScheduleExactAlarms()` exact，否则 `setAndAllowWhileIdle`；channel、smallIcon、manifest receiver 均未改动。

### 2. 电池优化引导与通知权限弹窗行为

- `AndroidManifest.xml` 新增 `android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，`aapt2 dump badging` 已确认进包。
- `MainActivity.maybeShowBatteryOptimizationDialog()` 使用 `PowerManager.isIgnoringBatteryOptimizations(packageName)`：已忽略不弹；未忽略且用户此前没有拒绝过时，先弹与通知权限同风格的说明框；点「去开启」直接发起 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 原生一键弹窗；点「暂不开启」写入 `battery_optimization_prompt_declined`，之后不再弹。
- 通知权限弹窗行为复核：API 33+ 已授权时不弹（用户测试通知能响，说明真机上权限大概率已授权，没看到说明框属正常）；未授权首次进入会先弹说明，点「去开启」触发系统原生权限弹窗；被永久拒绝则引导应用详情设置页。电池优化引导会在通知权限已授予或用户选择「暂不开启」后接着出现，避免两个弹窗同时堆叠。

### 验证结果

- `ReminderTest` 30→38 个用例：新增真机场景 15:01/16:04/1 分钟 → 15:03、触发时刻二次校验有效、提醒已过转 5 秒立即提醒、1 分钟不钳制、legacy 0 兜底 60、超过 1440 钳到 1440；原「提醒已过不调度」断言按新规则改为「截止未到仍调度并立即补发」。
- 全部 JVM 测试：Streak 19、Todo 25、Leave 54、ThemeMode 12、TaskStats 22、TaskOrder 7、Cancel 16、Reminder 38，合计 **193/193** 通过，原 185 用例零回归。
- `native-android\build.bat` 完整重跑 EXIT=0；`apksigner verify --verbose` 输出 `Verifies`，v2=true、v3=true、signers=1；`aapt2 dump badging` 确认 minSdk 24、targetSdk 34、`POST_NOTIFICATIONS`、`RECEIVE_BOOT_COMPLETED`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 均在包内；`aapt2 dump xmltree` 确认 `.notification.ReminderReceiver` 与三个 action 注册不变。
- 产物：`native-android/out/checktodo-debug.apk`，**115,897 字节**。Web 版零改动；持久化结构不变，`requiredMinutes` 仍是可选字段。

### 已知限制

- 无真机环境，本机只完成 JVM 用例、构建、签名与包结构自验；真实闹钟准点触发、通知声音/振动、电池优化原生弹窗与 ROM 省电策略仍需真机复测。
- API 31+ 未授予精确闹钟权限时走 inexact `setAndAllowWhileIdle`，可能延迟几分钟；国产 ROM 激进省电仍可能进一步延迟，加入电池优化白名单是缓解手段而非绝对保证。
- 「提醒已过但截止未到」只在当前这次全量重调度或数据变更时补发 5 秒提醒；若 App 一直未启动且错过原始闹钟，会受系统闹钟/ROM 策略限制，启动后才会重算。

## 2026-10-24 课表核心功能移植（WakeUp 功能改写）

### 实现范围

- 导航：`MainActivity` 顶部三标签改为「打卡 / 待办 / 课表」，`Mode.TIMETABLE` 控制渲染与「新增」按钮语义；`EXTRA_OPEN_TIMETABLE` 供小卡片直达课表页。
- 周视图：`timetable/TimetableView` 自绘节次时间轴与 5-7 天课程网格，支持周切换、回当前周、今天表头高亮、非本周课程淡化、同节多课并排、圆角色块和纵向滚动。
- 控制层：`timetable/TimetableController` 负责周次状态、课程详情、编辑/删除、学期设置和 CSV 导入；布局为 `res/layout/timetable_panel.xml`、`dialog_course_editor.xml`、`dialog_timetable_settings.xml`。
- 领域层：`domain/TimetableLogic.java` 提供当前周计算（钳制 1..maxWeek）、单双周/本周判定、冲突检测和今日课程；`domain/CourseBase.java`、`CourseDetail.java`、`TimeNode.java`、`TimetableConfig.java` 定义两层课程、节次时间和学期配置。
- 数据层：`data/TimetableRepository.java` 使用独立 SharedPreferences 文件 `check-todo-timetable` 与键 `timetable.v1`，以 `org.json` 保存 config、timeNodes、courses、details。颜色持久化为稳定 1..9 值，避免 UUID 变化导致配色漂移；任务/待办/主题旧数据不受影响。
- CSV 导入：`domain/TimetableCsvParser.java` 手写解析，无第三方库；入口为课表页「导入 CSV」，通过 `ACTION_OPEN_DOCUMENT` 读取文本。
- 今日课程 Widget：`widget/TodayCourseWidget.java` + `widget/TodayCourseWidgetService.java` + `res/xml/today_course_widget_info.xml`；按节次排序显示今日课程，点击条目打开课表页，复用 `FLAG_MUTABLE` template + `setOnClickFillInIntent`。
- 未实现：教务登录、在线抓取、高校专用 parser、分享码/在线导入、超星/青果 WebView、背景图片；课程提醒作为可选增强本轮未做。

### CSV 规则

- 默认列序：`课程名,星期,开始节,结束节,老师,教室,周次`。
- 显式表头可用常见别名识别 `课程/课程名/课程名称/name`、`星期/周几/day`、`开始节/开始节次/startnode`、`结束节/结束节次/endnode`、`老师/教师/teacher`、`教室/地点/room`、`周次/周/weeks`。
- 分隔符：逗号、分号、Tab 任选；每行至少 7 列，周次列后的额外列会合并为周次文本。
- 星期支持 `1-7`、`星期一`、`周一`、英文缩写；节次要求 1..60 且结束节不早于开始节。
- 周次支持 `1-16`、`1-15` 单周、`2-16` 双周、`1,3,5,7` 列表；错误行返回「第 N 行：原因」，导入完成显示成功/失败条数。

### 构建链修复

课表代码加入后，`d8` 直接接收全部 `.class` 文件触发 Windows「The input line is too long.」错误。`build.bat` 第 5 步改为先用 `jar cf build/classes.jar -C build/classes .` 聚合 class 文件，再把单个 jar 交给 `d8`。输出仍是原 `classes.dex`，后续 zipalign/apksigner 链路不变。

### 验证结果

- 全部 JVM 测试：Streak 19、Todo 25、Leave 54、ThemeMode 12、TaskStats 22、TaskOrder 7、Cancel 16、Reminder 38、Timetable 53，合计 **246 个断言全部通过**，原 193 项零回归。
- `build.bat` 完整重跑 EXIT=0；`javac -Xlint:all` 仅保留既有 API 24 兼容 deprecation 提示。
- `apksigner verify --verbose`：`Verifies`，v2=true、v3=true、signers=1。
- `aapt2 dump badging`：`com.checktodo.app`、minSdk 24、targetSdk 34、`provides-component:'app-widget'`。
- `aapt2 dump xmltree AndroidManifest.xml`：`.widget.TodayCourseWidget` 与 `.widget.TodayCourseWidgetService` 均已注册，含 APPWIDGET_UPDATE、TODAY_COURSE_ITEM、TODAY_COURSE_OPEN_APP、WIDGET_REFRESH。
- `aapt2 dump resources`：`layout/widget_today_course.xml`、`xml/today_course_widget_info.xml`、`layout/timetable_panel.xml`、`layout/dialog_timetable_settings.xml`、`courseColor1..courseColor9` 及夜间变体均进包。
- 产物：`native-android/out/checktodo-debug.apk`，**144,974 字节**。Web 版零改动。

### 本轮已知限制

- 无真机环境，周课表手势/滚动、详情弹窗、课程编辑、CSV 文件选择和今日课程 Widget 的实际显示与点击需真机复测。
- CSV 解析为手写实现，不支持带引号转义的字段；Excel 导出的常规无引号 CSV 均可处理。
- 课程颜色自动按稳定 ID 分配，当前未提供手动换色入口。
- 删除课程按整门课程（base）删除，未做单条排课粒度删除。
- 今日课程 Widget 依赖系统 30 分钟周期刷新与 App 数据变更广播；若 ROM 暂停 widget 服务，跨天列表可能延迟到下次刷新。

## 2026-10-24 第八轮增量：三视图小卡片 + 自定义节次时间表

### 1. 桌面小卡片切换扩展为三视图

- 模式常量改为切换顺序语义：`CheckTodoWidget.MODE_TASKS=0`（打卡）、`MODE_TODOS=1`（待办）、`MODE_COURSES=2`（课表/今日日程）。`nextMode()` 做 `(mode+1)%3` 循环，切换按钮点击即「打卡 → 待办 → 课表 → 打卡」。
- 持久化：沿用 `CheckTodoWidget` 现有 SharedPreferences 文件 `check-todo-widget`，键 `mode.<appWidgetId>`；默认值改为 `MODE_TASKS`（打卡视图优先，符合入口语义）。`getMode()` 对越界/脏值回落打卡视图。每次切换写盘后再 `updateAll()`，重启、开机、`onUpdate` 均从该键恢复。
- 集合视图重建：`CheckTodoWidget.updateWidget` 中 service 的 `data` 由 `checktodo://widget/<id>` 改为 `checktodo://widget/<id>/mode/<mode>`，切换后强制新建 `RemoteViewsFactory`，不会复用上一模式的条目缓存。
- 课表视图渲染：`CheckTodoWidgetService.Factory.onDataSetChanged` 走 `TimetableRepository` + `TimetableLogic.todayCourses`，再由 `widget/CourseWidgetItems` 统一生成「课程名 / N-M节 HH:mm-HH:mm · 教室」；标题「今日课程」，空态「今天没有课程」。
- 条目交互：打卡/待办视图保持原状（勾选打卡、完成待办；已完成/已完成待办走原确认链路）。课表视图条目 `fillInIntent` 只带 `itemType=course`，`CheckTodoWidget.onReceive` 收到后以 `EXTRA_OPEN_TIMETABLE=true` 打开 `MainActivity` 课表页——该常量现由 `TodayCourseWidget.EXTRA_OPEN_TIMETABLE` 提供（值即 `MainActivity.EXTRA_OPEN_TIMETABLE`），与独立卡片同一套跳转。
- 公共取数：新增 `widget/CourseWidgetItems`（纯 Java，无 Android 依赖），负责标题兜底、副标题文案与排序（开始节次 → level → baseId）。`TodayCourseWidgetService` 与三态卡片课表视图都只调用它，避免两处实现漂移。独立「今日课程」卡片继续保留，两种添加方式并存。
- 刷新：`WidgetRefresh.notifyDataChanged` 原本已同时广播到 `CheckTodoWidget` 与 `TodayCourseWidget`；`TaskRepository.persist`（打卡/待办/任务）与 `TimetableRepository.persist`（课程、配置、时间表）都会调用，因此三类数据任一变更都会重刷三态卡片。`TodayCourseWidget` 的刷新判断顺带改为常量 `WidgetRefresh.ACTION_REFRESH`。

### 2. 课表节次时间表用户自定义

- 新领域类 `domain/TimeTableSettings`（纯 Java）：`defaultTable()`/`generate()`/`defaultNode()`、`minutes()`/`format()`、`validate(list,nodeCount)`、`validateNode(node)`、`resize(list,nodeCount)`、`withNode()`、`rangeLabel()`/`spanLabel()`。`TimeNode` 结构未变，持久化仍是 `timetable.v1` 的 `timeNodes` 数组，**老数据零迁移代价**。
- 默认时间表（每节 45 分钟）：第 1 节 08:00-08:45；第 2 节 08:55-09:40（间休 10）；第 3 节 09:55-10:40（间休 15）；第 4 节 10:55-11:40；下午第 5 节从 14:00 起、第 6 节 14:55、第 7 节 15:50、第 8 节 16:45-17:30（间休 10）；晚上第 9 节 19:00、第 10 节 19:55、第 11 节 20:50、第 12 节 21:45-22:30、第 13 节 22:40-23:25。默认最多到 13 节，再往下会越过当天 23:59，故不再生成（`MAX_NODES=20` 仍允许用户自行填到 20 节）。
- 入口与交互：`dialog_timetable_settings.xml` 新增「时间表设置：N 节，点击编辑」按钮，打开 `res/layout/dialog_time_table.xml`；每节一行（`res/layout/item_time_node.xml`：第 N 节 + `start - end`），点击先弹 24 小时制 `TimePickerDialog` 选开始时间、再弹一次选结束时间；顶部三个按钮为「恢复默认 / 增加一节 / 删除一节」。编辑器内改动先进草稿（`TimetableController.showTimeTableEditor` 持有 `List<TimeNode> draft`），关闭时间表弹窗后仍需点课表设置的「保存」才落盘。
- 校验：`TimeTableSettings.validateNode` 拦「开始 ≥ 结束」，`validate` 额外拦「后一节开始 < 前一节结束」（相等允许）与格式错误。非法时在弹窗内红字并 Toast，不写入草稿之外的任何地方；保存时再校验一次整表，失败则保留设置弹窗并提示。
- 节数联动：`resize()` 按 1..nodeCount 重建列表，已有节原值保留、缺的用默认值补齐、多的裁掉；`input_daily_nodes` 的 `TextWatcher` 实时同步草稿与按钮文案，保存时 `TimetableRepository.saveTimeNodes` → `saveConfig` 两步都走同一规则。`TimetableRepository.load()` 也做「`timeNodes.size() != config.nodes` 就补齐/裁剪」，覆盖老数据节数变更后时间表不齐的情况。
- 读取方：`TimetableView` 时间轴原本就按传入的 `timeNodes` 绘制（`TimetableController.refresh` 传 `repository.getTimeNodes()`）；课程详情弹窗的「时间」、`TodayCourseWidgetService` 与三态卡课表视图的副标题现均通过 `TimeTableSettings.spanLabel()` 取自定义起止时间，改完时间表即刻生效。

### 测试与构建

- 新增 `test/TimeTableSettingsTest.java`：60 项断言（默认表、HH:mm 解析、开始<结束、相邻重叠拒绝、相等放行、节数扩充/裁剪保留、钳制、老数据迁移、`withNode`、区间文案、卡片条目排序与副标题）。
- 全部 JVM 测试：246 旧 + 60 新 = **306 项全部通过**，`failed=0`。
- `build.bat` 完整重跑 EXIT=0；`javac --release 11 -Xlint:all` 无新增告警。
- `apksigner verify --verbose`：`Verifies`，v2=true、v3=true、signers=1，证书 `CN=Android Debug`，SHA-256 `0b545e…35927`。
- `aapt2 dump badging`：`com.checktodo.app`、versionName 1.0、minSdk 24、targetSdk 34。
- 产物：`native-android/out/checktodo-debug.apk`，**153,320 字节**。

### 本轮已知限制

- 无真机，三视图切换后的 RemoteViews 重建、课表条目跳转、`TimePickerDialog` 嵌套弹窗表现需真机复测。
- 默认时间表最多覆盖 13 节；14..20 节（`MAX_NODES`）留空由用户自定义，未给定默认值以避免编排到次日。
- 增加/删除节次会同时改时间表草稿；若在时间表弹窗里增减节次后不点「每天节数」也更改，保存时以草稿节数为准并覆盖设置里的节数（`syncDraft` 双向同步）。
- 课表视图只展示「今天有课」，不做跨天/次日预览；条目点击固定打开课表页，不定位到具体课程。

## 2026-10-24 第九轮增量：本地 HTML 课表导入

### 1. 手写容错 HTML 解析器

- 新增 `native-android/src/com/checktodo/app/domain/SimpleHtmlParser.java`，零第三方依赖。实现方式为逐标签状态机扫描：记录 `table/tr/td/th` 标签栈与文本缓冲；忽略标签大小写、属性顺序、单双引号、无引号属性与未闭合标签；遇到 `script/style` 时丢弃其内容；`br` 转为换行。
- 文本清洗支持 `&nbsp;`、`&amp;`、`&lt;`、`&gt;`、`&quot;`、十进制/十六进制数字实体和全角空格，并折叠普通空白但保留换行。
- 表格展开阶段为每个单元格解析 `rowspan`/`colspan`，把不规则的教务网格展开为矩形网格；被占位或缺失的单元格填空字符串。该实现不执行 CSS/JS，也不解析嵌套表格语义，只提取表格行列文本。

### 2. 课表网格识别与导入

- 新增 `domain/HtmlTimetableParser.java`。候选网格要求 5-8 列、含星期表头（星期/周一/周天/一~日等）或可按列位推导星期、行文本中出现周次特征（`1-16周`、`1,3周`、`单周`、`双周` 等）。
- 单元格解析：课程名取第一行（实体解码后）；周次行支持多段（如 `1-8周；10-16周(单)`），每段生成一条 `CourseDetail`；单双周由「单/双/(单)/(双)」判定；教师/教室按行文本与常见教室关键词（楼、室、馆、场、号等）启发式区分；无法解析周次的块会计入警告并跳过。
- 节次策略：连讲课由 `rowspan` 得到 `step`；开始/结束节次超出当前课表时间表长度时跳过该排课，并在预览与导入结果中给出「超出当前时间表」警告（选择跳过而非钳制，避免把第 15 节课程错误显示到最后一节）。
- 导入链路：`TimetableController.openHtmlPicker()` 使用 `ACTION_OPEN_DOCUMENT` 选择 `.html` 文件；`handleHtmlResult()` 读取 URI 字节，`HtmlTimetableParser.decode()` 先剥 UTF-8 BOM，再严格 UTF-8 解码，解码异常时回落 GBK；解析结果弹出可滚动预览（课程名、星期、节次、周次、教师、教室），确认后才调用 `importHtmlCourses()` 写入当前课表并刷新课表与 Widget。
- 入口 UI 位于 `res/layout/timetable_panel.xml` 的 `button_import_html`，与「导入 CSV」并列；`MainActivity.onActivityResult()` 将 `REQUEST_HTML=3002` 转发给 `TimetableController.handleHtmlResult()`。

### 3. 支持与不支持

- 支持：教务页面另存为本地 HTML；不规则 `rowspan/colspan` 网格；单/双周与多段周次；连讲课；常见 HTML 实体；UTF-8、UTF-8 BOM 与 GBK 字节；未闭合标签和大小写/属性差异；导入前预览与确认。
- 不支持：教务登录、在线抓取、WebView 流程、高校专用格式、CSS 渲染定位、嵌套/高度动态脚本生成的表格、多课表映射选择。启发式识别失败时明确提示「未找到课表表格，建议改用 CSV」。

### 4. 测试与构建验证

- 新增 `test/HtmlTimetableParserTest.java`，共 34 项断言：实体与全角空格解码、rowspan/colspan 展开、未闭合标签容错、script/style 隔离、教务样例识别（连讲、教师、教室、单双周、多段周次、实体课程名）、非课表表格拒绝、超节次跳过与告警、UTF-8/BOM/GBK/空输入编码回落。
- `build.bat` 纯 Java 测试清单已加入两个解析类与新测试；全部 JVM 测试从 306 项增至 **340 项全部通过**（`failed=0`），原用例零回归。
- 完整 `build.bat` 重跑成功；`javac` 仅保留既有 `scaledDensity` 与 `Notification.Builder(Context)` deprecation 提示，无错误。
- `apksigner verify --verbose`：`Verifies`，v2=true、v3=true、signers=1。
- `aapt2 dump badging`：`com.checktodo.app`、每日打卡、minSdk 24、targetSdk 34、`POST_NOTIFICATIONS`、`RECEIVE_BOOT_COMPLETED`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 均在包内。
- `aapt2 dump resources`：`id/button_import_html`、`layout/timetable_panel`、`drawable/ic_notification` 与 night 资源配置均在包内。
- 产物：`native-android/out/checktodo-debug.apk`，**161,510 字节**。Web 版零改动。

### 本轮已知限制

- 无真机环境，SAF 文件选择、预览滚动与导入后刷新已通过编译和链路检查，但需真机确认。
- HTML 解析为通用启发式，不保证适配所有高校教务导出；脚本动态渲染或无标准 table 的页面无法识别。
- GBK 回落按「UTF-8 严格解码失败」判定；少数混合编码或含有非法 UTF-8 字节但主体不是 GBK 的文件仍可能乱码。
- 超出当前节次时间表长度的排课会跳过；用户应先在课表设置中增加节次数再导入。

## 2026-10-24 第十轮增量：教务系统在线导入（通用 WebView 抓取）

### 1. 提取流程与 JS 设计

- 入口位于 `timetable/TimetableController` 的 `showOnlineImport()` 与 `res/layout/timetable_panel.xml` 的 `button_import_online`；`res/layout/dialog_online_import.xml` 提供全屏 WebView、地址输入/跳转/历史按钮、加载状态、手动提取、导入和取消按钮。
- WebView 开启 JavaScript、DOM storage、缩放、宽视口与 mixed-content compatibility；`CookieManager` 开启 Cookie 与第三方 Cookie，Dialog 关闭时 `flush()` 并销毁 WebView。
- `onPageFinished` 后 500ms 与 1800ms 各触发一次 `evaluateJavascript`，覆盖登录后跳转和部分动态渲染场景；状态栏始终显示「已捕获 N 门课 / M 条排课」或识别失败提示。
- `domain/OnlineImportLogic.extractionScript()` 注入 IIFE：遍历 `document.querySelectorAll('table')`，保留行数 ≥5、最大列数 5..9、`textContent` 含「周」、非 `display:none/visibility:hidden` 且 `outerHTML` 小于 500KB 的前 12 张表；结果连同 `location.href`、`document.title` 用 `JSON.stringify` 返回。
- `parseCapture()` 兼容 WebView 回调的两种形态：直接 JSON 对象、或 JSON 字符串再包一层引号；内置极小 JSON reader，支持对象/数组/字符串/数字/布尔/null 与 `\uXXXX` 转义。解析在后台线程执行，UI 更新回主线程。

### 2. 合并、历史与安全入口

- 合并入口为 `OnlineImportLogic.merge()`。去重 key = 课程名 + 星期 + 开始节 + 结束节 + 周次范围 + 单双周；同名课程只保留一个 `CourseBase`，重复排课计 duplicate，缺失教室/教师用后续抓取结果补齐，其余字段保留首次结果。用户翻页、切换学期后可多次抓取累积。
- URL 处理：`normalizeUrl()` 补默认 `http://`，协议相对地址转 `http:`，但显式 scheme 只允许 `http/https`，`javascript:`、`file:`、`ftp:` 等直接拒绝。`history()` 规范化去重、最新置顶、最多 5 条，持久化于 SharedPreferences `check-todo-online-import` 的 `url_history`（换行分隔）。
- 导入确认复用 HTML 导入预览：显示课程名、星期、节次、周次、教师、教室与警告，确认后走既有 `importHtmlCourses()`，仓库仍按当前课表重映射 ID 后持久化并刷新 Widget。

### 3. Manifest 与网络取舍

- `AndroidManifest.xml` 新增 `android.permission.INTERNET`，`<application android:usesCleartextTraffic="true">`。选择应用级 cleartext 而不是 networkSecurityConfig：实现最小，且本 App 只把 WebView 交给用户输入的教务地址，没有其他网络业务；代价是整个应用进程允许明文 HTTP。
- 不做高校账号库、验证码识别、响应拦截和高校专用 parser；登录、验证码、课表页导航全部由用户在 WebView 中完成，提取只针对当前 DOM，对动态页面比响应拦截稳。

### 4. 测试与构建验证

- 新增 `test/OnlineImportLogicTest.java`，36 项断言：URL 补全与非 http/https 拒绝、历史 5 条/去重/非法输入、提取脚本关键过滤条件、对象与 WebView 引号 JSON、unicode 转义、非法 JSON 异常、同名课程与同排课去重、教师/教室补齐、不同周次累积、多课程顺序保持。
- 全部 JVM 测试：340 旧 + 36 新 = **376 项全部通过**（各套件 `failed=0`），原用例零回归。上一轮 handoff 预估 373 少算了 3 项；磁盘上的实际新增测试为 36 项断言。
- `build.bat` 完整重跑 EXIT=0；`javac` 仅保留既有 `scaledDensity` 与 `Notification.Builder(Context)` deprecation 提示，无错误。
- `apksigner verify --verbose`：`Verifies`，v2=true、v3=true、signers=1。
- `aapt2 dump badging`：`android.permission.INTERNET` 在包内；`aapt2 dump xmltree --file AndroidManifest.xml` 确认 `android:usesCleartextTraffic=true`。
- `aapt2 dump resources`：`layout/dialog_online_import`、`id/button_import_online`、`id/input_online_url`、`id/webview_online` 及 night 颜色/主题资源均在包内。
- 产物：`native-android/out/checktodo-debug.apk`，**173,880 字节**。Web 版零改动。

### 本轮已知限制

- 无真机环境，WebView 登录、验证码、Cookie 保持、多次翻页提取与不同教务系统的实际识别率需真机复测。
- 提取只扫描当前顶层 DOM，不递归跨域 iframe；强脚本渲染或无标准 `<table>` 的课表无法识别。
- 通用启发式可能误捕获隐藏或非课表表格，最终以预览确认为兜底；识别失败时提示改用 HTML 文件导入或 CSV。
- Cookie 保存到系统 WebView 存储，App 卸载或系统清理后登录态丢失。
- 允许明文 HTTP 是为了兼容旧教务系统，用户应自行避免在公共网络输入敏感账号。

## 2026-10-24 WakeUp parser 移植（方案 B）

### 0. 侦察结论

- `tools/jadx-out/sources/com/suda/yzune/wakeupschedule/schedule_parser/parser` 下实际清点到 **171 个 `.java` 文件**；其中 **51 个文件包含 `Method not decompiled`**，直接继承 `Parser` 的约 **148 个**，其余多为协程 continuation、混淆辅助类和厂商专用 bean。整体不是可逐个拷贝的源码形态：Kotlin 协程、KSoup、AndroidX、混淆类名和中文字符串乱码混在一起。
- 因此本 App 没有逐个搬 171 个 parser，而是重写 **5 个通用家族**，并把学校选择、清单解密、解析结果桥接到现有课表模型。这是一个有意的降级取舍，避免把不可恢复的反编译残片硬塞进零依赖工程。

### 1. Jsoup/KSoup 垫片

- 新增 `native-android/src/com/checktodo/app/jshim/`，包含 `Document / Element / Elements / Node / Selector / HtmlTreeBuilder / Entities / Jsoup`。
- 垫片实现 parser 实际会用到的 DOM 面：`text()`、`ownText()`、`attr()`、`select()`、`getElementsByTag/Class/Id`、`children()`、`parent()`、`className()` 等常见入口。
- 选择器支持：标签、`#id`、`.class`、`[attr]`、`[attr=v]`、`[attr^=v]`、`[attr*=v]`、后代空格、`>` 子级、逗号分组、`:contains`、`:containsOwn`、`:not`、`:gt`、`:lt`、`:eq`。
- 解析器本身容忍未闭合标签、大小写、属性顺序和引号差异；`script/style` 内容不进入 DOM 文本，常用 HTML 实体会解码。
- `test/JShimTest.java` 用 26 项断言覆盖上述 DOM 与选择器子集。

### 2. 重写的 5 个解析家族

落点为 `native-android/src/com/checktodo/app/wakeup_parser/`，统一走 `WakeupParser.parse(source) -> WakeupParserResult`，再由 `WakeupCourseMapper` 映射到现有 `CourseBase/CourseDetail`。家族选择由 `WakeupParserFactory` 完成，当前支持：

- `zf`：正方，参考 `schedule_parser/parser/p950zf/OooO.java`
- `jz`：金智，参考 `schedule_parser/parser/p949jz/OooO0o.java`
- `urp_new`：URP New，参考 `schedule_parser/parser/OooO.java`
- `kingosoft` / `kingo_new`：Kingosoft 系，参考 `schedule_parser/parser/o00O0OOO.java`
- `element_ui`：Element UI 卡片式课表，参考 `schedule_parser/parser/o0O00OOO.java`

说明：这不是把 171 个学校专用 parser 全部移植，也不是对 `qz`、`south_soft`、`cf` 等未支持家族的覆盖；`element_ui` 目前在清单里没有稳定映射，仍可通过显式类型入口测试。清单内使用上述厂商标准结构的大量学校可命中，变体差异大的学校可能识别失败，之后回落通用启发式解析。

### 3. 学校清单解密与接入

- 清单来源：WakeUp APK 的 `assets/school_info_android_new.txt`，已拷入 `native-android/res/raw/school_info_android_new.txt`，加密态为 **2,254 行、173,518 字节**。
- `SchoolInfoDecoder` 的解码链：`reverse` → `Base64 decode` → `AES-CBC`（key/IV 为 WakeUp 原值）→ `Base64 MIME decode` → `GZIP` → JSON。只在内存中解码，不落盘明文清单。
- 解码后得到 **3,570 所学校**；四个已映射家族的清单条目合计 **1,318** 条（`zf 970 + jz 128 + urp_new 47 + kingo_new 173`），`element_ui` 另算为独立实现，暂无已验证的清单映射。

### 4. UI 接入

- `TimetableController.showOnlineImport()` 打开教务导入后，先显示「学校」按钮；点击进入可搜索学校列表，最近使用学校置顶。
- 选择学校后自动填入该校教务 URL，并优先尝试对应专用 parser；解析为空或类型不支持时，自动回落到通用 `HtmlTimetableParser`。
- 学校选择、最近学校和 URL 历史持久化在 `check-todo-online-import` 偏好里，不影响任务、待办和主题数据。

### 5. 测试与构建

- 新增 `test/WakeupParserTest.java`，60 项断言：周次解析、家族选择、5 个代表性 HTML/JSON 夹具、模型桥接、加密清单解码和学校搜索。
- 全部 JVM 测试：**436 项通过**，14 个测试文件零回归；`build.bat` EXIT=0，`apksigner verify` v2/v3 均为 true，`aapt2 dump badging` 与 `resources` 确认新增资源与权限在包内。
- 产物：`native-android/out/checktodo-debug.apk`，**325,514 字节**。
- 本轮只补文档，未改代码，也未重复构建；上述结果沿用当前已验证的磁盘构建状态。

### 6. 已知限制

- 没有完成 WakeUp 全部 171 个 parser；实际是 5 个家族级重写，51 个 `Method not decompiled` 文件不可直接恢复。
- `element_ui` 无稳定清单映射；`qz`（强智）、`south_soft` 等家族未实现。
- Kingosoft 类解析依赖页面能给出可解析的 JSON；WebView 当前提取脚本以 table 为主，若页面只在脚本数据里提供课表，仍可能回落通用解析或失败。
- 真机上的登录、验证码、Cookie 保持和不同学校页面变体仍需实测；识别失败时最终由预览确认兜底。
- WakeUp 清单数据和解析逻辑仅按用户要求自用，不分发。
# 2026-10-25 第八轮：教务导入 UX / 闪退修复与多课表

## 1. 选择学校闪退根因

- 现象：真机点击「选择学校」直接崩溃，JVM 测试却全部通过。
- 根因：`SchoolInfoDecoder` 使用了 `java.util.Base64` 与 `InputStream.readAllBytes()`。`java.util.Base64` 自 API 26 起可用、`readAllBytes()` 自 API 33 起可用，而 App `minSdkVersion=24`，低版本真机直接抛 `NoSuchMethodError`。不是 AES key/IV、Base64 变体或 GZIP 流错误，解密链路本身正确。
- 修复（`native-android/src/com/checktodo/app/wakeup_parser/SchoolInfoDecoder.java`）：`java.util.Base64` 改为手写 lenient `decodeBase64()`（兼容标准与 URL-safe 字符表）；`readAllBytes()` 改为手写缓冲读循环；解码链保持 `reverse → Base64 → AES-CBC → Base64(MIME) → GZIP → JSON`，仅在内存解码，不落盘明文清单。
- 证据：`test/WakeupParserTest.java` 直接读取 `res/raw/school_info_android_new.txt` 真实字节，断言解出 JSON、学校数量 3,570，`zf/jz/urp_new/kingo_new` 家族计数正确且搜索可用。`TimetableController` 的清单解码在后台线程执行，异常捕获后显示「学校清单加载失败：…」，无崩溃路径；列表一次最多渲染 300 条匹配项，其余通过搜索过滤。

## 2. 教务导入页面重排

- `native-android/res/layout/dialog_online_import.xml`：网址输入框改为明确可见的卡片式输入框（背景 + 描边 + 可聚焦光标），与主按钮「打开」同行；「选择学校 / 历史」收在次要按钮行；顶部显示已选学校行（校名 + parser 类型 + 历史/移除入口）；WebView 打开即存在，未加载时由引导覆盖层显示说明，`onPageStarted` 时隐藏。深浅色使用现有 `values`/`values-night` 资源。
- `native-android/src/com/checktodo/app/timetable/TimetableController.java`：选择学校后自动填 URL 并加载；后台搜索、最近学校置顶与错误提示由该控制器管理。

## 3. 多课表支持

- 新增 `domain/TimetableSheet.java`（不可变课表：`id/name/config/timeNodes/bases/details`，提供 `withName/withConfig/withTimeNodes/withCourses/copy`）与 `domain/TimetableSheets.java`（`switchTo/create/rename/delete/replace/add/fromLegacy`）。
- `data/TimetableRepository.java` 改为 `timetable.v2`：`currentId` + `timetables[]`，每张课表独立持有 `TimetableConfig`、节次时间表、课程与排课。旧 `timetable.v1` 首次读取时自动迁移为第一张名为「我的课表」的课表，课程、配置与节次时间表不丢失；删除当前课表或最后一张被拒绝。既有便捷 API 代理到当前课表，`ReminderScheduler`、今日课程 Widget 与三视图卡片无需改动即跟随当前课表。
- UI：`timetable_panel.xml` 标题可点击，`TimetableController` 显示当前课表名并提供管理弹窗（点击切换、新建可选复制课程、重命名、删除非当前课表需确认）。切换课表重置到该课表当前周；导入一律写入当前课表；变更持久化后触发 `WidgetRefresh.notifyDataChanged(context)`。
- 测试：新增 `test/TimetableSheetsTest.java` 25 项断言，覆盖创建/切换/重命名/删除、配置与节次表隔离、复制/空建差异、旧 `v1` 迁移不丢课。

## 4. 测试与构建

- 全部 JVM 测试：**487 项通过**（15 个测试文件），新增 `TimetableSheetsTest`，原 462 项零回归。
- `build.bat` EXIT=0；`apksigner verify`：v2=true、v3=true、签名者 1；`aapt2 dump badging` 确认 `com.checktodo.app`、minSdk 24、targetSdk 34、`INTERNET` 等权限与 Widget 组件在包内。
- 产物：`native-android/out/checktodo-debug.apk`，**329,607 字节**（较上一版 +4,093 字节，来自多课表模型、学校解码修复与新 UI 资源）。

## 5. 已知限制

- 学校列表一次最多渲染 300 条，完整 3,570 所依赖搜索过滤，这是主线程渲染保护，不影响数据完整性。
- 专用 parser 仍为 5 个家族级实现，变体差异大的学校可能识别失败，回落通用启发式或提示改用 HTML/CSV。
- WebView 登录、验证码、Cookie 保持与各校页面变体仍需真机实测；识别失败由导入预览兜底。
- 桌面小卡片渲染仍跟随系统深浅色（RemoteViews 在桌面进程取资源），不跟随 App 内主题偏好。
# 2026-10-25 第十一轮：教务导入布局与 iframe 提取修复

## 1. 布局：状态栏遮挡 WebView

- 根因：`showOnlineImport()` 用 `AlertDialog.Builder.setView()`，内容会被套进 AlertDialog 的内部 custom 面板，首次测量时 WebView 权重不生效，状态栏贴在网址栏下方、WebView 被挤压成黑色区域。
- 修复：`TimetableController` 改用 `android.app.Dialog` + `requestWindowFeature(FEATURE_NO_TITLE)` + `setContentView(dialogView)`，窗口仍设为 MATCH_PARENT。`dialog_online_import.xml` 为固定三段式：顶部标题/说明/学校行/网址行，中间 `WebView` `layout_height=0dp + weight=1` 占满剩余空间，底部状态/操作栏固定在末尾，任何加载状态下都吸底。未加载网址时引导覆盖层显示「输入网址或选择学校后登录教务系统；打开课表页面后，课程会自动捕获」，`onPageStarted` 时隐藏。

## 2. 点击「导入」无反应

逐项排查结论：

- **iframe 未遍历（根因）**：旧提取脚本只执行 `document.querySelectorAll('table')`，KINGOSOFT 课表在内嵌 iframe 里时主文档抓不到，捕获数为 0，导入按钮因 `onlineDetails.isEmpty()` 被禁用且点击无反馈。现在 `OnlineImportLogic.extractionScript()` 递归 `scan(doc)`：先扫主文档，再遍历 `iframe,frame` 的 `contentDocument`；跨域 frame 由 `try/catch` 静默跳过；递归 frame 数与输出表格数都限制 12，隐藏表格用 `doc.defaultView.getComputedStyle` 过滤。
- **导入按钮无反馈**：捕获为 0 时点击「导入」不再静默，Toast「尚未捕获到课程：请确认已打开课表页面，可点击「重新提取」」；捕获>0 时进入既有预览确认。
- **状态栏文案**：成功时显示「已捕获 N 门课 / M 条排课」，替代含糊的「未识别…」。
- **重新提取反馈**：手动点击后按钮禁用并显示「提取中…」，完成后 Toast 结果；页面加载后的 500ms/1800ms 自动提取不弹 Toast，避免打扰。
- **KINGOSOFT 节次格式**：`HtmlTimetableParser` 新增方括号节次支持 `[1-3]节`、`[1-3]`、`[5]节`，且方括号内节次优先于网格行号推导；周次正则支持全角 `（单）/（双）` 与 `1-16[5-6]节` 这种无「周」字的写法。专用 `KingosoftParser` 仍面向 JSON 数据源；HTML 页面由通用解析器兜底，已补方括号格式测试。

## 3. 测试与构建

- `HtmlTimetableParserTest` 新增 5 项：方括号节次识别、起止节次、周次解析、无「周」字周次 + 方括号节次（1-16[5-6]节）、全角括号单周。
- `OnlineImportLogicTest` 新增 4 项：脚本遍历 `iframe,frame`、读取 `contentDocument`、递归深层 iframe、递归深度限制。
- 全部 JVM 测试：**496 项通过**（15 个测试文件），原 487 项零回归；`build.bat` EXIT=0。
- `apksigner verify`：v1=false、v2=true、v3=true、签名者 1；`aapt2 dump badging` 确认 `com.checktodo.app`、minSdk 24、targetSdk 34、`INTERNET` 等权限在包内。
- 产物：`native-android/out/checktodo-debug.apk`，**333,704 字节**。

## 4. 已知限制

- 跨域 iframe 无法读取（浏览器安全模型），只能捕获同源 frame。
- KINGOSOFT 若把课表放在脚本数据而非 table DOM 中，仍可能回落失败，需改用 HTML 文件导入/CSV。
- 自动提取有 500ms/1800ms 两次重试，动态渲染极慢的页面可能需要手动点「重新提取」。

# 2026-10-25 第十二轮：教务导入现扫、轮询与操作指引

## 1. 导入前强制现扫

- 根因：WakeUp 的 kingo_new 流程要求用户在教务里点「检索」后课表才渲染；旧实现只在 `onPageFinished` 后 500ms/1800ms 各扫一次，用户点检索后点「导入」时继续使用旧的零捕获结果。
- 位置：`timetable/TimetableController.showOnlineImport()` 的导入按钮监听、`extractOnlinePage()` 与 `updateOnlineStatus()`。
- 时序：点击导入时设置 `onlineImportRequested=true` 并立即 `evaluateJavascript` 现扫；本次扫描解析完成后用 `getAndSet(false)` 捕获该请求，有课程则关闭导入页进入既有预览，无课程则 Toast「尚未捕获到课程（扫描 X 个文档 / Y 张表格）」。若点击时已有自动扫描在途，`AtomicBoolean` 由那次扫描完成时消费，导入仍基于最新页面。

## 2. 持续轮询

- 对话框 `show()` 后启动主线程 `Handler` 循环，每 2500ms 扫描一次；仅在对话框显示且当前没有解析在途时执行，dismiss 时移除回调、清空 Handler/Runnable、复位导入请求并销毁 WebView。
- 状态更新按「捕获课程数/排课数是否变化」节流，避免每 2.5 秒刷新造成文字闪动；手动提取和导入现扫始终更新。「重新提取」保留即时触发，按钮期间禁用并显示「提取中…」。

## 3. 家族操作指引

- `domain/OnlineImportLogic.guideFor(type, importType)` 为纯 Java 映射，`familyKey` 优先学校清单 `type`，为空时回落 `importType`；`kingo_new` 归入 `kingosoft` 家族。
- 文案清单：kingo_new/kingosoft「登录后进入 主控→教学安排 或 班级课表→格式二，点教务上的检索按钮，出现课表后再点导入」；zf「进入课表页面并选好学期；首页课表不可导入」；jz「信息查询→学生个人课表，切换到学期课表」；urp/urp_new「打开本学期课表页面」；Element UI、未匹配家族与未选学校「打开课表页面后点导入」。
- UI 位于 `res/layout/dialog_online_import.xml` 的 `text_online_family_guide`；对话框打开、选择学校、移除学校后由 `updateOnlineFamilyGuide()` 刷新。

## 4. 诊断统计

- 注入脚本在递归 `scan(doc,d)` 时统计扫描文档数、全部 table 数、命中课表特征表格数和最大 iframe 深度，随 `JSON.stringify` 结果以 `stats:{docs,tables,matched,depth}` 返回。
- `OnlineImportLogic.Capture` 新增对应字段；成功状态显示「已捕获 N 门课 / M 条排课 · 扫描 X 文档」，失败状态显示「扫描 X 个文档 / Y 张表格，命中 0」，导入失败 Toast 同样包含统计。无 stats 的旧返回值按捕获表格数兜底。

## 5. 测试与构建

- `OnlineImportLogicTest` 扩展到 58 项：新增/更新家族指引 9 项、stats JSON 装配 4 项、无 stats 兜底 1 项、脚本内容与递归调用 5 项，覆盖文档/表格/命中/深度计数及 `scan(...,d+1)` 深度传递。
- 全部 JVM 测试：**514 项通过**（15 个测试文件），原 496 项零回归；`build.bat` EXIT=0。
- `apksigner verify --print-certs`：v2/v3 true，签名者 1（debug 证书 SHA-256 `0b545e...5927`）；`aapt2 dump badging` 确认 `com.checktodo.app`、minSdk 24、targetSdk 34、`INTERNET`/通知/闹钟相关权限在包。
- 产物：`native-android/out/checktodo-debug.apk`，**333,703 字节**。

## 6. 已知限制

- 跨域 iframe 无法读取，深 iframe 页面最多扫描 12 个文档/返回 12 张表格。
- 轮询只在教务导入对话框打开期间运行；App 退后台、WebView 页面仍在脚本渲染或安全模型拦截时，仍可能需要点「重新提取」。
- 家族指引为 WakeUp 反编译结论的通用化说明，个别学校变体路径可能不同；识别失败回落预览/HTML/CSV。

# 2026-10-25 第十三轮：教务导入三通道捕获（XHR + div + table）

## 1. 根因与通道设计

- 真机诊断「扫描 5 个文档 / 3 张表格 / 命中 0」说明旧通道确实在扫页面，但现代 Kingosoft/新青果课表可能由 AJAX 拉取 JSON 后用 div 网格渲染，页面没有可识别的课表 `<table>`。WakeUp 6.1.35 的 `kingo_new` 现代实现为云端 JS 插件，APK 反编译对应 Java 方法多不可恢复，因此本 App 采用泛化三通道捕获。
- 通道 1：`domain/OnlineImportLogic.extractionScript()` 在主文档和每个可访问 iframe 的 `contentWindow.performance.getEntriesByType('resource')` 中收集 `initiatorType=xmlhttprequest/fetch` 的 URL，去重后最多回传 30 条。`resourceUrls()` 只保留 http/https、与当前页同 host 或同一高校学术域的 URL，先按 `kbcx/xskb/kbxx/课表` 关键词排序，其次 `ProExt`、`pk/schedule/timetable`，最后普通 XHR。
- 通道 2：脚本扫描 `div,td` 中 class 命中 `kbcontent/kbtable/course/glyphicon-free` 或 id 匹配 `^\d+_\d+$` 的课程格子，要求文本含周次特征且未超长，回传文本、class、id 和兄弟/网格坐标。`domain/OnlineDivParser.parse()` 将文本解析为课程名、教师、教室、周次、单双周和可选 `[1-3]节` 连讲节次；`行_列` id 直接映射节次/星期，兄弟索引按 0 基转 1 基。
- 通道 3：保留原 table 扫描，仍递归同源 iframe、过滤隐藏表格并交给专用 parser/通用 HTML 解析器。
- 会话内重取：`timetable/TimetableController.fetchResource()` 使用 `CookieManager.getInstance().getCookie(url)` 的登录态，`HttpURLConnection GET` 携带与 WebView 相同的 UA、当前页 Referer、`X-Requested-With: XMLHttpRequest`，10 秒超时、跟随重定向、支持 gzip、2MB 截断；最多尝试 15 条，避免对教务系统失控。解析顺序为选校家族专用 parser → HTML 通用启发式。

## 2. 合并与诊断

- 三通道全部复用 `OnlineImportLogic.merge()`，去重 key 为课程名 + 星期 + 开始节 + 结束节 + 周次范围 + 单双周；同名课程合并为一个 `CourseBase`，重复排课计数，缺失教师/教室可由后续通道补齐。
- 状态栏与导入失败 Toast 显示 `DOM 表格 X · div 格子 Y · 数据请求 Z（其中解析出课程 W）`，成功时还显示累计课程/排课数与扫描文档数。
- `dialog_online_import.xml` 底部新增「诊断」按钮，长按状态栏同样打开。诊断内容包含页面 URL/标题、DOM 表格统计、div 格子统计、XHR 原始/过滤数量、iframe 深度与扫描文档数、每个候选 URL 的请求结果和解析排课数、三通道合计；文本可选中滚动并可一键复制。
- 调度取舍：自动 2.5 秒轮询只执行脚本扫描与 DOM/div 解析，不重取 XHR，避免频繁请求学校接口；点击「导入」和「重新提取」会现扫并执行候选资源重取。

## 3. Kingosoft JSON 契约

`wakeup_parser/KingosoftParser` 继续支持 WakeUp 旧外层数组格式：

```json
[{"week2":[{"kcmc":"操作系统","skdd":"E505","rkjs":"孙宣","jcxx":"1-2","xf":"3.5"}]}]
```

新增现代 AJAX 形状：

```json
{"list":[{"kcmc":"高等数学","jsxm":"张三","xkd":"A101","zc":"1-16周(单)","xq":"2","jc":"1-2","xf":"4.5"}]}
```

字段映射：`kcmc/name/courseName` → 课程名；`xq` → 星期；`jc` → 节次范围；`zc/week/weeks` → 周次与单双周；`xkd/js/room` → 教室；`jsxm/teacher` → 教师；`xf/credit` → 学分。外层数组键兼容 `list/rows/data`。

## 4. 测试与构建

- `OnlineImportLogicTest` 从 58 项增至 **87 项**：XHR/fetch 脚本内容、三通道 JSON 装配、资源 URL 同学术域接受/异域拒绝/非 http 拒绝/去重/关键词排序/30 条上限、div 显式与兄弟坐标、`[1-3]节` 连讲、单双周、教师教室、超周拒绝、无周次告警、三通道去重与不同星期保留。
- `WakeupParserTest` 从 60 项增至 **69 项**：新增现代 Kingosoft `{list:[...]}` 夹具，覆盖课程名、教师、教室、星期、节次、周次、单周类型与学分。
- 全部 JVM 测试：**552 项通过**（15 个测试文件），原 514 项零回归；`build.bat` EXIT=0。
- `apksigner verify`：v2=true、v3=true、签名者 1；`aapt2 dump badging` 确认 `com.checktodo.app`、minSdk 24、targetSdk 34、`INTERNET` 与既有 Widget/通知组件在包；`aapt2 dump xmltree` 确认 `AndroidManifest` 内 `usesCleartextTraffic=true`。
- 产物：`native-android/out/checktodo-debug.apk`，**341,895 字节**（2026-10-25 02:01 本轮全量构建产物）。

## 5. 已知限制

- XHR 候选依赖浏览器 Performance API 记录；页面早已加载、资源记录被浏览器清理或跨域 iframe 不可读时可能漏候选。
- 候选重取只用 GET，不模拟教务 POST 查询参数；接口要求表单提交、token 变化或 anti-CSRF 时可能拿不到数据。
- 未选学校或家族未支持时，JSON 响应没有专用 parser，只能等待 HTML/表格通道；这是当前泛化方案的明确边界。
- div 识别依赖常见 class/id 特征，非标准命名或课程数据完全在前端脚本内但未渲染成格子时无法解析。
- 真机各校教务变体仍需实测；诊断文本就是失败时的反馈通道，用户可复制发送以定位卡在哪条通道。

# 2026-10-25 第十四轮：教务导入自动 XHR 重取与证据包诊断

## 1. 自动重取时序

- `domain/OnlineImportLogic` 新增 `ResourceRefreshState`，内部维护 `LinkedHashSet<String> attempted`。每轮提取先经 `resourceUrls()` 过滤排序，再调用 `refresh(candidates, manual, limit)`：自动轮询 `manual=false` 时只返回未见过的新 URL；手动「重新提取」与「导入」前的现扫 `manual=true`，先清空尝试集，强制重取当前候选。单轮上限仍为 15，且打开教务导入对话框时 `reset()`，避免复用旧页面的重取记录。
- `timetable/TimetableController` 持有该状态并在后台线程使用；`onlineParsing` 保证同一对话框不会并发提取/重取。2.5 秒轮询因此可以在新数据接口出现后立即发现并重取，不再要求用户手动点「导入」或「重新提取」。
- 防重复网络请求：URL 完全相同则自动轮询不再重取；URL 的 query、token 或路径变化会形成新候选并自动重取一次。用户仍可通过手动重新提取强制刷新旧候选。

## 2. 证据包诊断

- `Capture.frameUrls` 由提取脚本收集每个可访问文档的 `location.href`（最多 12 层），诊断中逐条列出 iframe 层级。
- DOM 表格保留实际合并用的 `parseCapturedTables()` 结果，同时逐张重新解析命中的 `outerHTML`，用 `parseFailureReason()` 记录原因：解析器错误、首条警告，或「表头/单元格格式未被启发式识别，解析 0 条排课」。`OnlineImportLogic.tableEvidence()` 输出 `Table #N`、`Reason` 和转义后的 HTML；`htmlEvidence()` 压缩空白、转义 `< > &` 并截取前 3000 字符。
- 候选资源全部进入证据包，包括本轮因上限或自动去重而未重取的 URL（状态显示 0，parser 摘要说明未重取原因）。实际重取后，`fetchResource()` 返回 `ResourceResponse`（HTTP 状态、Content-Type、解码后的 body），非 2xx 也读取 error stream。`responsePreview()` 截取响应体前 300 字符，`resourceEvidence()` 输出 URL、HTTP、Content-Type、Preview 和各 parser 摘要。
- `parseResourceBody()` 记录 parser 尝试链：选校专用 parser 命中数/警告；响应像 HTML 时继续 `HtmlTimetableParser`；非 HTML 且专用 parser 未命中时明确标注。异常请求保留 URL 与 `IOException` 信息。
- 样例格式：

```text
iframe URLs：
  #1 http://jw.school.edu.cn/homes.html
DOM tables with evidence：
Table #1
Reason: 表头或单元格格式未被启发式识别，解析 0 条排课
HTML: &lt;table ...&gt;
XHR resource evidence：
URL: http://jw.school.edu.cn/kbcx...
HTTP: 200
Content-Type: application/json
Preview: {"list":[{"kcmc":"..."}]}
Parsers: DedicatedParser=12；解析 12 条排课
```

- 诊断仍为可选中滚动文本，底部「诊断」按钮、长按状态栏均可打开，一键复制整包。

## 3. 测试与构建

- `OnlineImportLogicTest` 从 87 项增至 **107 项**：`ResourceRefreshState` 的新 URL 首取、已知 URL 自动跳过、手动强制刷新、`reset()`、15 条上限；`frameUrls` JSON 装配；HTML/response evidence 的空白压缩、实体转义、截断；`resourceEvidence` 包含 URL/HTTP/Content-Type/Preview/Parsers；`tableEvidence` 包含索引、原因和转义 HTML。
- 全部 JVM 测试：**572 项通过**（15 个测试文件），上一轮 552 项零回归；`build.bat` EXIT=0。
- `apksigner verify`：Verified，v2=true、v3=true，签名者 1。`aapt2 dump badging` 仍确认 `com.checktodo.app`、`INTERNET`、Widget 与通知组件。
- 产物：`native-android/out/checktodo-debug.apk`，**341,895 字节**。

## 4. 已知限制

- 自动重取不会刷新完全相同的候选 URL；若同一接口需重新查询（如用户点检索后 URL 不变但数据变化），需点「重新提取」强制刷新。
- 单轮最多重取 15 条候选；超过部分仍列入诊断，但不自动请求。
- 重取仍只用 GET，不重放 POST body、anti-CSRF token 或自定义头；POST-only 数据接口可能返回非 2xx 或无课程。
- 非 2xx 和 error stream 会记录到证据包，但不会按成功课程通道合并。

# 2026-10-25 第十五轮：KINGOSOFT 结构化定点解析 + 统一周次文法

背景：拿到北师大珠海真实课表页面 `D:\Desktop\kb.htm`（KINGOSOFT `xskcb10319.jsp` 学生选课课程表，12KB）。上一轮通用启发式对该网格「命中 1 张表但解析 0 条排课」，原因是其结构高度规律但与通用文本网格假设不同：星期在表头 `tr.H` 的 `td.td0`、节次在每行 `td.td1` 的中文数字、课程在 `td.td` 的多个兄弟 `div` 中，且周次表达式带 `[起-止节]` 方括号后缀。本轮按真实结构写定点解析器，并把周次文法收敛为共享实现。

## 1. 结构化解析器 KingoReportParser

- 新增 `domain/KingoReportParser.java`（纯 Java，无 Android 依赖）。识别特征：`table id="mytable"`，或存在 `tr.H` 行且其单元格中至少 5 个可解析为星期。二者满足其一即进入结构化解析，避免依赖单一 id。
- 表头：遍历 `tr.H` 行，按 `td.td0` 文本（`星期一`..`星期日`）建立「列 → 星期」映射；首个 `colspan=2` 的空白角落单元格不参与。天数不足 5 则视为非课表网格，回落通用解析。
- 节次：每行在前两个单元格中找 `class=td1` 且文本为中文数字（`一`..`六`）的单元格，作为该行节次号。`上午/下午/晚上` 分段标签（同为 `td.td1`，可能带 `rowspan=2`）经 `SimpleHtmlParser` 的 rowspan 展开后位于独立列，不会被误当节次。
- 课程格：`td.td` 内的每个 `div` 为一条排课，四段文本按 `<br>` 分隔：课程名 / 教师 / 周次表达式[起-止节] / 教室。解析锚点先在行内定位含周次特征且带 `[节次]` 后缀的那一行，再取其上两行为课程名与教师、下一行为教室，避免对课程名本身做格式假设。`div.div_nokb` 空格子文本为空，自然跳过。
- 一格多课：同一 `td.td` 中多个兄弟 `div` 会生成多条排课（真实文件中「形势与政策3」一格含 4 位教师的 4 条单周排课）。为此 `SimpleHtmlParser` 新增：兄弟块级 `div` 的文本用换行分隔（而不是拼接成一长行），并保留结构化元数据 `Table.id`、`Table.structuredRows`、`Row.className`、`Cell.text/id/className/rowspan/colspan`，旧文本网格 API 不变。
- 超界处理：延续既定「跳过而非钳制」口径，节次超出当前时间表长度的排课记警告并跳过；课程门数在节点校验前先建 `CourseBase`，因此被跳过的晚间课仍计入课程总数。
- 接入点：`HtmlTimetableParser.parse()` 入口处先调 `KingoReportParser.parse(tables, maxWeek, nodes)`，解析出排课即直接采用；失败或未命中特征则走原通用启发式。本地 HTML 文件导入、教务在线导入 DOM 通道、`parseCapturedTables` 等全部现有调用点自动获得该能力，无需改 Controller 层。

## 2. 统一周次文法 WeekExpression

- 新增 `domain/WeekExpression.java`，把原先分散在 HTML/CSV/在线解析里的周次规则收敛为单一实现：`HtmlTimetableParser.weekSegments()`、`TimetableCsvParser.parseWeeks()`、`OnlineDivParser` 均改为委托调用。
- 支持的文法：`1-16`、`7`（单周号）、`14-14`、`1-5,7-16`（逗号分段）、可选 `周`/`week` 后缀、`(单)/(双)` 及全角括号后缀、裸 `单/双` 标记；节次后缀 `[3-4]`、`[5]`、`[1-3]`（可带 `节`）。关键细节是先剥离方括号节次表达式再匹配周次数字，否则 `[3-4]` 中的 3、4 会被误读为额外周次。
- 逗号分段的模型处理：`CourseDetail` 现有模型是「起止周 + 单双周类型」，无法用一条记录表达 `1-5,7-16` 这种不连续集合；选择拆成多条 `CourseDetail`（同 baseId、同节次同教室教师，不同周次区间），与 CSV 多段周次的既有行为一致，UI/冲突检测/Widget 无需感知差异。
- 越界钳制沿用原策略：超出 `maxWeek` 的区间截断到 `maxWeek` 并告警；完全越界的区间跳过。

## 3. 真实文件核验与两处口径修正

用临时测试 harness 对 `D:\Desktop\kb.htm` 原文件全量解析（`decode()` 编码检测 + `HtmlTimetableParser.parse`）：

```text
nodes=13 bases=11 details=19 warnings=0
nodes=10 bases=11 details=19 warnings=0
nodes=8  bases=11 details=17 warnings=2
离散数学 day=1 node=1-3 week=1-16 teacher=周瑜 room=丽泽楼C103
计算方法 day=2 node=3-4 week=7-7  teacher=林定移 room=丽泽楼C106
计算方法 day=5 node=7-8 week=1-5  teacher=林定移 room=丽泽楼C106
计算方法 day=5 node=7-8 week=7-16 teacher=林定移 room=丽泽楼C106
形势与政策3 day=5 node=5-6 week=14/10/16/12 teacher=林子建/王诗韵/王艺璇/王丽莎
```

两处与任务书描述不符，按文件实况修正并在测试中固化：

- **「16 条排课」**：真实页面含 18 个课程块（`div`）。按 8 节时间表会跳过 2 条晚间 9-10 课，剩 16 条源排课；但其中 `1-5,7-16[7-8]` 按逗号拆分规则生成 2 条 `CourseDetail`，故正确结果是 **17 条排课**。按 App 默认 13 节时间表则 18 条全部在界内，拆分后为 **19 条排课、11 门课、0 警告**。测试按 8 节夹具断言 17 条并注明构成，同时断言 11 门课。
- **「计算方法周四 7-8」**：真实文件中该格位于第 5 个课程列（`星期五`），行节次为「四」（第 4 节）。人工核对原始 HTML（该行 `<tr>` 起于第 281 行，含 1 个 `td.td1` 节次格 + 5 个 `td.td` 课程格，目标 div 在第 316 行即第 5 列）确认星期为**周五**。测试按 `day=5` 断言。

## 4. 测试与构建

- 新增 `test/KingoReportParserTest.java`，共 **37 项断言**：结构识别（`KINGOSOFT` 标记）、8 节夹具 17 条排课 + 11 门课、2 条晚间 9-10 跳过警告；离散数学的星期/节次/周次/教师/教室；计算方法 7 周周二 3-4 节与 `1-5,7-16` 拆出的两条周五 7-8 节；形势与政策3 四条排课的教师与单周号、全部周五 5-6 节；周次文法（范围、单周号、塌缩范围、逗号分段、单双周标记、`周` 可选、节次数字不混入周次、越界钳制）；非 KINGOSOFT 小表格回落通用解析；`SimpleHtmlParser` 结构化元数据（表 id、行 class、colspan、rowspan、td1 class）与兄弟 div 分行。
- `build.bat` 纯 Java 编译与运行列表加入 `WeekExpression.java`、`KingoReportParser.java`、`KingoReportParserTest.java`。
- 全部 JVM 测试：**609 项通过**（16 个测试文件），上一轮 572 项零回归；`build.bat` EXIT=0。
- `apksigner verify`：Verified，v1=false、v2=true、v3=true，签名者 1。`aapt2 dump badging` 确认 `com.checktodo.app` v1.0、minSdk 24 / targetSdk 34、`INTERNET`/通知权限与 Widget 组件齐全。
- 产物：`native-android/out/checktodo-debug.apk`，**345,991 字节**（上一轮 341,895，新增解析器约 4KB）。

## 5. 已知限制

- KINGOSOFT 结构化解析覆盖 `xskcb*.jsp` 报表网格这一真实格式；其他 KINGOSOFT 变体或非 KINGOSOFT 页面仍回落通用启发式，识别率不保证。
- 页面元数据（学号/姓名/学年学期/总学分）本轮未采集导入，仅用于解析诊断思路；课表仍需用户自设学期开始日与总周数。
- 周次表达式若出现文法未覆盖的写法（如汉字周次、跨学期标记），该排课会进警告列表而非静默丢弃，但不会生成记录。

# 2026-10-25 第十六轮：几何网格通用提取

背景：新教务课表（新版正方、青果/KINGOSOFT 现代 AJAX 页）常由接口拉数据后用 div 网格渲染，页面可能没有可识别的 `<table>`。上一轮三通道已覆盖 XHR 与 div class/id 特征，但对无明显 class/id 的自绘网格仍可能命中 0。本轮新增「布局即语义」的几何兜底通道，不依赖学校专属标签。

## 1. JS 发现与回传

- `domain/OnlineImportLogic.extractionScript()` 在主文档与每个可访问 iframe 中调用 `scanGeo(doc)`：遍历 `body *`，跳过 `script/style/nav/header/iframe` 等非内容节点，读取 `getBoundingClientRect()`，过滤宽度 <8、高度 <6、文本超过 500 字符的不可见候选。
- 星期表头：文本严格匹配 `星期[一二三四五六日]` 或 `周[一二三四五六日]`，回传文本与中心坐标。
- 节次标记：`一`、`第1节`、`第1-2节`、两位以内数字、`HH:MM` 或 `HH:MM-HH:MM`，同样回传中心坐标。
- 课程块：`innerText` 含周次/节次特征（如 `1-16周`、`[1-3]节`、`(单)`），换行数 1..6、长度不超过 400 字符，最多 200 块；父容器和子元素都会被采集，Java 侧再去重。
- 回传 JSON 新增 `geometry:{courses,days,periods}` 与统计 `geo/geoDays/geoPeriods`；`Capture` 对应新增 `geometryCourses/geometryDays/geometryPeriods`，`parseCapture()` 完成 JSON 到对象的装配。

## 2. Java 几何解算

- 新增 `domain/GeometryGridSolver.java`（纯 Java）。`Block` 表示课程块中心坐标与宽高，`Mark` 表示表头/节次标中心坐标。
- 星期映射：课程块中心 X 与星期表头 X 做最近邻匹配；有效表头至少 5 个，否则不做几何解算并输出警告。无 `星期X` 时可把单字星期标提升为列头，用于极简布局。
- 节次映射：课程块 Y 范围与节次标 Y 重叠，取最小/最大节号；课程文本自带 `[1-3]` 时以方括号节次优先；只有时间段（`08:00-08:45`）时按 Y 排序编号；完全没有节次标时按 Y 聚类推断行号，`gridInfo` 标注低置信度。
- 文本解析：按换行拆分，先找真实周次特征行（避免课程名中的裸数字误判），首行为课程名，教师按中文人名特征识别，教室按 `楼/室/馆/号/区` 或字母+数字识别；周次、逗号分段、单双周与连讲节次全部复用 `domain/WeekExpression`。首行本身像节次标（如 `第1-2节`）时跳过该块，避免把表头当课程。
- 层级去重：父容器 `innerText` 会包含子块文本，`distinctLeaves()` 按面积升序保留最小叶子，父块只有在不含已保留叶子时才参与，避免同一课程被重复解算两次。

## 3. 流水线与取舍

- `timetable/TimetableController` 在线导入解析顺序为：选校专用 parser → DOM 表格（含 KINGOSOFT 结构化解析）→ 几何网格 → div 格子 → XHR 会话重取。每个通道生成 `HtmlTimetableParser.Result` 后立即用 `OnlineImportLogic.merge()` 合并。
- 取舍：几何通道与表格通道并行尝试，而不是只在表格完全失败后启用。这样实现更简单、无额外状态机；重复排课由既有「课程名 + 星期 + 起止节次 + 周次范围 + 单双周」口径去重，缺失教师/教室可互相补齐。
- 状态栏与诊断新增几何计数：课程块数、星期头数、节次标数、解析出的排课数；捕获为 0 时仍完整显示各通道统计，用户能看出是发现 0、解析 0 还是只有几何失败。

## 4. 测试与构建

- 新增 `test/GeometryGridSolverTest.java`，共 **36 项断言**：KINGOSOFT 表格形态（中文单字节次、`[1-3]` 连排、单周、逗号分段、教师教室、最近邻列）、通用 div 网格形态（`第1节` 阿拉伯节次、单双周、行/列映射）、时间轴行号、无节次标的 Y 聚类低置信度、无星期表头告警、父容器去重、WebView geometry JSON 往返解算、两行/三行/节次误判文本。
- 修正一处实现缺陷：`PERIOD_TEXT` 原先把「节」写成必需字符，导致 `一/二/三` 节次标全部失效并强制进入低置信聚类；现在 `节` 可选，`一` 与 `第1节` 都能识别。
- 全部 JVM 测试：**645 项通过**（17 个测试文件），上一轮 609 项零回归；`build.bat` EXIT=0。
- `apksigner verify`：Verified，v1=false、v2=true、v3=true，签名者 1。`aapt2 dump badging`：`com.checktodo.app` v1.0、minSdk 24、targetSdk 34、`INTERNET`/通知/电池优化权限与入口 Activity 齐全。
- 产物：`native-android/out/checktodo-debug.apk`，**350,087 字节（342KB）**。

## 5. 已知限制

- 页面至少需要 5 个可定位的星期表头（或可提升的单字星期列）；没有表头的自由排版页面无法可靠做列映射。
- 极端布局（课程块跨多列、虚拟滚动只渲染视口、CSS transform 缩放未还原）可能造成星期错列或节次漏行；无节次标时的聚类结果只按行排序，置信度低于显式标记。
- `innerText` 在不同 WebView/ROM 上换行行为略有差异；两行夹具中课程名为空时会跳过而不是猜测。
- 跨域 iframe 仍无法访问坐标；本方案只覆盖同源 frame，跨域数据依赖 XHR 通道或用户保存 HTML 后导入。

# 第十六轮：三视图卡片修复与教务导入手动提取模型（2026-10-25）

## 1. 三视图桌面卡片缺陷清单与修复

| # | 现象 | 根因 | 修复 |
| - | ---- | ---- | ---- |
| 1 | 待办条目点「完成」后直接消失，无法在卡片上撤销完成 | `TodoLogic.filterWidgetTodos` 过滤掉已完成待办，而点击处理里 `setTodoCompleted(id, !completed)` 的撤销分支永远没有可见条目可点 | 过滤规则改为已完成待办保留显示（仍按截止时间升序），条目动作标签随状态显示「完成/撤销」，撤销后回进行中/已过期状态 |
| 2 | 个别启动器上切换视图后短暂出现上一视图的行 | Factory 只在 `onDataSetChanged` 才从 SharedPreferences 读模式，部分 OEM 启动器在数据变更回调前就向 Factory 取视图，沿用构造时的默认打卡模式 | Factory 构造时从集合视图服务 URI（`checktodo://widget/<id>/mode/<mode>`）解析初始模式，`onDataSetChanged` 仍以存储为唯一事实源；URI 解析写成 `WidgetModes.modeFromUri` 纯函数 |
| 3 | 删除卡片后重建（尤其系统复用 widgetId）可能带出旧模式 | `CheckTodoWidget` 未实现 `onDeleted`，`mode.<id>` 键永久残留 | 新增 `onDeleted`，删除实例时清理对应模式键 |
| 4 | 模式逻辑分散在 Android Provider 里，无法单测，异常值越界会导致视图错乱 | 常量、循环、文案、键名都写在 `CheckTodoWidget`，且旧 `getMode` 只在读取时兜底 | 新增纯 Java `widget/WidgetModes`（normalize/next/title/emptyText/modeKey/modeFromUri）与 `widget/WidgetModeStore` 抽象（Memory 实现 + Provider 内 SharedPreferences 实现），读写都强制 normalize |
| 5 | 空态文案与视图语义不一致（待办视图显示「没有待完成待办」） | 文案硬编码在 Provider，未与三视图收敛 | 文案统一进 `WidgetModes`，待办空态为「没有待办」 |

其余链路复核结论：`setRemoteAdapter` 的 data URI 含模式，切换后 `updateAppWidget + notifyAppWidgetViewDataChanged` 会强制重建 Factory，数据 URI 变化即重建，不复用旧 Factory；三态卡课表视图与 `TodayCourseWidget` 均走 `CourseWidgetItems.build` + `TimetableRepository` 当前选中课表，取数口径一致；打卡条目 fillIn 只在可打卡/已完成状态携带 ID，其他状态整行点击兜底打开 App，已完成转 `MainActivity` 弹「取消今日打卡」确认框；`ACTION_CLICK_ITEM` 为兼容旧 PendingIntent 的遗留分支，保留无害；`WidgetRefresh.notifyDataChanged` 显式广播到两个 Provider，各实例按自身存储的模式独立刷新，模式不丢、不跳变。诊断入口保留：底部「诊断」按钮与长按状态栏均可打开可滚动证据包并一键复制。

状态矩阵推演（三视图 × 切换 × 数据变更 × 多实例）：

| 场景 | 实例 A（打卡） | 实例 B（待办） | 实例 C（课表） |
| ---- | -------------- | -------------- | -------------- |
| 冷启动 / 重启 | 标题「今日打卡」，任务列表 | 标题「待办」，含已完成可撤销 | 标题「今日课程」，当前课表条目 |
| A 点「切换」 | A → 待办（URI/标题/空态同步重建），B/C 不变 | 不变 | 不变 |
| B 条目勾选完成 | A 不变 | 条目转「已完成 · 撤销」，位置不变 | 不变 |
| B 条目撤销 | 不变 | 条目回到原状态 | 不变 |
| C 条目点击 | 不变 | 不变 | 打开 App 直达课表页 |
| App 内数据变更 | 刷新保持打卡视图与最新数据 | 刷新保持待办视图 | 刷新按当前课表重建列表 |
| 删除实例 A | `mode.<A>` 清理，重建回落打卡 | B 模式保留 | C 模式保留 |
| 模式值异常（越界/负数） | normalize 回打卡 | 同左 | 同左 |

## 2. 教务导入改为手动提取模型

- 彻底移除 2.5 秒 Handler 轮询与 `onPageFinished` 后 500ms/1800ms 两次自动扫描：`onlinePollHandler/onlinePollRunnable/onlineImportRequested` 字段与相关清理代码全部删除，页面加载完成只更新提示文案「请按顶部指引操作，到位后点『提取课表』」，WebView 主线程不再被周期性几何全量遍历阻塞（这正是复杂教务页卡死的根因）。
- 底部按钮由「重新提取」改为醒目的「提取课表」：点击后按钮禁用并显示「提取中…」，一次性执行 DOM 表格 + 几何网格 + div 格子 + XHR 资源重取（XHR 尝试集每轮强制刷新，仍受单轮 15 条上限保护），完成后状态栏显示「已捕获 N 门课 / M 条排课 · 扫描 X 文档 · 几何块 Y · DOM 表格/数据请求明细」并 Toast 结果。
- 顶部说明与引导覆盖层文案同步改为手动流程；家族指引保持常驻显示，提醒用户走到哪一步。

新交互流程：进入对话框 → 输入网址或搜索选择学校（选校后自动加载并显示家族指引）→ 在 WebView 中自行登录、按指引导航到课表页（验证码手动输入）→ 点「提取课表」→ 状态栏出现捕获统计 → 可继续翻页/切学期后再次提取累积 → 点「导入」进入预览确认。导入不再重复现扫，直接使用最近一次提取结果；若尚未提取到课程，Toast 提示「尚未捕获到课程：请先点『提取课表』」。

## 3. 测试与构建

- 新增 `test/WidgetModeTest.java`（25 项断言）：模式循环、越界 normalize、按 widgetId 的存储隔离与删除回落、URI 模式解析、三视图标题/空态文案、`CourseWidgetItems` 排序与副标题装配、缺失课程兜底文案。
- 调整 `test/TodoTest.java`：小卡片待办过滤改为已完成保留显示（撤销链路可用），并更新截止时间升序断言。
- 全部 JVM 测试：**1568 项通过，0 失败**（29 个测试文件；第二十轮 `ScauScheduleDefaultsTest` 54、第二十一轮 `WidgetFactorySnapshotTest` 38 + `FrameDumpTest` 62、第二十二轮 `FrameDiscoveryRegressionTest` 52）。`build.bat` EXIT=0。
- `apksigner verify`：Verified，v1=false、v2=true、v3=true，签名者 1。`aapt2 dump badging`：`com.checktodo.app` v1.0、minSdk 24、targetSdk 34、`POST_NOTIFICATIONS`/`RECEIVE_BOOT_COMPLETED`/`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`/`INTERNET` 权限与两个 app-widget receiver 齐全。
- 产物：`native-android/out/checktodo-debug.apk`，**403,573 字节（394KB）**。

## 4. 已知限制

- RemoteViews 在桌面进程渲染，卡片配色仍跟随系统深浅色，无法读取 App 内手动主题偏好；这是 Launcher 进程取资源的固有限制。
- 待办卡片保留已完成条目是为了可撤销；大量历史已完成待办会一直显示，当前没有完成时间戳可做「最近完成」过滤，需要清理时在 App 内处理。
- 教务导入识别率仍取决于页面结构与家族 parser 覆盖；手动提取模型要求用户到位后主动点按钮，若忘记提取直接点导入会得到提示而不是自动扫描。

---

# 第十三轮：小卡片空态串状态 + 提取课表卡死（接手完成）

> 承接上一轮未完成的两个真机问题。接手时按任务书先核磁盘现状，结论与任务书的描述**不一致**：
> 任务书写「失败测试已写、`WidgetFactoryState` 不存在、16 个编译错误、提取链路未动工」，
> 但磁盘上 `WidgetFactoryState.java` 已存在、`CheckTodoWidgetService` 已接入它、`build.bat`
> 已把两个新测试编进测试清单，且提取链路已使用 `onlineExecutor`。第一次 `build.bat` 直接
> **EXIT=0、715 项全绿**。即：前任在额度耗尽前已把主体实现写完，只是报告没来得及更新，
> 任务书引用的是更早的中间态。因此本轮的工作是「核实 → 补缺口 → 加固 → 记录」，不是重写。

## 1. 问题 A：课表为空时小卡片三视图串状态

### 根因（按代码事实）

串状态不是「渲染时读错 mode」这么简单，而是**两个时序缝隙**叠加：

1. **Factory 被复用、`onDataSetChanged` 尚未跑**。`RemoteViewsFactory` 的生命周期由启动器
   驱动，滑动/重建时启动器可能先调 `getCount()/getViewAt()` 再调 `onDataSetChanged()`。
   只要 `getCount()` 直接返回「上一轮解析出来的列表 size」，这一瞬间吐出的就是**上一个模式的行**。
   课表为空时最明显：列表是 0 条，但缓存里还留着打卡/待办的行，于是「标题是课表、内容是打卡」。
2. **mode 有两个来源**：Intent 的 data URI 与 `SharedPreferences` 里的 `mode.<id>`。
   切视图时只换了 URI、存储还没落盘（或反之），两个来源短暂不一致，标题与行来自不同来源。

### 修法

新增纯 Java 状态机 `WidgetFactoryState`，把「模式来源 + 是否已加载」显式建模，让上面两个缝隙
在类型层面无法绕过：

- 构造时只从 URI 取**初始** mode（`WidgetModes.modeFromUri`，无 mode 段返回 -1 则保持默认）；
- `load(tasks, todos, courses, storedMode)` 在 `onDataSetChanged` 里调用，**以存储 mode 覆盖 URI**，
  同时记录三列行数并置 `loaded=true`；
- `canRender()` 在 `loaded` 之前恒为 `false`，`count()` 在未加载时恒返回 0。
- `CheckTodoWidgetService.Factory.getCount()` 改读 `state.count()`，`getViewAt()` 首行
  `if (!state.canRender()) return null;` —— 未加载完成时宁可显示空态文案，也不吐旧行。

三条关键约束因此由构造保证，而非依赖调用顺序自觉：
**① 未加载不渲染；② 存储 mode 是唯一事实源；③ 标题与行数同源（都读 `state.mode()`）**。

### 失败测试 → 转绿

`test/WidgetEmptyStateTest.java`（41 项断言）覆盖「空课表 / 空待办 / 空打卡 × 三视图」全矩阵，
并按失败测试原有的契约实现，未改动任何一条断言：

| 用例 | 断言要点 |
| ---- | -------- |
| `testEmptyCoursesView` | 空课表切到课表视图：`count()==0`、`canRender()==true`、mode 为课表、标题「今日课程」、空态「今天没有课程」、且 `count()` 不等于 tasks/todos 数量 |
| `testEmptyMatrix` | 3 种空态组合 × 3 视图 = 9 组，每组 `count()` 只跟随自己那一列数据源 |
| `testNoStaleRowsBeforeLoad` | 未 `load` 前 `canRender()==false` 且 `count()==0`；`load` 后再 `load` 空课表能立刻把行数降到 0 |
| `testModeCycleFromCourses` | 空课表 → 打卡 → 待办 → 课表 循环，每步行数跟当前模式数据源 |
| `testStoredModeWinsOverUri` | URI 只做初始种子；`load` 时存储 mode 覆盖 URI；越界 mode 被 normalize 回打卡 |
| `testTitlesFollowLoadedMode` | 三种模式下标题与已加载 mode 一致，杜绝「标题课表、内容打卡」 |

`WidgetModeTest`（25 项）同时覆盖模式循环、按 widgetId 的存储隔离、URI 解析、`CourseWidgetItems` 排序与兜底文案。
两个测试类合计 66 项，当前全 PASS。

## 2. 问题 B：点「提取课表」卡死

### 卡死来源逐段拆解（按代码事实，非推测）

提取链路一共 5 段重活，修复前全部落在 UI 线程：

| 阶段 | 代码位置 | 为什么会卡 | 量级 |
| ---- | -------- | ---------- | ---- |
| ① 注入 JS 并回传 | `extractionScript()` → `evaluateJavascript` 回调 | 回调 `onReceiveValue` **必然在 UI 线程**；返回的是 `JSON.stringify` 后的大字符串（DOM 表格 outerHTML + div 格子 + 几何块 + XHR 列表） | 修复前几何块上限 200、表格单张 <500KB，字符串可达数 MB |
| ② JSON 解析 | `OnlineImportLogic.parseCapture(raw)` | 自研 `JsonReader` 递归下降解析上面那坨字符串，纯 CPU | 与 ① 成正比，MB 级字符串是几十~上百 ms 级 |
| ③ 四个 parser 串行 | `parseCapturedTables`（可对每张表再单独解析一遍出证据）/ `GeometryGridSolver.solve` / `OnlineDivParser.parse` / `parseResourceBody` | 全同步、纯 CPU；诊断块里还会对每张表**再解析一次** | parser 数量 × 表格数 × 行数² 级 |
| ④ merge 去重 | `OnlineImportLogic.merge` × N 次 | 每次 merge 重扫已有 bases/details | 课程数² 级字符串比较 |
| ⑤ XHR 重取 15 候选 | `fetchResource`（`HttpURLConnection`） | 15 × (10s 连接 + 10s 读取) 最坏 ≈ **300s** | 网络阻塞 |

①+②+③+④ 是纯 CPU，占 UI 线程 = 掉帧/ANR；⑤ 是阻塞 IO，占 UI 线程 = 界面完全不动。
两者叠加，用户在复杂教务页点「提取课表」就成了「卡死」。

### 修法一：全链路移出 UI 线程

- 新增 `onlineExecutor = Executors.newSingleThreadExecutor()`，与对话框同生命周期；
  `onDismiss` 里 `shutdownNow()` 并置空，避免已销毁 WebView 的回调继续跑解析。
- `evaluateJavascript` 回调只做三件轻量事：判对话框是否还在、读 `maxWeek/nodes/userAgent`
  等标量、设状态文案「正在解析课表...」，然后 `submitOnlinePipeline(pipeline)` 把
  **②③④⑤ 整体**投进 executor。`submitOnlinePipeline` 对「池已关闭」做了
  `isShutdown` + `RuntimeException` 双重兜底，任何情况都会 `onlineParsing.set(false)`，
  按钮不会永久停在「提取中…」。
- 结果一次性打包成 final 局部变量，`activity.runOnUiThread` 回到 UI 线程只做
  「更新 onlineBases/onlineDetails、写诊断、刷新状态、复位标志」。

**线程归属结论**：`WebView`/`RemoteViews`/`View`/`Toast`/`Dialog` 全部留在 UI 线程；
`parseCapture`、四个 parser、`merge`、`HttpURLConnection` 全部在后台单线程串行执行
（单线程而非线程池，是为了保住「人工翻页累积」的先后顺序，且天然避免并发写 `onlineDetails`）。

### 修法二：JS 减负（`extractionScript()`）

| 措施 | 实现 | 效果 |
| ---- | ---- | ---- |
| 几何扫描遍历上限 | `for(var g=0; g<els.length && g<3000; g++)` | `querySelectorAll('body *')` 在巨型教务页可达数万节点，卡在 WebView 主线程；3000 封顶 |
| 跳过隐藏/零尺寸 | `el.clientWidth<1` 或 `el.clientHeight<1` 前置跳过；`getBoundingClientRect()` 后 `r.width<8` 或 `r.height<6` 再跳；`getComputedStyle` 判 `display:none/visibility:hidden` | 隐藏菜单/弹层不再参与几何计算（它们原本会被误识别成课程块） |
| 跳过无意义标签 | `SCRIPT/STYLE/NOSCRIPT/NAV/HEADER/TITLE/META/LINK/IFRAME/FRAME` 先 `continue` | 省掉最常见的误命中 |
| 几何块上限 | 200 → **120**（`geoCourses.length>=120` 即 `continue`） | 单日课表不可能有 120 个课程块，超出的必是噪声 |
| 文本长度早退 | `textContent.length>500` 直接跳过；课程块 `innerText` 再限 400 | 避免对大容器做 `split/trim` |
| 捕获字符串总量封顶 | `payload + html.length < 1500000`（1.5MB），单张表另限 `<500000` | 直接掐住 ① 的回传体积，也就掐住了 ② 的解析量 |
| 其它上限 | `tables` ≤12 张、`cells` ≤200、`frames` ≤12、`resources` ≤30、`geoDays` ≤30、`geoPeriods` ≤60 | 每个维度都有界，最坏情况可估算 |

这些上限的意义不只是「快一点」：它们把 ① 的回传字节数和 ②③ 的输入规模变成了**可估算的常数上界**，
而不是「取决于教务页有多复杂」。

### 修法三（本轮新增加固）：单轮抓取总预算

做完上面两步后仍有残留问题：`ResourceRefreshState` 只限「本轮几次」（15 次），
但每次 `fetchResource` 有 10s+10s 超时，**15 个不可达候选仍会串成最坏 300s**。
虽然已在后台线程、不再卡 UI，但用户看到的就是「提取中…」五分钟不恢复 —— 与卡死体感无异。

新增纯 Java `OnlineImportLogic.FetchBudget`（截止时间模型，毫秒时间戳注入，便于 JVM 单测）：

- `new FetchBudget(startMillis, 60_000)`：单轮总预算 60s；
- `canStart(now)`：耗尽即 `false`，循环里直接写诊断「本轮预算已耗尽，下次点提取继续」；
- `remaining(now)`：把**单次请求超时夹到剩余额度内**（`Math.min(10_000, max(1000, remaining))`），
  保证即使最后一次请求也不会超出预算太多；
- `record()/spent()`：记录实际发起次数，便于统计。

同时把 `CookieManager.getInstance().getCookie()` 从后台线程挪到 UI 线程（`cookieFor()`），
只把 Cookie **字符串** 传进后台。理由：`CookieManager` 属 WebView 家族，部分 ROM 对其线程
亲和性并无保证，跨线程访问属于隐藏风险；顺手也让 `fetchResource` 不再触碰任何 WebView 对象。

### 关于「耗时来源」的可辩护结论

- **CPU 侧**：唯一可确定的是「输入体积」。JS 侧把回传封在 1.5MB、几何块 120、遍历 3000 之后，
  `parseCapture` 的输入与四个 parser 的循环次数都变成有界值，CPU 段从「随页面复杂度爆炸」
  变成「常数上界内的毫秒~几十毫秒级」。这段现在也不再占 UI 线程，即使慢也只表现为按钮转圈。
- **IO 侧**：这是卡死的主要贡献者，也是最不可控的。修复前最坏 15×20s≈300s；修复后被
  `FetchBudget` 钉死在 60s 内，且每次超时随剩余额度收缩。
- **诚实的边界**：本轮**没有**做真机 systrace/Profiler 采样，所以不给「某函数占 380ms」这类
  编造数字。上面所有量级都能在代码里逐条对上（上限常量、超时常量、循环上限），属于可复核的
  结构性论证，不是测量值。

## 3. 测试与构建（本轮实测）

```
[4/7] pure Java domain tests
...
passed=729 failed=0        ← 汇总 19 个测试文件
[5/7] d8 dex
[6/7] package and align
[7/7] reuse debug keystore
Verify APK → Verifies (v2=true, v3=true, signers=1)
Build complete: D:\Desktop\check_todo\native-android\out\checktodo-debug.apk
EXIT=0
```

- 729 项全通过、**0 失败**；对比接手时的 715 项，本轮净增 14 项（`FetchBudget` 用例），无回归。
- 新增 `FetchBudget` 用例覆盖：起始剩余额度、`record` 计数、半程仍可发起、**恰好到截止点即不可发起**、
  超期剩余恒 0（不为负）、预算 0/负数视为 0、以及「15 个候选每个吃满 20s」的模拟——
  验证实际只发起 3 次、总耗时 ≤60s。
- `apksigner verify`：Verified，v2/v3 = true，signers = 1。
- `aapt2 dump badging`：`com.checktodo.app` v1.0，minSdk 24，targetSdk 34，
  `POST_NOTIFICATIONS`/`RECEIVE_BOOT_COMPLETED`/`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`/`INTERNET`
  四个权限齐全。
- 产物 354,183 字节（346KB）。
- **Web 版零改动**：`src/`、`dist/`、`public/`、`index.html`、`package.json`、`vite.config.js`
  最新 mtime 仍为 2026-10-24 02:48（早于本轮），本轮所有写盘都在 `native-android/` 与两份 md。

## 4. 本轮改动的文件清单

| 文件 | 改动 |
| ---- | ---- |
| `src/com/checktodo/app/domain/OnlineImportLogic.java` | 新增 `FetchBudget`（截止时间预算，纯 Java） |
| `src/com/checktodo/app/timetable/TimetableController.java` | 抽取 `cookieFor()` 在 UI 线程快照 Cookie；`fetchResource` 改吃 `String cookies` + `int timeoutMillis`；抓取循环接入 `FetchBudget`，预算耗尽写诊断；新增 `ONLINE_FETCH_BUDGET_MS`/`ONLINE_FETCH_TIMEOUT_MS` 常量 |
| `test/OnlineImportLogicTest.java` | 新增 `testFetchBudget()`，14 项断言 |
| `NATIVE_ANDROID_REPORT.md` / `README.md` | 更新测试数与产物大小，补充本轮章节 |

> 未改动：`WidgetFactoryState`、`CheckTodoWidgetService`、`WidgetModes`、`WidgetEmptyStateTest`、
> `WidgetModeTest`、`extractionScript()` 的全部上限参数——这些前任已写完且测试全绿，本轮只做核实，
> 按任务书要求「失败测试不重写」。

## 5. 本轮未做（明确说明）

- 未做真机 / 模拟器实测：本环境没有可用的 adb 设备，所有结论来自代码审查 + JVM 测试 + 构建产物校验。
  「不再卡死」在真机上的最终确认仍需一次手动验证（点提取、观察按钮是否在 60s 内恢复）。
- 未给 `TimetableController` 里的 pipeline 写 JVM 单测：它直接依赖 `WebView`/`Dialog`/`Toast`，
  在零第三方库（无 Robolectric/Mockito）的约束下无法在 JVM 实例化。可测的部分（`FetchBudget`、
  `ResourceRefreshState`、`parseCapture`、四个 parser、`merge`）已经全部有测试覆盖。

---

# 第十四轮：提取架构重做（轻发现 + 按需取数据）

> 真机反馈：点「提取课表」**永远显示提取中**，点诊断**永远显示「尚未扫描」**。
> 用户判断「四合一巨型脚本本身就是架构错误」，要求放开思路重做。本轮按此执行，
> 并附真实案例（北师大珠海 Kingosoft）离线夹具验证。

## 1. 「尚未扫描」的根因结论

现象拆成两半，分别有确切的代码原因：

**① 诊断为什么一直是「尚未扫描」** —— `TimetableController` 里
`private String onlineDiagnostics = "尚未扫描";` 是字段初值，而全文只有**一处**赋值：
旧 `extractOnlinePage` 的 `pipeline` 在**全部阶段跑完之后**才执行
`onlineDiagnostics = finalDiagnostics;`（旧代码 1315 行附近）。也就是说：

- 诊断内容只在「整条流水线成功走完」时才诞生；
- 中途卡住、超时、异常、回调丢失，赋值语句根本执行不到；
- 于是 `showOnlineDiagnostics()` 永远读到初始值「尚未扫描」。

结论：**「尚未扫描」不是没扫描，而是「扫描结果只在整个流程结束后才写入」**——
它是流程没跑完的<b>表征</b>，不是原因。旧实现的诊断缓冲是「终态字符串」，天然无法反映进度。

**② 为什么流程跑不完** —— 旧链路的数据来源只有一个：`evaluateJavascript(
extractionScript(), callback)` 的**返回值**。这条路有两个结构性死穴：

| 死穴 | 具体机制 |
| --- | --- |
| JS 单表达式没有超时 | `extractionScript()` 是一个 IIFE，内部递归 `scan(doc, depth)` 并对每个文档执行 `querySelectorAll('body *')`、`getComputedStyle`、`performance` 读取。巨型教务页可能上万元素；只要某个 frame 的脚本抛异常未被内层 `try/catch` 覆盖、或被 CSP 拦截、或页面重导航打断，`ValueCallback` **永不回调**，`onlineParsing` 永远为 `true`，按钮永远停在「提取中…」 |
| 巨型返回值不可靠 | 返回值是 `JSON.stringify` 后的数 MB 串，要过「JS 序列化 → WebView 桥 → Java 反序列化」。任何一环失败（返回 `null`、"null"、转义截断），旧代码的 `parseCapture` 直接 `return null`，外层 `catch (Exception ignored) { error = "页面提取结果无法解析"; }` 把原因吞掉，且这一路也没有超时 |

两者叠加：**没有超时 + 单点巨型通道 = 一旦失败就永久静默**。

## 2. 新架构

### 2.1 架构图

```
┌──────────────── UI 线程 ────────────────┐   ┌──────── 后台单线程 (online-extract) ────────┐
│ 点「提取课表」                          │   │                                             │
│  ├─ 按钮禁用 →「提取中…」              │   │                                             │
│  ├─ 诊断重置：「提取已开始…」          │   │                                             │
│  ├─ addJavascriptInterface(__checkTodoSink)│                                          │
│  └─ evaluateJavascript(discoveryScript) ─┼──►│                                             │
│       │ (回调也在 UI 线程, 仅存 raw+countDown)│                                          │
│       └──────────────────────────────────┼──►│ ① 通道1 轻发现  (3s)                        │
│                                          │   │   parseDiscovery(raw)                       │
│                                          │   │   → frame 树 + resources                    │
│                                          │   │   → candidates() 排序 (xskcb 优先)          │
│                                          │   │                                             │
│                                          │   │ ② 通道2 取数据  (单候选 8s, 总 30s)         │
│                                          │   │   for 每个候选:                             │
│  runOnUi { evaluateJavascript(           │◄──┼──    runOnUi 投递 fetchProxyScript(id,url)  │
│    fetchProxyScript(id, url)) }          │──►│    await 拼装完成 / 8s 超时                   │
│                                          │   │    ├ 成功 → body                            │
│  __checkTodoSink.begin/chunk/done/fail   │   │    └ 失败 → fetchResource(Cookie 直取) 回落  │
│       │ (WebView 线程)                   │   │                                             │
│       └─ post 到 worker ─────────────────┼──►│   ChunkAssembler (按 id 分离, 按 seq 定序)    │
│                                          │   │                                             │
│                                          │   │ ③ 通道3 深度扫描 (仅长按开启, 12s)           │
│                                          │   │                                             │
│                                          │   │ ④ 解析合并                                  │
│                                          │   │   parseResourceBody → 专用parser→HTML→div    │
│                                          │   │   merge 去重                                │
│ ⑤ finish: 按钮恢复 + 状态 + Toast        │◄──┼──  runOnUi(finish)  (幂等 AtomicBoolean)     │
└──────────────────────────────────────────┘   └─────────────────────────────────────────────┘
        ▲                                                     │
        │ 看门狗线程: sleep(30s+1.5s) → 强制 cancel + finish    │
        └─────────────────────────────────────────────────────┘
```

### 2.2 代码位置

| 文件 | 职责 |
| --- | --- |
| `src/com/checktodo/app/domain/OnlineExtraction.java`（新增，纯 Java） | 通道 1 脚本 + `Discovery`/`FrameNode` 解析；通道 2 `fetchProxyScript`/`abortScript`/`abortAllScript`；`ChunkAssembler`（分块拼装）；`ExtractionDeadline`（阶段 + 硬超时）；`Diagnostics`（线程安全阶段缓冲）；`candidates()`（候选排序） |
| `src/com/checktodo/app/timetable/ChunkBridge.java`（新增） | `@JavascriptInterface` 接收器 `begin/chunk/done/fail`，把 WebView 线程回调**原样投递**到后台 executor |
| `src/com/checktodo/app/timetable/OnlineExtractionSession.java`（新增） | 一次提取的运行时：单线程 executor、拼装器、桥接、deadline、诊断；`await()` 统一带预算等待；`close()` 幂等 |
| `src/com/checktodo/app/timetable/TimetableController.java`（重写提取链路） | `extractOnlinePage` 编排、`runExtraction` 后台主体、`fetchViaProxy`、`runDeepScan`、`finishExtraction`、`UiProgress`、`runOnUi`；长按开启深度扫描；dismiss 四步取消 |
| `src/com/checktodo/app/domain/OnlineImportLogic.java`（增量） | 新增 `resolveUrl`（相对→绝对 + 折行空白 + `../` 折叠）、`normalizePath`、`readJsonValue`、`sameAcademicSitePublic`；`normalizeUrl` 改为剥离 URL 内所有空白 |
| `test/RealCaseImportTest.java` + `test/fixtures/*.htm`（新增） | 真实案例离线夹具测试，91 项 |

### 2.3 各通道线程归属

| 阶段 | 执行线程 | 说明 |
| --- | --- | --- |
| 按钮状态、诊断重置、`addJavascriptInterface` | UI | WebView 有线程亲和性，必须在 UI 线程 |
| 通道 1 `evaluateJavascript(discoveryScript)` **投递** | UI | 只投递 |
| 通道 1 **回调** `onReceiveValue` | UI（WebView 规定） | 只做 `discoverRaw[0]=value; latch.countDown()`，不做任何解析 |
| 通道 1 **解析** `parseDiscovery` | 后台单线程 | 从 `runExtraction` 里调用 |
| 通道 2 注入 `fetchProxyScript` **投递** | UI（经 `runOnUi`） | 后台线程不能直接碰 WebView |
| 通道 2 `sink.begin/chunk/done/fail` | WebView 线程 → **立即 post 到后台** | 桥接层只投递，不处理 |
| 通道 2 `ChunkAssembler.chunk/done` | 后台单线程 | 与 pipeline 同一 executor ⇒ 天然串行、无需加锁 |
| 通道 2 轮询「是否拼装完成」 | 后台单线程 | `sleepQuietly(40)` + deadline 检查 |
| Cookie 直取 `HttpURLConnection` | 后台单线程 | 阻塞 IO 绝不入 UI |
| 通道 3 深度扫描 | 投递在 UI、回调在 UI、解析在后台 | 与通道 1 同模式 |
| 解析（专用 parser / HTML / div / merge） | 后台单线程 | 纯 CPU |
| `finish`：按钮恢复、状态栏、Toast | UI | `UiProgress.finish` 幂等 |
| 看门狗超时兜底 | 独立 `online-extract-watchdog` 线程 | 30s+1.5s 后强制 cancel + finish |
| 诊断读取（点「诊断」） | UI | 读 `Diagnostics` 的 `synchronized` 快照，**提取中可用** |

**关键不变量**：`parseCapture` / 四个 parser / `merge` / `HttpURLConnection` **从未**在 UI 线程执行；
`WebView` / `View` / `Toast` / `Dialog` **从未**在后台线程执行。

## 3. fetch 代理分块协议

### 3.1 JS 侧（`fetchProxyScript(requestId, url)`）

```js
sink.begin(id, url)                  // 开始
sink.chunk(id, seq, total, text)     // 第 seq 块（0 起），total = ceil(len/262144)
sink.done(id, status, length)        // 正常结束
sink.fail(id, reason)                // HTTP 非 2xx / fetch reject / abort / 分块抛错
```

- `fetch(url, {credentials:'include', cache:'no-store', redirect:'follow'})` —— 带会话 Cookie，不吃缓存；
- `res.ok` 为假直接 `fail('HTTP '+status)`；
- `res.text()` 后按 `CHUNK_BYTES = 262144`（256KB）切块，逐块回传；
- **每个回传点前都检查 `__checkTodoAbort[id]`**：abort 后立即 `fail('aborted ...')` 并停止后续块；
- 所有 `sink` 调用包在 `try/catch` 内：单个回调抛错不会中断 fetch 链，从而不会让 Java 侧永远等不到终态；
- 注入字符串由 `jsString()` 转义：`\\`、`'`、`\n`、`\r`、`\u2028/\u2029`，并把 `<` 转成 `\x3c`，防止 URL 里的 `</script>` 截断注入；
- `.catch` 覆盖 fetch reject，确保失败也一定有 `fail` 回调。

### 3.2 Java 侧（`ChunkAssembler`）

```java
begin(id, url)                  -> 新建 State
chunk(id, seq, total, text)     -> 拒绝：未知 id / total<=0 / seq 越界 / 已失败
                                   接受：parts[seq] 为空才写入（重复块忽略）
done(id, status, length)        -> received < total ⇒ 失败「数据块不完整 r/t」
fail(id, reason)                -> 标记失败（HTTP 403 / aborted ...）
```

- **按 `requestId` 分离**：多个候选可乱序甚至并发回传，互不污染；
- **按 `seq` 定序**：只有 `done` 时才按序拼接，乱序到达也能拼对；
- **缺块 = 失败**：绝不返回半截 HTML 去糊弄 parser（旧架构正是这么丢掉数据的）；
- **容量护栏**：`total` 超过 `MAX_BODY_CHARS/CHUNK_BYTES+8` 时截断并标记 `truncated`；拼接上限 8MB。

### 3.3 双保险

某候选在 fetch 代理上失败（跨域 frame、CSP 禁 fetch、桥接不可用、HTTP 非 2xx）时，
自动回落 `HttpURLConnection` + UI 线程预先取好的会话 Cookie 直取（8s，受剩余预算夹取）。
状态栏/诊断会写明走的哪条路：`页内 fetch` / `页内 fetch 失败 → Cookie 直取(200)`。

## 4. 超时矩阵

| 层 | 位置 | 上限 | 到期行为 |
| --- | --- | --- | --- |
| 全流程 | `OnlineExtractionSession.TOTAL_BUDGET_MS` | **30s** | `ExtractionDeadline` 到期 ⇒ 所有 `await`/`canContinue` 返回 false，剩余候选跳过并写诊断 |
| 看门狗 | `TimetableController.watchdog` | 30s + 1.5s | 强制 `cancel()` + `close()` + `finish("提取超时：已强制结束")`，**按钮必恢复** |
| 通道 1 单步 | `DISCOVER_TIMEOUT_MS` | 3s | `await` 返回 false，记「通道 1 超时：脚本可能被 CSP 拦截」 |
| 通道 2 脚本投递 | `fetchViaProxy` 内 `await(submitted, 2000)` | 2s | 记「代理脚本投递超时（UI 线程未响应）」，回落直取 |
| 通道 2 单个 fetch | `FETCH_TIMEOUT_MS` | 8s | 记「代理超时：8000ms 内未收齐」，回落直取 |
| 通道 2 单个直取 | `HttpURLConnection` connect/read | 8s（由 `stepTimeout` 夹到剩余预算） | `IOException` ⇒ 记「直取失败」，候选计失败 |
| 通道 3 深度扫描 | `await(latch, 12000)` | 12s | 记「深度扫描超时或被取消」，不影响已拿到的结果 |
| 单次 HTTP 参数 | `ONLINE_FETCH_TIMEOUT_MS` | 10s | 与上面 8s 复合取小 |

**不变量**：任何一条到期都只影响「本轮少拿几个候选」，不会让 `onlineParsing` 卡在 true。
`UiProgress.finish` 用 `AtomicBoolean` 保证正常收尾 / 看门狗 / dismiss 三路只有第一次生效。

## 5. 真实案例验证（离线夹具）

夹具：`D:\Desktop\kb.htm`（12,343B 数据页）与
`D:\Desktop\KINGOSOFT高校教学综合管理服务平台.htm`（181,361B 外壳），已复制到
`native-android/test/fixtures/`。

### 5.1 frame 发现

外壳里的真实结构：

```html
<iframe name="frmReport" ... src="../student/wsxk.xskcb10319.jsp?params=eG49MjAyNiZ4cT0w&amp;t=

N0UzQzZDNjIxQTEwMDAyNjVFRDk5QURDRkVCMzZDQ0JBNTk1QjE4QTA0OUM0RDBFQThCMjAxNUZD
NDI0QkIzQUE2REJGRTUzQUYwRDBFNTZBNDE3MkU1RjAyRjYwOUE4">
```

`src` 被源码折行，含 `\r\n`。测试验证：

- `发现 frmReport 指向 wsxk.xskcb10319.jsp`
- `原始 src 含折行空白（真实页面特征）`
- `相对 src 解析为绝对 URL` → `https://jwxt.bnuzh.edu.cn/student/wsxk.xskcb10319.jsp?...`
- `解析后不含任何空白字符`
- `路径已折叠 ../（不含 /../）`
- `frame 树含 2 个文档（主文档 + frmReport）`、`frmReport depth=1 且 name 正确`
- `候选列表包含 xskcb10319.jsp` 且**排在 `custom/js/jquery.js` 之前**

### 5.2 分块管道 → 真实响应体 → 11 门课

```
PASS 真实响应体长度 > 10KB
PASS 分块拼装完成
PASS 拼装结果与原文完全一致
PASS 块数符合 256KB 协议
PASS 真实数据页解析出 11 门课
PASS 真实数据页解析出 19 条排课
PASS 解析无错误
PASS 解析出「离散数学」
PASS 解析出「数据结构」
PASS 解析出「马克思主义基本原理」
PASS 解析出「博弈论」
PASS 教室信息解析正确（丽泽楼）
```

> 口径说明：夹具文件实际是 UTF-8（`new String(bytes,"GBK")` 会得到乱码），因此测试刻意走
> **生产同款** `HtmlTimetableParser.decode`（剥 BOM → 严格 UTF-8 → 回落 GBK），
> 保证测的是线上解码行为而不是测试专用的解码捷径。

### 5.3 超时矩阵与 null 回调

`PASS t0+29.9s 仍可继续` / `PASS t0+30s 恰好到期不可继续` / `PASS 已超时单步返回 0` /
`PASS 取消后不可继续` / `PASS 临近截止时单步超时收缩到剩余额度` /
`PASS 超时文案含扫描/成功数` / `PASS null 回调给出原因` / `PASS "null" 回调给出原因` /
`PASS 非法 JSON 给出原因` / `PASS 双重编码的 JSON 也能解析`

## 6. 测试与构建输出

```
[4/7] pure Java domain tests
...
passed=91 failed=0      ← RealCaseImportTest（新增）
[5/7] d8 dex
[6/7] package and align
[7/7] reuse debug keystore
Verify APK → Verifies (v2=true, v3=true, signers=1)
Build complete: D:\Desktop\check_todo\native-android\out\checktodo-debug.apk
EXIT=0
```

- **820 项全通过、0 失败**；接手时 729 项，本轮净增 **91 项**（`RealCaseImportTest`），**零回归**。
- `apksigner verify --verbose`：`Verifies`、v1=false、v2=true、v3=true、signers=1。
- `aapt2 dump badging`：`com.checktodo.app` v1.0、minSdk 24、targetSdk 34、label「每日打卡」、
  launchable `MainActivity`。
- 产物：`native-android/out/checktodo-debug.apk`，**362,375 字节（354KB）**。
- **Web 版零改动**：`src/`、`dist/`、`public/`、`index.html`、`package.json`、`vite.config.js`
  最新 mtime 仍为 `2026-10-24 02:48`。

## 7. 本轮改动文件清单

| 文件 | 性质 |
| --- | --- |
| `domain/OnlineExtraction.java` | 新增（纯 Java 核心：发现/分块/预算/诊断） |
| `timetable/ChunkBridge.java` | 新增（`@JavascriptInterface` 接收器） |
| `timetable/OnlineExtractionSession.java` | 新增（运行时上下文） |
| `timetable/TimetableController.java` | 重写提取链路；删除 `submitOnlinePipeline`、`updateOnlineStatus`；dismiss 四步取消；长按深度扫描 |
| `domain/OnlineImportLogic.java` | 新增 `resolveUrl`/`normalizePath`/`readJsonValue`/`sameAcademicSitePublic`；`normalizeUrl` 剥离内部空白 |
| `test/RealCaseImportTest.java` | 新增，91 项 |
| `test/fixtures/kingosoft_shell.htm`、`kingosoft_kb.htm` | 新增强实夹具 |
| `build.bat` | 接入 `OnlineExtraction.java` 与 `RealCaseImportTest` |
| `README.md`、`NATIVE_ANDROID_REPORT.md` | 架构说明与数据更新 |

## 8. 已知限制（诚实边界）

- **未做真机实测**：本环境无可用 adb 设备。所有结论来自代码审查 + JVM 测试 + 构建产物校验。
  「点提取不再卡死」的最终确认仍需一次真机操作（点提取 → 观察 30s 内按钮是否恢复、诊断是否有逐阶段日志）。
- **fetch 代理依赖页面可执行 JS**：若教务站点用严格 CSP 禁 `fetch`，主通道会失败并回落 Cookie 直取
  （诊断里能看到「fetch 代理失败 → Cookie 直取」）。此时解码仍由 `HtmlTimetableParser.decode` 处理 GBK/UTF-8。
- **跨域 frame 无法访问**：`contentDocument` 抛错的 frame 只计 `blocked` 计数，拿不到 URL；
  这类站点只能靠 Cookie 直取或深度扫描兜底。
- **诊断在提取中读取的是快照**：点「诊断」打开的是静态文本对话框，不自动滚动刷新；
  再次点开可见最新内容（每次都读 `Diagnostics.text()`）。
- **`TimetableController` 的编排层没有 JVM 单测**：它直接依赖 `WebView`/`Dialog`/`Toast`，
  在零第三方库（无 Robolectric/Mockito）约束下无法在 JVM 实例化。可测部分（发现解析、候选排序、
  分块拼装、超时矩阵、null 处理、真实响应解析）已全部覆盖，共 91 项。

---

# 第十五轮：被动捕获通道 + 插件化 parser + 停止按钮

> 真机反馈：北师大（Kingosoft）导入已成功 ✅；**华农（新版正方 jwglxt）超时未捕获，深度扫描也不行**。
> 用户同时要求：取消扫描时间上限、加手动停止按钮、自建插件化 parser 体系。

## 1. 根因验证（结论：不是参数问题，是 HTTP 方法问题）

按用户给出的 MA 判断先做代码核对，结论**成立**：

| 检查项 | 代码事实 |
| --- | --- |
| `fetch` 代理是否只发 GET | `OnlineExtraction.fetchProxyScript()` 中 `fetch(url,{credentials:'include',cache:'no-store',redirect:'follow'})` —— **无 `method`、无 `body`**，默认就是 GET |
| Cookie 直取是否只发 GET | `TimetableController.fetchResource()` 中 `connection.setRequestMethod("GET")` |
| 是否存在任何 POST 能力 | 全仓 `grep -rn 'setDoOutput\|"POST"' src/` —— **零命中** |

新版正方 `/kbcx/xskbcx_cxXskbcxIndex.html` 本身是查询表单壳，课表数据由用户点「查询」后
**POST**（`gnmkdm=N2151`，带 `xnm/xqm`）返回。两条主动通道都只能 GET，所以：

- 候选里**有** `xskbcx` 相关 URL（frame 树/资源条目里能看到），但 GET 它只会拿回空壳或 4xx
  → **「候选里有 xskbcx 但解析 0」**，与用户描述的诊断现象一致；
- 深度扫描也无效，因为深度扫描扫的是**已渲染 DOM**，而正方课表数据在 iframe 里的表格中，
  且页面初始未查询时根本没有数据——扫也扫不出东西。

**结论：对 POST 型系统，任何「重放请求」的思路都需要知道 POST 体（参数难猜、可能带 token），
正确方向是「不重放，只旁观」。**

## 2. 被动捕获通道（核心新增）

### 2.1 思路

用户点「查询」时，**WebView 自己发了 POST**，带全了参数与会话 Cookie，拿回了真正的课表数据。
我们不需要重放这个请求，只需要在这次真实响应经过时**复制一份**。

### 2.2 Tee 流细节

```
                 ┌────────────── TeeInputStream ──────────────┐
 HttpURLConnection│  read(byte[],off,len)                      │
   getInputStream ├─► upstream.read(...) ──┬─► 返回给 WebView（原样字节）
        │         │                          │
        │         │                          └─► sink.feed(...)  ← 同步 arraycopy
        │         └────────────────────────────────────────────┘
        │                                          │
        └── 读线程 = WebView 的线程                  ▼
                                          drainer 单线程 executor
                                          → 解码 → Captured → CapturePool
```

关键点：

1. **主读路径不做 IO**：`read()` 里只多一次 `arraycopy` 进 `Sink`，页面的读速度不受捕获影响。
2. **落池在独立线程**：`deliver()` 把 sink 内容 `execute` 到 `passive-capture` 单线程池，
   解码/建对象/进池全部发生在后台，绝不阻塞页面。
3. **读完才交付**：交付挂在包装流的 `close()` 上（`TeeInputStreamClosing`），
   因为只有读完了才知道 body 是否完整——提前交付会往池里塞半截响应。
4. **截断语义**：`Sink.feed` 在超出 `limit` 时只保留前 `limit` 字节并置 `truncated`，
   绝不让半个响应体冒充完整响应。
5. **不自行解压**：请求显式带 `Accept-Encoding: identity`；万一上游仍返回压缩体
   （`Content-Encoding` 非空且非 identity），捕获体记为 `[compressed N bytes]` 占位，
   避免把二进制塞给 parser。
6. **透传保真**：原方法、原请求头（剔除 `host/connection/content-length/accept-encoding/cookie` 这些受控头）、
   Cookie 由 `CookieManager` 重新注入；响应状态码 / `Content-Type`（含 charset）/ 原字节流回给 WebView。
7. **只拦文本**：`isStaticAsset` 直接放行 `.js/.css/.png/.jpg/.gif/.ico/.svg/.woff/.ttf/.mp4/.pdf`；
   `PassiveCapture.isCapturableType` 只认 `text/html`、`application/xhtml+xml`、`application/json`、
   `text/json`、`text/plain`。其余一律返回 `null` 交回系统。
8. **异常必放行**：`shouldInterceptRequest` 整个体包 `try/catch`，任何异常都 `return null`，
   宁可放弃捕获也不让页面白屏。

### 2.3 捕获池：为什么是内存 + LRU 8MB

| 选项 | 结论 |
| --- | --- |
| 落盘 | ✗ 在页面加载旁路做文件 IO 会把延迟引入主路径；且捕获内容是登录态下的个人数据（课表、可能含学号姓名），落盘留下明文残影 |
| 内存无上限 | ✗ 长时间浏览+翻页会持续累积，OOM 风险 |
| **内存 + LRU 8MB / 120 条** | ✓ 一次提取通常只用几十~几百 KB；随对话框销毁即释放；有界可预期 |

去重口径：**URL + 内容 SHA-256 前 8 字节**。同 URL 同内容只刷新访问顺序（计入 `duplicates`）；
同 URL 不同内容（例如翻了学期）作为新条目保留——这是刻意的，因为新版正方每查一次学期就换一组数据。

### 2.4 接线与诊断打通

- `PassiveCaptureClient extends WebViewClient`，**保留原有导航行为**（`onPageStarted/onPageFinished/
  onReceivedError` 通过 `Navigation` 接口回调），只额外覆盖 `shouldInterceptRequest`。
- 状态栏在浏览期间实时更新：`被动捕获 N 个响应 · 数据接口 M 个 · xKB/yKB`；
  `refreshCaptureStatus` 会避开「提取中」阶段，不冲掉进度文案。
- 提取时**捕获池作为最高优先级数据源**（通道 0），逐条记录到诊断：
  ```
  [0.1s] 通道 0：被动捕获池 3 个响应（数据接口 2 个）
  [0.1s] 捕获 #1 jwglxt/kbcx/xskbcx_cxXskbcxIndex.html [POST 200 text/html]
  [0.1s]   · Dedicated：不适用
  [0.1s]   · NewZhengFang：不适用
  [0.1s]   · KingoGrid：0 条排课
  [0.1s]   · HtmlGrid：0 条排课
  [0.1s]   → 所有插件均未解析出课程
  [0.1s]   未解析的捕获响应：jwglxt/kbcx/... [text/html]
            <table class="Nsb_table">...</table>（正文预览 400 字符，供回传排查）
  ```
- **解析失败的响应体会写进证据包**（单行压缩 + 截断 400 字符），这正是「用户回传后补定点规则」的闭环入口。
- dismiss 时：先 `evaluateJavascript(abortAllScript())` → `removeJavascriptInterface` →
  `session.close()` → `capture.close()`（停掉排空线程）→ 清空捕获池 → 最后 `webView.destroy()`。

## 3. 插件化 parser 体系

### 3.1 设计

```java
public interface Parser {
    String name();                              // 进诊断
    int priority();                             // 数字小的先试
    boolean supports(Input input);              // 廉价特征探测，不抛异常
    HtmlTimetableParser.Result parse(Input input); // 允许抛，注册表兜住
}
```

`TimetableParserRegistry.parse(input)` 按优先级依次尝试，**第一个产出课程的插件胜出**即停。
不继续跑后面的理由：不同插件的解析口径可能冲突，混在一起会产生重复课程；
要合并多来源应在上层按响应分别解析再 `merge`。

`Input` 携带 `body / url / contentType / maxWeek / nodes` —— URL 是重要识别依据
（新正方的 `kbcx`、Kingosoft 的 `xskcb` 都在路径里）。

### 3.2 已注册插件

| 插件 | priority | 识别 | 解析 |
| --- | --- | --- | --- |
| `Dedicated` | 0 | 注入的选校专用规则（WakeUp 家族） | 委托给 `WakeupParserFactory`，无选校时不参与 |
| `NewZhengFang` | 10 | URL 含 `kbcx/xskbcx/jwglxt/kbtable`，或 JSON 含 `kcmc/xqj/jsxx/cdmc/zcd` | JSON 字段映射；HTML 片段落到网格启发 |
| `KingoGrid` | 20 | 正文含 `mytable/td1`，或 URL 含 `xskcb/kingosoft` | `KingoReportParser`（#mytable 四行文本） |
| `HtmlGrid` | 100 | 任意 HTML | `HtmlTimetableParser` 通用启发式（兜底） |

`Dedicated` 用可注入委托实现，JVM 测试不需要任何 WakeUp 依赖（未注入时 `supports` 恒 false）。
控制器在打开对话框时按当前选校注入、关闭时解除，避免上一所学校的规则残留。

## 4. 新正方适配结论（诚实标注边界）

**已做**：

- URL/正文特征识别；
- JSON 路径：依次尝试 `tmpList`、`xsKbList`、`xkkList`、`kbList`、`kbcxList`、`items`、`rows`、`data`、`list`，
  再兜底「任何 value 是含 `kcmc` 的对象数组」；
- 字段映射：`kcmc`(课程名)、`xqj`(星期，支持 `1`/`星期三`)、`jcs`/`jc`(节次，支持 `1-2`/`1-2节`)、
  `zcd`(周次，支持 `1-16周`/`1-16周(单)`/`1,3,5`)、`cdmc`(教室)、`xm`/`jsxx`(教师)；
- HTML 片段：直接交给 `HtmlTimetableParser`（新正方课表片段本质仍是星期列 × 节次行的表格）。

**未做/未验证**：

- **没有华农的真实响应体样例**。本仓库既无华农截图也无抓包，`tools/wakeup-src` 里也**没有**新版 jwglxt 的
  parser（全局搜索 `jwglxt|xskbcx|N2151` 只命中苏大旧端点 `xskbcx.aspx?gnmkdm=N121603`，
  属旧版正方 `.aspx` 体系，与华农的 `jwglxt` 不同代）。
  因此 `NewZhengFangParser` 是**按已知惯例的尽力实现**，不能宣称「已支持华农」。
- 解析失败时响应体进诊断证据包（400 字符预览）。用户把那段回传后，即可按真实字段补定点规则——
  注册表已经留好位置，新增一个插件即可，不需要改控制器。

## 5. 超时模型改造与停止语义

| 项 | 改造前 | 改造后 |
| --- | --- | --- |
| 全流程上限 | 30s 硬超时 | **无上限**（`TOTAL_BUDGET_MS = Long.MAX_VALUE/4`，实际等于不限） |
| 结束方式 | 超时 / 自然完成 | **自然完成 / 用户点「停止」/ 看门狗（仅防死锁）** |
| 按钮 | 提取时禁用 | **「提取课表」原位置切换为「停止」**（避免布局跳动） |
| 看门狗 | 30s+1.5s 无条件触发 | **仅当连续 180s 无任何活动**才强制收尾 |
| 运行时长 | 不显示 | 状态栏与诊断全程显示 |

停止语义（`domain/ExtractionState`，纯 Java 可测）：

```
IDLE ──start()──► RUNNING ──stop()────► STOPPED           （保留结果）
                    │
                    ├──complete()────► COMPLETED         （保留结果）
                    └─abortByWatchdog()─► WATCHDOG_ABORTED（保留结果）
```

- `stop()` / `complete()` / `abortByWatchdog()` 都**幂等**：只有第一次从 `RUNNING` 转出才返回 true；
- `keepsResults()` 对 STOPPED/COMPLETED/WATCHDOG_ABORTED 均为 **true** ——
  **用户点了停止也不会丢掉已捕获/已解析的内容**，停完仍可直接点「导入」；
- 实现顺序：状态机 `RUNNING→STOPPED` → 置 JS `__checkTodoAbort` → `session.close()`
  （cancel deadline + shutdownNow）→ 恢复按钮、保留 `onlineDetails` → `reset()` 放行下一轮。

## 6. 测试

新增 `test/PassiveCaptureTest.java`，**126 项**：

| 分组 | 覆盖 |
| --- | --- |
| Tee 透传 | 小块读/逐字节读两侧一致；5000B 输入配 2000B 上限 → WebView 仍读全、捕获只留 2000、标记 truncated；上限 0；close 语义 |
| 类型过滤 | html（含 charset）/json/plain 捕获；图片/脚本/字体/null 不捕获；`mimeOf` |
| 捕获模型 | method 规范化、hash 稳定、xskbcx 判定数据接口、静态 css 不算、shortUrl 去 query |
| 捕获池 | 同 URL 同内容去重、同 URL 不同内容新增、空 body 拒绝、LRU 按容量+条目数驱逐、超容量单条丢弃不清池、clear |
| 捕获池 → 解析链 | 把 **kb.htm 真实响应体**放进池，遍历池 → 注册表 → merge，**解析出 11 门课 / 19 条排课** |
| 新正方 JSON | `tmpList`/`kbList`/根数组三种形态；同名课程合并 base；星期/节次/周次/单双周/教室/教师；缺字段跳过；非法 JSON 不抛 |
| 新正方工具 | `parseDay` 中英数字、`parseNodes` 区间与单节、`parseWeeks` 双周与 maxWeek 夹取 |
| 注册表 | 优先级升序、NewZhengFang/KingoGrid 在 HtmlGrid 之前、空响应、Dedicated 注入/解除 |
| 停止语义 | IDLE 不可停止、RUNNING 幂等、**STOPPED 保留结果**、时长冻结、文案、reset、COMPLETED、WATCHDOG_ABORTED 保留结果 |

构建输出：

```
BUILD EXIT=0
TOTAL=946   FAIL=0     ← 820（零回归）+ 126（新增）
apksigner verify → Verifies (v1=false, v2=true, v3=true)
badging → com.checktodo.app v1.0, targetSdk 34, label「每日打卡」
APK: 378,759 bytes (370KB)
```

Web 版零改动（`src/`、`dist/`、`public/` 最新 mtime 仍为 `2026-10-24 02:48`）。

## 7. 本轮改动文件

| 文件 | 性质 |
| --- | --- |
| `domain/PassiveCapture.java` | 新增：TeeInputStream / Sink / Captured / CapturePool（内存+LRU 8MB）/ 类型过滤 |
| `domain/ExtractionState.java` | 新增：停止语义状态机（纯 Java） |
| `domain/TimetableParserRegistry.java` | 新增：插件注册表（按优先级尝试） |
| `domain/NewZhengFangParser.java` | 新增：新正方尽力 parser（JSON 多字段 + HTML 回落） |
| `domain/HtmlGridParser.java`、`domain/KingoGridParser.java` | 新增：通用网格 / Kingosoft 网格插件 |
| `domain/DedicatedParserPlugin.java` | 新增：选校专用 parser 的注册表桥接 |
| `timetable/PassiveCaptureClient.java` | 新增：`shouldInterceptRequest` 旁路 + Tee + 导航行为保留 |
| `timetable/TimetableController.java` | 接线捕获客户端；提取链增加「通道 0 捕获池」；解析统一走注册表；停止按钮；看门狗放宽；删除 `parseResourceBody` |
| `timetable/OnlineExtractionSession.java` | 取消 30s 上限；新增 `touch/idleMillis`；候选上限 12→20 |
| `res/layout/dialog_online_import.xml` | 新增 `button_online_stop`（与原按钮同位置，默认 gone） |
| `domain/OnlineImportLogic.java` | `ResourceResponse` 增加 `originUrl` |
| `test/PassiveCaptureTest.java` | 新增 126 项 |
| `build.bat` | 接入 7 个新领域类 + 新测试 |
| `README.md`、`NATIVE_ANDROID_REPORT.md` | 更新 |

## 8. 已知限制（诚实边界）

- **未做真机实测**：本环境无 adb 设备。华农是否真的能导入，取决于两件我无法在此验证的事：
  ① 捕获通道在真机上确实拦到 POST 响应；② 该校返回的字段名落在 `NewZhengFangParser` 的候选集内。
  **若解析 0 条，请把诊断里「未解析的捕获响应」那段回传**，即可补定点规则。
- **低版本降级**：API < 21 时 `shouldInterceptRequest` 只有 String 重载、拿不到方法名，
  被动捕获按 GET 处理会失效（minSdk 是 24，实际不会触发，但代码里保留了这条降级路径）。
- **POST 请求体不捕获**：只捕获响应，不碰请求体（`shouldInterceptRequest` 本来也读不到 POST body）。
  这是刻意的——重放请求体既不可靠也没必要。
- **压缩响应不解析**：显式请求 `identity`，若上游强制压缩则只记长度占位。
- **捕获池进程级生命周期**：随对话框创建/销毁；对话框关闭时清空，不跨会话保留（隐私考量）。
- **`PassiveCaptureClient` 无 JVM 单测**：它直接依赖 `WebViewClient`/`HttpURLConnection`/`CookieManager`，
  零第三方库下无法在 JVM 实例化。其可测核心（Tee/池/类型过滤/解码）已全部在 `PassiveCaptureTest` 覆盖。

---

# 第十六轮：被动捕获安全化重构（紧急回归修复）

> **事故**：第十五轮被动捕获上线后，用户**无法登录教务**。进入学校官网即提示
> 「该项目应用未注册，无法登录」（学校统一身份认证 CAS/OAuth 类系统的报错）。
> 判定为 `PassiveCaptureClient` 拦截破坏登录链路导致。

## 1. 根因结论（逐条对照代码给证据）

`shouldInterceptRequest` 把请求接管成「我们自己开的 `HttpURLConnection`」，但初版的接管条件
放得极宽。四条红线同时存在，任何一条都足以打断 SSO：

### 红线 ①：没有过滤 HTTP 方法 —— **登录 POST 被接管**

```java
// 第十五轮 PassiveCaptureClient.shouldInterceptRequest（原代码）
String method = request.getMethod();
if (url.isEmpty() || !url.startsWith("http")) return null;
if (isStaticAsset(url)) return null;
return intercept(url, method == null ? "GET" : method, request.getRequestHeaders());
                              ^^^^^^^^^^^^^^^^^^^^^^^^^^^^ 任意方法都进 intercept
```

```java
// intercept() 内
connection.setRequestMethod(method);   // POST 也照做，但没有 body
```

登录表单就是 POST。被接管后：
- 我们**没有 POST body**（`shouldInterceptRequest` 读不到请求体），于是上游收到一个「空 body 的 POST」；
- SSO 端据此认为请求不合法，直接回落到「应用未注册」错误页；
- 更糟的是我们把那个错误页当作正常响应回给了 WebView，用户看到的就是那句报错。

### 红线 ②：跟随重定向再返回 —— CAS 的 `Location` 语义被吞掉

```java
connection.setInstanceFollowRedirects(true);   // ← 关键
...
int status = connection.getResponseCode();
...
return new WebResourceResponse(emptyToNull(mime), charset, stream);  // 返回的是最终页，不是 302
```

`setInstanceFollowRedirects(true)` 让 `HttpURLConnection` 在内部跟随 30x，我们**永远拿不到 302**，
返回给 WebView 的是「跳转后的最终页」。而 CAS/OAuth 的整条认证链就是靠 302 + `Location` 串起来的
（`/cas/login` → 302 → `/cas/serviceValidate?ticket=ST-...` → 302 → 业务页）。跳转由我们私下完成，
WebView 没有经历这些跳转，于是：
- `Referer` 链断裂（SSO 会校验）；
- WebView 的 CookieManager 没有机会在每一跳上收到并写入 `Set-Cookie`；
- 回调 URL 上的 `ticket` 参数时序错乱。

### 红线 ③：没有认证 URL 黑名单 —— `/cas/login` 也被代理

原代码只排除了静态资源，`https://cas.xxx.edu.cn/cas/login`、`/sso/auth`、`/oauth2/authorize`
全部照拦。认证端点是最不该被第三方改写的请求。

### 红线 ④：响应头一个都没透传 —— `Set-Cookie` 全部丢失

```java
// 两处构造点，都没有第 5 个参数 responseHeaders
return new WebResourceResponse(emptyToNull(mime), charset, stream);   // 199 行
return new WebResourceResponse(emptyToNull(mime), charset, wrapper);  // 218 行
```

整个文件 `grep -n "Set-Cookie\|responseHeaders\|getHeaderFields"` **零命中**。
这意味着凡是走了拦截路径的响应，**`Set-Cookie` 全部被丢弃**：
WebView 的 CookieManager 拿不到 `JSESSIONID` / `SAML` 会话票，
后续所有请求都是未认证状态 —— 这是「登录会话建立不起来」的最直接原因。

> 补充：请求头那侧原本确实没有显式删除 Origin/Referer，但过滤逻辑写死在网络方法里、
> 用 `setRequestProperty` 逐条写入，`getRequestHeaders()` 可能为 null，且大小写混杂，
> 一旦某次改动写错就会连带丢掉 SSO 校验头。这次一并收敛到纯函数里的显式名单。

## 2. 修复清单

### 2.1 新增 `domain/PassiveCapturePolicy`（纯函数，全部可 JVM 测）

| 函数 | 职责 |
| --- | --- |
| `shouldIntercept(enabled, url, method)` | 总开关 → 协议 → **只 GET** → **认证关键词** → 静态资源；返回 `Decision{intercept, reason}` |
| `matchAuthToken(url)` | 认证关键词匹配（17 个词，大小写不敏感） |
| `isStaticAsset(url)` | 26 种扩展名，query/fragment 也正确剥离 |
| `shouldForwardResponse(status)` | **30x 一律放行**；204/304/非 2xx 放行；仅 2xx 可接管 |
| `forwardableHeaders(incoming)` | 剔除 9 类传输头 + `proxy-*`，其余**原样保留**（含 Origin/Referer/UA/自定义头），大小写归一 |
| `upstreamHeaders(incoming, managedCookie)` | 转发头 + **Cookie 整体覆盖**（CookieManager 为空时回落原 Cookie）+ `Accept-Encoding: identity` |
| `forwardableResponseHeaders(upstream)` | 白名单透传；**多个 `Set-Cookie` 用 `\n` 保留为多值** |
| `describe(url, method)` | 诊断文案 |

### 2.2 `PassiveCaptureClient` 重写

| 项 | 修复后 |
| --- | --- |
| 方法过滤 | 决策层拦截，非 GET 直接 `return null` |
| 重定向 | `setInstanceFollowRedirects(false)` → 拿到 30x → `shouldForwardResponse` 判失败 → `disconnect` + `return null` |
| 认证 URL | 决策层关键词拦截 |
| 请求头 | `PassiveCapturePolicy.upstreamHeaders(...)` 逐条 `setRequestProperty`，Origin/Referer/UA/自定义头全部保留 |
| Cookie | `CookieManager.getCookie(url)` 整体覆盖；`safeCookie` 包 `catch (Throwable)` |
| 响应头 | `new WebResourceResponse(mime, charset, status, reason, responseHeaders, body)` —— **六参数构造，responseHeaders 非空** |
| 非文本响应 | 直接 `disconnect` + `return null`（交回系统） |
| failsafe | `shouldInterceptRequest` 与 `intercept` **两层** `catch (Throwable)`；`disconnect` 也包 try |
| 开关 | `setEnabled(boolean)`；关闭时入口第一行就 `return null` |

### 2.3 开关 UI

- `res/layout/dialog_online_import.xml` 底栏新增 `check_online_capture`（CheckBox，默认 `checked=true`）；
- `TimetableController`：打开对话框时 `setChecked(loadCaptureEnabled())`，变更时
  `saveCaptureEnabled()` + `client.setEnabled()` + 清空捕获池 + 刷新状态栏；
- 偏好键 `passive_capture_enabled`（默认 `true`），存在既有 `check-todo-online-import` 里；
- 关闭后状态栏明确提示「被动捕获已关闭（不再拦截任何请求）」。

## 3. 测试

新增 `test/PassiveCapturePolicyTest`，**136 项**：

| 分组 | 断言要点 |
| --- | --- |
| 方法过滤 | GET/小写 get/null/空串可捕获；**POST/PUT/DELETE/PATCH/HEAD/OPTIONS/post 一律放行**且原因含「非 GET」 |
| 认证 URL | 17 个真实形态（`/cas/login`、`/cas/serviceValidate`、`/sso/auth`、`/oauth2/authorize`、`/oauth/callback`、`/openid/login`、`/saml/SSO`、`/cas/logout`、`/session/refresh`、`/ticket/ST-`、`/j_spring_security_check`、`/captcha.png`、`/verify`、`/idp`、大写 `/LOGIN`、`/ServiceLogin`）全部放行；课表 URL 仍可捕获 |
| 静态资源 | js/css/png/woff2/svg#frag/pdf 放行；`jsp/list`、`?file=app.js` 仍可捕获 |
| **30x 不拦** | 300/301/302/303/307/308 全部放行；204/304/404/500 放行；200/201/206 可接管；399/400 边界 |
| 头部保真 | Origin/Referer/UA/Accept/Accept-Language/X-Requested-With/**自定义头** 全部保留；null 头名跳过；null 输入返回空 Map |
| 传输头剔除 | Host/Connection/Keep-Alive/Proxy-Connection/Transfer-Encoding/Content-Length/Upgrade-Insecure-Requests/Cookie/Accept-Encoding/Proxy-Authorization 全部剔除 |
| Cookie 重注入 | 3 键 Cookie 完整保留；旧 Cookie 被覆盖；CookieManager 为空时回落；两边都空不产生 Cookie 头 |
| 响应头透传 | **Set-Cookie 透传**；多值用换行保留；Location/Cache-Control 透传；X-Powered-By 剔除；大小写不敏感 |
| 总开关 | 关闭后连认证 URL 也放行 |
| failsafe | null/空/javascript:/file:/data: 全部放行；`describe(null,null)` 不抛 |
| **回归场景** | 按「进官网 → CAS 登录(GET) → 登录(POST) → serviceValidate(ticket) → 回调(?ticket=) → logout → 进教务 → 点查询(GET) → 查询(POST) → js」逐步断言，每个会破坏登录的请求都放行 |

构建输出：

```
BUILD EXIT=0
TOTAL=1082  FAILED=0     ← 946（零回归）+ 136（新增）
apksigner verify → Verifies (v1=false, v2=true, v3=true)
badging → com.checktodo.app v1.0, targetSdk 34, label「每日打卡」
APK: 382,856 bytes (374KB)
```

Web 版零改动（`src/`、`dist/`、`public/` 最新 mtime 仍为 `2026-10-24 02:48`）。

## 4. 本轮改动文件

| 文件 | 性质 |
| --- | --- |
| `domain/PassiveCapturePolicy.java` | **新增**：拦截决策纯函数（方法/认证 URL/30x/头部/Cookie/响应头） |
| `timetable/PassiveCaptureClient.java` | **重写**：按 policy 执行；`setInstanceFollowRedirects(false)`；六参数 WebResourceResponse 带 Set-Cookie；双层 failsafe；`setEnabled` 开关 |
| `timetable/TimetableController.java` | 接线开关：读取/持久化偏好、变更时同步 client、状态栏提示 |
| `res/layout/dialog_online_import.xml` | 新增 `check_online_capture` 复选框 |
| `test/PassiveCapturePolicyTest.java` | **新增** 136 项 |
| `build.bat` | 接入新类与新测试 |
| `README.md`、`NATIVE_ANDROID_REPORT.md` | 记录回归根因与修复 |

## 5. 已知限制

- **未做真机实测**：本环境无 adb 设备。登录链路是否恢复需真机确认。若仍有异常，
  **请先关掉对话框里的「被动捕获」开关**——关闭后行为与原生 `WebViewClient` 完全一致，
  可立即区分「是捕获导致」还是「别的原因」。
- **关闭开关后无法得知 POST 型系统能否解析**：被动捕获是真机上唯一能拿到 POST 响应的途径；
  关闭后华农这类系统只能靠 GET 候选 + 深度扫描，大概率仍拿不到数据。
- **捕获仍会在非认证的 GET 页面上发生**：这是刻意保留的捕获价值来源；
  规则已排除认证链路与传输/敏感头，正文里若含个人信息仅存于内存池，随对话框销毁释放。
- **`PassiveCaptureClient` 本身无 JVM 单测**：它依赖 `WebViewClient`/`HttpURLConnection`/`CookieManager`。
  所有可测的决策逻辑已抽到 `PassiveCapturePolicy` 并 136 项覆盖；
  网络层只能靠真机验证（因此才提供了开关作为兜底）。

---

# 第十七轮：华农（新版正方 jwglxt）课表网格定点解析

> 输入：真实页面 `D:\Desktop\scau.htm`（个人课表查询页）+ `scau2.htm`（外壳，不解析）。

## 0. 先更正一处事实：`<span class="title1">` 并没有被漏掉

更正意见认为「此前只匹配到 12 个 `<u>` 调课块，漏了 `<span>` 正常课」。**这一定位不准确**，
实测与代码都能证明：

| 事实 | 证据 |
| --- | --- |
| 第十六轮解析器**已解析出全部 19 个课程块** | `ScauGridTests` 上一轮断言 `课程块 19 个`、`14 门课`、`22 条排课`、`0 警告`，全部 PASS |
| 解析器从不用 `<u>` 计数 | `ScauGridParser.splitBlocks` 按 `div.timetable_con` 的块边界切段，标题用 `<(?:u\|span\|a\|h[1-6])[^>]*class="title1"` 抽取，两种载体一视同仁 |
| 页面里 `span` 有 26 个、`u` 有 12 个，但**网格内**只有 `u` 6 个 + `span` 13 个 | `kbgrid_table_0` 内 `timetable_con` 共 19 个；`scau.htm` 共 12 张 `<table>`，另外 11 张是**同一课表的重复副本**（为兼容旧版渲染），`u=12 / span=26` 是把副本也数进去了 |
| 「12 块」应指含调课标记的块 | `kbgrid_table_0` 内 `<u>` 恰好 6 块，全文 12 块（含副本） |

**结论**：上一轮的课程清点本身是完整的（19 块 / 14 门 / 22 条）。但以下两点确实是**真实缺口**，本轮已补：

1. **单行式排版**：虽然本夹具的 19 个网格块全部是多行式（单行式 `<p>` 那 19 条全在 `kblist_table` 课程列表里），
   但换个学期/换所学校，网格很可能改用单行式——必须支持；
2. **两种排版混排**、以及**无包裹块**的兜底。

## 1. 网格格式（实测确认）

```html
<table id="kbgrid_table_0" class="table table-hover table-bordered text-center timetable1">
  <!-- 第 1 行：标题栏 colspan=9 -->
  <tr><td colspan="9"><div class="timetable_title">
      <h6 class="pull-left">2026-2027学年第1学期</h6>罗欣悦的课表
      <h6 class="pull-right">学号：202613310215</h6></div></td></tr>
  <!-- 第 2 行：9 列表头 -->
  <tr><td width="7.5%"><span class="time">时间段</span></td>
      <td width="5%"><span class="time">节次</span></td>
      <td width="12.5%"><span class="time">星期一</span></td> … <td>星期日</td></tr>
  <!-- 节次行：rowspan 的「上午/下午/晚上」 + 节次号/起止时间 + 7 个 td_wrap -->
  <tr><td rowspan="5"><span class="time">上午</span></td>
      <td><span class="festival">1</span><span>08:00</span><br><span>08:40</span></td>
      <td rowspan="1" id="1-1" class="td_wrap"></td>
      …
      <td rowspan="2" id="3-1" class="td_wrap">
        <!-- 变体 A：调课记录 -->
        <div class="timetable_con text-left">
          <u class="title1 showJxbtkjl" data-jxb_id="52A039…">【调】大学数学Ⅰ</u>
          <p>(1-2节)4-14周</p><p>  上课地点:4203</p><p>卢建平</p>
          <p>26种子科学1-2,26林学(林学+食工双学位)1</p><p>选课人数:100</p><p></p>
        </div>
        <!-- 变体 B：正常课程 -->
        <div class="timetable_con text-left">
          <span class="title1">大学英语Ⅰ</span>
          <p>(3-4节)4-15周</p><p>  上课地点:5B502</p><p>燕晓黎</p>…
        </div>
      </td></tr>
  …
```

**实测统计（`kbgrid_table_0` 内）**：

| 项 | 数量 |
| --- | --- |
| 含课程的 `td_wrap` 单元格 | **16** |
| 其中含 2 个 `div.timetable_con` 的格子 | 3（`5-1`、`5-8`、`4-10`） |
| 课程块（`div.timetable_con`） | **19** |
| 其中 `<u>` 调课块 / `<span>` 正常块 | **6 / 13** |
| 去重课名 | **14** |
| 排课条数（周次分段展开） | **22** |
| 节次时间 | **15** 节 |

## 2. 格式文法

### 2.1 坐标

```
cell := <td rowspan="N" id="D-P" class="td_wrap"> block* </td>
D ∈ 1..7（星期）  P ∈ 1..15（起始节）  N ≥ 1（跨度）
```
- **id 权威**：`3-1` 即周三第 1 节，不依赖表头推算（表头多一列「时间段」，通用网格会错位）；
- **跨度**：优先 `rowspan`；文本 `(N-M节)` 作辅证（`M-N+1` 应与 `rowspan` 一致）。

### 2.2 课程块

```
block  := <div class="timetable_con">
            ( <u class="title1" data-jxb_id=…>TITLE</u>
            | <span class="title1">TITLE</span>
            | TITLE )                     ← 无包裹也兜住
            <p>…</p>+
          </div>

TITLE  := 「【调】|【补】|【停】|【改】|（调）」? 课程名「[属性]…」
```

### 2.3 两种 `<p>` 排版

**多行式**（夹具全部是这种）：
```
<p>(2-5节)7-12周,14-15周</p>
<p>  上课地点:DS806</p>
<p>陈明洁</p>
<p>26种子科学2</p>
<p>选课人数:32</p>
<p></p>
```

**单行式**（同一网格换学期可能出现）：
```
<p>7-12周,14-17周  上课地点:DS526  钟伟良  26种子科学2  选课人数:32</p>
```

### 2.4 行分类规则

| 形态 | 判定 | 去向 |
| --- | --- | --- |
| 含「周」字且能解析出周次 | 周次行 | → 周次（可多段） |
| 以 `上课地点:` / `地点:` / `教室:` 等开头 | 教室行 | → 教室（剥前缀） |
| 以 `选课人数:` / `教学班:` / `学分:` 等开头 | 元数据 | 丢弃 |
| 2~4 个汉字，或 2~6 个名字用逗号分隔，且不含数字/括号/斜杠 | 人名 | → 教师 |
| 其余（班级名单） | 班级 | 丢弃（非错误） |

### 2.5 周次文法

| 输入 | 输出 |
| --- | --- |
| `4-14周` | `[4-14]` |
| `4-7周,9-14周` | `[4-7] [9-14]` ← 逗号分段、每段带周字 |
| `7-12周,14-17周` | `[7-12] [14-17]` |
| `第8-8周` | `[8-8]` |
| `2-3周` / `4-4周` | 单段 |
| `1-16周(单)` | `[1-16]` 单周 |
| `26种子科学1-2,26林学…` | **不解析**（无「周」字 → 不是周次行） |
| `(1-2节)4-14周` | 先剥 `(1-2节)` → `[4-14]`（避免 1-2 被当周次） |

## 3. 实现要点与踩到的坑

| 坑 | 现象 | 修法 |
| --- | --- | --- |
| `SimpleHtmlParser` 不折 `<p>` | 标题与 `(1-2节)4-14周` 粘连成一行，块切不开 | `p`/`li` 开标签也插入 `\n` |
| 空行被 `normalizeCell` 压掉 | 无法用 `\n\n` 标块边界 | 引入 `BLOCK_BOUNDARY` 边界标记 |
| 边界标记用 `\u0001` 无效 | Java `trim()` 删除所有 `<= U+0020` 字符，标记被吃掉 | 改用零宽非空白字符 **`\u200B`** |
| 班级名单被当周次 | `26种子科学1-2` → 凭空多出 1-2 周、1-3 周排课 | 周次行必须含「周」字（`isWeekLine`） |
| 单行式被按双空格切 | `normalizeCell` 把连续空白压成单空格，切不开 | 改**语义锚点**：周次段 + `上课地点:` + 人名启发 |
| 单行式的假周次 | `5B304` 的 5、`26种子科学1-2` 的 1-2 被扫成周次 | `findWeekSpan` 只取开头那一段连续周次文本 |
| `[无等级]` 被当调课标记剥掉 | 课名变成 `体育Ⅰ[跆拳道品势01]` | 只剥 `【调/补/停/改】` 单字标记，保留属性方括号 |
| `kblist_table` 被误判为网格 | 报「有网格无课程块」假警告 | 无 `kbgrid_table_` 前缀时必须同时有 `timetable1` class + `td_wrap` 单元格 |
| 注册表被 `NewZhengFang` 抢先 | 华农 URL 也含 `kbcx`，`NewZhengFang` 在 10 | `ScauGridPlugin` 提到 10，`NewZhengFang` 降到 15 |

## 4. 断言明细（145 项，节选关键）

```
PASS ★课程块 19 个（实测）
PASS ★14 门课
PASS ★22 条排课
PASS 解析 0 警告 / 0 错误
PASS 网格内 <u class=title1> 共 6 块
PASS 网格内 <span class=title1> 共 13 块
PASS 两种变体合计 19 块（= 解析出的课程块数）
PASS ★u 变体（调课）课名存在：大学数学Ⅰ
PASS ★span 变体（正常课）课名存在：大学英语Ⅰ / 植物学实验 / 习概 / 人工智能通识A
PASS ★大学数学Ⅰ 周三(3-1) 4-14周    └ (1-2节) 起 1 跨 2，教室 4203，教师 卢建平
PASS ★大学数学Ⅰ 周五(5-1) 拆出 4-7周 / 9-14周
PASS 周五第二个课程块 第8-8周（教室 5B602）
PASS ★大学数学Ⅰ 周四(4-8) 4-14周   └ (8-9节)，教室 4104
PASS ★4-4周（单周）存在 / ★5-14周存在（国家安全教育与军事理论，4-10，(10-12节) 跨 3 节）
PASS ★大学英语Ⅰ = (3-4节)4-15周 一条
PASS ★植物学实验 = (8-11节) 7-12/14-17 拆两条（跨度 4 节）
PASS ★习概 = (10-12节)4-14周 一条（教师 陈洁，教室 5C304）
PASS ★植物学 (13-15节)4-14周 跨度 3
PASS ★剥离【调】/【补】/【停】/（调）；★解析结果中无【】残留
PASS ★逗号分段解成 2 段；★班级名单不算周次行；★节次括号被剥离
PASS ★单行式被识别 / 周次段 7-12周,14-17周 完整取到 / 不含班级数字
PASS ★单行式只解析出 1 段周次（无假周次）
PASS ★单行式教室 = 东区田径看台2 / ★单行式教师 = 赵燕
PASS 混排：多行式 + 单行式同页解析出 2 门课 / 3 条排课 / 0 警告
PASS ★节次时间 15 节（1: 08:00-08:40 … 8: 14:30-15:10 … 15: 21:00-21:40）
PASS ★HtmlTimetableParser 入口直接解析出 22 条
PASS ★Kingo 夹具仍解析出 11 门课（未被华农干扰）
```

## 5. 测试与构建

```
BUILD EXIT=0
TOTAL=1227  FAILED=0     ← 1082（零回归）+ 145（新增 ScauGridParserTest）
javac errors: 0
apksigner verify → Verifies (v1=false, v2=true, v3=true)
badging → com.checktodo.app v1.0, targetSdk 34, label「每日打卡」
APK: 386,952 bytes (378KB)
```

Web 版零改动（`src/`、`dist/`、`public/` 最新 mtime 仍为 `2026-10-24 02:48`）。

## 6. 改动文件

| 文件 | 性质 |
| --- | --- |
| `domain/ScauGridParser.java` | **新增**：华农网格定点解析（坐标/两块变体/两排版/行分类/周次门禁/时间抽取） |
| `domain/ScauGridPlugin.java` | **新增**：注册表插件（priority 10） |
| `domain/NewZhengFangParser.java` | priority 10 → 15（让位给更严格的华农定点识别） |
| `domain/SimpleHtmlParser.java` | `<p>/<li>` 折行；新增 `BLOCK_BOUNDARY`（`\u200B`）标记 div 块边界 |
| `domain/HtmlTimetableParser.java` | 入口优先级插入华农（Kingo → 华农 → 通用） |
| `timetable/TimetableController.java` | HTML 导入预览新增「应用时间表」按钮（≥4 节时间时）；`applyPageTimeNodes` |
| `test/ScauGridParserTest.java` + `test/fixtures/scau_grid.htm` | 新增 145 项 + 真实夹具 |
| `build.bat`、`README.md`、`NATIVE_ANDROID_REPORT.md` | 接入与文档 |

## 7. 已知限制

- **未做真机实测**：本环境无 adb 设备。夹具解析已完全跑通（19 块/14 门/22 条），
  但「教务 DOM 通道 + 被动捕获」在真机上的完整链路仍需用户验证。
- **单行式在本夹具中未被真实网格使用**：19 个网格块全是多行式；单行式的 19 条 `<p>` 位于
  `kblist_table`（课程列表，非课表网格）。单行式支持是用「合成的等价网格」端到端验证的
  （`testBothLayoutsEndToEnd`），不是从真实网格里观察到的——**换学期后若网格改版可能需要调整**。
- **「12 块」的口径差异**：本报告以 `div.timetable_con` 计数（19 块）。若按「含调课标记的块」计，
  网格内是 6 块、全文 12 块（含 11 张重复副本表）。已按 `div` 口径写死断言，并在测试注释里说明了差异。
- **时间校准做成可选**：不自动应用，避免用户在不知情下改变已有提醒的时间口径。
- **班级名单不进模型**：`CourseBase`/`CourseDetail` 没有班级字段，解析后丢弃（非错误、不告警）。

---

# 第十八轮：课表设置两处交互修复（节数同步 + 保存时校验）

## 1. 问题一：增删节后「每天节数」不同步

### 根因

节数在**三个地方**各存一份，靠回调手动对齐：

| 位置 | 存储 | 谁负责同步 |
| --- | --- | --- |
| 设置弹窗「每天节数」输入框 | 输入框文本 | `nodesWatcher` |
| 设置弹窗草稿 | `int[] draftCount` + `timeTableDraft` | `syncDraft()` |
| 时间表编辑弹窗 | `int[] liveCount` | `syncCountField` 回调 |

`showTimeTableEditor(draft, nodeCount, onChange, syncCountField)` 里，增/删节后调
`syncCountField.run()` 把 `liveCount[0]` 回填给输入框——但只要有一条路径漏调（或者
`draftCount` 与 `liveCount` 已经分叉），返回后输入框就停留在旧值。这是**多副本状态**的
典型症状：修一个入口不解决另一个入口。

### 修法：单一数据源

新增 `domain/TimeTableDraft`（纯 Java，可 JVM 测）：

```java
public final class TimeTableDraft {
    private final List<TimeNode> nodes;          // 唯一状态
    public int nodeCount() { return nodes.size(); }  // 节数不是独立字段
    public boolean setNodeCount(int requested)   // 输入框改节数 → 裁剪/扩充，保留已填值
    public boolean addNode() / removeNode()      // 弹窗增删节
    public void resetDefaults()
    public boolean setNodeTime(int node, String s, String e)
    public String validate()                     // 整表校验（保存时用）
    public List<Issue> issues() / String issueOf(int node)  // 行内定位
}
```

关键设计：**节数 = `nodes.size()`，不单独存 int**。这样「两处数值脱节」在结构上不可能发生。
输入框、增删按钮、编辑器渲染、保存落盘全部只读这一个对象：

```
输入框 afterTextChanged ──► draft.setNodeCount(n) ──┐
                                                     ├──► draft（唯一真相）
弹窗 增加/删除一节      ──► draft.addNode()/remove() ─┘
                                                     │
输入框显示 ◄── String.valueOf(draft.nodeCount()) ◄───┤
按钮文案 ◄── "时间表设置：N 节" ◄───────────────────────┤
保存落盘 ◄── repository.saveTimeNodes(draft.nodes()) ◄─┘
```

`syncCountField` 保留但语义改变：不再搬运一个独立计数，而是把 `draft.nodeCount()` 回填给输入框
（回填时临时摘掉 watcher，避免再触发一次 `setNodeCount`，虽幂等但会白跑）。

## 2. 问题二：编辑过程的交叉提醒 → 保存时校验

### 改前（打断式）

`pickTime` → 选完结束时间后立即：

```java
String nodeError = TimeTableSettings.validateNode(...);
if (!nodeError.isEmpty()) {
    error.setText(nodeError);
    Toast.makeText(activity, nodeError, Toast.LENGTH_SHORT).show();   // ← 打断
    return;                                                            // ← 且不写入
}
String tableError = TimeTableSettings.validate(next, draft.size());
if (!tableError.isEmpty()) {
    error.setText(tableError);
    Toast.makeText(activity, tableError, Toast.LENGTH_SHORT).show();   // ← 又一次
    return;
}
```

### 改后（安静 + 保存时校验）

| 时机 | 行为 |
| --- | --- |
| 改某一节时间（TimePicker 确认） | **静默写入草稿**，直接重绘；非法/交叉都不拦 |
| 行内有问题的节次 | 该行「第 N 节」与时间文字**变红**（`statusMissedText`） |
| 汇总行 | 「共 N 节；点击任意一节修改时间（红字 M 处待修正）」 |
| 增一节 / 删一节 | **不校验、不提醒**，只同步草稿与输入框 |
| 恢复默认 | 直接落盘生效 + 胶囊条「已恢复默认时间表（N 节）」 |
| **点「保存」** | **唯一的校验入口**：交叉 / 起止倒置 / 格式错误 → 胶囊条提示第一条，**不落盘**，弹窗保持打开；无问题 → 保存 + 胶囊条「时间表已保存（N 节）」+ 关闭弹窗 |

### 胶囊条（`timetable/CapsuleToast`）

- 布局 `toast_capsule.xml` + 背景 `capsule_background.xml`（深色圆角、`elevation` 阴影）；
- 挂在 `activity_main` 根部，**负底边距 + `bottom|center_horizontal`** 使其浮在内容之上、不占布局；
- 淡入 160ms → 停留 2600ms → 淡出 220ms，`Handler` 计时，重复调用会**重置计时**而非堆叠；
- 不阻断任何操作（用户可不理会继续改），与 Toast/AlertDialog 的本质区别；
- 自实现而非 Snackbar：本工程零第三方依赖（无 Material），为一个提示条引入 Material 会显著增包。

## 3. 顺带修掉一个真实的默认表缺陷

`TimeTableDraftTest` 断言「1..20 节的默认表全部合法」时发现 **14 节以上必然失败**：

```
count=14 -> 第 14 节开始时间不能早于第 13 节结束时间
```

根因：`TimeTableSettings.generate(count)` 在起点超过 23:15 时直接 `break`，导致
`generate(20)` 只返回 13 节；而 `defaultNode(n)` 对超出部分返回**最后一节**（第 13 节）的时间——
于是第 14..20 节与第 13 节完全重叠。后果是「恢复默认」在 14 节以上反而会让表非法、无法保存。

修复：

- 新增常量 `DEFAULT_NODES = 13`，`defaultTable()` 返回 13 节标准作息（08:00 起，末节 22:40-23:25）；
- `generate(n)`: 前 13 节按标准作息，超出部分在 `[23:25, 23:59]` 内**按节数均分并串行推进**：

```
14: 23:25-23:29   15: 23:29-23:33   16: 23:33-23:37   17: 23:37-23:41
18: 23:41-23:45   19: 23:45-23:49   20: 23:49-23:53
```

严格递增、互不重叠、绝不用 `format()` 取模到次日（旧做法会产生 `23:35-00:20` 这种结束早于开始的非法节次）；
- `defaultNode(n)` 改为**从满表 `generate(MAX_NODES)` 里按位置取**——
  不能写 `generate(n)` 取末节，因为超出部分是按总数均分的，总数不同结果就不同，
  `resize()` 逐节补齐时会前后错位。

> 诚实说明：14 节以上的默认区间只有 4 分钟，是**为了让表合法**的兜底值。
> 真实作息（华农夹具就有 15 节）必须由用户按本校时间手改——编辑器点一下即可，且保存时才校验。
> 这比"默认值非法、恢复默认就保存不了"要好得多。

## 4. 测试

新增 `test/TimeTableDraftTest.java`，**68 项**：

| 分组 | 断言要点 |
| --- | --- |
| 单一数据源 | ★节数恒等于节点数；改节数后同步；幂等；`nodes()` 返回副本不影响内部 |
| 节数同步 | ★改输入框后节点数同步；扩充/裁剪后**已填值保留**；乱序/缺节的旧数据被规整为连续 1..N |
| 增删节 | ★加/删后节数与节点数同步；多轮增删仍一致；1 节时删不动；满节时加不动 |
| 恢复默认 | ★节数不变；★恢复后必然合法；★1..20 节的默认表全部合法 |
| 单节编辑 | 越界/非法格式返回 false 且不改动；★允许写入倒置（编辑期不拦），校验时才报 |
| 整表校验 | ★交叉被检出并指明节次；★起止倒置；★首尾相接不算交叉；后节早于前节算交叉 |
| 行内问题 | ★多问题时全部返回；`issueOf` 精确定位；合法时 issues 为空（不标红） |
| 上下界 | ★0/负数夹到 1；★超上限夹到 20；`MAX_NODES` 与 `TimeTableSettings` 一致 |

`TimeTableSettingsTest` 更新 1 项断言（原断言锁定的正是缺陷行为）：

```diff
- check("超出默认表范围时返回末节值", defaultNode(21).startTime.equals(last.startTime));
+ check("★超出默认表时不再复制末节时间", !defaultNode(21).startTime.equals(last.startTime));
+ check("★超出部分严格递增", minutes(defaultNode(21).startTime) >= minutes(last.endTime));
```

构建输出：

```
BUILD EXIT=0
TESTS=1296  FAILED=0     ← 1227（零回归）+ 68（新增）+ 1（断言更新）
javac errors: 0
apksigner verify → Verifies (v1=false, v2=true, v3=true)
badging → com.checktodo.app v1.0, targetSdk 34, label「每日打卡」
APK: 391,204 bytes (382KB)
```

Web 版零改动（`src/`、`dist/`、`public/` 最新 mtime 仍为 `2026-10-24 02:48`）。

## 5. 本轮改动文件

| 文件 | 性质 |
| --- | --- |
| `domain/TimeTableDraft.java` | **新增**：节数单一数据源 + 校验 + 行内问题定位 |
| `domain/TimeTableSettings.java` | 新增 `DEFAULT_NODES=13`；`generate()` 超出部分均分且不跨天；`defaultNode()` 从满表取 |
| `timetable/CapsuleToast.java` | **新增**：胶囊条（淡入/自动消失/不阻断） |
| `timetable/TimetableController.java` | 设置弹窗改由 `TimeTableDraft` 驱动；编辑器改为静默标记 + 保存时校验；删除 `syncDraft` |
| `res/layout/toast_capsule.xml`、`res/drawable/capsule_background.xml` | **新增**：胶囊条布局与背景 |
| `res/layout/activity_main.xml` | 根加 `root_container` id；底部 `<include>` 胶囊条 |
| `res/layout/dialog_time_table.xml` | 新增「保存」按钮；错误文本改为 gone |
| `test/TimeTableDraftTest.java` | **新增** 68 项 |
| `test/TimeTableSettingsTest.java` | 更新 1 项断言 |
| `build.bat`、`README.md`、`NATIVE_ANDROID_REPORT.md` | 接入与文档 |

## 6. 已知限制

- **未做真机实测**：本环境无 adb 设备。「增删节 → 返回输入框已同步」「编辑时不再弹 Toast」
  「保存时胶囊条提示」这三条交互需真机确认；逻辑层已由 68 项单测锁死。
- **14 节以上默认区间很短（4 分钟）**：这是物理约束（一天 1440 分钟放不下 20×45 分钟）
  下的合法兜底，不是理想作息，用户需自行手改。
- **胶囊条依赖 `activity_main` 根布局**：若宿主 Activity 换成别的布局，`CapsuleToast.create`
  会返回 null，调用处全部判空（提示静默失效，不影响功能）。
- **保存按钮落盘后才关闭弹窗**：若 `saveTimeNodes` 失败（存储异常），弹窗保持打开并提示失败。

---

# 第十九轮：保存误报紧急修复（用户被完全阻塞）

> **反馈**：「我的时间设置没有任何问题，课表设置保存不了，一直提醒检查时间起止。」
> 判定为第十八轮「保存式校验」改造引入的误报。

## 1. 先复现，再修

按纪律先写 `test/TimeTableSaveRegressionTest.java`，**在修复前跑出失败**：

```
FAIL ★minutes 处理全角空格
FAIL minutes 处理不换行空格
FAIL ★摘要包含第 1 节 / 第 4 节 / 第 5 节
FAIL ★摘要包含原因（开始时间必须早于结束时间）
FAIL 摘要用分隔符连接多条
passed=52 failed=7
```

同时把「旧口径必然失败」写成**反向对照断言**（`staleCheck`），让它长期锁住这个 bug：

```java
int staleConfigNodes = 13;   // 仓库里 sheet.config.nodes 仍是旧值
check("★对照：旧口径（用 sheet.config.nodes=13 校验 15 节草稿）必然失败",
        !TimeTableSettings.validate(d15.nodes(), staleConfigNodes).isEmpty());
```

## 2. 根因：仓库层用**旧节数**校验新草稿

`TimetableRepository.saveTimeNodes()`：

```java
// 修复前
public boolean saveTimeNodes(List<TimeNode> nodes) {
    TimetableSheet sheet = current();
    if (!TimeTableSettings.validate(nodes, sheet.config.nodes).isEmpty()) return false;  // ← 旧节数
    List<TimeNode> resized = TimeTableSettings.resize(nodes, sheet.config.nodes);        // ← 旧节数
    ...
}
```

`TimeTableSettings.validate(nodes, nodeCount)` 的第一件事就是：

```java
if (sorted.size() != nodeCount) return "时间表节次数量应为 " + nodeCount + " 节";
```

**触发链路**：

1. 用户打开「课表设置」，把「每天节数」13 → 15（或 15 → 13）；
2. 输入框的 `TextWatcher` → `draft.setNodeCount(15)`，草稿变成 15 节且**完全合法**；
3. 点保存 → `draft.validate()` 通过（用的是草稿节数 15）；
4. `repository.saveTimeNodes(draft.nodes())` → 内部用 `sheet.config.nodes`（**还是 13**）校验；
5. `15 != 13` → 返回 `false`；
6. 界面（第十八轮）把它显示成「时间表保存失败，请检查各节起止时间」。

**所以：只要用户动过「每天节数」，无论时间填得多正确都永远保存不了**。这解释了「我的时间设置没有任何问题」——用户是对的，问题从来不在时间上，而在节数口径错位。

同一处的第二个隐患：第十八轮把错误文案统一成了「请检查各节起止时间」，掩盖了真实原因（节次数量不符），让用户完全无法自查。

## 3. 嫌疑点逐个排查结果

| 排查方向 | 结论 | 证据 |
| --- | --- | --- |
| a) 校验读的是否是编辑后草稿 | **✅ 是**（`draft.nodes()`），但**落库层又用自己的旧节数校验了一遍** | `saveTimeNodes` 第 124 行的 `sheet.config.nodes` |
| b) rowspan / 上午下午标签导致行号偏移 | **❌ 不涉及**——时间表是 1..N 的扁平列表，没有表格 rowspan 展开 | `TimeTableDraft.nodes` 是 `List<TimeNode>` |
| c) 相等边界 `==` 是否被误判 | **❌ 正确**——`start < previousEnd` 才判交叉，`==` 放行 | `issues()` / `validate()` 均为 `<` |
| d) 隐藏字符 / 空白导致 parseTime 失败 | **✅ 真缺陷**——`trim()` 不去 U+3000/U+00A0，解析返回 -1 被判格式错误 | `minutes("　08:00　")` 修复前 = -1 |
| e) 规整逻辑错误重排合法表 | **❌ 正确**——`resize` 逐节 `find` 保留原值 | 老数据迁移用例通过 |

**结论：两处问题全修**——(a) 落在仓库层的节数口径错位（主因，完全阻塞）、(d) 空白字符（次要，会造成「格式错误」误报）。

## 4. 修复

### 4.1 主因：统一校验口径

新增 `TimeTableSettings.validateForSave(nodes, targetNodeCount)`：

```java
public static String validateForSave(List<TimeNode> nodes, int targetNodeCount) {
    int count = Math.max(1, Math.min(MAX_NODES, targetNodeCount));
    List<TimeNode> normalized = resize(nodes, count);   // 按目标节数规整
    return validate(normalized, count);                  // 再用同一节数校验
}
```

`saveTimeNodes` 改为：

```java
public boolean saveTimeNodes(List<TimeNode> nodes) {
    TimetableSheet sheet = current();
    if (nodes == null || nodes.isEmpty()) return false;
    int targetCount = Math.max(1, Math.min(TimeTableSettings.MAX_NODES, nodes.size()));  // ← 草稿节数
    if (!TimeTableSettings.validateForSave(nodes, targetCount).isEmpty()) return false;
    List<TimeNode> resized = TimeTableSettings.resize(nodes, targetCount);
    // ★ 节数以本次保存为准：同步写回 config，否则下次保存/渲染又会错位
    TimetableConfig config = ...(nodes = targetCount)...;
    updateCurrent(new TimetableSheet(sheet.id, sheet.name, config, resized, ...));
    return true;
}
```

控制器两处保存（设置弹窗 / 编辑器）都改为调用 `validateForSave(draft.nodes(), draft.nodeCount())`，
**与落库层完全同源**：`validateForSave` 通过 ⇒ `saveTimeNodes` 一定成功。

### 4.2 次要：空白字符

```java
// 修复前
String value = hhmm.trim();   // trim() 只去 <= U+0020

// 修复后
String value = stripWhitespace(hhmm);  // 额外处理 \u3000 \u00A0 \u200B \uFEFF \u2028 \u2029 \u1680
```

### 4.3 提示列出所有问题行

```java
public static String summarize(List<Issue> issues)   // "第 1 节开始时间必须早于结束时间；第 4 节与第 3 节时间交叉；…"
```

- 节次号 + 原因，用「；」连接，不以分隔符结尾；
- 超过 6 条时追加「等共 N 处问题」，避免胶囊条撑爆屏幕；
- 控制器在保存失败时优先用「所有行内问题」，仅在拿不到行内问题时回退到整表校验文案（例如「节次数量应为…」）。

## 5. 测试

`test/TimeTableSaveRegressionTest.java`，**61 项**：

| 分组 | 断言 |
| --- | --- |
| 13 节起止相接 | ★草稿校验通过 / ★无行内问题 / ★与仓库层同口径通过 |
| 15 节真实作息 | ★校验通过 / 与仓库层同口径通过 / 节次连续 |
| v1 老数据迁移（乱序 + 重复节次） | ★规整为 6 节且连续 / ★保留已填时间 / ★校验通过 |
| **改节数（主因）** | ★13→15 草稿通过；★**对照：旧口径按 13 校验必然失败**且原因含「节次数量」；★修复口径通过；15→13 同理 |
| **保存编排契约** | ★`validateForSave` 与草稿校验同源；★真有问题时必须拦住 |
| 相等边界 | ★首尾相接通过、无行内问题；★差 1 分钟才算交叉 |
| 空白字符 | ★全角空格 / 不换行空格 / 制表符 / `\r` 均能解析；★带空白值校验通过且不报格式错 |
| 相邻对配对 | ★5 节均匀作息零问题；★只改第 4 节只报第 4 节，第 3/5 节不受牵连 |
| 减少节数 | ★15→10 裁剪后仍合法 |
| 提示完整 | ★列出第 1/4/5 节与原因；★用「；」连接；★不以分隔符结尾 |

构建输出：

```
BUILD EXIT=0
TESTS=1357  FAILED=0     ← 1296（零回归）+ 61（新增复现夹具）
javac errors: 0
apksigner verify → Verifies (v1=false, v2=true, v3=true)
badging → com.checktodo.app v1.0, targetSdk 34, label「每日打卡」
APK: 391,204 bytes (382KB)
```

Web 版零改动（`src/`、`dist/`、`public/` 最新 mtime 仍为 `2026-10-24 02:48`）。

## 6. 本轮改动文件

| 文件 | 性质 |
| --- | --- |
| `domain/TimeTableSettings.java` | **新增 `validateForSave()`**（统一校验口径）；`minutes()` 改用 `stripWhitespace()` |
| `data/TimetableRepository.java` | `saveTimeNodes()` 改用草稿节数校验并**同步写回 config.nodes**；`saveConfig()` 注释澄清 |
| `domain/TimeTableDraft.java` | 实现 `summarize()`（列出所有问题行，>6 条提示总数） |
| `timetable/TimetableController.java` | 两处保存改用 `validateForSave`；失败提示改为列出全部问题行 |
| `test/TimeTableSaveRegressionTest.java` | **新增** 61 项复现夹具（含「旧口径必然失败」反向对照） |
| `build.bat`、`README.md`、`NATIVE_ANDROID_REPORT.md` | 接入与文档 |

## 7. 已知限制

- **未做真机实测**：本环境无 adb 设备。「改节数后保存成功」「全角空格不再报错」「提示列出所有问题行」
  三条需真机确认；逻辑层已由 61 项复现夹具（含反向对照）锁死。
- **`saveTimeNodes` 现在会写回 `config.nodes`**：这是有意为之（保证草稿与库同节数）。
  若将来有调用方期望「只存时间表、不动节数」，需要改用显式节数参数的变体。
- **`validateForSave` 会按目标节数 `resize` 后再校验**：若传入的 nodes 少于目标节数，
  会补默认值而不是报「缺节」——这与 `resize` 的既有语义一致，也避免把「用户刚加节、还没填时间」判成错误。

---

# 第二十轮：默认作息改为华农实测刻度（15 节）

## 1. 来源

华南农业大学新版正方 jwglxt 课表页 `scau.htm` 的节次列实测刻度，
`<span class="festival">N</span><span>起</span><br><span>止</span>` 逐节抄录：

| 节 | 起止 | 节 | 起止 | 节 | 起止 |
| --- | --- | --- | --- | --- | --- |
| 1 | 08:00-08:40 | 6 | 12:40-13:20 | 11 | 17:10-17:50 |
| 2 | 08:45-09:25 | 7 | 13:25-14:05 | 12 | 17:55-18:35 |
| 3 | 09:55-10:35 | 8 | 14:30-15:10 | 13 | 19:30-20:10 |
| 4 | 10:40-11:20 | 9 | 15:15-15:55 | 14 | 20:15-20:55 |
| 5 | 11:25-12:05 | 10 | 16:25-17:05 | 15 | 21:00-21:40 |

**关键差异**：华农**单节 40 分钟**，不是 45 分钟。旧实现用「45 分钟 + 10 分钟」规则推演，
第 3 节会算成 09:40（实际 09:55）、第 5 节会落在 12:05 之外——推演与真实作息差得很远。

## 2. 改动

### 2.1 硬编码常量表 + 单一来源

```java
private static final String[] SCAU_START = { "08:00","08:45","09:55", ... };
private static final String[] SCAU_END   = { "08:40","09:25","10:35", ... };
public static final int DEFAULT_NODES = SCAU_START.length;   // 15
```

`generate()` / `defaultNode()` / `resize()` **全部引用同一组数组**，不再各算一套：

```java
private static TimeNode scauNode(int node) {
    int index = node - 1;
    if (index < 0 || index >= DEFAULT_NODES) return new TimeNode(node, UNSET, UNSET);
    return new TimeNode(node, SCAU_START[index], SCAU_END[index]);
}
```

**删除的逻辑**：
- `MORNING_NODES` / `AFTERNOON_NODES` / `INNER_BREAK_MINUTES` / `LONG_BREAK` / `BLOCK_BREAK_MINUTES` 推演参数；
- `LAST_START_MINUTES` 的「23:15 后 break」；
- 16..20 节的「23:25–23:53 均分兜底」——那是为了让默认值合法而造出的 4 分钟假数据，对用户毫无意义。

### 2.2 16..20 节策略：留空，用户必填

`MAX_NODES` 仍为 20。第 16 节起超出华农表，**不猜、不兜底**，返回 `UNSET`（空串）：

```java
public static final String UNSET = "";
public static boolean isUnset(TimeNode node) { ... }
```

`validate()` / `issues()` 在**格式校验之前**先判空节：

```java
if (isUnset(node)) return "请填写第 " + node.node + " 节时间";
```

这条短路很关键：空串交给 `minutes()` 会返回 -1，若落到后面的分支就会被误报成
「时间格式应为 HH:mm」或「开始时间必须早于结束时间」——那正是用户最难自查的错误。

### 2.3 补齐防交叉（顺带修掉一个真实问题）

`resize()` 补缺失节次时直接套常量表会出问题：老用户第 5 节是旧作息的 14:00，
而华农第 6 节默认 12:40，**补进去就插到了前面**，整表变交叉、保存被拒。

新增 `fillAfter()`：

```java
if (start >= previousEnd) return node;          // 正常情况
int newStart = Math.min(previousEnd, dayEnd - 1);
int newEnd = Math.min(newStart + duration, dayEnd);   // 夹在 23:59 内，绝不跨天
```

顺延还要防跨天：23:25 + 40 分钟经 `format()` 取模会变成 00:05（倒置），所以结束时间被夹在 23:59。

### 2.4 老用户不受影响

- 已保存的时间表原样保留（`TimeTableDraft.of` / `getTimeNodes` 不做任何重写）；
- 只有「恢复默认」（`resetDefaults`）或「增加一节」（`addNode` 走 `resize` 补）才用新默认；
- 恢复默认**保持当前节数**，只换时间。

## 3. 测试

新增 `test/ScauScheduleDefaultsTest.java`，**54 项**：

| 分组 | 断言要点 |
| --- | --- |
| **与真实页面比对** | ★从 `scau_grid.htm` 解析出 15 个刻度；★`defaultTable()` **逐节与页面一致**（不抄常量，抄错即失败）；★第 3 节 09:55-10:35、第 6 节 12:40-13:20、第 13 节 19:30-20:10、第 15 节 21:00-21:40；★单节 40 分钟 |
| 默认表合法 | ★自身校验通过 / `validateForSave` 通过 / 严格不重叠；★1..15 节任意数量全部合法 |
| 首尾相接 | ★08:40==08:40 放行；★早 1 分钟才算交叉 |
| 16..20 留空 | ★前 15 节有值、16..20 节为空；★`defaultNode(16/20)` 为空、`(15)` 有值；★提示「请填写第 16 节时间」 |
| 空节提示 | ★**不是**倒置、**不是**格式错误；★行内问题恰好 1 条且定位正确；★填好后立即通过；★`validateNode` 同文案 |
| 老用户不受影响 | ★13 节旧作息原样保留（第 1 节仍 08:00-08:45、末节 22:40-23:25）且仍合法；★加节顺延防交叉、正向不跨天；★恢复默认保持节数、只换时间 |
| 同源 | ★`defaultNode(n)` 与 `generate(20)[n]` 逐节一致；★`resize` 补齐值同源；★`resize(null,18)` 也把 16..18 留空 |

同步调整的旧断言（共 10 处，都是依赖旧 13 节/45 分钟作息的）：

| 文件 | 原断言 | 新断言 |
| --- | --- | --- |
| `TimeTableSettingsTest` | 默认时间表 13 节 / 第 1 节 08:00-08:45 / 第 2 节 08:55-09:40 / 第 4 节 10:55-11:40 / 第 5 节 14:00 / 第 9 节 19:00 | 15 节 / 08:00-08:40 / 08:45-09:25 / 10:40-11:20 / 第 6 节 12:40 / 第 13 节 19:30 |
| 同上 | 每节时长 45 分钟 | **40 分钟** |
| 同上 | 超出部分严格递增（均分兜底） | **第 16/20 节返回空** |
| 同上 | 裁剪后末节 21:45-22:30 | 17:55-18:35（华农第 12 节） |
| 同上 | 单节 08:00-08:45 / 两节 08:00-09:40 | 08:00-08:40 / 08:00-09:25 |
| 同上 | widget 副标题 1-2节 08:00-09:40 / 2-3节 08:55-10:40 / 3节 09:55-10:40 | 08:00-09:25 / 08:45-10:35 / 09:55-10:35 |
| `TimeTableDraftTest` | 1..20 节默认表全部合法 | **1..15 全部合法**；★16..20 留空且提示「请填写」而非倒置/格式误报（第 16 节为第一条，`issues()` 列全部） |
| `TimeTableSaveRegressionTest` | 13 节默认表长度 | 显式构造 13 节旧作息（该用例本来就是测老数据） |

构建输出：

```
BUILD EXIT=0
TESTS=1416  FAILED=0     ← 1357（零回归口径）+ 54（新增）+ 断言调整
javac errors: 0
apksigner verify → Verifies (v1=false, v2=true, v3=true)
badging → com.checktodo.app v1.0, targetSdk 34, label「每日打卡」
APK: 391,204 bytes (382KB)
```

Web 版零改动（`src/`、`dist/`、`public/` 最新 mtime 仍为 `2026-10-24 02:48`）。

## 4. 本轮改动文件

| 文件 | 性质 |
| --- | --- |
| `domain/TimeTableSettings.java` | 硬编码 `SCAU_START/SCAU_END`；`DEFAULT_NODES=15`；`generate/defaultNode/resize` 同源；`UNSET` + `isUnset()`；`validate/validateNode` 空节短路；`resize` 补 `fillAfter` 防交叉；删除全部推演/兜底逻辑 |
| `domain/TimeTableDraft.java` | `issues()` 对空节报「请填写第 N 节时间」并退出相邻比较链 |
| `test/ScauScheduleDefaultsTest.java` | **新增** 54 项（含与真实页面逐节比对） |
| `test/TimeTableSettingsTest.java` | 调整 10 处旧断言到华农刻度 |
| `test/TimeTableDraftTest.java` | 1..20 全部合法 → 1..15 全合法 + 16..20 空节提示 |
| `test/TimeTableSaveRegressionTest.java` | 13 节用例改为显式构造老数据 |
| `build.bat`、`README.md`、`NATIVE_ANDROID_REPORT.md` | 接入与文档 |

## 5. 已知限制

- **只覆盖华农一套作息**：默认值现在绑定 `scau.htm`；其它学校的用户需要手动改时间，
  或将来加入「按学校选择默认作息」的能力（当前未做，避免过度设计）。
- **16..20 节默认留空**：用户必须手填才能保存。这是刻意选择——比塞 4 分钟假数据诚实，
  但确实增加了操作步骤；提示文案已明确到具体节次号。
- **顺延后时长可能不足 40 分钟**：老用户末节接近 23:59 时，新加的节被夹在当天内，
  区间会变短（仍正向、不跨天）。这比跨天倒置安全，但用户可能需要手动调整。
- **未做真机实测**：本环境无 adb 设备。「恢复默认后是华农 15 节」「16 节提示请填写」
  需真机确认；逻辑层已由 54 项（含与真实页面比对）锁死。

---

# 第二十一轮：小卡片串状态根治 + frame HTML 转储通道

## 1. 小卡片三视图串状态：机制级根治

### 1.1 根因（不是"没修对"，是"修的方向不对"）

上轮 `WidgetFactoryState` 加了 `loaded` 标志与「未加载前 count=0」，但仍偶发。读代码后确认根因是
**它允许 `onDataSetChanged` 用实时存储模式覆盖构造时的模式**：

```java
// 旧代码（已删除）
int storedMode = state.mode();
if (appWidgetId != INVALID) storedMode = CheckTodoWidget.getMode(context, appWidgetId); // ← 实时读
...
state.load(tasks.size(), todos.size(), courses.size(), storedMode);                    // ← 覆盖快照
```

### 1.2 竞争时序（状态矩阵）

| 时刻 | 存储模式 | Factory 实例 | state.mode | 列表字段内容 | 结果 |
| --- | --- | --- | --- | --- | --- |
| T0 | 打卡 | F1 | 打卡 | 打卡[3] | 正常 |
| T1 用户点切换 | 待办 | F1 | 打卡 | 打卡[3] | 存储已变，F1 尚未刷新 |
| T2 用户再点 | 课表 | F1 | 打卡 | 打卡[3] | 连点两次 |
| T3 F1 收到迟到的 onDataSetChanged | 课表 | F1 | **课表** | 打卡[3] ← **旧模式数据** | `state.count()` |
| T4 getCount() | — | F1 | 课表 | 打卡[3] | 返回 `courses=0` 或按课表计数 |
| T5 getViewAt(0) | — | F1 | 课表 | 从 `tasks` 取 | **串视图 / 越界 / 用任务 id 打卡** |

关键缺陷：**"行数"与"内容"是两个独立字段**，可以来自不同代。`loaded` 标志只能保证"都写过一次"，
挡不住"写了一次之后又被覆盖模式"。

### 1.3 修法：三条不变量（`WidgetFactorySnapshot`）

| 不变量 | 实现 | 挡住的场景 |
| --- | --- | --- |
| **① 构造即冻结** | `freeze(widgetId, uri, storedMode)` 只在此刻决定 mode；`bind()` 只改行数不改 mode；**不提供读实时状态的入口** | T3 的"后期覆盖" |
| **② 行数与内容同源** | `Rows<T>` 与快照**同代**；`getViewAt` 校验 `rows.mode() == snapshot.mode()`，不同代直接返回 null | T5 的串视图 |
| **③ 模式可校验** | `accepts(mode)` / `staleReason(mode)`；`fillInIntent` 带 `EXTRA_MODE`，Provider 不符即丢弃 | 旧模式残留点击 |

Factory 侧（`CheckTodoWidgetService`）：

```java
private volatile WidgetFactorySnapshot snapshot;              // 构造时冻结
private volatile WidgetFactorySnapshot.Rows<Row> rows;       // onDataSetChanged 整体替换

public void onDataSetChanged() {
    int stored = ...;
    WidgetFactorySnapshot next = snapshot;
    if (snapshot.needsRebuild(stored)) next = WidgetFactorySnapshot.of(appWidgetId, stored); // 纪律 3
    List<Row> built = buildRows(next.mode());                  // 一次性取数（纪律 4）
    rows = Rows.of(next.mode(), built);                        // 先换内容
    snapshot = next.bind(built.size());                        // 再换快照：count == rows.size()
}

public RemoteViews getViewAt(int position) {
    if (!snapshot.loaded() || !rows.has(position)) return null;
    if (rows.mode() != snapshot.mode()) return null;           // 不同代 -> 拒绝渲染
    Row row = rows.get(position);                              // 只读，零仓库查询
    ...
    fillInIntent.putExtra(CheckTodoWidget.EXTRA_MODE, snapshot.mode());
}
```

Provider 侧校验：

```java
int clickedMode = intent.getIntExtra(EXTRA_MODE, MODE_UNKNOWN);
if (clickedMode != MODE_UNKNOWN && normalize(clickedMode) != getMode(context, appWidgetId)) {
    updateAll(context);
    return;   // 旧模式残留点击，丢弃
}
```

> 兼容性：升级前已渲染的 RemoteViews 不带 `EXTRA_MODE`，按 `MODE_UNKNOWN` 走原逻辑，不会让老卡片失效。

### 1.4 旧类

`WidgetFactoryState` 标记 `@Deprecated` 并保留（仅旧单测引用），生产代码已不引用。

## 2. frame HTML 转储通道

### 2.1 用户洞察

课表页常是「外壳 + iframe」，真正的课表在某个 frame 里。`fetch` 只能拿到原始 HTML/JSON；
**被脚本渲染过、或需要现场会话的 DOM，只有在该 frame 自己的上下文里读 `outerHTML` 才能拿到**——
这正是用户「控制台 copy + 存 HTML + 导入」成功的原因。本通道把这一步自动化。

### 2.2 协议（复用现有分块协议）

```
probe（列表阶段，绝不 dump）
  evaluateJavascript(FrameDump.probeScript(path))
    -> JSON {tables, summary(≤60字), children}

dump（用户点「提取选中框架」后，逐 frame）
  evaluateJavascript(FrameDump.dumpScript(reqId, url, path))
    frame 内：h = document.documentElement.outerHTML
    __checkTodoSink.begin(reqId, url)
    __checkTodoSink.chunk(reqId, seq, 0, h.slice(i, i+64K))   × N
    __checkTodoSink.done(reqId, 'ok', h.length)
    失败：__checkTodoSink.fail(reqId, reason)

Java 侧：ChunkAssembler（与 fetch 代理同一个）→ 完整 HTML
超时矩阵：probe 2s / 单 frame 8s / 总预算 60s
容量：单 frame 2MB、总 8MB（沿用 CHUNK_BYTES 协议）
```

### 2.3 交互（按用户补充：手动选择优先）

| 环节 | 行为 |
| --- | --- |
| 入口 | 在线导入对话框新增「框架」按钮 |
| 列表 | 每项：`URL尾段 · 标题 [L层级] · N张表 · 正文前60字`；跨域标 `[跨域]`；含子框架标「含 N 个子框架」 |
| 默认勾选 | **只勾可访问且像课表的**（`kbcx`/`kb`/课表/课程/周）；都不像则**全不勾** |
| 「全选」 | 可选按钮，非默认 |
| 嵌套 | `Candidate.chain` 记录下标链（如 `[2,1]`），`frameResolver` 逐级 `defaultView.frames[i].document` 下钻 |
| 提取 | 点「提取选中框架」→ 逐 frame dump，进度「正在提取 2/3：kbcx/xxx.html」 |
| 解析 | 逐 frame 走 `TimetableParserRegistry`（与 HTML 导入同一条链），再 `merge` 去重 |
| 诊断 | 逐条：`frame#1 kbcx/x.html · 87KB · 11 门课 / 19 条 · KingoReport` |

### 2.4 与其它通道的关系

| 通道 | 拿到什么 | 适用 |
| --- | --- | --- |
| 被动捕获 | POST 响应（数据接口） | 正方等"点查询才出数据"的系统 |
| fetch 代理 | GET 原始 HTML / JSON | 静态页、数据接口 |
| **frame dump（本轮）** | **渲染后的 DOM** | **课表在 iframe 里、或需要 JS 渲染** |
| 深度扫描（几何） | 屏幕几何推断 | 最后手段 |

## 3. 测试

### 3.1 `WidgetFactorySnapshotTest`（38 项）

| 分组 | 断言 |
| --- | --- |
| 冻结 | URI 解析 / 存储优先 / 存储无效回落 URI / null URI |
| 不可变 | ★`bind()` 不改模式；多次 bind 仍不变；bind 返回新对象 |
| 同源 | ★count == rows.size()；★模式同代；Rows 持副本 |
| 模式校验 | ★同模式接受；★旧模式（待办/打卡）拒绝；给出「已丢弃」原因 |
| **快速切换竞争** | ★三代快照各自保持模式与行数；★第 1 代的迟到点击被第 3 代拒绝 |
| 数据变更 | ★清空后模式不变、行数 0（空态非串视图）；★旧快照仍保留 2 行 |
| 宿主复用 | ★`needsRebuild` 检出存储≠快照；重建后旧模式点击失效 |
| 边界 | 未绑定 count=0；★越界不可渲染；负数夹 0；越界模式归一 |

### 3.2 `FrameDumpTest`（62 项）

| 分组 | 断言 |
| --- | --- |
| 默认勾选 | ★kbcx 勾选 / 广告不勾 / ★跨域不勾且不可勾 / ★都不像时全不勾 |
| 脚本分离 | ★light 脚本**不含** outerHTML；★dump 脚本含；含 begin/chunk/done/fail；dump 脚本 <2000 字符 |
| 路径 | 空→主文档；`[2]`；★嵌套 `[2,1]`；三层；★由 discovery 推导 `[0]/[1]/[1,0]` |
| 探测解析 | tables/children/摘要；非 JSON 回落 -1 |
| 逐 frame 解析 | ★**kingosoft 作为 frame dump 解析出 11 门课 / 19 条**；★华农 14 门 / 22 条 |
| 合并去重 | ★同一 frame 喂两次仍是 11 门 / 19 条 |
| 容错 | 空 frame 记失败、无课表记未识别、★好的 frame 仍成功 |
| 诊断 | 显示大小、命中课数、未命中原因、合并总数 |

### 3.3 构建输出

```
BUILD EXIT=0
TESTS=1516  FAILED=0     ← 1416 零回归 + 38（widget）+ 62（frame dump）
javac errors: 0
apksigner verify → Verifies (v1=false, v2=true, v3=true)
badging → com.checktodo.app v1.0, targetSdk 34, label「每日打卡」
APK: 403,572 bytes (394KB)
```

Web 版零改动（`src/`、`dist/`、`public/` 最新 mtime 仍为 `2026-10-24 02:48`）。

## 4. 本轮改动文件

| 文件 | 性质 |
| --- | --- |
| `widget/WidgetFactorySnapshot.java` | **新增**：快照冻结 + 内容同源 + 模式校验 |
| `widget/WidgetFactoryState.java` | 标记 `@Deprecated`（保留供旧单测） |
| `widget/CheckTodoWidgetService.java` | Factory 改为快照驱动；`Row` 预构建；`getViewAt` 零懒加载；fillInIntent 带 `EXTRA_MODE` |
| `widget/CheckTodoWidget.java` | `EXTRA_MODE`/`MODE_UNKNOWN` 常量；`onReceive` 校验点击模式 |
| `domain/FrameDump.java` | **新增**：候选勾选、probe/dump 脚本、frame 路径、逐 frame 解析合并、诊断 |
| `timetable/TimetableController.java` | 新增「框架」按钮 + `showFramePicker`/`probeFrames`/`dumpFrames`/`dumpOneFrame` |
| `res/layout/dialog_online_import.xml` | 新增「框架」按钮 |
| `res/layout/dialog_online_frames.xml` | **新增**：框架列表与操作按钮 |
| `test/WidgetFactorySnapshotTest.java`、`test/FrameDumpTest.java` | **新增** 38 + 62 项 |
| `build.bat`、`README.md`、`NATIVE_ANDROID_REPORT.md` | 接入与文档 |

## 5. 已知限制

- **未做真机实测**（无 adb）。需真机确认：小卡片快速连点不再串、框架列表能列出并 dump 到课表 frame。
  可测逻辑已由 100 项锁定。
- **frame 路径依赖「同层出现次序」**：`window.frames` 的顺序与发现脚本的遍历顺序一致，
  但若页面在探测与 dump 之间动态增删 iframe，下标会错位。此时 dump 会因读不到目标而 `fail`，
  **不会误报成功**（失败信息会进诊断）。
- **跨域 frame 无法 dump**：列表里标 `[跨域]` 且不可勾选，这受同源策略限制，无绕过。
- **单 frame 2MB 截断**：超大 DOM 会被截断（诊断标「已截断」），可能解析不全。
- **手动模式不自动兜底**：用户不勾任何一个就什么也不 dump——这是刻意选择（避免卡顿），
  若将来要"自动全量"，建议放在长按菜单里而不是默认。

---

# 第二十二轮：框架发现超时修复 + 状态提示上移

> 真机反馈（华农 WebVPN 页面）：① 点「框架」→「框架发现超时」，刷新后再点 → 0 个文档可选；
> ② 状态提示文字被底部操作栏遮挡。

## 1. 框架发现超时

### 1.1 根因（代码层事实，不是猜测）

旧 `discoveryScript()` 是**一个同步巨型脚本**，结构如下：

```js
function harvest(win){                       // ← 每个文档都调用一次
  var es = win.performance.getEntriesByType('resource');   // ← 真机上的主要开销
  for (...) if (initiatorType==='xmlhttprequest'||'fetch') resources[name]=1;
}
function one(doc, depth, label){ ... harvest(doc.defaultView); ... recurse into iframes ... }
one(document, 0, '');
return JSON.stringify({...frames, resources: Object.keys(resources).slice(0,30)...});
```

华农页面经 WebVPN 改写后：整页 DOM 被重新包裹、首页挂 echarts（大量 canvas/图层）、
还有多层嵌套 iframe。`performance.getEntriesByType('resource')` 要遍历该文档**全部**资源条目，
在 `one()` 的递归里被乘以文档数。结果：主线程被占满数秒。

第二个放大因素：`probeScript` 用了 `body.innerText`——`innerText` 会**强制排版（layout）**，
在大 DOM 上单次可达数百毫秒。

**为什么超时后是"0 个文档"而不是"部分结果"**：这是一个原子脚本，`evaluateJavascript` 的回调
要么整体返回、要么永不返回。超时 → `latch.await` 返回 false → `return` 前
`out.clear()` 已经执行过 → 列表为空 → 用户看到 0 个文档。

**去重集合排查结论**：`onlineResourceState`（`ResourceRefreshState`）**只在打开对话框时**
`reset()` 一次，frame 发现路径**从未**使用它。所以"刷新后 0"不是 dedup 造成的，
而是**超时未复位 + 原子脚本结果全丢**。本轮仍按用户要求把"发现不得走去重"写成断言固化下来。

### 1.2 修复

| 项 | 改前 | 改后 |
| --- | --- | --- |
| 脚本结构 | 1 个同步巨型脚本（递归 + 每帧 harvest） | **2 个**：`listFramesScript()` 只列路径；`frameInfoScript(path)` 单帧取信息 |
| 资源采集 | 每个文档 `performance.getEntriesByType` | **移除**（改由被动捕获/fetch 代理承担） |
| 摘要取文 | `body.innerText`（强制排版） | `textContent`，先 `slice(0,200)` 再折叠空白 |
| 失败影响面 | 一处超时 → 全部结果丢失 | 单帧 2s 独立超时 → **只丢该帧**，其余照常展示 |
| 发现预算 | 3s | **10s** |
| 刷新语义 | 复用上一轮状态 | 先 `out.clear()` + `render()` 再全新探测，**不走去重集合** |
| 跨域帧 | 直接过滤掉 | **保留并标 `[跨域]`**，避免被误读成"页面没有 frame" |
| 超时提示 | "框架发现超时，请确认页面已加载" | "框架发现超时（Nms），请稍后点「刷新列表」重试；若反复超时，可直接用「提取课表」" |

### 1.3 预算表

| 阶段 | 预算 | 说明 | 依据 |
| --- | --- | --- | --- |
| **frame 列表**（`listFramesScript`） | **10s**（原 3s） | 只读 location/title/子帧数，不递归 harvest | 原 3s 在 WebVPN+echarts 页面上稳定超时；脚本本身 <2KB、无重活，10s 是给慢设备与巨 DOM 的余量 |
| **单帧信息**（`frameInfoScript`） | **2s** / 帧 | `textContent` + `slice(0,200)`；table 计数 | 只做一次 `querySelectorAll('table')` 与文本截断；单帧失败不影响其它帧 |
| 单帧 dump | **8s** | `outerHTML` 序列化 + 分块回传 | DOM 序列化比读文本重一个量级 |
| dump 总预算 | **60s** | 多帧累加 | 用户明确放宽（原 30s） |
| 单帧容量 | **2MB** | 超出截断并标记 | 避免单个巨型 DOM 拖垮整轮 |
| 单块过桥 | 256KB（`CHUNK_BYTES`） | 复用 `ChunkSink` 协议 | 大字符串一次性过桥易截断 |
| 探测并发 | **串行** | 并发注入会让 WebView 主线程排队 | 低端机上并发反而更慢 |

> 关于"事实依据"：本环境无 adb，无法给出真机 profile 数字。上述判断来自**代码结构**（每个文档一次
> `getEntriesByType` + 原子脚本无部分结果）与用户的稳定复现现象（3s 必超时、刷新后 0）。
> 修复后 10s 预算配合"只列路径"的轻量脚本，即使主线程有 1-2s 抖动也有足够余量。

### 1.4 分帧探测的数据流

```
① evaluateJavascript(listFramesScript())                 [1 次, 10s]
   └→ {pageUrl, pageTitle, frames:[{url,title,depth,name,accessible}], resources:[], blocked}

② FrameDump.candidates(discovery)                        [纯 Java]
   └→ List<Candidate>，含 chain（如 [2,1]）与默认勾选（仅 kbcx/kb/课表/课程/周）

③ 对每个可访问帧 evaluateJavascript(frameInfoScript(path)) [N 次, 各 2s, 串行]
   └→ {tables, summary(≤60字), children, url, title}
   └→ 失败/超时：不更新（tableCount=-1），该帧显示为未知，其它帧不受影响

④ render()：列表展示 URL尾段 · 标题 [L1] · N张表 · 摘要
```

## 2. 状态提示被底部操作栏遮挡

**改前**：`text_online_status` 是底部操作栏里的 `layout_weight=1` TextView，`singleLine=true`。
同栏还有「被动捕获」复选框 + 提取/停止/框架/诊断/导入/取消 6 个按钮，状态文字被挤成几个字符 + 省略号。

**改后**：

- 状态文字移到 **WebView 上方**（顶部标题/说明之后、`FrameLayout` 之前），`maxLines=2` 自动换行；
- 背景用 `@drawable/card`（`@color/cardBackground` + `cardBorder`），**`values-night` 里自动变深色**，
  文字用 `@color/textSecondary`（夜间 `#a7b2ad`），无需额外 drawable；
- 底部操作栏保持吸底，并包一层 **`HorizontalScrollView`**——小屏/大字体下按钮总宽超屏时横向可滚，
  不再被压成 0 宽。

## 3. 测试

新增 `test/FrameDiscoveryRegressionTest.java`，**52 项**：

| 分组 | 断言 |
| --- | --- |
| 脚本轻量性 | ★列路径脚本**不含** `getEntriesByType`/`innerText`/`outerHTML`/`textContent`；仍遍历 iframe；限层 4/限帧 16；<2000 字符 |
| 单帧脚本 | ★用 `textContent`、**不用** `innerText`；★先 `slice(0,200)` 再折叠空白；限 60 字；各自 try/catch |
| 预算表 | ★发现 = 10s 且旧 3s 已不存在（读源码断言）；单帧探测 2s；dump 8s；总 60s；2MB |
| **部分结果** | ★3 帧中 1 帧失败，其余 2 帧的表数/摘要保留；★失败帧标 -1；★候选数量不变 |
| 失败隔离 | ★失败帧勾选保持、路径不错乱、不影响另一帧解析 |
| **不去重** | ★同一 JSON 解析两次，帧数与默认勾选完全一致；★候选为每轮新建对象，互不共享状态 |
| 0 帧可区分 | 空帧列表无 error；★超时（null）带 error；★非法 JSON 带 error |
| JSON 装配 | ★kbcx 帧默认勾选；跨域帧保留且不可勾；★label 含尾段/表数/摘要/`[L1]` |

同步更新 `RealCaseImportTest` **3 项**旧断言（它们要求的正是本次要移除的慢操作）：

```diff
- check("发现脚本采集 performance 资源", script.contains("getEntriesByType('resource')"));
- check("发现脚本只收 xhr/fetch", script.contains("initiatorType==='xmlhttprequest'"));
- check("发现脚本回传 resources 列表", script.contains("resources:Object.keys(resources)"));
+ check("★发现脚本不再采集 performance 资源（性能回归点）", !script.contains("getEntriesByType"));
+ check("★发现脚本不含 initiatorType 过滤", !script.contains("initiatorType"));
+ check("发现脚本回传空的 resources 列表", script.contains("resources:[]"));
```

构建输出：

```
BUILD EXIT=0
TESTS=1568  FAILED=0     ← 1516 零回归 + 52 新增
javac errors: 0
apksigner verify → Verifies (v1=false, v2=true, v3=true)
badging → com.checktodo.app v1.0, targetSdk 34, label「每日打卡」
APK: 403,573 bytes (394KB)
```

Web 版零改动（`src/`、`dist/`、`public/` 最新 mtime 仍为 `2026-10-24 02:48`）。

## 4. 本轮改动文件

| 文件 | 性质 |
| --- | --- |
| `domain/OnlineExtraction.java` | `discoveryScript()` 改为 `listFramesScript()` 的兼容别名（不再采集 performance 资源）；新增超轻量 `listFramesScript()` |
| `domain/FrameDump.java` | 新增 `frameInfoScript(path)`（textContent + 先截断后折叠）；`probeScript` 标 `@Deprecated` 转调；跨域帧不再被过滤 |
| `timetable/OnlineExtractionSession.java` | `DISCOVER_TIMEOUT_MS` 3s → 10s |
| `timetable/TimetableController.java` | 发现改分帧；新增 `evaluateForPicker`；`probeOne` 返回成功与否；刷新先复位；超时提示可重试 |
| `res/layout/dialog_online_import.xml` | 状态文字移到 WebView 上方（两行、card 背景）；底部栏包 `HorizontalScrollView` |
| `test/FrameDiscoveryRegressionTest.java` | **新增** 52 项 |
| `test/RealCaseImportTest.java` | 更新 3 项断言（性能回归点） |
| `build.bat`、`README.md`、`NATIVE_ANDROID_REPORT.md` | 接入与文档 |

## 5. 已知限制

- **未做真机实测**（无 adb）。修复依据是代码结构 + 用户稳定复现现象；10s 预算是否足够，
  需在华农 WebVPN 页面上确认一次。
- **资源候选不再来自 performance**：`fetch` 代理的候选池现在只剩 frame 文档 URL。
  这对"课表在 iframe 里"的场景更直接（frame dump 正是为此），但若某页面把课表数据放在
  XHR 接口且用户没开被动捕获，可能少一条通道——此时建议开启「被动捕获」。
- **跨域帧只能展示不能读取**：列表里标 `[跨域]`、不可勾选，受同源策略限制。
- **状态条占一行高度**：WebView 可视区域相应减少约 30dp，这是把提示放在可见位置的代价。

## 第 N+2 轮：「框架」按钮 UI 线程死锁 —— 每次必现的「框架发现超时 + 共 0 个文档」

**真机现场（用户复现）**：点「框架」→「框架发现超时」；刷新列表 →「共 0 个文档」。与页面快慢无关，**每次必现**——这直接排除了上一轮"脚本太重/预算不够"的解释。

### 根因：UI 线程上同步等待自己投递的回调（自锁）

调用链：`framesButton.onClick`（**UI 线程**）→ `showFramePicker` → `discoverFramesForPicker`（同步）→ `evaluateForPicker` → `runOnUi`——`runOnUi` 检测到已在主线程**内联**执行 `webView.evaluateJavascript(script, callback)`，随后 `latch.await(10s)` 把 **UI 线程阻塞住**。而 `ValueCallback.onReceiveValue` 恰恰要投递回 **UI 线程主循环**：等待方阻塞了投递方，`countDown` 永不发生 → 必然 10s 超时 → 返回 null → 候选列表被 `out.clear()` 后不再填充 → 0 个文档。

上一轮拆分脚本（只列路径 + 轻量脚本）方向正确但没打到这一枪：脚本再轻，UI 线程自锁也会让回调进不来。上一轮的 probe 路径没死锁纯属侥幸——`probeFrames` 恰好在后台线程里 await。

### 修复（TimetableController）

1. **`evaluateAsync`**（新增）：UI 线程只投递不等待。超时看门狗 `Handler.postDelayed` + `OneShotGate`（新增 domain 类）仲裁「回调/超时/异常」三终点只算一路：看门狗先触发则晚到回调作废；回调先到则 `removeCallbacks` 撤销看门狗，撤不到的靠门槛兜底。WebView 抛异常立即以超时语义结束。
2. **`discoverFramesForPicker` 改异步**：结果/超时经回调回 UI 线程再续跑（解析 → 填列表 → 渲染 → `probeFrames`）。UI 线程不再出现任何 `latch.await`。
3. **轮次守卫**：`frameDiscoveryGeneration`（AtomicInteger）每轮「发现/探测」递增。异步回调返回时轮次已变（用户点了「刷新列表」）→ 旧回调整体作废：不写列表、不覆盖状态文案。`probeFrames` 的进度与完成写入同样带守卫——修掉"旧探测线程覆盖新一轮提示"的潜在串台。
4. `evaluateForPicker` 保留给后台线程用（`probeOne`）：那里 await 的是后台线程、回调走 UI 线程，不构成死锁。

### 测试

新增 `test/FramePickerThreadingRegressionTest.java` **39 项**：

| 分组 | 断言 |
| --- | --- |
| OneShotGate once 语义 | ★首路接受、其余全拒；isSettled；★8 线程 × 20 轮并发竞争恰好 1 路胜出 |
| 发现流程禁阻塞（读源码） | ★`discoverFramesForPicker` 不含 `evaluateForPicker(`、不含 `latch.await`；★改走 `evaluateAsync`；超时文案保留 |
| evaluateAsync 结构（读源码） | ★postDelayed 看门狗 + removeCallbacks 撤销；★每轮 `new OneShotGate()`；★`.settle()` 仲裁；★catch RuntimeException 立即结束 |
| 轮次守卫（读源码） | ★发现回调与 probeFrames 均检查 `frameDiscoveryGeneration`；字段为 AtomicInteger |

`OneShotGate`（domain，纯 Java）登记进 build.bat 编译清单；新测试登记进编译+运行清单。

构建输出：

```
BUILD EXIT=0
TESTS=1607  FAILED=0     ← 1568 零回归 + 39 新增
javac errors: 0
apksigner verify → Verifies (v1=false, v2=true, v3=true)
APK: 403,573 bytes (394KB)
```

### 本轮改动文件

| 文件 | 性质 |
| --- | --- |
| `domain/OneShotGate.java` | 新增：三路仲裁 once 门槛（纯 Java） |
| `timetable/TimetableController.java` | `discoverFramesForPicker` 异步化；新增 `evaluateAsync`；`probeFrames` 带轮次守卫；新增 `frameDiscoveryGeneration` 字段 |
| `test/FramePickerThreadingRegressionTest.java` | 新增 39 项 |
| `build.bat`、`NATIVE_ANDROID_REPORT.md`、`README.md` | 登记与文档 |

### 已知限制

- 死锁修复的最终确认仍需真机：点「框架」应立即出现「正在发现框架…」→ 数秒内「已发现 N 个文档」，不再出现 10s 必超时。

## 第 N+3 轮：真机两 bug —— 小卡片切换整体错位 / 导入课表被手动新增清空

**真机现场（用户复现）**：

1. 桌面多张三视图卡片之间「串状态」：某张卡明明是课表模式，却渲染着待办内容；按该卡的「切换」后错位**轮转**——打卡页对上课表、待办对上打卡，**整体像 off-by-one 且无法自愈**。
2. 教务导入课表成功、显示正常；随后用「添加课程」手动新增一门课，**导入的课程全部消失**。

两项均为确定性逻辑缺陷（不依赖时序偶然），已用纯 Java 测试先复现（红）再修复（绿）。

---

### Bug 1：切换按钮以「存储模式 +1」，而不是「所见模式 +1」

#### 根因

`CheckTodoWidget.onReceive` 的 `ACTION_SWITCH` 分支：

```java
setMode(context, appWidgetId, nextMode(getMode(context, appWidgetId)));   // 存储值 +1
```

切换按钮的 PendingIntent（`switchIntent`）**只带 appWidgetId，不带任何模式信息**；而条目点击的 fillInIntent 带了 `EXTRA_MODE` 且 Provider 会校验（第 98-113 行）。于是两条纪律不一致：

| 路径 | 携带所见模式 | 旧代渲染时的行为 |
|---|---|---|
| 条目点击 `ACTION_ITEM` | 是（`EXTRA_MODE`） | 校验不符 → 丢弃并 `updateAll` 自愈 ✅ |
| **切换按钮 `ACTION_SWITCH`** | **否** | 以存储值 +1 → 与用户所见脱节 ❌ |

启动器在内存压力 / widget 重建后会继续渲染**上一代** RemoteViews，此时用户所见模式 ≠ `WidgetModeStore` 中的模式。用户看到课表，按下切换却按存储里的打卡 +1 得到待办——每张卡都基于「看不见的状态」偏移一格，于是表现为整体 off-by-one 轮转；因为每次切换都不回写「所见」，这个偏差**永远无法自愈**。§6.3 的「fillInIntent 带 EXTRA_MODE 校验」只覆盖了条目点击，漏掉了切换按钮这一条。

#### 修复

1. **新增 `widget/WidgetSwitchLogic.java`（纯 Java）**：`apply(store, appWidgetId, seenMode)` 以**所见模式**为基准 +1 并写回存储，返回 `Result(mode, stale, requiresRefresh)`：
   - 所见 == 存储：正常前进一格，`stale=false`；
   - 所见 ≠ 存储（旧代点击）：仍按所见 +1 并写回存储 → 存储被拉回用户真正看到的那一代，**下一次点击即恢复正常（自愈）**，`stale=true`；
   - 未携带模式（升级前生成的老卡片）：回落存储值 +1，保持旧行为。
2. **`CheckTodoWidget.switchIntent(context, id, seenMode)`**：PendingIntent 携带 `EXTRA_MODE = 本代渲染模式`；同时把 `data` 与 `requestCode` 改为按 `(widgetId, mode)` 唯一——避免个别启动器按同 identity 复用旧 PendingIntent 的 extra。
3. **`updateWidget`** 把本次渲染的实际 `mode` 传给 `switchIntent`；`ACTION_SWITCH` 改走 `WidgetSwitchLogic.apply`。
4. **`ACTION_ITEM` 的校验改用 `WidgetFactorySnapshot.accepts()`**（原本手写 `normalize(...) != getMode(...)`，语义相同但少了诊断入口）；旧代点击 → `updateAll` 整卡重建，与切换路径的自愈口径一致。
5. 删除已无调用方的 `nextMode()` / `setMode()`（推进逻辑只在 `WidgetSwitchLogic` 一份实现）。

#### 测试证据（`test/WidgetSwitchStaleTest.java`，13 项）

| 分组 | 断言 |
| --- | --- |
| 所见模式驱动切换 | ★所见打卡 +1 得到待办（存储是课表时也如此，证明不是以存储为基础轮转）；结果写回存储 |
| 旧代判定 | 所见 ≠ 存储 → `stale=true`；所见 == 存储 → `stale=false` |
| 旧代点击自愈 | ★旧代点击被标记 stale；★从所见课表继续轮转；★存储被拉回所见轮转结果；需整卡刷新 |
| 轮转闭环 | 打卡→待办→课表→打卡（连续四次切换序列正确）；闭环后存储与所见同步 |
| 未知模式回落 | 未携带模式回落存储；不报 stale；同样写回 |

**红 → 绿证据**：将 `WidgetSwitchLogic` 中 `int base = carried ? normalize(seenMode) : stored;` 还原为 `int base = stored;`（即旧行为）后重跑，5 项失败：

```
FAIL 所见打卡 +1 得到待办（不是以存储课表为基础轮转）
FAIL 结果写回存储
FAIL 所见 == 存储时不视为旧代
FAIL 旧代点击从所见课表继续轮转
FAIL 自愈：存储被拉回所见轮转结果
passed=7 failed=5
```

---

### Bug 2：`saveCourse` 用「只含本次这一条」的新列表整体替换排课

#### 根因

`data/TimetableRepository.saveCourse`（手动新增/编辑课表的唯一写入口，`TimetableController.java:633`）原实现：

```java
List<CourseDetail> nextDetails = new ArrayList<>();        // ← 空集合
if (detail != null) nextDetails.add(new CourseDetail(detailId, baseId, ...));
else for (CourseDetail item : sheet.details)
        if (!item.baseId.equals(baseId)) nextDetails.add(item);
updateCurrent(new TimetableSheet(..., bases, nextDetails));
```

`detail != null`（新增 / 编辑）分支里，`nextDetails` **只放了本次这一条**，已导入的排课**一条都没有带过来** → 保存后 `sheet.details` 只剩新课一条：`bases` 仍在但所有课程都没有排课，周视图与卡片自然全部空白，用户看到的就是「导入的课全没了」。

附带缺陷：新课 `baseId` 在本方法内重新生成（`UUID`），而调用方传入的 `detail.baseId` 是空串——重绑只发生在详情构造参数里，编辑路径（`CourseDetail.id` 稳定）也会因此错位。`importCourses` 与 `saveCourse` 还各写一份合并逻辑，前者用 `LinkedHashMap` 先放导入再放已有，后者用列表新造，两处规则不一致是这类回归的温床。

#### 修复

1. **新增 `domain/TimetableEditor.java`（纯 Java，两个静态方法）**：
   - `saveCourse(sheet, base, detail, generatedBaseId, usedIds)`：以 `new ArrayList<>(sheet.details)` 为底；新增课程 → 追加一条绑定到 `baseId` 的排课；编辑 → 按 `detail.id` 原位替换（无稳定 id 且该课只有一条排课时替换那一条）；`detail == null` → 只删该课的排课。课程主数据沿用原色，其余课程与排课**逐条原样保留**。
   - `importCourses(sheet, bases, details)`：为每门导入课生成新 id 并**追加**，详情全部指向新 id——与手动新增共用同一套不可变 `TimetableSheet` 构造。
2. **`TimetableRepository.saveCourse` / `importCourses`** 改为委托纯函数后 `updateCurrent(...)`；仓储层不再持有合并规则。

#### 测试证据（`test/TimetableEditRegressionTest.java`，26 项）

| 分组 | 断言 |
| --- | --- |
| 导入 N 门 → 手动新增 1 门 | ★新增后课程数 = N+1；★导入的 3 条排课都在且教室原样；总排课数 = N+1；新课排课绑定到生成的 baseId；无空 baseId 孤儿详情；原表不被就地修改 |
| 真实导入→新增链路 | 走 `TimetableEditor.importCourses` 导入 5 门 → 手动新增 → 6 门全在、导入 5 条排课一条不少 |
| 编辑已有课程 | 课程数/排课数不变；名称/教室已更新；排课 id 不变；保留原色；其余课程排课未受影响 |
| 新课程 id 重绑 | ★新详情绑定到生成的 baseId；位置与教室保留；无孤儿详情；每次生成消耗并登记一个新 id |
| 删除排课 | `detail=null` 只删该课排课，课程主数据与其它课程保留 |

**红 → 绿证据**：将 `TimetableEditor` 还原为「`nextDetails = new ArrayList<>()`」（即旧逻辑）后重跑：

```
FAIL 导入的 3 条排课都还在
FAIL 导入课程排课的教室原样保留
FAIL 总排课数 = 3 + 1
FAIL 导入的 5 条排课一条不少
FAIL 导入排课未被覆盖
```

（后续「编辑已有课程」用例在旧逻辑下直接抛 `IndexOutOfBoundsException`，同样证明旧实现会整体丢弃排课。）

---

### 构建与测试

```
native-android\build.bat  →  BUILD EXIT=0
TESTS=1646  FAILED=0      ← 1607 基线零回归 + 39 新增（WidgetSwitchStaleTest 13 + TimetableEditRegressionTest 26）
javac --release 11 -Xlint:all → 0 error / 0 warning
```

尾部输出原文：

```
[5/7] d8 dex
[6/7] package and align
[7/7] reuse debug keystore
Sign APK
Verify APK
Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Verified using v3.1 scheme (APK Signature Scheme v3.1): false
Verified using v4 scheme (APK Signature Scheme v4): false
Verified for SourceStamp: false
Number of signers: 1
Build complete: D:\Desktop\check_todo\native-android\out\checktodo-debug.apk
```

产物：`native-android/out/checktodo-debug.apk`，**403,573 bytes**（与基线同尺寸——两处修复为等量代码替换；已用 `cmp` 核对 APK 内 `classes.dex` 与 `build/dex/classes.dex` 逐字节一致，且新类 `WidgetSwitchLogic` / `TimetableEditor` 均在 `build/classes/` 中）。

### 本轮改动文件

| 文件 | 性质 |
| --- | --- |
| `widget/WidgetSwitchLogic.java` | 新增：所见模式驱动的切换纯函数（含旧代自愈） |
| `widget/CheckTodoWidget.java` | `switchIntent` 携带 `EXTRA_MODE` + 按 (id,mode) 唯一 identity；`ACTION_SWITCH` 走纯函数；`ACTION_ITEM` 改用 `accepts()` 校验；删除无调用方的 `nextMode/setMode` |
| `domain/TimetableEditor.java` | 新增：课程保存（新增/编辑/删除排课）与导入合并纯函数 |
| `data/TimetableRepository.java` | `saveCourse` / `importCourses` 委托纯函数，删除原有会清空排课的合并逻辑 |
| `test/WidgetSwitchStaleTest.java` | 新增 13 项 |
| `test/TimetableEditRegressionTest.java` | 新增 26 项 |
| `build.bat` | 登记 2 个新源文件 + 2 个新测试（编译清单与运行清单） |
| `NATIVE_ANDROID_REPORT.md`、`README.md` | 本文档与测试数同步 |

Web 版（根目录 `src/`、`dist/`、`public/`）零改动。

### 已知限制

- 启动器层面的「旧代 RemoteViews 继续渲染」无法被应用直接观测，本轮采用「切换按钮携带所见模式」把决策权交给用户所见的那一代，已在纯 Java 层闭环；最终体感（多卡连点切换不串、不轮转）仍需真机确认。
- `saveCourse(detail=null)` 的语义为「删除该课排课、保留主数据」，与 `TimetableController` 当前调用（恒传非 null）一致；若后续新增「删排课留课程」的入口，可直接复用该分支。

## 第 N+2 轮：C 方案落地 —— JVM 里真实执行注入脚本（Nashorn 模拟真机 harness）

**动机**：本机长期无 adb，注入 WebView 的脚本此前只能做字符串断言（不含某子串之类），**从未被真正运行过**——字符串断言抓不住语法错误，也抓不住脚本与 Java 协议的对接错位。本轮把「脚本在引擎里真跑 + 与真实 Java 协议对接」变成 build.bat 的常驻测试步骤。

### 1. 依赖（仅测试，不进 APK）

`tools/test-lib/`：nashorn-core-15.4 + asm/asm-tree/asm-commons/asm-util 9.6，Maven Central 下载 + SHA1 全校验，由新增 `native-android/prepare-test-libs.bat` 一键获取（jar 已验证后可重复执行跳过）。build.bat 开头检查缺库即报错提示先跑该脚本。「零第三方库」红线约束的是 APK 依赖，测试工具链不受限（与 tools/sdk 同性质）。

### 2. harness 结构

| 文件 | 职责 |
| --- | --- |
| `test/fixtures/mockdom.js` | 浏览器环境 mock：`makeDoc(spec)`（location/title/querySelectorAll/contentDocument/嵌套 frames/跨域 SecurityError 抛出）、`MiniPromise`（Nashorn 无 Promise，微缩 thenable 支持 then/catch/拒绝穿透）、`makeSink(javaSink)`（把真实 ChunkAssembler 暴露成 `window.__checkTodoSink` 形态）、mock fetch |
| `test/ScriptEngineSimulationTest.java` | 41 项：发现（真实外壳结构/16 帧上限/depth 4/跨域保留/默认勾选）× 单帧信息（含越界与跨域失败隔离）× dump 端到端（脚本执行 → 真实 ChunkAssembler → round-trip → 解析链 11 门课/19 条）× fetch 代理（300KB 多块/HTTP 500/abort/拒绝/无 sink）× 双请求同 assembler 不串 |

用例覆盖发现、单帧、dump、fetch 代理全部四类生产脚本，对接的是**真实** `OnlineExtraction.ChunkAssembler`（经由与 `addJavascriptInterface` 等价的 JS 垫片）。

### 3. 当场抓到并修复的两个真 bug（先红后绿）

| Bug | 现场 | 修复 |
| --- | --- | --- |
| `dumpScript` 是不带括号的匿名 IIFE（`function(){...}()`） | 语句位置语法错误，Nashorn 报 `Expected an operand but found )`，WebView 上必报 SyntaxError → **frame dump 通道在真机上从未执行过** | IIFE 加括号 `(function(){...})()` |
| `dumpScript` 给 `sink.chunk` 传 `total=0` | `ChunkAssembler.chunk` 对 `total<=0` 直接拒绝 → 即使语法修好，done 时必然「没有任何数据块回传」；且旧 64KB 步长在大 frame 上撞 40 块 parts 上限（2.5MB < 8MB 单帧预算） | total 实算 `Math.ceil(h.length/step)||1`；步长对齐 `CHUNK_BYTES`（256KB，与 fetch 代理同口径，8MB ≤ 32 块） |

此前漏网原因：旧测试只做 dump 脚本的字符串断言（`.begin(`、`.chunk(` 存在性），从未执行它。这两个 bug 意味着**「框架勾选 → 提取」这条路在真机上修好框架发现后依然走不通**——本轮提前在 JVM 里拦住了。

### 4. 构建链配套修复

测试编译单行 javac 加新文件后超 Windows cmd 8191 字符上限（`输入行太长`），已拆为两行：src 集（含 jshim/wakeup 变量）+ `-classpath` 依赖前者的 test 集。

构建输出：

```
BUILD EXIT=0
TESTS=1687  FAILED=0     ← 1646 零回归 + 41 新增（harness 内含红转绿后的全部断言）
javac errors: 0
apksigner verify → Verifies (v2=true, v3=true)
APK: 403,573 bytes（dumpScript 修复不改变打包内容尺寸）
```

### 5. 本轮改动文件

| 文件 | 性质 |
| --- | --- |
| `test/fixtures/mockdom.js` | 新增：mock DOM + MiniPromise + sink 垫片 |
| `test/ScriptEngineSimulationTest.java` | 新增 41 项 |
| `domain/FrameDump.java` | dumpScript 两个真 bug 修复（IIFE 括号 + total 实算 + 256KB 步长） |
| `prepare-test-libs.bat` | 新增：测试库下载 + SHA1 校验 |
| `build.bat` | 测试库检查/登记 + javac 拆行 |
| `HANDOFF.md`、`README.md`、`NATIVE_ANDROID_REPORT.md` | 文档 |

### 6. 已知限制

- mock DOM 只覆盖四个脚本实际触达的 API 面（有意为之，不多不少）；页面级差异（真实 WebVPN 改写、渲染性能）仍需真机确认。
- `evaluateJavascript` 的宿主行为（回调线程、超时不触发）不在引擎模拟范围——那类问题由上一轮的 UI 线程测试与预算机制兜底。

## 第 N+3 轮：卡片「标题对不上正文」——世代权威唯一化（URI 模式 = 上屏标题同代）

**真机现场（用户二次反馈，含关键线索）**：卡片顺序 课表/打卡/待办；待办卡按切换后正文像「回到最初」切到打卡，而标题是课表——即 **title 与正文各差一代**：标题=课表、正文=打卡。

### 根因：正文与标题的模式权威不同源

- 标题、切换按钮 PendingIntent、集合视图服务 URI，都在**同一次 `updateWidget`** 里从同一个存储读数生成——天然同代。
- 但 Factory 侧有两处「跟着存储走」：
  1. `WidgetFactorySnapshot.freeze`：URI 与存储冲突时**以存储为准**（旧注释："存储是权威"）；
  2. `Factory.onDataSetChanged`：**每次实时重读存储**，不一致就整卡重建到存储模式。
- 标题上屏与正文重建是两个异步时刻。存储在这两刻之间被下一次切换推进一代，正文就跳到**上屏标题之外的世代**：标题=课表(2)、正文=打卡(0)。此后按切换，"所见模式"（PI 携带的是标题代 2）与正文代（0）继续各自漂移——表现即用户描述的「顺序 课表打卡待办 的轮转错位」。

上一轮（所见模式+1）修的是切换推进基准，但"所见"本身被本条污染，所以症状仍在。

### 修复：世代权威唯一化

| 改动 | 内容 |
| --- | --- |
| `WidgetFactorySnapshot.freeze` | **URI 模式是权威**（它必然与上屏标题同代）；存储只在 URI 不可解析（老启动器丢 data 的极端情况）时回落。删除"冲突以存储为准"分支 |
| `CheckTodoWidgetService.Factory.onDataSetChanged` | **不再实时读存储**；永远按构造时冻结的模式重建行（数据新鲜度由重新查仓库保证）。世代推进的唯一合法路径 = 启动器按新 data URI 重绑 → 新 Factory 冻结新模式 |
| `WidgetFactorySnapshot.needsRebuild` | 删除——"跟随存储重建"的旧机制正是竞争源头 |

正向链路：`ACTION_SWITCH` → `WidgetSwitchLogic.apply`（所见+1，写存储）→ `updateAll` → 新标题+新 URI+notify 同刻发出 → 启动器重绑新 Factory → 正文与标题原子进入同一代。迟到刷新（旧 Factory 收到 onDataSetChanged）只刷新数据、**不换世代**——正文永远不动离上屏标题。

### 测试（先红后绿）

`WidgetFactorySnapshotTest` 重写竞争用例（37→41 项，含 4 项源码级断言）：

- ★freeze 冲突以 URI 为准（两个方向都钉死：URI=课表/存储=打卡 → 课表；URI=打卡/存储=课表 → 打卡）；
- ★迟到刷新仍按冻结代重建（正文与上屏标题同代，不被存储拽走）；重绑后与新一代标题同代；
- ★`onDataSetChanged` 方法体不含 `getMode(`（读源码断言）；★按 `snapshot.mode()` 重建；★`needsRebuild` 已删除。

### 构建输出

```
BUILD EXIT=0
TESTS=1690  FAILED=0     ← 1687 零回归 + 净增 3（重写竞争用例 37→41）
apksigner verify → Verifies (v2=true, v3=true)
APK: 403,573 bytes
```

### 真机验证点

1. 反复快速切换任一卡片：标题与正文必须永远同代（不会再出现"课表标题+打卡内容"）；
2. 在错位状态下按一次切换应立即归位（stale 点击自愈路径不变）；
3. 课表/打卡/待办三卡并存时轮转各自独立、互不带动。

## 第 N+4 轮：鸿蒙手机卡片中心搜不到卡片 —— 补齐卡片发现所需的 provider 元数据

**真机现场（荣耀 60 SE / HarmonyOS）**：长按应用图标无「服务卡片」入口；桌面长按 → 卡片中心搜索「打卡」搜不到本应用的两张卡。

### 排查

Manifest 两个 receiver 声明完整（APPWIDGET_UPDATE + provider meta-data + label + widgetCategory=home_screen），结构无缺陷。缺的是**鸿蒙/华为卡片中心收录与展示所依赖的 provider 元数据**：

| 缺失项 | 影响 |
| --- | --- |
| `previewImage` | 鸿蒙卡片中心以预览图为收录/展示依据之一，无预览图的卡在部分版本不收录（或显示为空白格） |
| `previewLayout`（API 31+） | 无实时预览的卡在卡片中心排后/不显示真实样貌 |
| `targetCellWidth/Height`（API 31+） | 卡片中心按目标格数布局，缺失时部分版本不列出 |
| `description`（API 31+） | 卡片中心详情页与搜索摘要 |

### 修复

- 生成两张预览图 `res/drawable-nodpi/widget_checktodo_preview.png`、`widget_today_course_preview.png`（纯 Python zlib 手写 PNG，~2KB/张，无 PIL 依赖；脚本存 `scripts/gen_widget_previews.py` 可重生成）；
- 两个 `appwidget-provider` 补 `previewImage`/`previewLayout`（指向各自 initialLayout）/`targetCellWidth=4`/`targetCellHeight=3`/`description`；
- 「今日课程卡片」label 改「每日打卡·今日课程卡」——使搜索「打卡」可同时命中两张卡；新增两个 description 字符串。

APK 内已用 `aapt2 dump xmltree` 逐项验证 provider 属性编译在案。构建：EXIT=0、1690 项全过，APK 407,863 字节（+4KB = 两张预览图）。

### 真机操作指引（鸿蒙）

1. 覆盖安装新 APK，**打开一次 App**；
2. **重启手机**（华为/鸿蒙桌面会缓存卡片列表，覆盖安装后不刷新是常见现象）；
3. 长按图标 → 「服务卡片」；或长按桌面空白 → 卡片 → 搜索「每日打卡」/「打卡」；
4. 若仍不出现：检查 设置 → 系统和更新 → 纯净模式（对非渠道包的卡片收录可能有延迟），并再重启一次桌面。

## 第 N+5 轮：紧急度排序（截止 − 完成所需时间）+ 卡片按时间实时刷新

**真机反馈（2026-10-27）**：①打卡/待办应按「截止时间 − 完成需要时间」排序——哪个任务更紧就排上面（App 与小卡片一致）；②卡片不实时刷新：到打卡开始时间了仍显示「未开始」，点「切换」才更新。

### 1. 紧急度排序

| 处 | 改前 | 改后 |
| --- | --- | --- |
| `TaskLogic.sortForToday`（今日视图 + 小卡片打卡组共用） | 组内按 endTime 升序 | **进行中/已错过/未开始**三组按「最晚开始时刻 = endTime − requiredMinutes」升序（越紧急越靠前）；已完成/请假/休息保持 endTime 升序（无紧急度概念） |
| `TodoLogic`（App 待办列表 + 小卡片待办共用） | pending/completed/filterWidgetTodos 全按 endTime | 新增 `sortByUrgency`（endTime − durationMinutes，同刻按创建先后），三处统一改走它；`sortByEndTime` 保留 |
| 状态分组 | ACTIVE→DONE→MISSED→UPCOMING→LEAVE→OFF | 不变（紧急度只作用于组内排序） |

例：22:00 截止需 120 分钟（最晚 20:00 开始）排在 21:00 截止需 5 分钟（最晚 20:55）之前。

### 2. 卡片按时间实时刷新

**根因**：卡片状态随时间跃迁（未开始→可打卡→已错过），但旧实现只有数据变更/用户操作/30 分钟系统轮询（`updatePeriodMillis`，粗粒度且 Doze 下延迟）才刷新。

**方案**：状态跃迁点显式化为刷新边界，AlarmManager 逐边界唤醒——

- `domain/WidgetRefreshLogic`（纯函数）：下一边界 = min(今日各重复日任务 startTime/endTime、48h 窗口内未完成待办 endTime、次日 00:00 翻日点)；无边界返回 -1 交由 30 分钟轮询兜底；
- `widget/WidgetRefreshScheduler`：注册 RTC_WAKEUP 闹钟（精确策略与提醒一致），发往 CheckTodoWidget 的 ACTION_REFRESH（manifest 已注册，无需改动）；
- 挂链：`ReminderScheduler.rescheduleAll`（所有数据变更统一入口，TaskRepository.persist / MainActivity / BOOT_COMPLETED / 每日翻日都经它）末尾续约；闹钟触发 → updateAll → 再续约下一边界，链条自续。

### 3. 测试（先红后绿）

新增 `UrgencyOrderRefreshTest` **20 项**：紧急度排序（含同刻按名、已完成组回归）、待办三入口同口径、刷新边界（开始前/进行中/已错过/跨日待办/已完成待办/非重复日任务/48h 窗外）、调度接线源码断言（ACTION_REFRESH 续约、rescheduleAll 挂链、RTC_WAKEUP）。红跑 8 FAIL 后全绿。

```
BUILD EXIT=0
TESTS=1710  FAILED=0     ← 1690 零回归 + 20 新增
apksigner verify → Verifies (v2=true, v3=true)
APK: 407,865 bytes
```

### 本轮改动文件

| 文件 | 性质 |
| --- | --- |
| `domain/WidgetRefreshLogic.java` | 新增：刷新边界纯函数 |
| `widget/WidgetRefreshScheduler.java` | 新增：刷新闹钟调度 |
| `domain/TaskLogic.java` | 组内排序键 = endTime − requiredMinutes（三组） |
| `domain/TodoLogic.java` | 新增 sortByUrgency，三处调用切换 |
| `notification/ReminderScheduler.java` | rescheduleAll 末尾挂接卡片刷新调度 |
| `widget/CheckTodoWidget.java` | ACTION_REFRESH 分支续约下一边界 |
| `test/UrgencyOrderRefreshTest.java` | 新增 20 项 |
| `build.bat`、`README.md`、`NATIVE_ANDROID_REPORT.md` | 登记与文档 |

## 第 N+6 轮：卡片分组序 / 已错过不刷新（单链闹钟根因）/ 完成待办即消失

**真机反馈（2026-10-28，荣耀 60 SE / MagicOS）**：①打卡卡片分组顺序不对——「已完成/已错过」跑到「未开始」上面；②卡片的「开始时间」能自动刷，「截止时间」永远不刷（到点后仍停在上一个状态）；③待办勾选完成后仍留在卡片上，需要立即消失。

### 1. 打卡卡片分组顺序：新增卡片专用排序

**根因**：`CheckTodoWidgetService.buildRows` 的打卡分支直接复用 `TaskLogic.sortForToday`，而该排序是**为 App 今日视图设计**的（ACTIVE→DONE→MISSED→UPCOMING→LEAVE→OFF）。卡片的诉求是「可操作的在上、看完的在下」，两者分组序天然不同。

**修复**：`TaskLogic` 新增 `WidgetGroup` 枚举（rank 0..5 = ACTIVE→UPCOMING→DONE→MISSED→LEAVE→OFF）、`widgetGroup(TaskStatus)`、`widgetRank(TaskStatus)`、`sortForWidgetToday(tasks, completedDates, now)`：

- 分组按 `WidgetGroup.rank()`；
- **组内**全部按 `orderKey = endTime − requiredMinutes`（同刻按名字）——故意复用 `sortForToday` 的私有 `orderKey`，保证两处「谁更紧急」的结论永远一致；
- 只把 `CheckTodoWidgetService.buildRows` 的打卡分支切到新函数；`sortForToday` 与其 `todayRank` 一行未改，`TaskOrderTest` 的 ACTIVE→DONE→MISSED→UPCOMING 断言原样保留（另有本轮新增断言显式钉死「卡片排序不影响 App 视图」）。

**取舍说明**：`WidgetGroup` 是 `TaskLogic` 内的嵌套枚举，避免了 `TaskLogic`↔`TaskLogic.WidgetGroup` 的循环导入；代价是枚举名带了 `Widget` 前缀。LEAVE/OFF 在卡片的实际数据里基本不可达（`buildRows` 已用 `isScheduled` 过滤，非重复日不进卡片），但保留排序位是为了让纯函数在任意输入下都有确定结果。

### 2. 「已错过」不自动刷新：单链闹钟的结构性失效

**根因**：旧刷新链是**单链**——`WidgetRefreshScheduler.scheduleNext` 只挂**最早的一个**边界，触发后 `ACTION_REFRESH → updateAll → 再 scheduleNext` 续约。这个设计有一个结构性失效模式：**只要有一次闹钟被系统吞掉，链条就永久断裂**（Doze 合并、MagicOS 省电策略、重启后 BOOT_COMPLETED 被延迟或丢弃），直到下一次数据变更才重建。开始边界是任务入场后的第一个闹钟、间隔短，存活率高；越靠后的边界（截止时刻）越容易撞上断链——正好对应「开始能刷、截止永远不刷」。另外 `updatePeriodMillis=30min` 在多数 ROM 上被系统忽略或合并。

**修复（防御纵深）**：

1. `WidgetRefreshLogic.nextRefreshBounds(tasks, todos, now, cap)`：返回**未来全部**边界（今日各重复日任务 start/end、48h 内未完成待办 end、次日 00:00），`TreeSet` 升序去重，上限 `MAX_BOUNDS=32`；**翻日点恒保留**（超限时丢弃最靠后的普通边界，由它顶住最后一格）。原 `nextRefreshAt` 保留（现为列表首个元素的等价实现，旧测试零改动）。
2. `WidgetRefreshScheduler` 改为**每个边界独立闹钟**：`requestCodeFor(at)` 由边界时刻确定性派生（`REQUEST_BASE + (at ^ at>>>32) & 0xFFFFFF`），同一时刻重复调度只覆盖不叠加，不同边界互不影响——**丢一个不拖垮其余**。照搬 `ReminderScheduler` 的 `KEY_CODES` 模式：prefs `check-todo-widget-refresh/scheduled_codes` 登记本轮 requestCode 集合，每轮调度后 `alarms.cancel` 掉上一轮已过/已变的边界。
3. 链路保留：`ReminderScheduler.rescheduleAll` 末尾与 `CheckTodoWidget` 的 `ACTION_REFRESH` 分支仍调用调度（数据变更后边界集合要重建）；由于独立闹钟已覆盖全部边界，链条断裂不再致命。
4. 诊断日志（`TAG=CheckTodoWidgetRefresh`）：每个边界输出 `M月d日 HH:mm:ss (epoch) + mode=exact|inexact`，取消时输出 `cancelled stale refresh requestCode=…`，便于真机 logcat 排查。

### 3. 待办完成即从卡片消失

**修复**：`TodoLogic.filterWidgetTodos` 开头加 `if (todo.completed) continue;`。App 内待办「已完成」分组与撤销（`TodoLogic.completed` + `MainActivity`）**未改动**，`TodoTest` 新增断言钉死「completed 分组仍暴露已完成条目供撤销」。数据变更经既有 `WidgetRefresh.notifyDataChanged` 即时刷新，无需新增刷新链路。

### 4. 测试（先红后绿）

- `UrgencyOrderRefreshTest` 由 20 项扩到 **47 项**（+27）：卡片四状态并存分组序（含输入打乱），四组组内紧急度逐一断言，请假末位 + `widgetRank` 单调，App 今日视图排序不受影响；边界列表（全收集 / 去重升序 / cap=32 与 cap=5 / 无数据仍有翻日点 / cap 挤占不丢翻日点 / 单值兼容 / MAX_BOUNDS）；源码断言（独立 requestCode、KEY_CODES 清理、Log.i + alarmMode、buildRows 改走 `sortForWidgetToday` 且不再出现 `sortForToday`、filterWidgetTodos 过滤已完成）；`filterWidgetTodos` 隐藏已完成。
- `TodoTest` 语义更新：「completed todos stay visible for undo」→「completed todos disappear from widget」，顺序断言改为不含已完成条目；新增「completed group still exposes finished todos for undo」回归。项数 25→26。
- 红证据：新测试首轮编译即失败（`sortForWidgetToday` / `widgetRank` / `nextRefreshBounds` / `MAX_BOUNDS` 均不存在）；实现后仅剩 1 FAIL（`alarmMode` 诊断文案断言未满足），补 `alarmMode(AlarmManager)` 后转绿。

```
BUILD EXIT=0
TESTS=1738  FAILED=0     ← 1710 基线 + 27 新增（UrgencyOrderRefreshTest）+ 1 新增（TodoTest）
apksigner verify → Verifies (v2=true, v3=true)
APK: 411,961 bytes
```

### 本轮改动文件

| 文件 | 性质 |
| --- | --- |
| `domain/TaskLogic.java` | 新增 `WidgetGroup`/`widgetGroup`/`widgetRank`/`sortForWidgetToday`（卡片专用分组序，组内复用 orderKey） |
| `domain/WidgetRefreshLogic.java` | 新增 `nextRefreshBounds` + `MAX_BOUNDS`；`nextRefreshAt` 保留兼容 |
| `widget/WidgetRefreshScheduler.java` | 重写为每边界独立闹钟 + KEY_CODES 清理 + 诊断日志 |
| `widget/CheckTodoWidgetService.java` | 打卡分支改 `sortForWidgetToday`；待办注释同步 |
| `domain/TodoLogic.java` | `filterWidgetTodos` 过滤已完成 |
| `test/UrgencyOrderRefreshTest.java` | +27 项 |
| `test/TodoTest.java` | 语义更新 + 1 项回归 |
| `README.md`、`NATIVE_ANDROID_REPORT.md` | 文档与测试数（1738） |

## 第 N+7 轮：卡片「已错过混在中间」——纯函数返回值被丢弃（buildRows 接线真 bug）

**真机反馈（2026-10-29）**：卡片打卡组里「已错过」没有在最下面，混在中间——上一轮新加的卡片分组排序没有生效。

### 根因：纯函数返回值被丢弃

`sortForToday` / `sortForWidgetToday` / `sortByUrgency` 都是**纯函数**：排序后返回新列表、不改参数。而 `CheckTodoWidgetService.buildRows` 的调用是语句位置裸调用：

```java
TaskLogic.sortForWidgetToday(tasks, completedDates, now);  // ← 返回值丢弃！
for (Task task : tasks) { ... }                             // ← 仍是仓库原始顺序
```

于是卡片的**状态**（逐行实时计算）是对的，但**顺序**一直是仓库返回顺序——分组与紧急度从未生效。追溯发现这个坏模式从最初的 `sortForToday` 调用就存在：**卡片此前从未真正按任何排序渲染过**。上一轮 worker 的测试只覆盖纯函数返回值（JVM 直跑全绿），Android 侧调用点不在 JVM 测试射程内，于是漏网——与 dumpScript 语法错误同类：**字符串/纯函数断言测不到宿主层接线**。

全局排查：`MainActivity` 的 `sortForToday`、`TodoLogic` 内部三处均正确接收返回值，仅此一处。

### 修复

`buildRows` 接收返回值：`List<Task> ordered = TaskLogic.sortForWidgetToday(...)` 并遍历 `ordered`。

### 测试（先红后绿）

`UrgencyOrderRefreshTest` 新增 3 项源码断言（50 项）：★buildRows 接收返回值；★不存在行首裸调用（按行扫描，赋值行尾部同文本不算）；★App 今日视图调用点防回退。红 2 FAIL → 绿。

```
BUILD EXIT=0
TESTS=1741  FAILED=0
apksigner verify → Verifies (v2=true, v3=true)
APK: 411,961 bytes
```

## 第 N+8 轮：AI 判定打卡（接入智谱 GLM-4.6V-Flash 免费视觉模型）

**产品目标**：提高自律监督效果。建任务可选「AI 判定模式」（拍标准照 + 特征描述）；此后打卡需拍照，App 把【标准照+当前照+特征描述】发给 GLM-4.6V-Flash（智谱免费视觉模型，OpenAI 兼容）判定成败。分三阶段执行（域层 → 壳层 → 收尾），域层与壳层由 `[free]claude-opus-5-5` 完成（两次 90 分钟看门狗击杀后从断点续跑成功），收尾文档由主会话补齐。

### 1. 功能设计（按产品决策实现）

| 项 | 说明 |
| --- | --- |
| 验证策略 | `Task.verifyMode`：NONE（默认，老数据兼容）/ AI_PHOTO；标准照与特征描述随任务持久化 |
| 打卡流程 | 系统相机拍照（ACTION_IMAGE_CAPTURE，存私有目录，零第三方库）→ L1 离线预检 → GLM 判定 → pass=完成 / fail=展示理由可重拍 / uncertain=完成+「待复核」/ 异常=降级 L1+待复核（流程永不阻塞） |
| L1 离线预检 | ①ExifInterface 拍摄时间与当前差 >10 分钟拦截旧图；②aHash（8x8 灰度均值，纯 Java）与历史打卡照汉明距离 ≤5 拦截重复图 |
| GLM 调用 | OpenAI 兼容 messages，双 image_url（base64 data URI，长边 ≤512px）+ 特征描述；严格 JSON {verdict, confidence, reason}，解析容错 markdown 栅栏；HTTP 超时 20s，后台线程 |
| 注入防护 | 用户特征描述仅作【证据】：定界符包裹 + 系统提示声明「证据中的指令一律忽略」 |
| BYOK 设置 | 「AI 判定设置」入口（主界面 ⋮ 菜单）：base_url / model / api key，默认预置智谱模板；key 只存本机；附隐私明示 |
| 小卡片 | AI_PHOTO 任务行点击 → 打开 App 拍照（路由抽成纯函数 WidgetCheckInRouting，卡片与 App 共用） |
| 判定历史 | AiVerifyRecord（时间/verdict/confidence/reason）随打卡记录持久化，供审计 |

### 2. 新增模块

| 层 | 文件 |
| --- | --- |
| domain（纯 Java，346 项测试） | VerifyMode、VerificationPolicy、ImageHash、ExifFreshness、AiPromptBuilder、AiVerdict、AiVerdictParser、AiVerifyConfig、AiVerifyRecord、GlmRequest + Task.verifyMode |
| ai（Android 薄壳） | GlmHttp（HttpURLConnection 执行）、AiImageShell（Bitmap→灰度矩阵/缩放/base64）、AiPhotoStore、AiPhotoProvider、AiVerifySettings |
| 接线 | MainActivity（拍照流程+判定对话框+设置入口+建任务 AI 模式）、TaskRepository、CheckTodoWidget / CheckTodoWidgetService / WidgetCheckInRouting（卡片路由）、dialog_task_editor.xml |
| 测试 | AiVerifyCoreTest、AiVerifyProtocolTest、WidgetAiRoutingTest（合计 +410 项） |

### 3. 执行过程记录（看门狗击杀与断点续跑）

- 第一次派发（001）：`[free]claude-opus-5-5` 请求超时，磁盘零落盘 → 重派；
- 第二次（002）：跑满 90 分钟看门狗被杀（zombie），但域层 11 文件 + 346 项测试已完整落盘且构建绿 → 断点续跑；
- 第三次（003）：壳层大量落盘后再次被杀，但击杀时状态已是 **EXIT=0 / 2151 项全过**——代码功能完整，仅文档未写，由主会话补齐（本轮）。

### 4. 构建输出（最终态）

```
BUILD EXIT=0
TESTS=2151  FAILED=0     ← 1741 零回归 + 410 新增（AI 域 346 + 壳层路由等 64）
apksigner verify → Verifies (v2=true, v3=true)
APK: 436,535 bytes
```

### 5. 持久化新增

- `check-todo.v1` 内：任务 `verifyMode`、标准照路径、特征描述、判定历史（AiVerifyRecord）；
- 独立 key `ai-verify.v1`（BYOK 配置：base_url/model/api key），存 `check-todo` SharedPreferences。

### 6. 真机验证点

1. 建任务选「AI 判定」→ 拍标准照 + 写特征描述；
2. 打卡：拍无关场景 → 应 fail 并给理由；拍同场景新照 → pass 完成；
3. 重交同一张旧图 → L1 拦截（重复图）；
4. 「AI 判定设置」清空 key → 打卡降级为「待复核」而非失败；
5. 卡片上 AI 任务点击 → 进 App 拍照流程。

## 第 N+9 轮：AI 判定「HTTP 404」——endpoint 归一化 + 可诊断错误 + 测试连接

**真机反馈（MuMu 模拟器实测复现）**：AI 判定报「AI 服务返回 HTTP 404」，key 配置正确。

### 根因（adb + curl 双实证）

- PC 直连智谱正确端点（无 key）返回 **401**——服务器与路径正常；
- 模拟器设置对话框实测：base_url 实际存的是**完整端点** `https://open.bigmodel.cn/api/paas/v4/chat/completions`（把文档里的完整地址粘进 base_url 是最自然操作），key/model 正确；
- `AiVerifyConfig.endpoint()` 无条件追加 `/chat/completions` → 实际请求 `…/v4/chat/completions/chat/completions` → 404。

### 修复

1. **endpoint() 归一化**：base 去尾斜杠后若以 `/chat/completions` 结尾（忽略大小写）先剥掉再统一拼接——填 base（…/v4）或粘贴完整端点**均正确**，用户已存配置无需改动；
2. **非 2xx 错误可诊断**：`AiHttpDiagnostics.statusWhy`（域层，纯 Java 测试覆盖）——文案带最终 endpoint + 响应体前 120 字（换行折叠、响应体中回显的 key 自动抹成 ***、401/403 鉴权失败、429 额度、402 余额分类文案）；
3. **GlmHttp 诊断日志**（TAG=`CheckTodoAi`）：每次请求记录 endpoint 与状态码，绝不记录 key；
4. **设置对话框「测试连接」**：`GlmHttp.requestProbe` 发最小 chat 请求（max_tokens=1），结果 Toast 区展示（成功含服务端回报的模型名；失败含状态码与 endpoint），按钮期间置灰；
5. 设置页 hint 明示「填到 /v4 或直接粘贴完整 chat/completions 地址均可」；
6. Manifest 开 `android:debuggable="true"`（自用 debug 包，run-as 可排查）。

### 测试与构建

新增 `AiEndpointNormalizeTest` **65 项**（归一化三形态+默认模板+幂等、诊断文案分类与脱敏、探针请求体、壳层接线源码断言）。过程中修正：新域文件登记、statusWhy 直调域层（单一实现源）。

```
BUILD EXIT=0
TESTS=2216  FAILED=0
apksigner verify → Verifies (v2=true, v3=true)
APK: 436,536 bytes
```

### 执行记录

主会话 adb 实测定位根因 → 派发 deepseek-v4.1-flash（中途 CLI 挂掉，核心归一化+诊断已落盘）→ 主会话收尾（测试连接按钮、debuggable、build.bat 登记、statusWhy 直调、文档）。

## 第 N+10 轮：AI 判定扩展到「待办」（可选启用）

**需求**：打卡侧 AI 判定实测好用（用户已切 GLM-4.1V-Thinking-Flash），把同一能力对称加到待办，可选择是否启用。

### 行为设计

| 项 | 说明 |
| --- | --- |
| Todo 模型 | 新增 verifyMode（NONE 默认，老数据兼容）/ aiFeature / aiReferencePhoto；不可变纪律（withAi/withCompletion/withFields 均保留 AI 字段） |
| 待办编辑器 | 「AI 拍照判定」勾选 + 拍标准照 + 特征描述输入，随待办持久化（check-todo.v1 todos 新增三键） |
| 完成流程 | 点「完成」→ 拍照 → L1（EXIF + aHash 去重，历史取待办自己的记录）→ GLM → pass=完成+记录 / fail=理由+重拍+申诉 / uncertain=完成+待复核 / 异常=降级完成+待复核 |
| 待复核展示 | 已完成 AI 待办截止行追加「（待复核）」（最近记录 needsReview 时） |
| 小卡片 | 「完成即消失」不变；AI 待办按钮文案「拍照完成」，点击经 WidgetCheckInRouting.decideTodo（新增）打开 App；普通待办卡片内切换不变 |
| 撤销 | 不需要 AI，行为不变 |

### 实现

- `domain/Todo.java`：+verifyMode/aiFeature/aiReferencePhoto（null 回落 NONE）；withAi；
- `data/TaskRepository.java`：saveTodo 扩签名（旧签名委托兼容）、todoFromJson/todoToJson 三键、待办 AI 记录独立存储 `aiRecordsTodo`（load/persist 对称）、getTodoAiRecords / todoCheckInWithVerdict / appealTodoLastVerdict / latestTodoRecordNeedsReview；
- `widget/WidgetCheckInRouting.java`：decideTodo / isAiPhoto(Todo) / todoActionLabel；
- `ai/` 与 domain 判定链完全复用（GlmRequest.buildBodyFor 接待办标题与特征）；
- `MainActivity`：待办编辑器 AI 区块、相机流程（REQUEST_AI_TODO_CHECKIN/REFERENCE_PHOTO）、待复核标记。

### 测试与构建

新增 `TodoAiMirrorTest` **18 项**（模型默认值/脏数据回落/withAi 不可变/withCompletion、withFields 保留 AI 配置/路由四分支/按钮文案）；`WidgetAiRoutingTest` 的 isAiPhoto(null) 断言改为显式 Task 转型（新增 Todo 重载后消除歧义）。

```
BUILD EXIT=0
TESTS=2234  FAILED=0     ← 2216 零回归 + 18 新增
apksigner verify → Verifies (v2=true, v3=true)
APK: 440,632 bytes
```

### 执行记录

deepseek-v4.1-flash 两次空报告（CLI 秒挂、零落盘，会话弃用）→ 主会话直接实现并收尾。

## 第 N+11 轮：三模型失败转移链 + AI 周报

**需求**：①判定模型单点不稳定 → 按序最多 3 个模型失败转移；②每周结束自动生成「总结+警醒」周报。执行：[free]claude-opus-5-5 三次看门狗击杀中完成主体，主会话收尾（类型错误 3 处、断言语义 1 处、build.bat 登记）。

### 模型链（功能二）

- `domain/ModelChain`：纯函数决策——RETRYABLE（网络/5xx/429/不可解析/不支持图片）→ 下一个；**401/403 key 无效 → 不换模型终局降级**；空位剔除、≤3；
- `AiVerifyConfig`：model 单字段 → models 链（≤3），老持久化单值读时迁移为链首；公开 `ofChain` 工厂（消除 null 重载歧义）；默认链 GLM-4.1V-Thinking-Flash → GLM-4V-Flash → GLM-4.5-Flash（第三位为文本模型，收到图片会失败并自动跳过，设置页 hint 说明）；
- `GlmHttp`：按链逐个调用 + Log.i（第 N/3 个模型 + 状态码，不记 key）；verdict.reason 前缀「[模型名] 」；requestProbe 对整链依次尝试；
- **空链回落语义修正**：回落**完整默认三链**（原单元素回落会悄悄丢失链位 2/3 的转移能力）。

### AI 周报（功能一）

- `domain/WeeklyReport`：周区间（周一起始）纯函数 + summarize（每任务应卡/实卡/streak/漏卡日期 + 待办计数）+ 防注入提示词组装 + **stripThinking**；
- 调度：ACTION_DAILY（每日 00:00）当天周一 → 后台生成上一自然周报告 → prefs（weekly-report.v1，保留 8 份）+ 通知；失败静默；
- 入口：⋮ →「AI 周报」：历史列表（最近 8 条）+「生成本周报告」（本周一至今）。

### 实测发现与修复（模拟器）

1. **thinking 模型思维链泄漏**：GLM-4.1V-Thinking-Flash 把 `<think>…</think>` 推理原文随正文返回，周报上屏带草稿 → `stripThinking` 剥离（成对块/未闭合截断/游离闭合三规则，5 项测试）；
2. **思维链烧尽输出额度**：finish=length 时智谱把推理放 reasoning_content、content 为空 → max_tokens 800→2000→4000（实测 4000 稳定出全文）；
3. 链探针模拟器实测：`HTTP 200（判定链第 1/1 个模型：glm-4.6v-flash）`；周报模拟器实测生成成功（总结 + 3 条警醒，无泄漏，截图 artifacts/ai-verify/）。

```
BUILD EXIT=0
TESTS=2486  FAILED=0     ← 2234 零回归 + 252 新增（ModelChainTest 97 + WeeklyReportTest 147 + strip 5…以实际为准）
apksigner verify → Verifies
APK: 452,918 bytes
```

### 持久化新增

- `ai-verify.v1`：models 数组（≤3，兼容老单 model 迁移链首）；
- `weekly-report.v1`：周报历史（周区间/生成时刻/模型/正文，保留 8 份）。

## 第 N+12 轮：AI 判定「相册上传」渠道（与拍照并存）

**需求**：AI 判定的**标准照**与**打卡/完成照**都要能「从相册选择」，而不是只能现拍。相册图必须**拷进 app 私有目录**（`ACTION_GET_CONTENT` 返回的 Uri 只是临时授权，GLM 请求要读成 base64，跨 activity 就失效）；预检口径按渠道区分——**跳过 EXIF 新鲜度**（相册图本来就是历史图，按新鲜期拦会把正常打卡堵死）、**保留 aHash 去重**（防重复交同一张图）与 GLM 判定；**拍照渠道行为一字不改**。执行：域层+测试一次 build（红→绿），壳层接线一次 build（绿）。

### 域层（新增 1 类 + 扩 1 类）

- **`domain/PhotoChannel`（新）**：封闭枚举 `CAMERA("camera","拍照",true)` / `GALLERY("gallery","从相册选择",false)`；`enforcesFreshness()`（只有拍照 true）、`enforcesDedup()`（两渠道恒 true）、`filePrefix(base)`（相册 → `gal-shot` / `gal-ref`，便于审计区分来源）、`parse(raw[,fallback])`（宽松解析，null/空/未知一律回落**拍照**=最严格口径，脏数据不放水）、`orCamera(null)`、`duplicateHint()`（重复图拦截文案按渠道给下一步）、常量 `PICKER_TITLE = "选择打卡图片来源"`。
- **`domain/VerificationPolicy`（扩）**：`Precheck` 增加 `channel` 与 `freshnessChecked` 两个字段；新增**渠道显式重载** `precheck(PhotoChannel, shotAtMillis, nowMillis, thresholdMinutes, currentHash, historyHashes)`——`!enforcesFreshness()` 时**整项跳过**新鲜度（不是把阈值放大），去重块两渠道都执行；旧 5 参签名 `precheck(shotAt, now, threshold, hash, history)` 委托为 `CAMERA` 渠道，文案与结果码逐字不变（`AiVerifyCoreTest` 325-384 的既有调用点零改动）。
- **为什么必须显式传渠道**：曾考虑用 `shotAtMillis == null` 隐式推断相册，语义上是错的——相机不写 EXIF 时拍照渠道也合法地为 null，两者必须分开；渠道因此不进状态字段，而是作为参数一路传到预检。

### 壳层（`AiPhotoStore` + `MainActivity` + 编辑器布局）

- **`ai/AiPhotoStore.copyFromUri(Context, Uri, prefix)`**：`ContentResolver.openInputStream` → 32KB 分块流式写入 `getFilesDir()/ai_photos/gal-*-<时间戳>.jpg`；**不解码不缩放**（后续 `AiImageShell` 按既有口径 2048/512/JPEG82 处理，与拍照图完全一致）；空内容、null 流、任何异常 → `Log.w` + 删残留 + **返回 null**（调用方按「没有图」提示，绝不把失败当成有图）；成功才 `Log.i` 字节数，**日志不含 Uri/key**。
- **四个相册请求码**：`1006` 任务打卡照、`1007` 待办完成照、`1008` 任务标准照、`1009` 待办标准照；拍照请求码 `1002~1005` 原样保留。`onActivityResult` 新增四条路由：**先 `copyFromUri` 落盘，再进既有链路**（任务/待办分别调 `runAiPrecheck(task, shot, PhotoChannel.GALLERY)` / `runAiTodoPrecheck(todo, shot, PhotoChannel.GALLERY)`；标准照走 `applyTaskReferencePhoto` / `applyTodoReferencePhoto` 回填编辑器状态）。
- **来源选择对话框**：`pickAiCheckInSource(task, todo, forTodo)` 弹「选择打卡图片来源」→ `拍照` / `从相册选择` / `取消`。**四个入口全部接入**：任务卡片圆区、待办勾选完成、卡片 AI 行拉起 App（`maybeStartAiCheckInFromWidget`）、判定未通过与 L1 拦截对话框的「重新拍摄」（同时新增 NEUTRAL「从相册选择」出口，重拍路径不变）。
- **两个编辑器的标准照**：任务编辑器布局新增 `button_ai_reference_gallery`（挨着 `button_ai_reference`），待办编辑器代码内新增 `aiTodoReferenceGalleryButton`；相册结果与拍照结果共用同一套回填（路径 + 按钮文案 + 状态行），保存时随任务/待办持久化，**选图失败不清空已有标准照**。
- **并发防护**：相册渠道不预建目标文件，用 `pendingGalleryPick` 挡重复点击；`pendingShotTaskId/pendingShotTodoId` 先存 id，结果回来据此续跑；异常路径全部复位。

### 判定链与安全（无回归）

- 两渠道之后**完全同链**：拷贝落盘 → L1 预检 → L2 GLM（`GlmHttp.requestAsync` 三模型失败转移链）→ 结果映射；降级铁律不变（未配 key / 无标准照 / 网络失败 / 不可解析 → 完成 + 待复核，永不阻塞，绝不映射为 fail）。
- **渠道不进 `Outcome`**：L2 映射、aHash 历史口径（只取已完成打卡记录的照片哈希，fail 照不作为重复基准）、`precheckRecord`（未完成、无哈希、不进重复样本）对两渠道完全一致。
- API key 仍只存设备、只进 `Authorization` 头；隐私提示（照片与特征描述上传到用户自己配置的服务商）不变；照片仍只落 app 私有目录（无存储权限、卸载即清除），`AiPhotoProvider` 仍 `exported=false`。
- 顺手修一处红：`GlmRequest.buildTextBody` 未显式指定模型时原先回落判定链链首（图片模型），而 `WeeklyReportTest` 钉的是周报专用文本模型 → 改为回落 `AiVerifyConfig.WEEKLY_MODEL`（`glm-4.5-flash`，不吃图片链，`max_tokens` 见 `TEXT_MAX_TOKENS=65536`）。

### 测试

`test/PhotoChannelTest.java` 新增 **137 项**（域层用例 + 壳层源码断言）：渠道枚举与文案、`parse` 六类脏输入回落、`filePrefix`、拍照渠道新鲜度/去重/文案原口径、**旧签名等价拍照渠道**（含无 EXIF 放行）、**相册 + 旧 EXIF 放行**（一小时/一年前/时钟超前/阈值=1 分钟四种）与同数据拍照渠道拦 stale 的对照、**相册 + 重复 aHash 拦截**（距离 5 拦 / 6 放、多条历史命中其一即拦）、哈希不可用与脏历史放行、重复图文案按渠道分叉、脏渠道（null）回落拍照不放水、历史样本口径、L2 与降级铁律、`copyFromUri` 流式/失败返 null/空内容清理/关流/日志不含 key、四个相册请求码与路由、双编辑器相册按钮、卡片入口同一交互、`ACTION_IMAGE_CAPTURE` 四处保留、`requestAsync` ≥2 处（判定链未回归）。

```
BUILD EXIT=0
TESTS=2623  FAILED=0     ← 2486 零回归 + 137 新增（PhotoChannelTest）
apksigner verify → Verifies
APK: 457,017 bytes
```

### 持久化新增

- 无新 SharedPreferences 键。相册照片落 `getFilesDir()/ai_photos/gal-shot-*.jpg` / `gal-ref-*.jpg`（文件名前缀即渠道审计），判定记录结构 `AiVerifyRecord` 不变。


## 第 N+12 轮：相册上传渠道（标准照与打卡/完成照）+ 模型链默认序调整 + 周报专用模型

**需求**：①AI 判定的标准照与打卡/完成照增加「从相册选择」渠道（与拍照并存）；②判定链默认序改为 GLM-4.1V-Thinking-Flash → GLM-4V-Flash → glm-4.6v-flash（第三位从文本模型换成免费视觉模型，全链可识图）；③周报固定用 GLM-4.5-Flash（纯文本，最大输出 96K），token 上限放宽 65536。

### 相册渠道（gallery-channel-001/002）

- `domain/PhotoChannel`：CAMERA/GALLERY 渠道枚举——GALLERY 的 `freshnessApplies=false`（相册图多为历史图，EXIF 时间戳无意义），aHash 去重与 GLM 判定完整保留；
- `AiPhotoStore.copyFromUri`：相册 URI 流式拷进私有目录（临时授权 + GLM 读 base64 需真实文件），之后与拍照图同一条链路；
- `VerificationPolicy.precheck` 渠道显式化：相册渠道跳过新鲜度、保留去重（域层测试钉死：相册+旧 EXIF → 通过；相册+重复 aHash → 拦截）；
- MainActivity：双编辑器「从相册选择标准照」按钮、打卡/完成先弹「拍照 / 从相册选择」选择框、onActivityResult 相册路由（拷贝 → 既有链路）。

### 模型链默认序 + 周报专用模型（主会话直接修改）

- `DEFAULT_MODEL_3`：glm-4.5-flash（文本）→ **glm-4.6v-flash**（视觉），判定链全链可识图；
- 新增 `AiVerifyConfig.WEEKLY_MODEL = "glm-4.5-flash"`：周报为纯文本任务，固定用该模型（96K 输出），不占用视觉判定链；
- `GlmRequest.TEXT_MAX_TOKENS`：4000 → **65536**（周报总结与提醒写足篇幅）。

### 构建与实测

```
BUILD EXIT=0
TESTS=2623  FAILED=0     ← 2486 零回归 + 137 新增（PhotoChannelTest 等渠道预检/路由）
apksigner verify → Verifies
APK: 见 out/checktodo-debug.apk
```

模拟器实测：相册渠道 E2E（建 AI 待办 → 拍标准照 → 完成 → 相册选图 → 预检跳过新鲜度 → GLM 判定）与周报（GLM-4.5-Flash 生成）——证据见 artifacts/ai-verify/。

## 第 N+13 轮：AI 周报长文超时与网络重试

### 根因与修复

- **根因确认：超时过短。** `TIMEOUT_MS = 20_000` 原先被文本和判定请求共同使用，且 `postTextSync()` 经 `postStatus()` 写死同一组连接/读取超时；长报告生成可超过 20 秒而触发 socket 超时。现增加 `TEXT_TIMEOUT_MS = 90_000`，周报文本路径使用它，判定及其他 POST 保持 20 秒。
- `requestTextAsync()` 对非 HTTP 状态类的网络 `IOException` 最多自动重试一次，间隔 2 秒；HTTP 失败（如 4xx/5xx）不重试。两次网络尝试失败后才回调错误，API key 未配置等 HTTP/业务问题不改变判定链。
- 周报生成按钮提示改为「单次最多等待 90 秒，网络失败将重试」，准确反映两次尝试的最长总耗时；最终超时文案同步显示 90 秒。周报仍固定使用 `glm-4.5-flash`，模型配置不需改动；本次未做真机 API 在线验证。
- `WeeklyReportTest` 新增 6 项源码断言，覆盖文本/判定超时隔离、90 秒进度提示与 2 秒单次重试；`WidgetAiRoutingTest` 更新原先依赖 setter 字面量的断言，改验判定调用仍走 `TIMEOUT_MS` 默认重载。

### 构建与测试

```
BUILD EXIT=0
TESTS=2629  FAILED=0
WeeklyReportTest=161 passed, 0 failed
apksigner verify → Verifies
APK: 457,017 bytes（native-android/out/checktodo-debug.apk）
```

### N+13 补充：周报超时修复与自适应降额（codex [free]gpt-5.6-sol + 主会话）

**真机反馈**：周报生成提示「网络超时」。

1. **根因一（超时太短）**：文本请求误用判定链 20 秒超时 → 新增 `TEXT_TIMEOUT_MS=90s`（判定仍 20s），进度文案同步，网络类失败重试 1 次（codex worker 修复）；
2. **根因二（GLM-4.5-Flash 免费档排队极慢）**：模拟器实测两次 90 秒均超时（对照判定链模型 <20 秒出全文）→ 周报模型序列化：`WEEKLY_MODEL` 优先，失败逐个落到判定链模型（去重、≤4 个，`textModelSequence` 纯函数）；
3. **根因三（意外收获，1210 自适应降额）**：转移过程中暴露智谱各模型 max_tokens 上限不同（4.1v-thinking=16384、glm-4v-flash=1024），65536 被整体拒绝 → `clampFromError` 解析服务端 1210 错误里的上限、**同模型降额重试一次**（自适应任意模型，6 项测试）。

**模拟器实测全链路**（logcat）：
```
glm-4.5-flash: timeout, try next model
glm-4.1v-thinking-flash → HTTP 400（1210 上限）
text request (clamped max_tokens=16384) model=glm-4.1v-thinking-flash, responseChars=1524
→ HTTP 200 周报生成成功
```

```
BUILD EXIT=0
TESTS=2638  FAILED=0
APK: 457,017 bytes
```

### N+13 追加：周报文本模型切换 GLM-4-Flash-250414

用户指定：周报专用文本模型换 **GLM-4-Flash-250414**（最大输出 16K，非 thinking 无思维链开销，免费档响应快）。`WEEKLY_MODEL` 切换、`TEXT_MAX_TOKENS` 16384（顶满上限，1210 自适应降额仍兜底）、相关断言同步。

**模拟器实测**：`text request 1/4 model=glm-4-flash-250414 → HTTP 200`，~10 秒出全文（对比 GLM-4.5-Flash 两次 90 秒超时）——周报生成体验显著改善。

```
BUILD EXIT=0
TESTS=2638  FAILED=0
APK: 457,017 bytes
```
