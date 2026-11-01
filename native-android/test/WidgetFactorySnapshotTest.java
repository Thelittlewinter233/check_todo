package test;

import com.checktodo.app.widget.WidgetFactorySnapshot;
import com.checktodo.app.widget.WidgetModes;

import java.util.ArrayList;
import java.util.List;

/**
 * 小卡片「三视图切换偶发串状态」的机制级回归测试。
 *
 * <p><b>根因回顾：</b>旧 {@code WidgetFactoryState} 允许 {@code onDataSetChanged} 用<b>实时</b>
 * 存储模式覆盖构造时的模式。于是出现「行数已是新模式、列表内容仍是旧模式」的中间态：
 * {@code getCount()} 返回新模式的条数，{@code getViewAt} 却从旧模式的列表取 → 串视图/越界/点错。</p>
 *
 * <p>本测试用纯 Java 复现三种真实竞争，并锁死四条纪律：</p>
 * <ol>
 *   <li>构造即冻结，任何后续操作不得改动快照模式；</li>
 *   <li>行数与内容同源（{@code count == rows.size()}，且两者同代）；</li>
 *   <li>fillInIntent 携带的 mode 与当前不一致时必须被拒；</li>
 *   <li>宿主复用旧实例时（storedMode != snapshot.mode）能检出并要求重建。</li>
 * </ol>
 */
public final class WidgetFactorySnapshotTest {
    private static int passed;
    private static int failed;

    private static final int ID = 42;

    public static void main(String[] args) {
        testFreezeFromUri();
        testFreezeUriWinsEvenWhenStoredDiffers();
        testNoLiveReread();
        testRowsAndCountSameSource();
        testStaleClickRejected();
        testRapidSwitchRace();
        testDataChangeDuringSwitch();
        testHostReuseDetection();
        testNoLiveStoreReadInOnDataSetChanged();
        testBoundaries();
        report();
    }

    // ------------------------------------------------------------------

    private static void testFreezeFromUri() {
        WidgetFactorySnapshot snap = WidgetFactorySnapshot.freeze(
                ID, uri(ID, WidgetModes.MODE_COURSES), WidgetModes.MODE_TASKS);
        check("★URI 指定课表模式（与存储冲突时以 URI 为准——URI 与上屏标题同代）",
                snap.mode() == WidgetModes.MODE_COURSES);

        WidgetFactorySnapshot same = WidgetFactorySnapshot.freeze(
                ID, uri(ID, WidgetModes.MODE_COURSES), WidgetModes.MODE_COURSES);
        check("URI 与存储一致 → 课表模式", same.mode() == WidgetModes.MODE_COURSES);

        WidgetFactorySnapshot fromUriOnly = WidgetFactorySnapshot.freeze(
                ID, uri(ID, WidgetModes.MODE_TODOS), -1);
        check("存储无效时回落 URI", fromUriOnly.mode() == WidgetModes.MODE_TODOS);

        WidgetFactorySnapshot none = WidgetFactorySnapshot.freeze(ID, null, WidgetModes.MODE_TODOS);
        check("URI 为 null 时回落存储", none.mode() == WidgetModes.MODE_TODOS);
    }

    private static void testFreezeUriWinsEvenWhenStoredDiffers() {
        // 世代权威 = 服务 URI（与标题/切换按钮同刻同源）。存储是异步漂移的另一路信号，
        // 用它覆盖 URI 会让正文跳到上屏标题之外的世代——2026-10-26 真机
        // 「标题课表对上打卡正文」的根因，此处反向钉死。
        WidgetFactorySnapshot snap = WidgetFactorySnapshot.freeze(
                ID, uri(ID, WidgetModes.MODE_TASKS), WidgetModes.MODE_COURSES);
        check("★存储比 URI 新也不听存储 → 采用 URI（打卡）", snap.mode() == WidgetModes.MODE_TASKS);
    }

