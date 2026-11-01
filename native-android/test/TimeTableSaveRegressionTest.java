package test;

import com.checktodo.app.domain.TimeNode;
import com.checktodo.app.domain.TimeTableDraft;
import com.checktodo.app.domain.TimeTableSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * 保存误报的复现测试：「我的时间设置没有任何问题，课表设置保存不了」。
 *
 * <p><b>为什么单独一个测试文件：</b>这是一次真机回归，用户被完全阻塞。复现要覆盖
 * 真实数据形态（13~15 节、起止相接、旧数据迁移），并逐个钉死排查方向里的嫌疑点，
 * 避免下次改动再次引入。</p>
 *
 * <p><b>核心不变量（本文件最重要的一条）</b>：
 * <b>「编辑后校验通过」必须等价于「能够落盘」</b>。
 * 前者用 {@link TimeTableDraft#validate()}，后者由仓库层
 * {@code saveTimeNodes} 用<b>同一节数</b>再校验一次。只要两层对节数的理解不一致，
 * 就会出现「草稿合法但保存失败」——这正是本次误报的机理。</p>
 */
public final class TimeTableSaveRegressionTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        testRealUserTable13();          // 13 节起止相接
        testRealUserTable15();          // 15 节（华农夹具就是 15 节）
        testMigratedLegacyData();       // v1 老数据迁移形态
        testChangedNodeCount();         // ★改节数后保存被拒（主因）
        testSavePlanContract();         // ★保存编排：草稿与落库口径必须一致
        testEqualBoundaryAllowed();     // 相等边界必须放行
        testWhitespaceAndHiddenChars(); // 隐藏字符 / 空白
        testAdjacentPairPairing();      // 相邻对比较不串行
        testShrinkCount();              // 减少节数
        testIssuesListAll();            // 提示要列出所有问题行
        report();
    }

    // ------------------------------------------------------------------
    // 1. 真实用户数据形态
    // ------------------------------------------------------------------

    /**
     * 13 节、起止相接（第 1 节 08:00-08:45，第 2 节 08:55…），保存必须成功。
     *
     * <p>这组数据来自旧版默认作息（45 分钟 + 10 分钟），代表「老用户已保存的表」。
     * 默认表已改为华农 15 节，所以这里显式构造，而不是取 defaultTable()。</p>
     */
    private static void testRealUserTable13() {
        List<TimeNode> table = new ArrayList<>();
        String[] starts13 = {"08:00", "08:55", "09:55", "10:55", "14:00",
                "14:55", "15:50", "16:45", "19:00", "19:55",
                "20:50", "21:45", "22:40"};
        for (int i = 0; i < starts13.length; i++) {
            int begin = TimeTableSettings.minutes(starts13[i]);
            table.add(new TimeNode(i + 1, starts13[i], TimeTableSettings.format(begin + 45)));
        }
        check("13 节老数据长度", table.size() == 13);

        TimeTableDraft draft = TimeTableDraft.of(table, 13);
        check("13 节草稿节数", draft.nodeCount() == 13);
        check("★13 节起止相接：草稿校验通过", draft.validate().isEmpty());
        check("★13 节起止相接：无行内问题", draft.issues().isEmpty());
        check("★13 节：与仓库层同口径校验通过",
                TimeTableSettings.validate(draft.nodes(), 13).isEmpty());
    }

    /** 15 节：用户真实学校里 15 节很常见（华农夹具实测就是 15 节）。 */
    private static void testRealUserTable15() {
        List<TimeNode> table = new ArrayList<>();
        String[] starts = {"08:00", "08:55", "09:50", "10:45", "11:40",
                "14:00", "14:55", "15:50", "16:45", "17:40",
                "19:00", "19:55", "20:50", "21:45", "22:40"};
        for (int i = 0; i < starts.length; i++) {
            int begin = TimeTableSettings.minutes(starts[i]);
            table.add(new TimeNode(i + 1, starts[i], TimeTableSettings.format(begin + 45)));
        }
        check("15 节表长度", table.size() == 15);
        TimeTableDraft draft = TimeTableDraft.of(table, 15);
        check("★15 节：草稿校验通过", draft.validate().isEmpty());
        check("★15 节：与仓库层同口径校验通过",
                TimeTableSettings.validate(draft.nodes(), 15).isEmpty());
        check("15 节：节次连续", contiguous(draft));
    }

    /** v1 老数据：只有 12 节、顺序错乱、含重复节次——经规整后保存必须成功。 */
    private static void testMigratedLegacyData() {
        List<TimeNode> legacy = new ArrayList<>();
        legacy.add(new TimeNode(5, "14:00", "14:45"));
        legacy.add(new TimeNode(1, "08:00", "08:45"));
        legacy.add(new TimeNode(3, "09:50", "10:35"));
        legacy.add(new TimeNode(2, "08:55", "09:40"));
        legacy.add(new TimeNode(5, "14:00", "14:45"));   // 重复节次
        legacy.add(new TimeNode(4, "10:55", "11:40"));

        TimeTableDraft draft = TimeTableDraft.of(legacy, 6);
        check("★老数据规整后为 6 节", draft.nodeCount() == 6);
        check("★老数据规整后节次连续", contiguous(draft));
        check("★老数据保留已填时间（第 3 节 09:50）",
                "09:50".equals(draft.node(3).startTime));
        check("★老数据规整后校验通过", draft.validate().isEmpty());
        check("★老数据与仓库层同口径校验通过",
                TimeTableSettings.validate(draft.nodes(), 6).isEmpty());

        TimeTableDraft mismatch = TimeTableDraft.of(TimeTableSettings.generate(12), 13);
        check("★配置 13 节但只有 12 节数据 → 补齐为 13", mismatch.nodeCount() == 13);
        check("★补齐后校验通过", mismatch.validate().isEmpty());
    }

    // ------------------------------------------------------------------
    // 2. 嫌疑点逐个排查
    // ------------------------------------------------------------------

    /**
     * ★主因：改「每天节数」后，草稿有 N 节，但仓库层仍用旧的 config.nodes 校验 → 必然失败。
     *
     * <p>真机现象：用户没改任何时间、只把节数从 13 改成 15（或反之），点保存就报
     * 「保存失败，请检查各节起止时间」——因为
     * {@code TimeTableSettings.validate(nodes, sheet.config.nodes)} 里
     * {@code nodes.size() != sheet.config.nodes}，直接返回「节次数量应为 13 节」。</p>
     */
    private static void testChangedNodeCount() {
        TimeTableDraft draft = TimeTableDraft.of(TimeTableSettings.defaultTable(), 13);
        draft.setNodeCount(15);
        check("改成 15 节后草稿节数 15", draft.nodeCount() == 15);
        check("★改成 15 节后草稿校验通过", draft.validate().isEmpty());

        String staleCheck = TimeTableSettings.validate(draft.nodes(), 13);
        check("★复现：仓库层若用旧节数 13 校验则失败", !staleCheck.isEmpty());
        check("★复现失败原因正是「节次数量」", staleCheck.contains("节次数量"));

        check("★修复口径：用草稿节数 15 校验通过",
                TimeTableSettings.validate(draft.nodes(), draft.nodeCount()).isEmpty());

        draft.setNodeCount(13);
        check("改回 13 节后草稿校验通过", draft.validate().isEmpty());
        check("★改回 13 节后仓库层若用 15 校验则失败",
                !TimeTableSettings.validate(draft.nodes(), 15).isEmpty());
    }

    /**
     * ★保存编排的契约测试。
     *
     * <p>把「先校验草稿 → 再落库」这段编排抽成纯函数
     * {@link TimeTableSettings#validateForSave(List, int)}，
     * 用<b>目标节数</b>而不是库里当前的节数。这样「草稿通过」与「能落盘」同源。</p>
     */
    private static void testSavePlanContract() {
        // 场景 A：13 节 → 15 节（用户只改节数）
        TimeTableDraft d15 = TimeTableDraft.of(TimeTableSettings.defaultTable(), 13);
        d15.setNodeCount(15);
        int staleConfigNodes = 13;   // ★仓库里 sheet.config.nodes 仍是旧值
        check("★对照：旧口径（用 sheet.config.nodes=13 校验 15 节草稿）必然失败",
                !TimeTableSettings.validate(d15.nodes(), staleConfigNodes).isEmpty());
        check("★修复：validateForSave 用目标节数 15 → 通过",
                TimeTableSettings.validateForSave(d15.nodes(), 15).isEmpty());
        check("★修复后与草稿校验同源（都通过）",
                TimeTableSettings.validateForSave(d15.nodes(), 15).isEmpty()
                        && d15.validate().isEmpty());

        // 场景 B：15 节 → 10 节
        TimeTableDraft d10 = TimeTableDraft.of(TimeTableSettings.generate(15), 15);
        d10.setNodeCount(10);
        check("★对照：旧口径（用 15 校验 10 节）失败",
                !TimeTableSettings.validate(d10.nodes(), 15).isEmpty());
        check("★减少节数后：validateForSave 通过",
                TimeTableSettings.validateForSave(d10.nodes(), 10).isEmpty());

        // 场景 C：真正有问题时必须拦住
        TimeTableDraft bad = TimeTableDraft.of(TimeTableSettings.defaultTable(), 5);
        bad.setNodeTime(2, "07:00", "07:45");
        check("★真有问题时 validateForSave 必须拦住",
                !TimeTableSettings.validateForSave(bad.nodes(), 5).isEmpty());
    }

    /** 相等边界：后一节开始 == 前一节结束，必须放行（不能写成 >= 或 >）。 */
    private static void testEqualBoundaryAllowed() {
        List<TimeNode> table = new ArrayList<>();
        table.add(new TimeNode(1, "08:00", "08:45"));
        table.add(new TimeNode(2, "08:45", "09:30"));
        table.add(new TimeNode(3, "09:30", "10:15"));
        TimeTableDraft draft = TimeTableDraft.of(table, 3);
        check("★首尾相接：草稿校验通过", draft.validate().isEmpty());
        check("★首尾相接：无行内问题", draft.issues().isEmpty());
        check("★首尾相接：仓库层同口径通过",
                TimeTableSettings.validate(draft.nodes(), 3).isEmpty());
        check("★首尾相接：validateForSave 通过",
                TimeTableSettings.validateForSave(draft.nodes(), 3).isEmpty());

        TimeTableDraft cross = TimeTableDraft.of(table, 3);
        cross.setNodeTime(2, "08:44", "09:30");
        check("★差 1 分钟才算交叉", !cross.validate().isEmpty());
    }

    /** 隐藏字符 / 前后空白：parseTime 不能因此失败被当 0 处理。 */
    private static void testWhitespaceAndHiddenChars() {
        check("minutes 处理前后空格", TimeTableSettings.minutes(" 08:00 ") == 480);
        check("★minutes 处理全角空格", TimeTableSettings.minutes("\u300008:00\u3000") == 480);
        check("minutes 处理制表符", TimeTableSettings.minutes("\t08:00\t") == 480);
        check("minutes 处理 \\r", TimeTableSettings.minutes("08:00\r") == 480);
        check("minutes 处理不换行空格", TimeTableSettings.minutes("\u00A008:00") == 480);

        List<TimeNode> table = new ArrayList<>();
        table.add(new TimeNode(1, "08:00", "08:45"));
        table.add(new TimeNode(2, "08:55", "09:40"));
        TimeTableDraft draft = TimeTableDraft.of(table, 2);
        draft.setNodeTime(2, " 08:55 ", " 09:40 ");
        check("★带空白的时间值校验通过", draft.validate().isEmpty());
        check("★带空白时间不被判格式错误", draft.issues().isEmpty());
        check("带空白后仓库层同口径通过",
                TimeTableSettings.validate(draft.nodes(), 2).isEmpty());
    }

    /** 相邻对比较：必须是「第 i 节 vs 第 i-1 节」，不能因为某节非法就错位到更早的行。 */
    private static void testAdjacentPairPairing() {
        List<TimeNode> table = new ArrayList<>();
        table.add(new TimeNode(1, "08:00", "08:45"));
        table.add(new TimeNode(2, "08:55", "09:40"));
        table.add(new TimeNode(3, "09:50", "10:35"));
        table.add(new TimeNode(4, "10:45", "11:30"));
        table.add(new TimeNode(5, "14:00", "14:45"));
        TimeTableDraft draft = TimeTableDraft.of(table, 5);
        check("★5 节均匀作息无任何问题（行号不串位）", draft.issues().isEmpty());

        draft.setNodeTime(4, "09:00", "09:30");
        List<TimeTableDraft.Issue> issues = draft.issues();
        check("★只有第 4 节被报错", issues.size() == 1 && issues.get(0).node == 4);
        check("★第 5 节不受牵连", draft.issueOf(5).isEmpty());
        check("★第 3 节不受牵连", draft.issueOf(3).isEmpty());
    }

    /** 减少节数：15 → 10，裁剪后剩余部分必须仍然合法。 */
    private static void testShrinkCount() {
        TimeTableDraft draft = TimeTableDraft.of(TimeTableSettings.generate(15), 15);
        check("15 节初始合法", draft.validate().isEmpty());
        draft.setNodeCount(10);
        check("★裁到 10 节后仍合法", draft.validate().isEmpty());
        check("★裁到 10 节后仓库层同口径通过",
                TimeTableSettings.validate(draft.nodes(), 10).isEmpty());
        check("裁剪后节次连续", contiguous(draft));
    }

    /** 提示要列出所有问题行（节次号 + 原因），不是只给第一条。 */
    private static void testIssuesListAll() {
        List<TimeNode> table = new ArrayList<>();
        table.add(new TimeNode(1, "09:00", "08:00"));   // 倒置
        table.add(new TimeNode(2, "08:10", "08:50"));   // 前节已倒置，无法比较
        table.add(new TimeNode(3, "09:00", "09:45"));   // 正常
        table.add(new TimeNode(4, "09:30", "10:15"));   // 与第 3 节交叉
        table.add(new TimeNode(5, "11:00", "10:00"));   // 倒置
        TimeTableDraft draft = TimeTableDraft.of(table, 5);
        List<TimeTableDraft.Issue> issues = draft.issues();
        check("★多处问题全部返回（≥3）", issues.size() >= 3);
        check("第 1 节（倒置）在内", hasIssue(issues, 1));
        check("第 4 节（交叉）在内", hasIssue(issues, 4));
        check("第 5 节（倒置）在内", hasIssue(issues, 5));
        check("第 3 节（正常）不在内", !hasIssue(issues, 3));

        String summary = TimeTableDraft.summarize(issues);
        check("★摘要包含第 1 节", summary.contains("第 1 节"));
        check("★摘要包含第 4 节", summary.contains("第 4 节"));
        check("★摘要包含第 5 节", summary.contains("第 5 节"));
        check("★摘要包含原因（开始时间必须早于结束时间）",
                summary.contains("开始时间必须早于结束时间"));
        check("摘要用分隔符连接多条", summary.split("；").length >= 3);
        check("摘要不以分隔符结尾", !summary.endsWith("；"));
    }

    // ------------------------------------------------------------------

    private static boolean contiguous(TimeTableDraft draft) {
        for (int i = 1; i <= draft.nodeCount(); i++) {
            TimeNode node = draft.node(i);
            if (node == null || node.node != i) return false;
        }
        return true;
    }

    private static boolean hasIssue(List<TimeTableDraft.Issue> issues, int node) {
        for (TimeTableDraft.Issue issue : issues) {
            if (issue.node == node) return true;
        }
        return false;
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
