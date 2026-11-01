package test;

import com.checktodo.app.domain.ScauGridParser;
import com.checktodo.app.domain.TimeNode;
import com.checktodo.app.domain.TimeTableDraft;
import com.checktodo.app.domain.TimeTableSettings;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 默认作息改为华农刻度后的回归测试。
 *
 * <p><b>关键点：断言不能只抄常量。</b>如果把 {@code SCAU_START/END} 抄进测试再断言相等，
 * 那只是「常量和常量相等」，抄错也照样通过。这里改为<b>从真实页面夹具
 * {@code test/fixtures/scau_grid.htm} 里重新解析出节次刻度</b>，再与
 * {@link TimeTableSettings#defaultTable()} 逐节比对——数据源与实现互相印证。</p>
 *
 * <p>同时锁死三条产品约定：</p>
 * <ol>
 *   <li>默认表 15 节、全部合法、首尾相接放行；</li>
 *   <li>16..20 节超出华农表 → 返回空，由用户手填；保存时提示「请填写第 N 节时间」，
 *       <b>绝不能误报成倒置或格式错误</b>；</li>
 *   <li>老用户已保存的时间表不受影响（只有「恢复默认」/新加节才用新默认）。</li>
 * </ol>
 */
public final class ScauScheduleDefaultsTest {
    private static int passed;
    private static int failed;

    private static final String FIXTURE = "test/fixtures/scau_grid.htm";

    public static void main(String[] args) {
        testDefaultTableMatchesRealPage();
        testDefaultTableValid();
        testAdjacentTouchingAllowed();
        testNodesOver15AreEmpty();
        testEmptyNodeMessage();
        testLegacyUserTableUnaffected();
        testResizeUsesSameSource();
        testDefaultNodeConsistency();
        report();
    }

    // ------------------------------------------------------------------
    // 1. 默认表必须与真实页面刻度一致
    // ------------------------------------------------------------------

    private static void testDefaultTableMatchesRealPage() {
        List<TimeNode> pageNodes = ScauGridParser.parseTimeNodes(readFixture());
        check("夹具里解析出 15 个节次刻度", pageNodes.size() == 15);

        List<TimeNode> defaults = TimeTableSettings.defaultTable();
        check("★默认表 15 节", defaults.size() == 15);
        check("★DEFAULT_NODES == 15", TimeTableSettings.DEFAULT_NODES == 15);

        boolean allMatch = true;
        StringBuilder mismatch = new StringBuilder();
        for (int i = 0; i < Math.min(pageNodes.size(), defaults.size()); i++) {
            TimeNode page = pageNodes.get(i);
            TimeNode mine = defaults.get(i);
            if (page.node != mine.node || !page.startTime.equals(mine.startTime)
                    || !page.endTime.equals(mine.endTime)) {
                allMatch = false;
                mismatch.append("第").append(page.node).append("节 页面=")
                        .append(page.startTime).append('-').append(page.endTime)
                        .append(" 默认=").append(mine.startTime).append('-').append(mine.endTime)
                        .append("; ");
            }
        }
        check("★默认表逐节与真实页面刻度一致" + (allMatch ? "" : " [" + mismatch + "]"), allMatch);

        // 抽查几个特征值（午休、晚休处的不规则间隔）
        check("第 1 节 08:00-08:40",
                "08:00".equals(defaults.get(0).startTime) && "08:40".equals(defaults.get(0).endTime));
        check("★第 3 节 09:55-10:35（不是推演的 09:40）",
                "09:55".equals(defaults.get(2).startTime) && "10:35".equals(defaults.get(2).endTime));
        check("第 6 节 12:40-13:20（午休后）",
                "12:40".equals(defaults.get(5).startTime) && "13:20".equals(defaults.get(5).endTime));
        check("第 13 节 19:30-20:10（晚休后）",
                "19:30".equals(defaults.get(12).startTime) && "20:10".equals(defaults.get(12).endTime));
        check("第 15 节 21:00-21:40（末节）",
                "21:00".equals(defaults.get(14).startTime) && "21:40".equals(defaults.get(14).endTime));

        // 华农单节 40 分钟，不是 45
        int duration = TimeTableSettings.minutes(defaults.get(0).endTime)
                - TimeTableSettings.minutes(defaults.get(0).startTime);
        check("★单节时长 40 分钟（华农刻度，非 45）", duration == 40);
    }

    private static void testDefaultTableValid() {
        List<TimeNode> defaults = TimeTableSettings.defaultTable();
        check("★默认表自身校验通过", TimeTableSettings.validate(defaults, 15).isEmpty());
        check("★默认表 validateForSave 通过",
                TimeTableSettings.validateForSave(defaults, 15).isEmpty());

        TimeTableDraft draft = TimeTableDraft.of(defaults, 15);
        check("默认表草稿无行内问题", draft.issues().isEmpty());
        check("默认表草稿校验通过", draft.validate().isEmpty());

        // 严格递增：每节开始 >= 上一节结束
        boolean ordered = true;
        for (int i = 1; i < defaults.size(); i++) {
            int prevEnd = TimeTableSettings.minutes(defaults.get(i - 1).endTime);
            int start = TimeTableSettings.minutes(defaults.get(i).startTime);
            if (start < prevEnd) ordered = false;
        }
        check("★默认表各节严格不重叠", ordered);

        // 1..15 节任意数量都合法
        boolean allValid = true;
        for (int count = 1; count <= 15; count++) {
            List<TimeNode> sample = TimeTableSettings.generate(count);
            if (!TimeTableSettings.validate(sample, count).isEmpty()) allValid = false;
        }
        check("★1..15 节的默认表全部合法", allValid);
    }

    private static void testAdjacentTouchingAllowed() {
        // 华农表里第 3 节 09:55 与第 2 节 09:25 之间有 30 分钟空档；
        // 但「首尾相接」必须放行（相等边界）。
        List<TimeNode> table = new java.util.ArrayList<>();
        table.add(new TimeNode(1, "08:00", "08:40"));
        table.add(new TimeNode(2, "08:40", "09:20"));
        TimeTableDraft draft = TimeTableDraft.of(table, 2);
        check("★首尾相接（08:40==08:40）放行", draft.validate().isEmpty());
        check("★首尾相接无行内问题", draft.issues().isEmpty());

        draft.setNodeTime(2, "08:39", "09:20");
        check("★早 1 分钟才算交叉", !draft.validate().isEmpty());
    }

    // ------------------------------------------------------------------
    // 2. 16..20 节：超出华农表 → 空，由用户手填
    // ------------------------------------------------------------------

    private static void testNodesOver15AreEmpty() {
        List<TimeNode> full = TimeTableSettings.generate(20);
        check("生成 20 节", full.size() == 20);
        check("★前 15 节有值", !TimeTableSettings.isUnset(full.get(14)));
        boolean emptyAfter15 = true;
        for (int i = 15; i < 20; i++) {
            if (!TimeTableSettings.isUnset(full.get(i))) emptyAfter15 = false;
        }
        check("★第 16..20 节为空（不猜、不兜底）", emptyAfter15);

        check("defaultNode(16) 为空", TimeTableSettings.isUnset(TimeTableSettings.defaultNode(16)));
        check("defaultNode(20) 为空", TimeTableSettings.isUnset(TimeTableSettings.defaultNode(20)));
        check("defaultNode(15) 有值", !TimeTableSettings.isUnset(TimeTableSettings.defaultNode(15)));
        check("defaultNode(15) 是 21:00-21:40",
                "21:00".equals(TimeTableSettings.defaultNode(15).startTime));

        // 校验：16 节时最后一个空节必须被指出
        String error = TimeTableSettings.validateForSave(full, 16);
        check("★16 节（第 16 节空）校验不通过", !error.isEmpty());
        check("★提示「请填写第 16 节时间」", error.contains("请填写第 16 节时间"));
    }

    private static void testEmptyNodeMessage() {
        List<TimeNode> table = TimeTableSettings.generate(16);

        // ★核心：空节绝不能被误报成「倒置」「格式错误」
        String error = TimeTableSettings.validate(table, 16);
        check("★空节提示不是倒置错误", !error.contains("早于结束"));
        check("★空节提示不是格式错误", !error.contains("格式"));
        check("★空节提示是「请填写」", error.contains("请填写第 16 节时间"));

        TimeTableDraft draft = TimeTableDraft.of(table, 16);
        List<TimeTableDraft.Issue> issues = draft.issues();
        check("行内问题恰好 1 条", issues.size() == 1);
        check("行内问题定位第 16 节", issues.get(0).node == 16);
        check("行内问题文案是「请填写」", issues.get(0).message.contains("请填写"));

        // 用户填好后立即通过
        draft.setNodeTime(16, "21:45", "22:25");
        check("★填好后 validateForSave 通过",
                TimeTableSettings.validateForSave(draft.nodes(), 16).isEmpty());
        check("填好后无行内问题", draft.issues().isEmpty());

        // 单节校验也走同一文案
        check("validateNode 空节提示请填写",
                TimeTableSettings.validateNode(new TimeNode(20, "", ""))
                        .contains("请填写第 20 节"));
    }

    // ------------------------------------------------------------------
    // 3. 老用户不受影响
    // ------------------------------------------------------------------

    private static void testLegacyUserTableUnaffected() {
        // 老用户存的是「45 分钟 + 10 分钟」那套旧默认（13 节，末节 22:40-23:25）
        java.util.List<TimeNode> legacy = new java.util.ArrayList<>();
        String[][] old = {
                {"08:00", "08:45"}, {"08:55", "09:40"}, {"09:55", "10:40"}, {"10:55", "11:40"},
                {"14:00", "14:45"}, {"14:55", "15:40"}, {"15:50", "16:35"}, {"16:45", "17:30"},
                {"19:00", "19:45"}, {"19:55", "20:40"}, {"20:50", "21:35"}, {"21:45", "22:30"},
                {"22:40", "23:25"}
        };
        for (int i = 0; i < old.length; i++) {
            legacy.add(new TimeNode(i + 1, old[i][0], old[i][1]));
        }

        TimeTableDraft draft = TimeTableDraft.of(legacy, 13);
        check("★老用户 13 节原样保留", draft.nodeCount() == 13);
        check("★老用户第 1 节仍是 08:00-08:45（未被新默认覆盖）",
                "08:00".equals(draft.node(1).startTime) && "08:45".equals(draft.node(1).endTime));
        check("★老用户末节仍是 22:40-23:25",
                "22:40".equals(draft.node(13).startTime));
        check("★老用户时间表仍合法（可直接保存）", draft.validate().isEmpty());

        // 老用户加一节：第 14 节按新默认（华农 20:15-20:55）补齐
        draft.addNode();
        check("老用户加节后 14 节", draft.nodeCount() == 14);
        // 老用户末节是旧作息的 22:40-23:25，而华农第 14 节默认是 20:15——直接套会「插到前面」，
        // 与第 13 节交叉。resize 的防交叉规则会把新增节顺延到前一节结束之后（保留 40 分钟时长）。
        check("★新增第 14 节不与老数据末节交叉",
                TimeTableSettings.minutes(draft.node(14).startTime) >= 23 * 60 + 25);
        // 顺延后被 23:59 夹住，时长会短于 40 分钟——但必须仍是正向区间、且不跨天。
        int extraStart = TimeTableSettings.minutes(draft.node(14).startTime);
        int extraEnd = TimeTableSettings.minutes(draft.node(14).endTime);
        check("★新增第 14 节是正向区间（不倒置）", extraEnd > extraStart);
        check("★新增第 14 节不跨天（结束 ≤ 23:59）", extraEnd <= 23 * 60 + 59);
        check("★老用户加节后整表合法", draft.validate().isEmpty());
        check("老用户第 1 节仍未被改动", "08:00".equals(draft.node(1).startTime));

        // 老用户「恢复默认」才切成华农 15？——不：恢复默认保持当前节数，只换时间
        TimeTableDraft sameCount = TimeTableDraft.of(legacy, 13);
        sameCount.resetDefaults();
        check("★恢复默认保持节数 13", sameCount.nodeCount() == 13);
        check("★恢复默认后第 1 节变成华农 08:00-08:40",
                "08:40".equals(sameCount.node(1).endTime));
        check("★恢复默认后仍合法", sameCount.validate().isEmpty());
    }

    // ------------------------------------------------------------------
    // 4. generate / defaultNode / resize 同源
    // ------------------------------------------------------------------

    private static void testResizeUsesSameSource() {
        List<TimeNode> resized = TimeTableSettings.resize(null, 15);
        check("resize(null,15) 得到 15 节", resized.size() == 15);
        boolean same = true;
        List<TimeNode> defaults = TimeTableSettings.defaultTable();
        for (int i = 0; i < 15; i++) {
            if (!resized.get(i).startTime.equals(defaults.get(i).startTime)) same = false;
        }
        check("★resize 补齐的值与 defaultTable 同源", same);

        List<TimeNode> over = TimeTableSettings.resize(null, 18);
        check("resize(null,18) 得到 18 节", over.size() == 18);
        check("★resize 也把 16..18 节留空", TimeTableSettings.isUnset(over.get(17)));
    }

    private static void testDefaultNodeConsistency() {
        List<TimeNode> full = TimeTableSettings.generate(20);
        boolean consistent = true;
        for (int node = 1; node <= 20; node++) {
            TimeNode single = TimeTableSettings.defaultNode(node);
            TimeNode batch = full.get(node - 1);
            if (!single.startTime.equals(batch.startTime) || !single.endTime.equals(batch.endTime)) {
                consistent = false;
            }
        }
        check("★defaultNode(n) 与 generate(20)[n] 逐节一致（同源）", consistent);
        check("defaultNode(0) 不越界", TimeTableSettings.defaultNode(0).node == 1);
        check("defaultNode(99) 不越界", TimeTableSettings.defaultNode(99).node == 20);
    }

    // ------------------------------------------------------------------

    private static String readFixture() {
        InputStream stream = null;
        try {
            stream = new FileInputStream(new File(FIXTURE));
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = stream.read(chunk)) >= 0) buffer.write(chunk, 0, count);
            return com.checktodo.app.domain.HtmlTimetableParser.decode(buffer.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("读取夹具失败：" + FIXTURE, exception);
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
    }

    private static void report() {
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
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
