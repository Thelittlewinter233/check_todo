package com.checktodo.app.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.checktodo.app.domain.CourseBase;
import com.checktodo.app.domain.CourseDetail;
import com.checktodo.app.domain.TimeNode;
import com.checktodo.app.domain.TimeTableSettings;
import com.checktodo.app.domain.TimetableConfig;
import com.checktodo.app.domain.TimetableEditor;
import com.checktodo.app.domain.TimetableLogic;
import com.checktodo.app.domain.TimetableSheet;
import com.checktodo.app.domain.TimetableSheets;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Timetable storage: SharedPreferences + org.json, with multiple isolated sheets. */
public final class TimetableRepository {
    private static final String PREFS_NAME = "check-todo-timetable";
    private static final String STORAGE_KEY = "timetable.v2";
    private static final String LEGACY_STORAGE_KEY = "timetable.v1";

    private final SharedPreferences prefs;
    private final Context context;
    private TimetableSheets sheets;

    public TimetableRepository(Context context) {
        this.context = context.getApplicationContext();
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        load();
    }

    public TimetableConfig getConfig() {
        return current().config;
    }

    public List<TimeNode> getTimeNodes() {
        return current().timeNodes;
    }

    public List<CourseBase> getBases() {
        return current().bases;
    }

    public List<CourseDetail> getDetails() {
        return current().details;
    }

    public CourseBase findBase(String baseId) {
        if (baseId == null) return null;
        for (CourseBase base : current().bases) {
            if (base.id.equals(baseId)) return base;
        }
        return null;
    }

    public TimeNode findTimeNode(int node) {
        for (TimeNode timeNode : current().timeNodes) {
            if (timeNode.node == node) return timeNode;
        }
        return null;
    }

    public List<TimetableSheet> getTimetables() {
        return sheets.all();
    }

    public TimetableSheet getCurrentTimetable() {
        return current();
    }

    public boolean switchTimetable(String id) {
        if (!sheets.switchTo(id)) return false;
        persist();
        return true;
    }

    public TimetableSheet createTimetable(String name, boolean copyCourses) {
        TimetableSheet created = sheets.create(name, copyCourses);
        persist();
        return created;
    }

    public boolean renameTimetable(String id, String name) {
        if (!sheets.rename(id, name)) return false;
        persist();
        return true;
    }

    public boolean deleteTimetable(String id) {
        if (!sheets.delete(id)) return false;
        persist();
        return true;
    }

    public void reload() {
        load();
    }

    /**
     * 保存选中课表的配置，并把时间表按新节数规整。
     *
     * <p>规整只做「补默认/裁尾部」：{@link TimeTableSettings#resize} 会保留已填值，
     * 所以与 {@link #saveTimeNodes} 配合时（先存时间表再存配置）不会丢掉用户刚填的时间。</p>
     */
    public void saveConfig(TimetableConfig next) {
        TimetableSheet sheet = current();
        TimetableConfig safeConfig = safeConfig(next, sheet.config);
        List<TimeNode> resized = TimeTableSettings.resize(sheet.timeNodes, safeConfig.nodes);
        updateCurrent(new TimetableSheet(sheet.id, sheet.name, safeConfig, resized,
                sheet.bases, sheet.details));
    }

    /**
     * 保存自定义节次时间表。
     *
     * <p><b>2026-10-25 回归修复：</b>原实现用 {@code sheet.config.nodes}（库里的<b>旧</b>节数）
     * 校验传入的 nodes。用户在设置里把「每天节数」从 13 改成 15 后，草稿有 15 节，
     * 这里按 13 节校验 → 直接返回 false，界面报「保存失败，请检查各节起止时间」，
     * 用户完全无法保存（且以为是自己时间填错了）。</p>
     *
     * <p>修复：改用 <b>nodes 自身的节数</b>校验（调用方已按输入框/编辑器的目标节数规整过），
     * 并在校验通过后把节数一并写回 config，保证「草稿」与「库」始终同一节数。</p>
     */
    public boolean saveTimeNodes(List<TimeNode> nodes) {
        TimetableSheet sheet = current();
        if (nodes == null || nodes.isEmpty()) return false;
        int targetCount = Math.max(1, Math.min(TimeTableSettings.MAX_NODES, nodes.size()));
        if (!TimeTableSettings.validateForSave(nodes, targetCount).isEmpty()) return false;
        List<TimeNode> resized = TimeTableSettings.resize(nodes, targetCount);
        // 节数以本次保存为准：改过「每天节数」时必须同步，否则下次保存/渲染又会错位。
        TimetableConfig config = sheet.config;
        if (config == null || config.nodes != targetCount) {
            config = new TimetableConfig(config == null ? "" : config.startDate,
                    config == null ? 20 : config.maxWeek, targetCount,
                    config != null && config.showSat, config != null && config.showSun,
                    config != null && config.showOtherWeekCourse);
        }
        updateCurrent(new TimetableSheet(sheet.id, sheet.name, config, resized,
                sheet.bases, sheet.details));
        return true;
    }

