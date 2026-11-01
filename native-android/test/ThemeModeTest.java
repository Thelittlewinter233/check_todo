import com.checktodo.app.domain.ThemeMode;

/**
 * 主题模式纯 Java 测试：持久化值解析（含缺省/非法回落）、循环顺序、值稳定性。
 */
public final class ThemeModeTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        testParseKnownValues();
        testParseFallback();
        testCycleOrder();
        testCycleIsClosed();
        testValueStability();

        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    private static void testParseKnownValues() {
        check("light 解析为 LIGHT", ThemeMode.fromString("light") == ThemeMode.LIGHT);
        check("dark 解析为 DARK", ThemeMode.fromString("dark") == ThemeMode.DARK);
        check("follow_system 解析为 FOLLOW_SYSTEM",
                ThemeMode.fromString("follow_system") == ThemeMode.FOLLOW_SYSTEM);
    }

    private static void testParseFallback() {
        check("null 回落 FOLLOW_SYSTEM", ThemeMode.fromString(null) == ThemeMode.FOLLOW_SYSTEM);
        check("空串回落 FOLLOW_SYSTEM", ThemeMode.fromString("") == ThemeMode.FOLLOW_SYSTEM);
        check("未知值回落 FOLLOW_SYSTEM", ThemeMode.fromString("sepia") == ThemeMode.FOLLOW_SYSTEM);
        check("大小写不匹配回落 FOLLOW_SYSTEM", ThemeMode.fromString("LIGHT") == ThemeMode.FOLLOW_SYSTEM);
    }

    private static void testCycleOrder() {
        check("LIGHT 下一个是 DARK", ThemeMode.LIGHT.next() == ThemeMode.DARK);
        check("DARK 下一个是 FOLLOW_SYSTEM", ThemeMode.DARK.next() == ThemeMode.FOLLOW_SYSTEM);
        check("FOLLOW_SYSTEM 下一个回到 LIGHT", ThemeMode.FOLLOW_SYSTEM.next() == ThemeMode.LIGHT);
    }

    private static void testCycleIsClosed() {
        ThemeMode mode = ThemeMode.LIGHT;
        for (int i = 0; i < 3; i++) mode = mode.next();
        check("循环 3 次回到起点", mode == ThemeMode.LIGHT);
    }

    private static void testValueStability() {
        boolean stable = true;
        for (ThemeMode mode : ThemeMode.values()) {
            stable = stable && ThemeMode.fromString(mode.value()) == mode;
        }
        check("value() 与 fromString 往返一致", stable);
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }
}
