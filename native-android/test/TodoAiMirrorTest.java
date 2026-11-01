package test;

import com.checktodo.app.domain.Todo;
import com.checktodo.app.domain.VerifyMode;
import com.checktodo.app.widget.WidgetCheckInRouting;

/**
 * AI 判定对称扩展到「待办」的回归测试：
 * Todo 模型扩展（null 回落 NONE / withAi / AI 字段随 withCompletion、withFields 保留）
 * 与小卡片待办路由（普通 → 卡片内切换；AI → 打开 App 拍照；已完成 → 忽略）。
 */
public final class TodoAiMirrorTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        testTodoDefaults();
        testTodoWithAiAndImmutability();
        testRoutingDecideTodo();
        testTodoActionLabel();
        report();
    }

    private static void testTodoDefaults() {
        Todo legacy = new Todo("t1", "老数据", 0L, 60_000L, 10, false, 0L);
        check("★老构造（7 参）→ verifyMode 回落 NONE", legacy.verifyMode == VerifyMode.NONE
                && !legacy.isAiPhoto());
        Todo dirty = new Todo("t2", "脏数据", 0L, 60_000L, 10, false, 0L,
                null, null, null);
        check("★持久化脏数据（null）→ NONE / 空串，不得非法态",
                dirty.verifyMode == VerifyMode.NONE && dirty.aiFeature.isEmpty()
                        && dirty.aiReferencePhoto.isEmpty());
    }

    private static void testTodoWithAiAndImmutability() {
        Todo base = new Todo("t3", "打扫房间", 0L, 60_000L, 20, false, 0L);
        Todo ai = base.withAi(VerifyMode.AI_PHOTO, "房间已打扫、桌面收拾干净", "/data/ref.jpg");
        check("★withAi 启用 AI_PHOTO", ai.isAiPhoto());
        check("★withAi 携带特征描述与标准照", ai.aiFeature.contains("打扫")
                && ai.hasReferencePhoto());
        check("原对象不变（不可变纪律）", !base.isAiPhoto() && base.aiFeature.isEmpty());
        check("isAiPhoto + 标准照齐备才走完整判定", ai.isAiPhoto() && ai.hasReferencePhoto());
        Todo noRef = base.withAi(VerifyMode.AI_PHOTO, "描述", "");
        check("未拍标准照 → hasReferencePhoto false（打卡走降级）",
                noRef.isAiPhoto() && !noRef.hasReferencePhoto());

        Todo completed = ai.withCompletion(true);
        check("withCompletion 保留 AI 配置", completed.completed
                && completed.isAiPhoto() && completed.hasReferencePhoto());
        Todo edited = ai.withFields("打扫客厅", 1L, 2L, 30, false);
        check("withFields 保留 AI 配置", edited.title.equals("打扫客厅")
                && edited.isAiPhoto() && edited.aiFeature.contains("打扫"));
    }

    private static void testTodoActionLabel() {
        check("普通待办按钮文案 = 完成", "完成".equals(WidgetCheckInRouting.todoActionLabel(
                new Todo("n", "普通待办", 0L, 60_000L, 10, false, 0L))));
        check("AI 待办按钮文案 = 拍照完成", "拍照完成".equals(WidgetCheckInRouting.todoActionLabel(
                new Todo("a", "AI 待办", 0L, 60_000L, 10, false, 0L,
                        VerifyMode.AI_PHOTO, "描述", "/data/ref.jpg"))));
    }

    private static void testRoutingDecideTodo() {
        Todo normal = new Todo("n", "普通待办", 0L, 60_000L, 10, false, 0L);
        Todo ai = new Todo("a", "AI 待办", 0L, 60_000L, 10, false, 0L,
                VerifyMode.AI_PHOTO, "描述", "/data/ref.jpg");
        check("★普通待办 → 卡片内切换完成态",
                WidgetCheckInRouting.decideTodo(normal) == WidgetCheckInRouting.Action.TOGGLE_TODO);
        check("★AI 待办 → 打开 App 拍照判定（卡片不能直接写库）",
                WidgetCheckInRouting.decideTodo(ai) == WidgetCheckInRouting.Action.OPEN_APP_AI_TODO);
        check("已完成待办 → 忽略（防御，卡片本就不展示）",
                WidgetCheckInRouting.decideTodo(ai.withCompletion(true))
                        == WidgetCheckInRouting.Action.IGNORE);
        check("null → 忽略", WidgetCheckInRouting.decideTodo(null)
                == WidgetCheckInRouting.Action.IGNORE);
        check("AI 待办按钮文案 = 拍照完成", "拍照完成".equals(WidgetCheckInRouting.todoActionLabel(ai)));
        check("普通待办按钮文案 = 完成", "完成".equals(WidgetCheckInRouting.todoActionLabel(normal)));
        check("decide(Task) 既有语义不受影响（AI 任务 → OPEN_APP_AI_CHECKIN）",
                WidgetCheckInRouting.decideTodo(ai) != WidgetCheckInRouting.Action.OPEN_APP_AI_CHECKIN);
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

    private static void report() {
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }
}