    private static void testNoLiveReread() {
        WidgetFactorySnapshot snap = WidgetFactorySnapshot.of(ID, WidgetModes.MODE_TASKS);
        WidgetFactorySnapshot bound = snap.bind(5);
        check("绑定后模式仍是构造时的打卡", bound.mode() == WidgetModes.MODE_TASKS);
        check("★bind() 不改变模式（不读实时状态）", bound.mode() == snap.mode());
        check("bind 链式多次也不改模式",
                bound.bind(9).bind(1).mode() == WidgetModes.MODE_TASKS);
        check("原快照不可变（bind 返回新对象）", snap.count() == 0 && bound.count() == 5);
    }

    private static void testRowsAndCountSameSource() {
        List<String> tasks = list("打卡A", "打卡B", "打卡C");
        WidgetFactorySnapshot.Rows<String> rows =
                WidgetFactorySnapshot.Rows.of(WidgetModes.MODE_TASKS, tasks);
        WidgetFactorySnapshot snap = WidgetFactorySnapshot.of(ID, WidgetModes.MODE_TASKS).bind(rows.size());
        check("★count 等于内容行数", snap.count() == rows.size());
        check("★快照模式与内容模式同代", snap.mode() == rows.mode());
        check("行可按下标取到", "打卡A".equals(rows.get(0)));

        // 内容不可变：外部改动不影响
        tasks.clear();
        check("★Rows 持有副本（外部 clear 不影响）", rows.size() == 3);
    }

    private static void testStaleClickRejected() {
        WidgetFactorySnapshot courses = WidgetFactorySnapshot.of(ID, WidgetModes.MODE_COURSES).bind(2);
        check("★同一模式的点击被接受", courses.accepts(WidgetModes.MODE_COURSES));
        check("★旧模式（待办）的点击被拒", !courses.accepts(WidgetModes.MODE_TODOS));
        check("★旧模式（打卡）的点击被拒", !courses.accepts(WidgetModes.MODE_TASKS));
        check("拒绝时给出原因", courses.staleReason(WidgetModes.MODE_TODOS).contains("已丢弃"));
        check("接受时原因为空", courses.staleReason(WidgetModes.MODE_COURSES).isEmpty());
    }

    /**
     * 关键复现：快速连续切换（打卡→待办→课表），每次切换都创建新快照。
     * 旧快照必须保持自己的模式，绝不能"跟着"变成最新模式。
     */
    private static void testRapidSwitchRace() {
        WidgetFactorySnapshot gen1 = WidgetFactorySnapshot.of(ID, WidgetModes.MODE_TASKS).bind(3);
        WidgetFactorySnapshot gen2 = WidgetFactorySnapshot.of(ID, WidgetModes.MODE_TODOS).bind(4);
        WidgetFactorySnapshot gen3 = WidgetFactorySnapshot.of(ID, WidgetModes.MODE_COURSES).bind(0);

        check("★第 1 代快照仍是打卡（3 行）",
                gen1.mode() == WidgetModes.MODE_TASKS && gen1.count() == 3);
        check("★第 2 代快照仍是待办（4 行）",
                gen2.mode() == WidgetModes.MODE_TODOS && gen2.count() == 4);
        check("★第 3 代快照是课表（0 行，空态）",
                gen3.mode() == WidgetModes.MODE_COURSES && gen3.count() == 0);

        // 迟到的点击属于第 1 代（打卡），此时当前已是课表 → 必须丢弃
        check("★迟到的旧点击被第 3 代拒绝", !gen3.accepts(gen1.mode()));
        check("第 1 代自己仍接受自己的点击", gen1.accepts(gen1.mode()));
    }

    /** 数据变更（行数变化）不得改变模式，也不得让旧快照"变空"。 */
    private static void testDataChangeDuringSwitch() {
        WidgetFactorySnapshot v1 = WidgetFactorySnapshot.of(ID, WidgetModes.MODE_TASKS).bind(2);
        WidgetFactorySnapshot v2 = v1.bind(0);   // 数据被删光
        check("数据清空后模式不变（仍是打卡）", v2.mode() == WidgetModes.MODE_TASKS);
        check("数据清空后行数为 0（空态而非串视图）", v2.count() == 0);
        check("★旧快照 v1 仍保留 2 行（互不影响）", v1.count() == 2);
    }

