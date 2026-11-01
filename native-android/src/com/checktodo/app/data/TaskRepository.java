package com.checktodo.app.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.checktodo.app.domain.AiVerifyRecord;
import com.checktodo.app.domain.Task;
import com.checktodo.app.domain.TaskLogic;
import com.checktodo.app.domain.Todo;
import com.checktodo.app.domain.VerifyMode;
import com.checktodo.app.notification.ReminderScheduler;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class TaskRepository {
    private static final String PREFS_NAME = "check-todo";
    private static final String STORAGE_KEY = "check-todo.v1";

    private final SharedPreferences prefs;
    private final Context context;
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private final Map<String, LinkedHashSet<String>> records = new LinkedHashMap<>();
    private final Map<String, Todo> todos = new LinkedHashMap<>();
    /** AI 判定历史：taskId → 判定记录（含降级/申诉，供展示与审计）。 */
    private final Map<String, List<AiVerifyRecord>> aiRecords = new LinkedHashMap<>();
    /** AI 判定历史（待办）：todoId → 判定记录，与任务侧同构、独立存储。 */
    private final Map<String, List<AiVerifyRecord>> aiRecordsTodo = new LinkedHashMap<>();

    public TaskRepository(Context context) {
        this.context = context.getApplicationContext();
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        load();
    }

    public List<Task> getTasks() {
        return Collections.unmodifiableList(new ArrayList<>(tasks.values()));
    }

    public Set<String> getCompletedDates(String taskId) {
        LinkedHashSet<String> set = records.get(taskId);
        return set == null ? Collections.emptySet() : Collections.unmodifiableSet(set);
    }

    public List<Todo> getTodos() {
        return Collections.unmodifiableList(new ArrayList<>(todos.values()));
    }

    /** 该任务的 AI 判定历史（时间正序写入的原始顺序）；无记录返回空列表。 */
    public List<AiVerifyRecord> getAiRecords(String taskId) {
        List<AiVerifyRecord> list = aiRecords.get(taskId);
        return list == null
                ? Collections.<AiVerifyRecord>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(list));
    }

    /** 该任务今天是否已打过「待复核」标记（供列表徽标展示）。 */
    public boolean hasReviewToday(String taskId, Date now) {
        String today = TaskLogic.dateKey(now);
        for (AiVerifyRecord record : getAiRecords(taskId)) {
            if (today.equals(record.dateKey) && record.needsReview()) return true;
        }
        return false;
    }

    public void reload() {
        tasks.clear();
        records.clear();
        todos.clear();
        aiRecords.clear();
        load();
    }

    public Task saveTask(String id, String name, boolean[] weekdays, String startTime, String endTime) {
        return saveTask(id, name, weekdays, startTime, endTime, Task.DEFAULT_REQUIRED_MINUTES);
    }

    /**
     * 保存任务。requiredMinutes 为「完成所需时间」，用于提醒时刻计算；老数据缺字段时按 60 兜底。
     */
    public Task saveTask(String id, String name, boolean[] weekdays, String startTime, String endTime,
                         int requiredMinutes) {
        String taskId = id == null || id.isEmpty() ? UUID.randomUUID().toString() : id;
        Task old = tasks.get(taskId);
        long createdAt = old == null ? System.currentTimeMillis() : old.createdAt;
        // 编辑任务时保留已存在的请假记录，避免改名称/时间把请假清空。
        // AI 判定配置同理保留——编辑基础信息不得把拍照判定配置抹掉。
        Task task = new Task(taskId, name.trim(), weekdays, startTime, endTime,
                old == null ? Collections.emptySet() : old.leaveDates, createdAt,
                requiredMinutes > 0 ? requiredMinutes : Task.DEFAULT_REQUIRED_MINUTES,
                old == null ? VerifyMode.NONE : old.verifyMode,
                old == null ? "" : old.aiFeature,
                old == null ? "" : old.aiReferencePhoto);
        tasks.put(taskId, task);
        records.putIfAbsent(taskId, new LinkedHashSet<>());
        persist();
        return task;
    }

    /**
     * 设置 AI 判定配置（拍照判定开关 + 特征描述 + 标准照路径）。
     *
     * <p>关闭（{@link VerifyMode#NONE}）时清空特征描述，但保留标准照路径字段为空串，
     * 使任务数据不留悬空引用；打开时要求已拍标准照，否则记为配置不完整
     * （打卡流程按降级处理，不阻塞）。</p>
     */
    public Task saveVerifyConfig(String taskId, VerifyMode mode, String feature, String referencePhoto) {
        Task old = tasks.get(taskId);
        if (old == null) return null;
        VerifyMode next = mode == null ? VerifyMode.NONE : mode;
        String photo = next == VerifyMode.AI_PHOTO && referencePhoto != null ? referencePhoto.trim() : "";
        Task task = old.withVerify(next, next == VerifyMode.AI_PHOTO ? feature : "", photo);
        tasks.put(taskId, task);
        persist();
        return task;
    }

    /**
     * AI 判定打卡：写入判定历史，并按结果决定是否完成打卡。
     *
     * <p>与 {@link #checkIn(String, Date)} 的区别是「判定结果与打卡动作绑定为一次原子
     * 持久化」——审计上必须能回答「这次打卡 AI 说了什么」。fail 且未申诉时不写完成日期，
     * 但判定记录仍然留下（重复判定的历史样本只取已完成打卡的记录，见
     * {@code VerificationPolicy.dedupHashes}）。</p>
     *
     * @param complete 是否完成打卡（由 {@code VerificationPolicy.Outcome#complete} 决定）
     * @return true = 状态发生了变更并已持久化
     */
    public boolean checkInWithVerdict(String taskId, AiVerifyRecord record, boolean complete) {
        LinkedHashSet<String> dates = records.get(taskId);
        if (dates == null) return false;
        boolean changed = false;
        if (record != null) {
            List<AiVerifyRecord> list = aiRecords.get(taskId);
            if (list == null) {
                list = new ArrayList<>();
                aiRecords.put(taskId, list);
            }
            list.add(record);
            changed = true;
        }
        if (complete && record != null) {
            // 判定记录里的 dateKey 即本次打卡日期，与 records 用同一格式（yyyy-MM-dd）。
            changed |= dates.add(record.dateKey);
        }
        if (!changed) return false;
        persist();
        return true;
    }

    /**
     * 申诉覆盖：AI 判 fail 时用户坚持完成打卡 → 记录标记 appealed 并补写完成日期。
     * 覆盖的是「该任务最近一条未完成的 fail 记录」，保持不可变纪律（替换而非修改）。
     *
     * @return true = 找到可申诉的记录并已持久化
     */
    public boolean appealLastVerdict(String taskId, String dateKey) {
        List<AiVerifyRecord> list = aiRecords.get(taskId);
        if (list == null || list.isEmpty()) return false;
        LinkedHashSet<String> dates = records.get(taskId);
        if (dates == null) return false;
        for (int i = list.size() - 1; i >= 0; i--) {
            AiVerifyRecord record = list.get(i);
            if (record.completed || !dateKey.equals(record.dateKey)) continue;
            list.set(i, record.withAppeal(true));
            dates.add(record.dateKey);
            persist();
            return true;
        }
        return false;
    }

    /** 待办的 AI 判定历史（供 L1 去重与待复核展示）。 */
    public List<AiVerifyRecord> getTodoAiRecords(String todoId) {
        List<AiVerifyRecord> list = aiRecordsTodo.get(todoId);
        return list == null
                ? Collections.<AiVerifyRecord>emptyList()
                : Collections.unmodifiableList(list);
    }

    /** 待办最近一条判定记录是否需要复核（已完成但 uncertain/降级/被申诉）。 */
    public boolean latestTodoRecordNeedsReview(String todoId) {
        List<AiVerifyRecord> list = aiRecordsTodo.get(todoId);
        return list != null && !list.isEmpty() && list.get(list.size() - 1).needsReview();
    }

    /**
     * 待办 AI 判定结果落地：按 complete 决定是否切换完成态，
     * 并把判定记录写入待办历史。complete=false 时不改完成态（fail 可重拍）。
     */
    public void todoCheckInWithVerdict(String todoId, AiVerifyRecord record, boolean complete) {
        if (complete) setTodoCompleted(todoId, true);
        if (record != null) {
            List<AiVerifyRecord> list = aiRecordsTodo.get(todoId);
            if (list == null) {
                list = new ArrayList<>();
                aiRecordsTodo.put(todoId, list);
            }
            list.add(record);
        }
        persist();
    }

    /**
     * 待办申诉覆盖：AI 判 fail 后用户坚持完成 → 最近一条未完成 fail 记录标记 appealed，
     * 并把待办切到完成态。覆盖口径与 appealLastVerdict 一致。
     */
    public boolean appealTodoLastVerdict(String todoId) {
        List<AiVerifyRecord> list = aiRecordsTodo.get(todoId);
        if (list == null || list.isEmpty()) return false;
        for (int i = list.size() - 1; i >= 0; i--) {
            AiVerifyRecord record = list.get(i);
            if (record.completed) continue;
            list.set(i, record.withAppeal(true));
            setTodoCompleted(todoId, true);
            persist();
            return true;
        }
        return false;
    }

    /**
     * 设置或取消某个日期的请假。日期键为 yyyy-MM-dd，与打卡记录保持同一格式。
     */
    public boolean setLeave(String taskId, String dateKey, boolean leave) {
        Task old = tasks.get(taskId);
        if (old == null || dateKey == null || dateKey.isEmpty()) return false;
        LinkedHashSet<String> next = new LinkedHashSet<>(old.leaveDates);
        boolean changed = leave ? next.add(dateKey) : next.remove(dateKey);
        if (!changed) return false;
        tasks.put(taskId, old.withLeaveDates(next));
        persist();
        return true;
    }

    public void deleteTask(String taskId) {
        tasks.remove(taskId);
        records.remove(taskId);
        aiRecords.remove(taskId);
        persist();
    }

    public boolean checkIn(String taskId, Date now) {
        LinkedHashSet<String> dates = records.get(taskId);
        if (dates == null) return false;
        String today = TaskLogic.dateKey(now);
        if (!dates.add(today)) return false;
        persist();
        return true;
    }

    /**
     * 取消今天打卡（防误点）。仅移除「今天」这一条记录，历史日期不受影响；
     * 移除后状态按当前时间重新判定（回到可打卡或已错过）。
     */
    public boolean cancelCheckIn(String taskId, Date now) {
        LinkedHashSet<String> dates = records.get(taskId);
        if (dates == null) return false;
        if (!dates.remove(TaskLogic.dateKey(now))) return false;
        persist();
        return true;
    }

    public Todo saveTodo(String id, String title, long startTime, long endTime,
                         int durationMinutes, boolean completed) {
        return saveTodo(id, title, startTime, endTime, durationMinutes, completed,
                null, null, null);
    }

    /** 含 AI 判定配置的保存（verifyMode null → 回落 NONE，兼容老调用）。 */
    public Todo saveTodo(String id, String title, long startTime, long endTime,
                         int durationMinutes, boolean completed,
                         VerifyMode verifyMode, String aiFeature, String aiReferencePhoto) {
        String todoId = id == null || id.isEmpty() ? UUID.randomUUID().toString() : id;
        Todo old = todos.get(todoId);
        long createdAt = old == null ? System.currentTimeMillis() : old.createdAt;
        Todo todo = new Todo(todoId, title.trim(), startTime, endTime,
                durationMinutes, completed, createdAt,
                verifyMode, aiFeature, aiReferencePhoto);
        todos.put(todoId, todo);
        persist();
        return todo;
    }

    public void deleteTodo(String todoId) {
        todos.remove(todoId);
        persist();
    }

    public boolean setTodoCompleted(String todoId, boolean completed) {
        Todo old = todos.get(todoId);
        if (old == null || old.completed == completed) return false;
        todos.put(todoId, old.withCompletion(completed));
        persist();
        return true;
    }

    private void load() {
        String raw = prefs.getString(STORAGE_KEY, null);
        if (raw == null) return;
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray taskArray = root.optJSONArray("tasks");
            if (taskArray != null) {
                for (int i = 0; i < taskArray.length(); i++) {
                    Task task = taskFromJson(taskArray.optJSONObject(i));
                    if (task == null) continue;
                    tasks.put(task.id, task);
                    records.putIfAbsent(task.id, new LinkedHashSet<>());
                }
            }

            JSONObject recordObject = root.optJSONObject("records");
            if (recordObject != null) {
                for (String taskId : records.keySet()) {
                    JSONArray dates = recordObject.optJSONArray(taskId);
                    if (dates == null) continue;
                    LinkedHashSet<String> set = records.get(taskId);
                    for (int i = 0; i < dates.length(); i++) set.add(dates.getString(i));
                }
            }

            JSONArray todoArray = root.optJSONArray("todos");
            if (todoArray != null) {
                for (int i = 0; i < todoArray.length(); i++) {
                    Todo todo = todoFromJson(todoArray.optJSONObject(i));
                    if (todo != null) todos.put(todo.id, todo);
                }
            }

            // AI 判定历史（待办）：新增节点，老数据没有该节点时按空处理。
            JSONArray todoAiArray = root.optJSONArray("aiRecordsTodo");
            if (todoAiArray != null) {
                for (int i = 0; i < todoAiArray.length(); i++) {
                    JSONObject entry = todoAiArray.optJSONObject(i);
                    if (entry == null) continue;
                    String todoId = entry.optString("todoId");
                    JSONArray list = entry.optJSONArray("records");
                    if (todoId.isEmpty() || list == null) continue;
                    List<AiVerifyRecord> parsed = new ArrayList<>();
                    for (int j = 0; j < list.length(); j++) {
                        AiVerifyRecord record = aiRecordFromJson(list.optJSONObject(j));
                        if (record != null) parsed.add(record);
                    }
                    if (!parsed.isEmpty()) aiRecordsTodo.put(todoId, parsed);
                }
            }

            // AI 判定历史：新增节点，老数据没有该节点时按空处理（向后兼容）。
            JSONObject aiObject = root.optJSONObject("aiRecords");
            if (aiObject != null) {
                for (String taskId : records.keySet()) {
                    JSONArray list = aiObject.optJSONArray(taskId);
                    if (list == null) continue;
                    List<AiVerifyRecord> parsed = new ArrayList<>();
                    for (int i = 0; i < list.length(); i++) {
                        AiVerifyRecord record = aiRecordFromJson(list.optJSONObject(i));
                        if (record != null) parsed.add(record);
                    }
                    if (!parsed.isEmpty()) aiRecords.put(taskId, parsed);
                }
            }
        } catch (Exception ignored) {
            tasks.clear();
            records.clear();
            todos.clear();
            aiRecords.clear();
            aiRecordsTodo.clear();
        }
    }

    private void persist() {
        try {
            JSONObject root = new JSONObject();
            JSONArray taskArray = new JSONArray();
            for (Task task : tasks.values()) taskArray.put(taskToJson(task));

            JSONObject recordObject = new JSONObject();
            for (Map.Entry<String, LinkedHashSet<String>> entry : records.entrySet()) {
                recordObject.put(entry.getKey(), new JSONArray(entry.getValue()));
            }

            JSONArray todoArray = new JSONArray();
            for (Todo todo : todos.values()) todoArray.put(todoToJson(todo));

            // AI 判定历史（待办）：只有存在记录时才写节点。
            if (!aiRecordsTodo.isEmpty()) {
                JSONArray todoAiArray = new JSONArray();
                for (Map.Entry<String, List<AiVerifyRecord>> entry : aiRecordsTodo.entrySet()) {
                    JSONArray list = new JSONArray();
                    for (AiVerifyRecord record : entry.getValue()) list.put(aiRecordToJson(record));
                    JSONObject item = new JSONObject();
                    item.put("todoId", entry.getKey());
                    item.put("records", list);
                    todoAiArray.put(item);
                }
                root.put("aiRecordsTodo", todoAiArray);
            }

            // AI 判定历史：只有存在记录时才写节点，避免老版本数据被塞进空壳。
            if (!aiRecords.isEmpty()) {
                JSONObject aiObject = new JSONObject();
                for (Map.Entry<String, List<AiVerifyRecord>> entry : aiRecords.entrySet()) {
                    JSONArray list = new JSONArray();
                    for (AiVerifyRecord record : entry.getValue()) list.put(aiRecordToJson(record));
                    aiObject.put(entry.getKey(), list);
                }
                root.put("aiRecords", aiObject);
            }

            root.put("tasks", taskArray);
            root.put("records", recordObject);
            root.put("todos", todoArray);
            prefs.edit().putString(STORAGE_KEY, root.toString()).apply();
            WidgetRefresh.notifyDataChanged(context);
            // 任何数据变更（增删改、打卡、取消打卡、请假、完成待办）后重算全部提醒。
            ReminderScheduler.rescheduleAll(context);
        } catch (Exception ignored) {
            // Persistence failures leave the current in-memory state usable.
        }
    }

    private static Task taskFromJson(JSONObject object) {
        if (object == null) return null;
        JSONArray weekdays = object.optJSONArray("weekdays");
        boolean[] days = new boolean[7];
        if (weekdays == null) return null;
        for (int i = 0; i < 7 && i < weekdays.length(); i++) days[i] = weekdays.optBoolean(i);
        // leaveDates 为可选字段：老数据没有该数组时按空集合加载，保持向后兼容。
        Set<String> leaveDates = new LinkedHashSet<>();
        JSONArray leaveArray = object.optJSONArray("leaveDates");
        if (leaveArray != null) {
            for (int i = 0; i < leaveArray.length(); i++) {
                String key = leaveArray.optString(i, "");
                if (!key.isEmpty()) leaveDates.add(key);
            }
        }
        return new Task(object.optString("id"), object.optString("name"), days,
                object.optString("startTime"), object.optString("endTime"), leaveDates,
                object.optLong("createdAt"),
                object.optInt("requiredMinutes", Task.DEFAULT_REQUIRED_MINUTES),
                // verifyMode 为新增字段：老数据没有该键 → null → VerifyMode.fromString 回落 NONE。
                VerifyMode.fromString(object.optString("verifyMode", null)),
                object.optString("aiFeature", ""),
                object.optString("aiReferencePhoto", ""));
    }

    private static JSONObject taskToJson(Task task) {
        JSONObject object = new JSONObject();
        JSONArray weekdays = new JSONArray();
        for (boolean day : task.weekdays) weekdays.put(day);
        try {
            object.put("id", task.id);
            object.put("name", task.name);
            object.put("weekdays", weekdays);
            object.put("startTime", task.startTime);
            object.put("endTime", task.endTime);
            // 只有存在请假记录时才写 leaveDates，避免老版本字段噪声。
            if (!task.leaveDates.isEmpty()) {
                object.put("leaveDates", new JSONArray(task.leaveDates));
            }
            object.put("createdAt", task.createdAt);
            // requiredMinutes 始终写入：老数据加载后重新保存即补齐该字段。
            object.put("requiredMinutes", task.requiredMinutes);
            // AI 判定配置：仅 AI_PHOTO 任务写入，普通任务数据保持与老版本逐字节一致。
            if (task.verifyMode != VerifyMode.NONE) {
                object.put("verifyMode", task.verifyMode.value());
                object.put("aiFeature", task.aiFeature);
                object.put("aiReferencePhoto", task.aiReferencePhoto);
            }
        } catch (Exception ignored) {
            // JSONObject.put with supported values cannot fail.
        }
        return object;
    }

    /**
     * AiVerifyRecord 的 Map 表示（值全是标量）→ JSON 对象。
     * 域层保持零依赖，转换在这里做。
     */
    private static JSONObject aiRecordToJson(AiVerifyRecord record) {
        JSONObject object = new JSONObject();
        try {
            for (Map.Entry<String, Object> entry : record.toMap().entrySet()) {
                object.put(entry.getKey(), entry.getValue());
            }
        } catch (Exception ignored) {
            // JSONObject.put with supported values cannot fail.
        }
        return object;
    }

    /** JSON 对象 → AiVerifyRecord；缺字段按域层的保守默认恢复，脏对象返回 null。 */
    private static AiVerifyRecord aiRecordFromJson(JSONObject object) {
        if (object == null) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            map.put(key, JSONObject.NULL.equals(object.opt(key)) ? null : object.opt(key));
        }
        return AiVerifyRecord.fromMap(map);
    }

    private static Todo todoFromJson(JSONObject object) {
        if (object == null) return null;
        String id = object.optString("id");
        String title = object.optString("title");
        if (id == null || id.isEmpty() || title == null || title.trim().isEmpty()) return null;
        long start = object.optLong("startTime", Long.MIN_VALUE);
        long end = object.optLong("endTime", Long.MIN_VALUE);
        if (start == Long.MIN_VALUE || end == Long.MIN_VALUE || end <= start) return null;
        int duration = object.optInt("durationMinutes", -1);
        if (duration < 0) return null;
        return new Todo(id, title.trim(), start, end, duration,
                object.optBoolean("completed"), object.optLong("createdAt", start),
                VerifyMode.fromString(object.optString("verifyMode", null)),
                object.optString("aiFeature", null), object.optString("aiReferencePhoto", null));
    }

    private static JSONObject todoToJson(Todo todo) {
        JSONObject object = new JSONObject();
        try {
            object.put("id", todo.id);
            object.put("title", todo.title);
            object.put("startTime", todo.startTime);
            object.put("endTime", todo.endTime);
            object.put("durationMinutes", todo.durationMinutes);
            object.put("completed", todo.completed);
            object.put("createdAt", todo.createdAt);
            object.put("verifyMode", todo.verifyMode.value());
            object.put("aiFeature", todo.aiFeature);
            object.put("aiReferencePhoto", todo.aiReferencePhoto);
        } catch (Exception ignored) {
            // JSONObject.put with supported values cannot fail.
        }
        return object;
    }
}
