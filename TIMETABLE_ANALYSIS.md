# WakeUp 课程表 v6.1.35 功能剖析报告

分析对象：`D:\Desktop\base.apk`

应用信息：`com.suda.yzune.wakeupschedule`，版本 6.1.35，Kotlin 应用，targetSdk 35，包体 39.5 MB。

信息来源：以本机 APK 的 `aapt2 dump` 与 jadx 反编译结果为准确认；GitHub 仓库 `YZune/WakeUpSchedule` 仅作结构参考，版本可能落后于 6.1.35，不作为结论依据。

工具输出：

- 资源表：`tools/wakeup-resources.txt`
- 清单：`tools/wakeup-manifest.txt`
- 反编译源码：`tools/jadx-out/sources/`
- jadx 版本：1.5.2，全量反编译完成；21669 个类，其中 91 个非致命错误，核心课表类可读

## 1. 页面布局

### 主课表页

入口为 `SplashActivity`，主界面由 `ScheduleActivity` 承载。课表本体在 `ScheduleFragment` 中：

- 分页容器使用 `ViewPager2`，适配器 `NewSchedulePagerAdapter`
- `getItemCount() = TableConfig.maxWeek`，即一页一教学周
- 每页为 `SchedulePagerItemLayout`，包含：
  - `monthAndWeekLayout`：顶部月份与星期表头
  - `scrollView`：纵向滚动区域，包裹课程网格
  - `courseTimeLayout`：左侧节次时间轴
  - `courseDataLayout` / `ScheduleAddCourseLayout`：实际课程网格

表头星期文案来自 `R.array.main_weekdays`。当前星期加粗，非当日文字使用约 32% alpha 弱化。

列数为 5 到 7 列，由 `showSat`、`showSun` 决定。时间轴宽度按公式 `0.64f / (columns + 0.64f)` 分配，其余宽度给课程网格。

课程块高度来自 `TableConfig.itemHeight`。课程块背景为圆角矩形，圆角半径 `radius`，默认 4；描边色 `strokeColor`，文字色 `courseTextColor`。课程颜色优先使用 `CourseBean.color`，未指定时按课程 `id % 9` 从内置色板取默认色。

非本周课程默认仍显示，但透明度降低；`showOtherWeekCourse` 控制是否显示，`otherWeekCourseAlpha` 控制其透明度。网格线由自定义 `NewGridBackgroundView` 绘制，`showGrid` 控制开关。

课程点击后打开 `CourseDetailBottomSheet`。长按课程进入编辑或拖拽排课模式。空课表状态使用 `ScheduleEmptyLayout`。

### 顶栏与入口

主界面顶部提供课表切换、当前周次显示与周次跳转，右侧为设置与更多操作入口。底部主体就是课表页本身，无固定底部导航栏。设置页为 `SettingsActivity`，课表样式设置由 `TableConfig` 与 `ScheduleStyleConfig` 承载。

### 课程详情弹窗

`CourseDetailBottomSheet` 展示：

- 课程名
- 周次范围
- 星期与节次范围
- 上课时间范围
- 教师
- 教室
- 备注
- 学分

若同一时间有多个课程，弹窗内使用 `RadioGroup` 按 `level` 选择具体课程。工具栏菜单提供编辑与删除。删除粒度有三种：仅本周当日该课程、删除当天同教师同地点的所有匹配记录、删除整个课程。

## 2. 可设置选项

课表设置分为两部分：数据库中的多课表数据，以及每个课表独立的 `SharedPreferences` 样式配置。

### 课表基础配置

存储文件名形如 `table{id}_config`，对应类为 `TableConfig`，继承 `ScheduleStyleConfig`：

| 字段 | 默认值 | 含义 |
| --- | --- | --- |
| `tableName` | - | 课表名称 |
| `nodes` | 20 | 每天最大节次数 |
| `background` | - | 背景图片或颜色配置 |
| `startDate` | - | 学期开始日期 |
| `maxWeek` | 20 | 学期总周数 |
| `sundayFirst` | - | 是否周日为第一列 |
| `order` | - | 课程块排序方式 |

### 显示样式配置

来自 `ScheduleStyleConfig`：

| 字段 | 默认值 | 含义 |
| --- | --- |
| `itemHeight` | 64 | 单节高度 |
| `itemAlpha` | 50 | 课程块透明度 |
| `headerTextSize` | 11 | 表头字号 |
| `itemTextSize` | 12 | 课程块字号 |
| `strokeColor` | - | 课程块描边色 |
| `textColor` | - | 表头文字色 |
| `courseTextColor` | - | 课程文字色 |
| `showSat` | true | 显示周六 |
| `showSun` | true | 显示周日 |
| `showOtherWeekCourse` | true | 显示非本周课程 |
| `showLocation` | true | 显示教室 |
| `showTeacher` | true | 显示教师 |
| `showTimeBar` | true | 显示左侧时间轴 |
| `radius` | 4 | 圆角半径 |
| `otherWeekCourseAlpha` | 50 | 非本周课程透明度 |
| `showRoomPrefix` | true | 显示教室前缀 |
| `showGrid` | false | 显示网格线 |
| `useDottedLine` | false | 时间轴虚线样式 |
| `itemCenterHorizontal` | false | 课程文字水平居中 |
| `itemCenterVertical` | false | 课程文字垂直居中 |
| `strokeColorCompose` | false | 描边色跟随课程色 |
| `textColorCompose` | false | 文字色跟随课程色 |
| `showTime` | false | 课程块内显示时间 |

