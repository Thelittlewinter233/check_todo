package test;

import com.checktodo.app.domain.TimeNode;
import com.checktodo.app.domain.TimeTableSettings;
import com.checktodo.app.widget.CourseWidgetItems;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * 节次时间表与卡片课程视图的纯 Java 测试：
 * 默认表生成、HH:mm 解析、校验（开始<结束、相邻不重叠）、节数联动保留已填值、卡片文案。
 */
public final class TimeTableSettingsTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        testDefaultTable();
        testMinuteParsingAndFormat();
        testValidateStartBeforeEnd();
        testValidateAdjacentOverlap();
        testValidateAdjacentEqualAllowed();
        testResizeKeepsValues();
        testResizeShrinkTruncates();
        testWithNodeReplace();
        testSpanLabel();
        testCourseWidgetItems();

        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    private static void testDefaultTable() {
        // 默认作息已改为华南农业大学实测刻度（来源 scau.htm 节次列），共 15 节、单节 40 分钟。
        List<TimeNode> table = TimeTableSettings.defaultTable();
        check("★默认时间表 15 节（华农作息，末节 21:00-21:40）", table.size() == 15);
        check("默认第 1 节 08:00-08:40",
                table.get(0).node == 1 && table.get(0).startTime.equals("08:00")
                        && table.get(0).endTime.equals("08:40"));
        check("默认第 2 节 08:45-09:25",
                table.get(1).startTime.equals("08:45") && table.get(1).endTime.equals("09:25"));
        check("默认第 3 节 09:55-10:35（2、3 节间休 30 分钟）",
                table.get(2).startTime.equals("09:55") && table.get(2).endTime.equals("10:35"));
        check("默认第 4 节 10:40-11:20",
                table.get(3).startTime.equals("10:40") && table.get(3).endTime.equals("11:20"));
        check("默认表不越过当天 24:00",
                TimeTableSettings.minutes(table.get(table.size() - 1).startTime) > 0
                        && TimeTableSettings.minutes(table.get(table.size() - 1).endTime) > 0);
        check("默认第 6 节 12:40 开始（午休后）", table.get(5).startTime.equals("12:40"));
        check("默认第 13 节 19:30 开始（晚休后）", table.get(12).startTime.equals("19:30"));
        check("默认表自身校验通过", TimeTableSettings.validate(table, table.size()).isEmpty());

        boolean contiguous = true;
        for (int i = 1; i < table.size(); i++) {
            if (TimeTableSettings.minutes(table.get(i).startTime)
                    < TimeTableSettings.minutes(table.get(i - 1).endTime)) {
                contiguous = false;
            }
        }
        check("默认表相邻节次不重叠", contiguous);
        check("★单节时长 40 分钟（华农刻度，非 45）",
                TimeTableSettings.minutes(table.get(0).endTime)
                        - TimeTableSettings.minutes(table.get(0).startTime) == 40);
        // 旧行为（已修复）：超出默认表时返回末节值，会让第 14..20 节与第 13 节时间完全相同，
        // 导致默认表在 14 节以上必然被判定为「交叉」。现在超出部分必须各有独立区间。
        // 16..20 节超出华农表：返回空值由用户手填（不推演、不兜底）。
        check("★超出华农表（第 16 节起）返回空",
                TimeTableSettings.isUnset(TimeTableSettings.defaultNode(16)));
        check("★第 20 节也返回空",
                TimeTableSettings.isUnset(TimeTableSettings.defaultNode(20)));
        check("★超出节次不再复制末节时间",
                !TimeTableSettings.defaultNode(16).startTime.equals(table.get(14).startTime));
    }

    private static void testMinuteParsingAndFormat() {
        check("08:00 → 480", TimeTableSettings.minutes("08:00") == 480);
        check("单位数小时兼容", TimeTableSettings.minutes("8:05") == 485);
        check("非法分钟返回 -1", TimeTableSettings.minutes("08:60") == -1);
        check("非法小时返回 -1", TimeTableSettings.minutes("24:00") == -1);
        check("空串返回 -1", TimeTableSettings.minutes("") == -1);
        check("缺冒号返回 -1", TimeTableSettings.minutes("0800") == -1);
        check("字母返回 -1", TimeTableSettings.minutes("ab:cd") == -1);
        check("null 返回 -1", TimeTableSettings.minutes(null) == -1);
        check("480 → 08:00", TimeTableSettings.format(480).equals("08:00"));
        check("跨天取模", TimeTableSettings.format(24 * 60 + 9 * 60).equals("09:00"));
    }

    private static void testValidateStartBeforeEnd() {
        List<TimeNode> nodes = TimeTableSettings.generate(3);
        check("合法表通过", TimeTableSettings.validate(nodes, 3).isEmpty());

        List<TimeNode> reversed = TimeTableSettings.withNode(nodes, 2, "10:00", "09:40");
        check("开始晚于结束被拒绝", !TimeTableSettings.validate(reversed, 3).isEmpty());

        List<TimeNode> equal = TimeTableSettings.withNode(nodes, 2, "10:00", "10:00");
        check("开始等于结束被拒绝", !TimeTableSettings.validate(equal, 3).isEmpty());
        check("单节校验拒绝相等",
                !TimeTableSettings.validateNode(new TimeNode(2, "10:00", "10:00")).isEmpty());

        List<TimeNode> badFormat = TimeTableSettings.withNode(nodes, 1, "8.00", "8.45");
        check("HH:mm 格式错误被拒绝", !TimeTableSettings.validate(badFormat, 3).isEmpty());

        check("数量与节数不符被拒绝", !TimeTableSettings.validate(nodes, 4).isEmpty());
        check("空表被拒绝", !TimeTableSettings.validate(new ArrayList<TimeNode>(), 3).isEmpty());
    }

    private static void testValidateAdjacentOverlap() {
        List<TimeNode> nodes = TimeTableSettings.generate(3);
        // 第 2 节起点提前到 09:30，早于第 1 节结束 09:40 → 重叠。
        List<TimeNode> overlap = TimeTableSettings.withNode(nodes, 2, "09:30", "10:15");
        String error = TimeTableSettings.validate(overlap, 3);
        check("相邻节次重叠被拒绝", !error.isEmpty());
        check("重叠错误定位到具体节次", error.contains("第 2 节"));

        // 调整第 3 节反而压到第 2 节：以排序后的后一节为准同样能报错。
        List<TimeNode> overlapNext = TimeTableSettings.withNode(nodes, 2, "10:30", "11:15");
        check("后一节覆盖前一节被拒绝", !TimeTableSettings.validate(overlapNext, 3).isEmpty());
    }

    private static void testValidateAdjacentEqualAllowed() {
        List<TimeNode> nodes = TimeTableSettings.generate(3);
        // 第 3 节起点与第 2 节终点重合：需要把第 3 节也同步后移才合法。
        List<TimeNode> touching = TimeTableSettings.withNode(nodes, 2, "09:40", "10:30");
        touching = TimeTableSettings.withNode(touching, 3, "10:30", "11:15");
        check("前一节结束 == 后一节开始允许", TimeTableSettings.validate(touching, 3).isEmpty());
    }

    private static void testResizeKeepsValues() {
        List<TimeNode> custom = new ArrayList<>(Arrays.asList(
                new TimeNode(1, "07:30", "08:15"),
                new TimeNode(2, "08:25", "09:10"),
                new TimeNode(3, "09:30", "10:15")));
        List<TimeNode> expanded = TimeTableSettings.resize(custom, 5);
        check("扩充到 5 节", expanded.size() == 5);
        check("扩充保留第 1 节自定义值", expanded.get(0).startTime.equals("07:30"));
        check("扩充保留第 3 节自定义值", expanded.get(2).startTime.equals("09:30"));
        check("扩充新节取默认值", expanded.get(3).startTime.equals(TimeTableSettings.defaultNode(4).startTime));
        check("扩充后节次连续", expanded.get(4).node == 5);

        List<TimeNode> shrunk = TimeTableSettings.resize(expanded, 2);
        check("裁剪到 2 节", shrunk.size() == 2);
        check("裁剪保留前两节", shrunk.get(1).startTime.equals("08:25"));

        List<TimeNode> zero = TimeTableSettings.resize(custom, 0);
        check("节数钳制至少 1 节", zero.size() == 1);
        List<TimeNode> huge = TimeTableSettings.resize(custom, 999);
        check("节数钳制不超过 20", huge.size() == TimeTableSettings.MAX_NODES);

        // 老数据：只存了 12 节，配置被改成 14 节 → 补齐且不改已有值。
        List<TimeNode> legacy = TimeTableSettings.resize(TimeTableSettings.defaultTable(), 10);
        List<TimeNode> migrated = TimeTableSettings.resize(legacy, 12);
        check("老数据 10 节迁移到 12 节", migrated.size() == 12 && migrated.get(11).node == 12);
        check("迁移后保留第 10 节原值", migrated.get(9).startTime.equals(legacy.get(9).startTime));
        check("迁移后校验通过", TimeTableSettings.validate(migrated, 12).isEmpty());
    }

    private static void testResizeShrinkTruncates() {
        List<TimeNode> table = TimeTableSettings.defaultTable();
        List<TimeNode> twelve = TimeTableSettings.resize(table, 12);
        check("15 节裁剪到 12 节", twelve.size() == 12 && twelve.get(11).node == 12);
        check("裁剪后末节为 17:55-18:35（华农第 12 节）",
                twelve.get(11).startTime.equals("17:55") && twelve.get(11).endTime.equals("18:35"));
    }

    private static void testWithNodeReplace() {
        List<TimeNode> table = TimeTableSettings.generate(3);
        List<TimeNode> updated = TimeTableSettings.withNode(table, 2, "09:00", "09:45");
        check("替换指定节", TimeTableSettings.find(updated, 2).startTime.equals("09:00"));
        check("不修改原列表", TimeTableSettings.find(table, 2).startTime.equals("08:45"));
        List<TimeNode> appended = TimeTableSettings.withNode(table, 4, "11:00", "11:45");
        check("缺失节次自动追加并排序", appended.size() == 4 && appended.get(3).node == 4);
    }

    private static void testSpanLabel() {
        List<TimeNode> table = TimeTableSettings.defaultTable();
        check("单节区间", TimeTableSettings.rangeLabel(table, 1).equals("08:00-08:40"));
        check("连续两节区间", TimeTableSettings.spanLabel(table, 1, 2).equals("08:00-09:25"));
        check("缺失节次返回空串", TimeTableSettings.rangeLabel(table, 99).isEmpty());
    }

    private static void testCourseWidgetItems() {
        List<TimeNode> table = TimeTableSettings.generate(4);
        com.checktodo.app.domain.CourseBase math =
                new com.checktodo.app.domain.CourseBase("b1", "高等数学", 1, "", 0);
        com.checktodo.app.domain.CourseBase physics =
                new com.checktodo.app.domain.CourseBase("b2", "大学物理", 2, "", 0);
        com.checktodo.app.domain.CourseDetail later = detail("d2", "b2", 2, 2, "A301");
        com.checktodo.app.domain.CourseDetail earlier = detail("d1", "b1", 1, 2, "教一201");

        List<CourseWidgetItems.Item> items = CourseWidgetItems.build(
                Arrays.asList(later, earlier), Arrays.asList(math, physics), table, new Date());
        check("卡片条目按开始节次排序", items.size() == 2 && items.get(0).detailId.equals("d1"));
        check("卡片标题取课程名", items.get(0).title.equals("高等数学"));
        check("卡片副标题含节次/时间/教室（华农刻度）",
                items.get(0).subtitle.equals("1-2节 08:00-09:25 · 教一201"));
        check("两节课程副标题写范围（华农刻度）",
                items.get(1).subtitle.equals("2-3节 08:45-10:35 · A301"));

        com.checktodo.app.domain.CourseDetail noRoom = detail("d3", "b1", 3, 1, "");
        check("无教室不输出分隔符",
                CourseWidgetItems.subtitle(noRoom, table).equals("3节 09:55-10:35"));
        check("时间表缺失时省略时间",
                CourseWidgetItems.subtitle(noRoom, new ArrayList<TimeNode>()).equals("3节"));
        check("课程缺失时兜底标题",
                CourseWidgetItems.title(noRoom, new ArrayList<com.checktodo.app.domain.CourseBase>())
                        .equals("未命名课程"));
    }

    private static com.checktodo.app.domain.CourseDetail detail(String id, String baseId, int startNode,
                                                               int step, String room) {
        return new com.checktodo.app.domain.CourseDetail(id, baseId, 1, startNode, step,
                1, 20, com.checktodo.app.domain.CourseDetail.TYPE_ALL, room, "", 0);
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("PASS " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }
}
