package com.checktodo.app.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.checktodo.app.ai.GlmHttp;
import com.checktodo.app.domain.AiVerifyConfig;
import com.checktodo.app.domain.Task;
import com.checktodo.app.domain.Todo;
import com.checktodo.app.domain.WeeklyReport;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 周报的持久化与数据装配薄壳（独立键 {@code weekly-report.v1}）。
 *
 * <p><b>为什么独立键</b>：周报是生成结果，不是任务数据——混进 {@code check-todo.v1}
 * 会被备份/导入导出链路带走，也会让主数据文件越长越大。这里一个 JSONArray，
 * 只保留最近 {@link WeeklyReport#HISTORY_LIMIT} 条（约两个月）。</p>
 *
 * <p><b>线程纪律</b>：{@link #load}/{@link #append} 只做 prefs 读写（快）；
 * {@link #generate} 会打网络（最多 20s），<b>只能在后台线程调用</b>——
 * ReminderReceiver 走 goAsync() + 线程，MainActivity 走 GlmHttp 的执行器。</p>
 *
 * <p><b>失败静默</b>：生成失败返回 null，调用方只 Log.w，不弹错误框、不发通知。
 * 脏持久化数据同样回落空历史，绝不让周报把 App 弄崩。</p>
 *
 * <p><b>日志口径</b>：只记周区间、状态与正文字数；<b>api key 与周报正文都不进日志</b>。</p>
 */
public final class WeeklyReportStore {

    /** 独立持久化键（与 check-todo.v1 / ai-verify.v1 并列）。 */
    public static final String STORAGE_KEY = "weekly-report.v1";
    private static final String PREFS_NAME = "check-todo";
    private static final String TAG = "CheckTodoWeekly";
    /** 周报通知的固定 id（与提醒通知的 requestCode 空间不重叠）。 */
    public static final int NOTIFICATION_ID = 910001;

    private WeeklyReportStore() {
    }

    /** 一条已生成的周报（不可变）。 */
    public static final class Entry {
        public final String weekStart;
        public final String weekEnd;
        public final long createdAt;
        public final String body;
        public final String model;

        public Entry(String weekStart, String weekEnd, long createdAt, String body, String model) {
            this.weekStart = weekStart == null ? "" : weekStart;
            this.weekEnd = weekEnd == null ? "" : weekEnd;
            this.createdAt = createdAt;
            this.body = body == null ? "" : body;
            this.model = model == null ? "" : model;
        }

        /** 列表展示标题：2026-10-19 ~ 2026-10-25。 */
        public String weekLabel() {
            return weekStart + " ~ " + weekEnd;
        }

        /** 全文对话框的标题：区间 + 生成时刻。 */
        public String titleWithTime() {
            return weekLabel() + "（生成于 " + WeeklyReport.formatMillis(createdAt) + "）";
        }

        /** 摘要（列表第二行）：正文首行，截断到 60 字。 */
        public String excerpt() {
            String text = body.trim().replace('\n', ' ');
            return text.length() > 60 ? text.substring(0, 60) + "…" : text;
        }

        boolean isValid() {
            return !weekStart.isEmpty() && !weekEnd.isEmpty() && !body.trim().isEmpty();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 读取历史周报（最新在前）。脏数据 → 空列表（不抛异常）。
     */
    public static List<Entry> load(Context context) {
        String raw = prefs(context).getString(STORAGE_KEY, null);
        if (raw == null || raw.trim().isEmpty()) return Collections.emptyList();
        try {
            JSONArray array = new JSONArray(raw);
            List<Entry> entries = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                Entry entry = new Entry(object.optString("weekStart"), object.optString("weekEnd"),
                        object.optLong("createdAt"), object.optString("body"),
                        object.optString("model"));
                if (entry.isValid()) entries.add(entry);
            }
            return entries;
        } catch (Exception exception) {
            // 历史被写坏（升级中断 / 手工改过）→ 丢弃历史，不影响主功能。
            Log.w(TAG, "history unreadable, reset to empty: " + exception.getClass().getSimpleName());
            return Collections.emptyList();
        }
    }

    /**
     * 追加一条周报并裁剪到最近 {@link WeeklyReport#HISTORY_LIMIT} 条（最新在前）。
     * 同一周区间已存在 → 覆盖（手动重复生成不产生两条）。
     */
    public static List<Entry> append(Context context, Entry entry) {
        if (entry == null || !entry.isValid()) return load(context);
        List<Entry> next = new ArrayList<>();
        next.add(entry);
        for (Entry existing : load(context)) {
            if (existing.weekStart.equals(entry.weekStart) && existing.weekEnd.equals(entry.weekEnd)) {
                continue; // 同周覆盖：只保留最新一份
            }
            next.add(existing);
        }
        if (next.size() > WeeklyReport.HISTORY_LIMIT) {
            next = new ArrayList<>(next.subList(0, WeeklyReport.HISTORY_LIMIT));
        }
        prefs(context).edit().putString(STORAGE_KEY, toJson(next)).apply();
        Log.i(TAG, "saved weekly report " + entry.weekLabel() + ", history=" + next.size());
        return next;
    }

    /** 清空历史（设置里「清除」用；也便于测试）。 */
    public static void clear(Context context) {
        prefs(context).edit().remove(STORAGE_KEY).apply();
    }

    /** 历史 → JSONArray 字符串。 */
    static String toJson(List<Entry> entries) {
        JSONArray array = new JSONArray();
        if (entries == null) return array.toString();
        for (Entry entry : entries) {
            if (entry == null) continue;
            try {
                JSONObject object = new JSONObject();
                object.put("weekStart", entry.weekStart);
                object.put("weekEnd", entry.weekEnd);
                object.put("createdAt", entry.createdAt);
                object.put("body", entry.body);
                object.put("model", entry.model);
                array.put(object);
            } catch (Exception exception) {
                Log.w(TAG, "skip entry: " + exception.getClass().getSimpleName());
            }
        }
        return array.toString();
    }

    // ------------------------------------------------------------------
    // 数据装配 + 生成
    // ------------------------------------------------------------------

    /**
     * 从仓库装配某个周区间的统计数据（纯读取，不打网络）。
     *
     * @param asOf 参考时刻：上一周的周报传周末（整周已过完），本周手动生成传 new Date()
     */
    public static WeeklyReport.WeeklyReportData summarize(Context context,
                                                          WeeklyReport.Week week, Date asOf) {
        TaskRepository repository = new TaskRepository(context);
        List<Task> tasks = repository.getTasks();
        List<Todo> todos = repository.getTodos();
        Map<String, Set<String>> completed = new HashMap<>();
        for (Task task : tasks) {
            completed.put(task.id, repository.getCompletedDates(task.id));
        }
        WeeklyReport.Week safeWeek = week == null ? WeeklyReport.previousWeekOf(asOf) : week;
        return WeeklyReport.WeeklyReportData.summarize(tasks, completed, todos,
                safeWeek.startKey, safeWeek.endKey, asOf);
    }

    /**
     * 生成周报正文（<b>只能在后台线程调用</b>：内部同步打网络）。
     *
     * @return 模型正文；未配 key / 网络失败 / 内容为空 → null（调用方静默）
     */
    public static String generate(Context context, WeeklyReport.Week week, Date asOf) {
        try {
            WeeklyReport.WeeklyReportData data = summarize(context, week, asOf);
            String prompt = WeeklyReport.WeeklyReportPrompt.build(data);
            AiVerifyConfig config = com.checktodo.app.ai.AiVerifySettings.load(context);
            if (!config.isConfigured()) {
                Log.w(TAG, "skipped: API key not configured");
                return null;
            }
            String body = GlmHttp.postTextSync(config, prompt);
            if (body == null || body.trim().isEmpty()) {
                Log.w(TAG, "skipped: empty response for " + data.week.label());
                return null;
            }
            Log.i(TAG, "generated " + data.week.label() + ", bodyChars=" + body.length()
                    + ", tasks=" + data.tasks.size());
            return body;
        } catch (IOException exception) {
            Log.w(TAG, "generate failed: " + exception.getMessage());
            return null;
        } catch (Exception exception) {
            Log.w(TAG, "generate failed: " + exception.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 生成 + 落盘（后台线程）。成功返回已保存的 Entry，失败返回 null。
     */
    public static Entry generateAndStore(Context context, WeeklyReport.Week week, Date asOf) {
        WeeklyReport.Week safeWeek = week == null ? WeeklyReport.previousWeekOf(asOf) : week;
        String body = generate(context, safeWeek, asOf);
        if (body == null) return null;
        AiVerifyConfig config = com.checktodo.app.ai.AiVerifySettings.load(context);
        Entry saved = new Entry(safeWeek.startKey, safeWeek.endKey,
                System.currentTimeMillis(), body, config.effectiveModel());
        append(context, saved);
        return saved;
    }
}