    /** Restores the default time table for the selected sheet. */
    public void resetTimeNodes() {
        TimetableSheet sheet = current();
        List<TimeNode> resized = TimeTableSettings.resize(TimeTableSettings.defaultTable(),
                sheet.config.nodes);
        updateCurrent(new TimetableSheet(sheet.id, sheet.name, sheet.config, resized,
                sheet.bases, sheet.details));
    }

    /**
     * 保存一门课程（新增 / 编辑 / 删除排课），其余课程与排课原样保留。
     *
     * <p><b>2026-10-26 回归修复：</b>原实现用「只含本次这一条」的新列表整体替换 details，
     * 教务导入后手动新增一门课会把导入课程的排课全部清掉（bases 还在但界面不渲染），
     * 且新课 baseId 由本方法重新生成、详情仍指向空 baseId。合并规则见
     * {@link TimetableEditor#saveCourse}。</p>
     */
    public CourseBase saveCourse(CourseBase base, CourseDetail detail) {
        TimetableSheet sheet = current();
        String baseId = base.id == null || base.id.isEmpty() ? UUID.randomUUID().toString() : base.id;
        TimetableSheet next = TimetableEditor.saveCourse(sheet, base, detail, baseId,
                new ArrayList<String>());
        updateCurrent(next);
        for (CourseBase item : next.bases) {
            if (item.id.equals(baseId)) return item;
        }
        return base;
    }

    public boolean deleteCourse(String baseId) {
        TimetableSheet sheet = current();
        if (baseId == null || findBase(baseId) == null) return false;
        List<CourseBase> nextBases = new ArrayList<>();
        List<CourseDetail> nextDetails = new ArrayList<>();
        for (CourseBase base : sheet.bases) {
            if (!base.id.equals(baseId)) nextBases.add(base);
        }
        for (CourseDetail detail : sheet.details) {
            if (!detail.baseId.equals(baseId)) nextDetails.add(detail);
        }
        updateCurrent(new TimetableSheet(sheet.id, sheet.name, sheet.config, sheet.timeNodes,
                nextBases, nextDetails));
        return true;
    }

    public void importCourses(List<CourseBase> importedBases, List<CourseDetail> importedDetails) {
        TimetableSheet sheet = current();
        TimetableSheet next = TimetableEditor.importCourses(sheet, importedBases, importedDetails);
        if (next != sheet) updateCurrent(next);
    }

    /** Color 1..9 is stable in storage so UUID generation cannot shift it after restart. */
    private static int stableColor(String id, int requested) {
        if (requested >= 1 && requested <= 9) return requested;
        return Math.abs(id == null ? 0 : id.hashCode()) % 9 + 1;
    }

    private TimetableSheet current() {
        TimetableSheet sheet = sheets.current();
        return sheet == null ? sheets.all().get(0) : sheet;
    }

    private void updateCurrent(TimetableSheet next) {
        if (sheets.replace(next)) persist();
    }

