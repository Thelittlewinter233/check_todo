package test;

import com.checktodo.app.domain.TimeNode;
import com.checktodo.app.domain.TimeTableDraft;
import com.checktodo.app.domain.TimeTableSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * 时间表草稿（单一数据源）的同步与校验测试。
 *
 * <p>对应两个真机缺陷：</p>
 * <ol>
 *   <li><b>节数不同步</b>：时间表里增删节后「每天节数」输入框不跟着变。
 *       根因是节数在两处各存一份（{@code draftCount[]} 与 {@code liveCount[]}）。
 *       现在节数 = {@code nodes.size()}，从结构上无法脱节；本测试锁死这条不变量。</li>
 *   <li><b>编辑时被交叉提醒打断</b>：改为保存时校验。本测试锁死
 *       「校验口径」与「行内问题定位」。</li>
 * </ol>
 */
public final class TimeTableDraftTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        testSingleSourceOfTruth();
        testCountSync();
        testAddRemove();
        testResetDefaults();
        testNodeTimeEditing();
        testValidation();
        testIssues();
        testClampAndBounds();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------
    // 单一数据源
    // ------------------------------------------------------------------

    private static void testSingleSourceOfTruth() {
        TimeTableDraft draft = TimeTableDraft.of(null, 12);
        check("初始节数取自入参", draft.nodeCount() == 12);
        check("★节数恒等于节点数", draft.nodeCount() == draft.nodes().size());

        // 用户改输入框 → 节数变 → 节点数同步变
        check("setNodeCount(8) 生效", draft.setNodeCount(8));
        check("★改节数后节点数同步为 8", draft.nodes().size() == 8);
        check("★节数与节点数仍相等（无脱节）", draft.nodeCount() == draft.nodes().size());

        // 幂等：设同一个值不算变化
        check("重复设同一节数返回 false", !draft.setNodeCount(8));
        check("重复设后数量不变", draft.nodeCount() == 8);

        // nodes() 返回副本，外部改动不影响草稿
        List<TimeNode> copy = draft.nodes();
        copy.clear();
        check("★nodes() 返回副本，外部清空不影响草稿", draft.nodeCount() == 8);
    }

    private static void testCountSync() {
        // 模拟"在时间表里手改第 3 节 → 再从输入框改节数 → 值必须保留"
        TimeTableDraft draft = TimeTableDraft.of(null, 10);
        draft.setNodeTime(3, "10:00", "10:45");
        check("手改第 3 节生效", "10:00".equals(draft.node(3).startTime));

        draft.setNodeCount(14);
        check("扩充到 14 节后第 3 节保留", "10:00".equals(draft.node(3).startTime));
        check("扩充后节数为 14", draft.nodeCount() == 14);
        check("新增的第 14 节有默认时间",
                TimeTableSettings.minutes(draft.node(14).startTime) > 0);

        draft.setNodeCount(4);
        check("裁剪到 4 节后第 3 节仍保留", "10:00".equals(draft.node(3).startTime));
        check("裁剪后节数为 4", draft.nodeCount() == 4);
        check("裁剪掉的第 5 节为 null", draft.node(5) == null);

        // 已保存数据有乱序/缺节时也能规整成 1..N
        List<TimeNode> messy = new ArrayList<>();
        messy.add(new TimeNode(3, "10:00", "10:45"));
        messy.add(new TimeNode(1, "08:00", "08:45"));
        TimeTableDraft fixed = TimeTableDraft.of(messy, 5);
        check("乱序/缺节的旧数据被规整为 5 节", fixed.nodeCount() == 5);
        boolean ordered = true;
        for (int i = 1; i <= 5; i++) {
            if (fixed.node(i) == null || fixed.node(i).node != i) ordered = false;
        }
        check("★规整后节次连续 1..5", ordered);
        check("已有值被保留（第 1 节 08:00）", "08:00".equals(fixed.node(1).startTime));
        check("已有值被保留（第 3 节 10:00）", "10:00".equals(fixed.node(3).startTime));
    }

    private static void testAddRemove() {
        TimeTableDraft draft = TimeTableDraft.of(null, 12);
        check("加一节成功", draft.addNode());
        check("★加节后节数为 13", draft.nodeCount() == 13);
        check("★加节后节点数同步为 13", draft.nodes().size() == 13);

        check("删一节成功", draft.removeNode());
        check("★删节后节数为 12", draft.nodeCount() == 12);
        check("★删节后节点数同步为 12", draft.nodes().size() == 12);

        // 连续加删不脱节
        for (int i = 0; i < 5; i++) draft.addNode();
        for (int i = 0; i < 3; i++) draft.removeNode();
        check("★多轮增删后仍一致", draft.nodeCount() == 14 && draft.nodes().size() == 14);

        // 边界
        TimeTableDraft low = TimeTableDraft.of(null, 1);
        check("1 节时删节返回 false", !low.removeNode());
        check("1 节时数量不变", low.nodeCount() == 1);

        TimeTableDraft high = TimeTableDraft.of(null, TimeTableDraft.MAX_NODES);
        check("满节时加节返回 false", !high.addNode());
        check("满节时数量不变", high.nodeCount() == TimeTableDraft.MAX_NODES);
    }

    private static void testResetDefaults() {
        TimeTableDraft draft = TimeTableDraft.of(null, 8);
        draft.setNodeTime(1, "23:00", "23:40");
        draft.setNodeTime(2, "23:10", "23:50");   // 与第 1 节交叉
        check("恢复前不合法", !draft.isValid());

        draft.resetDefaults();
        check("★恢复默认后节数不变（8）", draft.nodeCount() == 8);
        check("★恢复默认后必然合法", draft.isValid());
        check("恢复默认后第 1 节回到 08:00", "08:00".equals(draft.node(1).startTime));
        check("恢复默认后无行内问题", draft.issues().isEmpty());

        // 默认作息为华农 15 节：1..15 节必须全部合法。
        boolean allValid = true;
        for (int count = 1; count <= TimeTableSettings.DEFAULT_NODES; count++) {
            TimeTableDraft sample = TimeTableDraft.of(null, count);
            sample.resetDefaults();
            if (!sample.isValid()) allValid = false;
        }
        check("★1..15 节的默认表全部合法（华农作息）", allValid);

        // 16..20 节超出华农表：默认留空，必须**明确提示请填写**，而不是报倒置/格式错误。
        boolean overTips = true;
        for (int count = TimeTableSettings.DEFAULT_NODES + 1; count <= TimeTableDraft.MAX_NODES; count++) {
            TimeTableDraft sample = TimeTableDraft.of(null, count);
            sample.resetDefaults();
            String error = sample.validate();
            // validate() 只返回第一条：第一个空节是第 16 节（华农表末节之后）。
            if (error.isEmpty() || !error.contains("请填写第 16 节时间")) overTips = false;
            if (error.contains("早于结束") || error.contains("格式")) overTips = false;
            // issues() 必须把其余空节也一并列出，方便用户一轮填完。
            if (sample.issues().size() != count - TimeTableSettings.DEFAULT_NODES) overTips = false;
        }
        check("★16..20 节默认留空且提示「请填写」而非倒置/格式误报", overTips);
    }

    private static void testNodeTimeEditing() {
        TimeTableDraft draft = TimeTableDraft.of(null, 6);
        check("改第 2 节成功", draft.setNodeTime(2, "09:00", "09:45"));
        check("改后值正确", "09:00".equals(draft.node(2).startTime)
                && "09:45".equals(draft.node(2).endTime));
        check("改一节不改节数", draft.nodeCount() == 6);

        check("节次越界返回 false", !draft.setNodeTime(7, "09:00", "09:45"));
        check("节次为 0 返回 false", !draft.setNodeTime(0, "09:00", "09:45"));
        check("非法时间格式返回 false", !draft.setNodeTime(2, "abc", "09:45"));
        check("非法时间不改动原值", "09:00".equals(draft.node(2).startTime));

        // 编辑中允许出现非法组合（不拦），由保存时校验兜底
        check("允许写入倒置时间", draft.setNodeTime(3, "11:00", "10:00"));
        check("★倒置后草稿不合法", !draft.isValid());
        check("倒置问题定位到第 3 节", draft.issueOf(3).contains("第 3 节"));
    }

    private static void testValidation() {
        TimeTableDraft draft = TimeTableDraft.of(null, 5);
        check("默认表校验通过", draft.validate().isEmpty());

        draft.setNodeTime(2, "10:00", "11:00");
        draft.setNodeTime(3, "10:30", "11:30");   // 与第 2 节交叉
        String cross = draft.validate();
        check("★交叉被检出", !cross.isEmpty());
        check("交叉文案指明节次", cross.contains("第 3 节"));

        draft.resetDefaults();
        draft.setNodeTime(1, "09:00", "08:00");
        String reversed = draft.validate();
        check("★起止倒置被检出", !reversed.isEmpty());
        check("倒置文案正确", reversed.contains("开始时间必须早于结束时间"));

        // 合法边界：前一节结束 == 后一节开始
        TimeTableDraft touching = TimeTableDraft.of(null, 2);
        touching.setNodeTime(1, "08:00", "08:45");
        touching.setNodeTime(2, "08:45", "09:30");
        check("★首尾相接不算交叉", touching.isValid());

        // 相邻节次倒序同样算交叉
        TimeTableDraft swapped = TimeTableDraft.of(null, 2);
        swapped.setNodeTime(1, "10:00", "10:45");
        swapped.setNodeTime(2, "08:00", "08:45");
        check("后一节早于前一节被检出为交叉", !swapped.isValid());
    }

    private static void testIssues() {
        TimeTableDraft draft = TimeTableDraft.of(null, 5);
        draft.setNodeTime(1, "09:00", "08:00");    // 倒置
        draft.setNodeTime(2, "08:10", "08:50");    // 与第 1 节无效结束无法比较 → 单独判
        draft.setNodeTime(4, "10:00", "10:45");
        draft.setNodeTime(5, "10:30", "11:00");    // 与第 4 节交叉

        List<TimeTableDraft.Issue> issues = draft.issues();
        check("★问题多于一条时全部返回", issues.size() >= 2);
        check("第 1 节（倒置）在问题列表里", hasIssue(issues, 1));
        check("第 5 节（交叉）在问题列表里", hasIssue(issues, 5));
        check("无问题的第 3 节不在列表里", !hasIssue(issues, 3));
        check("issueOf(5) 非空", !draft.issueOf(5).isEmpty());
        check("issueOf(3) 为空", draft.issueOf(3).isEmpty());
        check("issueOf 对越界节次为空", draft.issueOf(99).isEmpty());

        // 全部合法时无问题
        draft.resetDefaults();
        check("★合法时 issues 为空（行内不标红）", draft.issues().isEmpty());
    }

    private static void testClampAndBounds() {
        TimeTableDraft draft = TimeTableDraft.of(null, 10);
        draft.setNodeCount(0);
        check("★节数下界夹到 1", draft.nodeCount() == TimeTableDraft.MIN_NODES);
        draft.setNodeCount(-5);
        check("★负数夹到 1", draft.nodeCount() == 1);
        draft.setNodeCount(999);
        check("★上界夹到 MAX_NODES", draft.nodeCount() == TimeTableDraft.MAX_NODES);
        draft.setNodeCount(TimeTableDraft.MAX_NODES + 1);
        check("超上限仍夹到 MAX_NODES", draft.nodeCount() == TimeTableDraft.MAX_NODES);

        check("MAX_NODES 与 TimeTableSettings 一致",
                TimeTableDraft.MAX_NODES == TimeTableSettings.MAX_NODES);
        check("空草稿节数为 1", TimeTableDraft.empty().nodeCount() == 1);
        check("空草稿合法", TimeTableDraft.empty().isValid());
    }

    // ------------------------------------------------------------------

    private static boolean hasIssue(List<TimeTableDraft.Issue> issues, int node) {
        for (TimeTableDraft.Issue issue : issues) {
            if (issue.node == node) return true;
        }
        return false;
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