    /** 世代一致性模拟：正文只跟随冻结代（URI 代），存储漂移不把正文拽离上屏标题。 */
    private static void testHostReuseDetection() {
        // 场景：标题（待办 gen）已上屏；下一次切换把存储推到课表；
        // 旧 Factory 收到迟到的 onDataSetChanged。
        WidgetFactorySnapshot displayedGen = WidgetFactorySnapshot.freeze(
                ID, uri(ID, WidgetModes.MODE_TODOS), WidgetModes.MODE_TODOS).bind(2);
        check("★迟到刷新仍按冻结代重建（正文与上屏标题同代，不被存储拽走）",
                displayedGen.mode() == WidgetModes.MODE_TODOS);
        check("冻结代行数保留", displayedGen.count() == 2);

        // 启动器按新 URI 重绑后（新 Factory 冻结课表代），正文与新一代标题同代原子切换。
        WidgetFactorySnapshot rebound = WidgetFactorySnapshot.freeze(
                ID, uri(ID, WidgetModes.MODE_COURSES), WidgetModes.MODE_COURSES).bind(3);
        check("重绑后正文与新一代标题同代", rebound.mode() == WidgetModes.MODE_COURSES);
        check("重绑后旧代点击被拒", !rebound.accepts(WidgetModes.MODE_TODOS));
    }

    /** 读源码断言：onDataSetChanged 不得实时读存储（世代权威唯一化的结构性保证）。 */
    private static void testNoLiveStoreReadInOnDataSetChanged() {
        String source = readSource("src/com/checktodo/app/widget/CheckTodoWidgetService.java");
        int start = source.indexOf("public void onDataSetChanged");
        int end = source.indexOf("private List<Row> buildRows");
        check("onDataSetChanged 方法体存在", start >= 0 && end > start);
        String body = source.substring(start, end);
        check("★onDataSetChanged 不读实时存储（getMode）", !body.contains("getMode("));
        check("★onDataSetChanged 按冻结快照模式重建", body.contains("snapshot.mode()"));
        // 延迟到重绑才换代的佐证：needsRebuild（跟随存储的旧机制）必须已删除。
        String snapshotSource = readSource("src/com/checktodo/app/widget/WidgetFactorySnapshot.java");
        check("★needsRebuild（跟随存储的旧机制）已删除", !snapshotSource.contains("needsRebuild"));
    }

    private static void testBoundaries() {
        WidgetFactorySnapshot empty = WidgetFactorySnapshot.EMPTY;
        check("EMPTY 未绑定", !empty.loaded());
        check("EMPTY 行数 0", empty.count() == 0);

        WidgetFactorySnapshot bound = WidgetFactorySnapshot.of(ID, WidgetModes.MODE_TASKS).bind(3);
        check("未越界可渲染", bound.hasRow(0) && bound.hasRow(2));
        check("★越界不可渲染（返回 false 让 getViewAt 给 null）", !bound.hasRow(3) && !bound.hasRow(-1));

        WidgetFactorySnapshot clamped = WidgetFactorySnapshot.of(ID, WidgetModes.MODE_TASKS).bind(-5);
        check("负数行数夹到 0", clamped.count() == 0);

        WidgetFactorySnapshot weird = WidgetFactorySnapshot.of(ID, 99);
        check("越界模式归一到打卡", weird.mode() == WidgetModes.MODE_TASKS);

        check("describe 含模式与行数", bound.describe().contains("打卡"));
    }

    // ------------------------------------------------------------------

    private static String uri(int id, int mode) {
        return "checktodo://widget/" + id + "/mode/" + mode;
    }

    private static List<String> list(String... items) {
        List<String> out = new ArrayList<>();
        for (String item : items) out.add(item);
        return out;
    }

    private static String readSource(String path) {
        java.io.InputStream stream = null;
        try {
            stream = new java.io.FileInputStream(new java.io.File(path));
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = stream.read(chunk)) >= 0) buffer.write(chunk, 0, count);
            return new String(buffer.toByteArray(), "UTF-8");
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("读取源码失败：" + path, exception);
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (java.io.IOException ignored) {
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