    private void load() {
        String raw = prefs.getString(STORAGE_KEY, null);
        if (raw != null) {
            try {
                TimetableSheets loaded = fromV2(new JSONObject(raw));
                if (loaded != null && !loaded.all().isEmpty()) {
                    sheets = loaded;
                    return;
                }
            } catch (Exception ignored) {
            }
        }

        String legacy = prefs.getString(LEGACY_STORAGE_KEY, null);
        if (legacy != null) {
            try {
                JSONObject root = new JSONObject(legacy);
                List<TimeNode> nodes = new ArrayList<>();
                List<CourseBase> bases = new ArrayList<>();
                List<CourseDetail> details = new ArrayList<>();
                TimetableConfig config = root.optJSONObject("config") == null
                        ? defaultConfig() : configFromJson(root.optJSONObject("config"));
                JSONArray nodeArray = root.optJSONArray("timeNodes");
                if (nodeArray != null) {
                    for (int i = 0; i < nodeArray.length(); i++) {
                        TimeNode node = timeNodeFromJson(nodeArray.optJSONObject(i));
                        if (node != null) nodes.add(node);
                    }
                }
                JSONArray baseArray = root.optJSONArray("courses");
                if (baseArray != null) {
                    for (int i = 0; i < baseArray.length(); i++) {
                        CourseBase base = baseFromJson(baseArray.optJSONObject(i));
                        if (base != null) bases.add(base);
                    }
                }
                JSONArray detailArray = root.optJSONArray("details");
                if (detailArray != null) {
                    for (int i = 0; i < detailArray.length(); i++) {
                        CourseDetail detail = detailFromJson(detailArray.optJSONObject(i));
                        if (detail != null && containsBase(bases, detail.baseId)) details.add(detail);
                    }
                }
                TimetableSheet sheet = new TimetableSheet("legacy-timetable", "默认课表", config,
                        TimeTableSettings.resize(nodes, config.nodes), bases, details);
                sheets = TimetableSheets.fromLegacy(sheet);
                return;
            } catch (Exception ignored) {
            }
        }

        sheets = TimetableSheets.fromLegacy(defaultSheet());
    }

    private static boolean containsBase(List<CourseBase> bases, String baseId) {
        for (CourseBase base : bases) {
            if (base.id.equals(baseId)) return true;
        }
        return false;
    }

    private void persist() {
        try {
            JSONObject root = new JSONObject();
            root.put("currentId", sheets.currentId());
            JSONArray sheetArray = new JSONArray();
            for (TimetableSheet sheet : sheets.all()) sheetArray.put(sheetToJson(sheet));
            root.put("timetables", sheetArray);
            prefs.edit().putString(STORAGE_KEY, root.toString()).apply();
            WidgetRefresh.notifyDataChanged(context);
        } catch (Exception ignored) {
            // Keep the in-memory data usable when SharedPreferences write fails.
        }
    }