可推断的设置入口包括：学期开始日期、周数、每天节节数、节次时间表编辑、显示周六日、课程颜色、背景、字号、当前项高亮、时间轴显示、网格显示、非本周课程显示等。具体开关均落在上述字段上。

## 3. 课表相关功能

### 多课表

支持多课表。每个课表是 `TableBean` 的一条记录，课程数据均带 `tableId`。课表切换后读取对应的 `table{id}_config`。

### 课程增删改

课程拆成两层：

- `CourseBaseBean`：课程名、颜色、备注、学分等公共信息
- `CourseDetailBean`：某天某节次某周段的一次具体排课

这使同一门课可以拆散在不同星期、节次、周次，也支持“同一时间多个课程”的情况，详情弹窗按 `level` 区分。

### 周次规则

`CourseDetailBean` 的 `startWeek`、`endWeek`、`type` 描述周次。`type` 区分连续周、单周、双周。页面根据当前周与该字段判断课程是否显示。

今天是第几周由 `TableConfig.startDate` 计算得到，页码索引即周次索引。

### 提醒与后台

课程提醒通知通道为 `schedule_reminder`，使用高优先级、震动。点击通知打开 `SplashActivity`。Android 12+ 会检查 `canScheduleExactAlarms()`，不可用时禁用课程提醒。

提醒的增删与课表数据变更联动，具体闹钟调度逻辑与 Widget 服务均在 `com.suda.yzune.wakeupschedule` 下实现。

## 4. 桌面小卡片

Manifest 确认有 6 个 Widget provider：

- `ScheduleAppWidget`：整周课表
- `TodayListAppWidget`：今日课程列表
- `TodayAndNextDayAppWidget`：今日与次日
- `TodayCourseAppWidget`：今日课程
- `TodayListAppWidgetMIUI`：MIUI 适配今日列表
- `TodayModernAppWidgetMIUI`：MIUI 适配今日现代样式

相关服务：

- `ScheduleAppWidgetService`
- `TodayColorfulService`
- `TodayModernService`

相关配置页：

- `WidgetStyleConfigActivity`
- `WeekScheduleAppWidgetConfigActivity`

刷新机制：

- 周课表 Widget 使用 `RemoteViewsService`
- `ScheduleAppWidgetService.onGetViewFactory()` 从 Intent data 解析 widget id 与周次模式
- 具体工厂类在 `schedule_appwidget.OooO0OO`，渲染时把整张课表布局转成位图，再设置到 `R.id.iv_schedule`。位图转换由 `ViewUtils` 完成

交互：

- 周课表 Widget 通过自定义广播 `WAKEUP_NEXT_WEEK`、`WAKEUP_BACK_WEEK` 翻周
- 今日 Widget 通过 `WAKEUP_NEXT_DAY`、`WAKEUP_BACK_TIME` 翻日
- 今日 Widget 还支持 `WAKEUP_REMIND_COURSE`、`WAKEUP_CANCEL_REMINDER` 设置或取消单课提醒
- 点击课程后跳回应用主界面，具体课程参数由 Widget 配置与 Intent extra 携带

## 5. 导入课表实现

### 导入途径

反编译确认的入口包括：

- 分享码导入
- 文件导入
- CSV 导入，UI 中称为“Excel 导入”
- HTML 文件导入
- 教务系统登录导入
- 备份导入
- 在线分享与导入
- 超星、青果等 WebView 解析流程

### CSV 导入

`ExcelImportFragment` 指向 `https://www.wakeup.fun/doc/.xlsx`，但实际解析要求文件保存或导出为 CSV。

`ImportViewModel.importFromExcel`，反编译名为 `Oooo0O0`，检查扩展名为 `csv`，读取文本后交给 `schedule_parser.parser.o0ooOOo`。

该解析器特征：

- 支持 `,`、`;`、Tab 三种分隔符
- 校验表头包含课程名与周次相关列
- 每行至少 7 列
- 解析周次范围、单双周、星期、起止节次、教师、教室
- 节次范围必须在 1 到 60 之间，否则报错

没有发现 Apache POI、XSSF、HSSF。它并不直接解析 `.xlsx`，只解析 CSV 文本。

### HTML 与教务系统导入

