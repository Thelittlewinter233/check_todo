package com.checktodo.app.domain;

/**
 * EXIF 拍摄时间新鲜度判定（L1 离线预检之一），纯函数。
 *
 * <p>拍摄时刻由 Android 壳层用 {@code android.media.ExifInterface} 从
 * {@code DateTimeOriginal} 读出（框架 API，无第三方库），本类只做时间差判定，
 * 因此可在 JVM 上完整测试。</p>
 *
 * <p><b>判定原则：只有「明确知道是旧图」才拦截。</b>很多相机不写 DateTimeOriginal、
 * 截图完全没有 EXIF、设备时钟与照片时钟可能有偏差——这些都判为
 * {@link State#UNKNOWN} 并放行（后续仍有 aHash 重复判定与 L2 视觉判定把关），
 * 避免把正常打卡误拦成死路。反过来，明确超过阈值的旧图必须拦截，
 * 这是「拿旧照片重复打卡」最直接的防线。</p>
 */
public final class ExifFreshness {
    /** 默认新鲜期：拍摄时间与当前时间差超过该分钟数即判为旧图。 */
    public static final int DEFAULT_THRESHOLD_MINUTES = 10;
    /** 未来时刻容忍：照片时间超前设备时间在此范围内视为时钟误差，仍算新鲜。 */
    public static final long FUTURE_TOLERANCE_MS = 5 * 60_000L;

    /** 新鲜度状态。 */
    public enum State {
        /** 明确在新鲜期内（含小幅超前）。 */
        FRESH,
        /** 明确超过新鲜期：旧图，拦截。 */
        STALE,
        /** 无法判定（无 EXIF / 时钟明显不符）：不拦截。 */
        UNKNOWN
    }

    private ExifFreshness() {
    }

    /**
     * 判定新鲜度。
     *
     * @param shotAtMillis    EXIF 拍摄时刻（毫秒）；null 表示无 EXIF 或解析失败
     * @param nowMillis       当前时刻（毫秒）
     * @param thresholdMinutes 新鲜期分钟数；≤0 时回落 {@link #DEFAULT_THRESHOLD_MINUTES}
     */
    public static State evaluate(Long shotAtMillis, long nowMillis, int thresholdMinutes) {
        if (shotAtMillis == null) return State.UNKNOWN;
        int threshold = thresholdMinutes > 0 ? thresholdMinutes : DEFAULT_THRESHOLD_MINUTES;
        long ageMillis = nowMillis - shotAtMillis;
        if (ageMillis > threshold * 60_000L) return State.STALE;
        // 照片时间明显超前设备时间：时钟不可信，无法据此判定新旧。
        if (ageMillis < -FUTURE_TOLERANCE_MS) return State.UNKNOWN;
        return State.FRESH;
    }

    /** 该状态是否需要拦截（只有 STALE）。 */
    public static boolean blocks(State state) {
        return state == State.STALE;
    }

    /**
     * 拦截原因文案；非 STALE 返回空串。
     *
     * @param ageMillis        照片距当前的时长（毫秒），用于把分钟数写进文案
     * @param thresholdMinutes 生效的新鲜期分钟数（≤0 已回落默认）
     */
    public static String reasonText(State state, long ageMillis, int thresholdMinutes) {
        if (state != State.STALE) return "";
        int threshold = thresholdMinutes > 0 ? thresholdMinutes : DEFAULT_THRESHOLD_MINUTES;
        long minutes = Math.max(0, ageMillis / 60_000L);
        return "这张照片拍摄于 " + minutes + " 分钟前，超过 " + threshold
                + " 分钟新鲜期，判为旧图。请重新拍摄当前实况照片。";
    }
}