    private static TimetableSheets fromV2(JSONObject root) {
        JSONArray array = root.optJSONArray("timetables");
        if (array == null || array.length() == 0) return null;
        List<TimetableSheet> parsed = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.optJSONObject(i);
            if (object == null) continue;
            String id = object.optString("id");
            String name = object.optString("name");
            TimetableConfig config = object.optJSONObject("config") == null
                    ? defaultConfig() : configFromJson(object.optJSONObject("config"));
            List<TimeNode> nodes = new ArrayList<>();
            List<CourseBase> bases = new ArrayList<>();
            List<CourseDetail> details = new ArrayList<>();
            JSONArray nodeArray = object.optJSONArray("timeNodes");
            if (nodeArray != null) {
                for (int j = 0; j < nodeArray.length(); j++) {
                    TimeNode node = timeNodeFromJson(nodeArray.optJSONObject(j));
                    if (node != null) nodes.add(node);
                }
            }
            JSONArray baseArray = object.optJSONArray("courses");
            if (baseArray != null) {
                for (int j = 0; j < baseArray.length(); j++) {
                    CourseBase base = baseFromJson(baseArray.optJSONObject(j));
                    if (base != null) bases.add(base);
                }
            }
            JSONArray detailArray = object.optJSONArray("details");
            if (detailArray != null) {
                for (int j = 0; j < detailArray.length(); j++) {
                    CourseDetail detail = detailFromJson(detailArray.optJSONObject(j));
                    if (detail != null && containsBase(bases, detail.baseId)) details.add(detail);
                }
            }
            parsed.add(new TimetableSheet(id, name, config,
                    TimeTableSettings.resize(nodes, config.nodes), bases, details));
        }
        if (parsed.isEmpty()) return null;
        TimetableSheets result = new TimetableSheets(parsed.get(0));
        for (int i = 1; i < parsed.size(); i++) result.add(parsed.get(i));
        result.switchTo(root.optString("currentId"));
        return result;
    }

    private static JSONObject sheetToJson(TimetableSheet sheet) {
        JSONObject object = new JSONObject();
        try {
            object.put("id", sheet.id);
            object.put("name", sheet.name);
            object.put("config", configToJson(sheet.config));
            JSONArray nodeArray = new JSONArray();
            for (TimeNode node : sheet.timeNodes) {
                JSONObject nodeObject = new JSONObject();
                nodeObject.put("node", node.node);
                nodeObject.put("startTime", node.startTime);
                nodeObject.put("endTime", node.endTime);
                nodeArray.put(nodeObject);
            }
            object.put("timeNodes", nodeArray);
            JSONArray baseArray = new JSONArray();
            for (CourseBase base : sheet.bases) baseArray.put(baseToJson(base));
            object.put("courses", baseArray);
            JSONArray detailArray = new JSONArray();
            for (CourseDetail detail : sheet.details) detailArray.put(detailToJson(detail));
            object.put("details", detailArray);
        } catch (Exception ignored) {
        }
        return object;
    }

    private static TimetableSheet sheetFromJson(String id, String name, JSONObject configObject) {
        return new TimetableSheet(id, name, configObject == null ? defaultConfig()
                : configFromJson(configObject), null, null, null);
    }

    private static TimetableConfig safeConfig(TimetableConfig next, TimetableConfig previous) {
        if (next == null) return previous;
        int nodes = Math.max(1, Math.min(TimeTableSettings.MAX_NODES, next.nodes));
        int maxWeek = Math.max(1, Math.min(40, next.maxWeek));
        return new TimetableConfig(next.startDate, maxWeek, nodes, next.showSat,
                next.showSun, next.showOtherWeekCourse);
    }

    private static TimetableConfig defaultConfig() {
        Calendar monday = Calendar.getInstance();
        int shift = (monday.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        monday.add(Calendar.DAY_OF_MONTH, -shift);
        return new TimetableConfig(TimetableLogic.dateKey(monday.getTime()), 20, 12,
                false, false, true);
    }

    private static TimetableSheet defaultSheet() {
        TimetableConfig config = defaultConfig();
        return new TimetableSheet("default-timetable", "默认课表", config,
                TimeTableSettings.resize(TimeTableSettings.defaultTable(), config.nodes),
                Collections.emptyList(), Collections.emptyList());
    }

    private static TimetableConfig configFromJson(JSONObject object) {
        return new TimetableConfig(object.optString("startDate",
                TimetableLogic.dateKey(new java.util.Date())), object.optInt("maxWeek", 20),
                object.optInt("nodes", 12), object.optBoolean("showSat", false),
                object.optBoolean("showSun", false),
                object.optBoolean("showOtherWeekCourse", true));
    }

    private static JSONObject configToJson(TimetableConfig config) {
        JSONObject object = new JSONObject();
        try {
            object.put("startDate", config.startDate);
            object.put("maxWeek", config.maxWeek);
            object.put("nodes", config.nodes);
            object.put("showSat", config.showSat);
            object.put("showSun", config.showSun);
            object.put("showOtherWeekCourse", config.showOtherWeekCourse);
        } catch (Exception ignored) {
        }
        return object;
    }

    private static TimeNode timeNodeFromJson(JSONObject object) {
        if (object == null) return null;
        return new TimeNode(object.optInt("node"), object.optString("startTime"),
                object.optString("endTime"));
    }

    private static CourseBase baseFromJson(JSONObject object) {
        if (object == null) return null;
        String id = object.optString("id");
        String name = object.optString("name");
        if (id.trim().isEmpty() || name.trim().isEmpty()) return null;
        return new CourseBase(id, name, object.optInt("color", 0), object.optString("note"),
                object.optDouble("credit", 0));
    }

    private static JSONObject baseToJson(CourseBase base) {
        JSONObject object = new JSONObject();
        try {
            object.put("id", base.id);
            object.put("name", base.name);
            object.put("color", base.color);
            object.put("note", base.note);
            object.put("credit", base.credit);
        } catch (Exception ignored) {
        }
        return object;
    }

    private static CourseDetail detailFromJson(JSONObject object) {
        if (object == null) return null;
        String id = object.optString("id");
        String baseId = object.optString("baseId");
        if (id.trim().isEmpty() || baseId.trim().isEmpty()) return null;
        return new CourseDetail(id, baseId, object.optInt("day"), object.optInt("startNode"),
                object.optInt("step", 1), object.optInt("startWeek"), object.optInt("endWeek"),
                object.optInt("type", CourseDetail.TYPE_ALL), object.optString("room"),
                object.optString("teacher"), object.optInt("level"));
    }

    private static JSONObject detailToJson(CourseDetail detail) {
        JSONObject object = new JSONObject();
        try {
            object.put("id", detail.id);
            object.put("baseId", detail.baseId);
            object.put("day", detail.day);
            object.put("startNode", detail.startNode);
            object.put("step", detail.step);
            object.put("startWeek", detail.startWeek);
            object.put("endWeek", detail.endWeek);
            object.put("type", detail.type);
            object.put("room", detail.room);
            object.put("teacher", detail.teacher);
            object.put("level", detail.level);
        } catch (Exception ignored) {
        }
        return object;
    }
}