APK 内包含 Jsoup，HTML 文件导入与登录抓取流程使用它，相关代码集中在 `schedule_import.login_school.*` 与 `WebViewLoginFragment`。

教务系统解析通过 `ParserProxy` 与基础解析接口 `schedule_parser.parser.base.OooOO0O` 完成。`ParserProxy` 暴露：

- 课程列表
- 时间表
- 课表名
- 节节数
- 开始日期
- 最大周数

解析器内有 160 个以上按高校区分的 parser 类，目录为 `schedule_parser/parser/`。

### 中间数据模型

解析器输出统一中间模型：

`schedule_parser.bean.base.Course`：

- `name`
- `day`
- `room`
- `teacher`
- `startNode`
- `endNode`
- `startWeek`
- `endWeek`
- `type`
- `credit`
- `note`
- `startTime`
- `endTime`

`schedule_parser.bean.base.TimeDetail`：

- `node`
- `startTime`
- `endTime`

`schedule_parser.bean.base.TimeTable`：

- `name`
- `timeList`

### 备份与分享

备份导入与分享导入走应用内既有数据结构，未发现独立公开标准格式。具体导出实现围绕 App 数据库与配置恢复展开。

## 6. 数据存储

Room 数据库为 `AppDatabase`，版本 14，库名 `wakeup`，API 24+ 存放在 `data/databases/db/wakeup`。

### TableBean

- `id`
- `schoolName`
- `tid`
- `timeTable`
- `type`
- `updateTime`

含义：一个课表。`timeTable` 指向节次时间表。

### CourseBaseBean

- `id`
- `courseName`
- `color`
- `tableId`
- `note`
- `credit`

主键：`(id, tableId)`。外键指向 `TableBean`。

### CourseDetailBean

- `id`
- `day`
- `room`
- `teacher`
- `startNode`
- `step`
- `startWeek`
- `endWeek`
- `type`
- `tableId`
- `level`
- `ownTime`
- `startTime`
- `endTime`

主键：`(day, startNode, startWeek, type, tableId, id, ownTime, startTime)`。外键指向 `CourseBaseBean`。

含义：一条具体排课。`step` 表示占几节，`level` 区分同时间多课。

### TimeTableBean

- `id`
- `name`

### TimeDetailBean

- `node`
- `startTime`
- `endTime`
- `timeTable`

主键：`(node, timeTable)`。

### 其他

- `AppWidgetBean`：Widget 实例配置
- `ManualCalendarBean`、`FinishCalendarBean`：日历集成数据，非核心课表

## 7. 移植建议

目标：在纯 Java、零第三方依赖的 Android App 中重写核心课表功能。

### 建议照搬

- 两层课程模型：课程主表与排课明细分离，天然支持一门课多种排法与同时间多课
- 一页一教学周的 ViewPager 结构，页码即周次
- 左侧时间轴加右侧课程网格的布局，列数由周六日开关决定
- 课程颜色兜底色板，按课程 id 取模
- `startDate + maxWeek` 计算当前周
- 详情弹窗的信息结构：名称、周次、节次、时间、教师、教室、备注、学分
- CSV 导入的列语义与校验规则，可用纯 Java 字符串解析重写
- 周课表 Widget 的“RemoteViewsService + 本地布局转位图”方案，零依赖下也可实现

### 需要改写

- Jsoup HTML 解析需要改写为手写的轻量 HTML 文本解析，或第一阶段仅支持文件与 CSV，不做教务系统登录抓取
- Room 需改为原生 `SQLiteOpenHelper` 或 JSON 持久化；建议 SQLite，字段结构可直接沿用上述实体
- `.xlsx` 直接解析在零依赖下成本高，建议明确只支持 CSV，并在 UI 中写清“导出为 CSV”
- 高校专用 parser 有 160 个以上，全部手写不现实，建议先做通用 CSV 与手工录入

### 与每日打卡的数据映射

可映射为：

- `TableBean` → 一个学期课表
- `CourseBaseBean` → 课程主数据
- `CourseDetailBean` → 每周重复的上课事件
- `TimeDetailBean` → 节次时间表
- `TableConfig` → 课表显示偏好

提醒可复用打卡任务现有的 `AlarmManager + BroadcastReceiver` 链路，把“当天需要、未完成、时间未过”换成“当天有课、未提醒、时间未过”。

## 8. 结论

WakeUp 的核心课表结构清晰：主课表页为 ViewPager 周分页，数据层为 Table / CourseBase / CourseDetail / TimeTable / TimeDetail 五层模型，样式集中在每个课表独立的 `TableConfig`。导入功能庞大，真正的通用格式是 CSV；HTML 与教务系统导入依赖 Jsoup 和大量高校专用 parser。对纯 Java 零依赖移植而言，最稳妥的路径是先实现手动建课、CSV 导入、周视图、时间表配置与一个今日课程 Widget，再评估是否值得手写 HTML 解析。
