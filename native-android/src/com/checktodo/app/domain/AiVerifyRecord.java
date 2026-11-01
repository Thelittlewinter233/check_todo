package com.checktodo.app.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次 AI 判定的历史记录（不可变值对象），随打卡记录持久化供展示与审计。
 *
 * <p>字段口径：</p>
 * <ul>
 *   <li>{@link #dateKey} / {@link #atMillis}：判定发生的日期键（yyyy-MM-dd）与时刻；</li>
 *   <li>{@link #verdict}：pass / fail / uncertain（字符串，未知值回落 uncertain）；</li>
 *   <li>{@link #confidence}：模型置信度 0..100（降级时为 0）；</li>
 *   <li>{@link #reason}：判定理由或降级原因；</li>
 *   <li>{@link #degraded}：是否为降级记录（网络失败 / 未配 key / 响应不可解析）；</li>
 *   <li>{@link #appealed}：是否被申诉覆盖（用户坚持完成 → 人工推翻判定）；</li>
 *   <li>{@link #completed}：该次判定最终是否完成了打卡（fail 且未申诉 → false）；</li>
 *   <li>{@link #photoHash}：本次照片的 aHash hex（重复判定的历史样本；纯色图为空串）。</li>
 * </ul>
 *
 * <p>持久化走 {@link #toMap()} / {@link #fromMap(Map)}：Map 的值全部是可 JSON
 * 序列化的标量（String/Long/Integer/Boolean），壳层直接转 org.json 写入。</p>
 */
public final class AiVerifyRecord {
    public final String dateKey;
    public final long atMillis;
    public final String verdict;
    public final int confidence;
    public final String reason;
    public final boolean degraded;
    public final boolean appealed;
    public final boolean completed;
    public final String photoHash;

    public AiVerifyRecord(String dateKey, long atMillis, String verdict, int confidence,
                          String reason, boolean degraded, boolean appealed,
                          boolean completed, String photoHash) {
        this.dateKey = dateKey == null ? "" : dateKey;
        this.atMillis = atMillis;
        this.verdict = AiVerdict.parseVerdict(verdict).key();
        this.confidence = Math.max(0, Math.min(100, confidence));
        this.reason = reason == null ? "" : reason;
        this.degraded = degraded;
        this.appealed = appealed;
        this.completed = completed;
        this.photoHash = photoHash == null ? "" : photoHash;
    }

    /** 申诉覆盖：返回新记录，原记录不变（不可变纪律）。 */
    public AiVerifyRecord withAppeal(boolean nextAppealed) {
        return new AiVerifyRecord(dateKey, atMillis, verdict, confidence, reason,
                degraded, nextAppealed, completed, photoHash);
    }

    /**
     * 是否进入「待复核」展示：打卡已完成，但结论是 uncertain、降级、或被申诉覆盖。
     * 未完成的 fail 记录是流程内事件，不算待复核。
     */
    public boolean needsReview() {
        if (!completed) return false;
        return degraded || appealed || AiVerdict.Verdict.UNCERTAIN.key().equals(verdict);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("date", dateKey);
        map.put("at", atMillis);
        map.put("verdict", verdict);
        map.put("confidence", confidence);
        map.put("reason", reason);
        map.put("degraded", degraded);
        map.put("appealed", appealed);
        map.put("completed", completed);
        map.put("photoHash", photoHash);
        return map;
    }

    /**
     * 从持久化 Map 恢复。null / 空 Map → null（不产生脏记录）；
     * 缺字段取保守默认（verdict=uncertain、completed=false）。
     */
    public static AiVerifyRecord fromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return null;
        String dateKey = stringOf(map.get("date"));
        long atMillis = longOf(map.get("at"));
        String verdict = stringOf(map.get("verdict"));
        int confidence = (int) longOf(map.get("confidence"));
        String reason = stringOf(map.get("reason"));
        boolean degraded = booleanOf(map.get("degraded"));
        boolean appealed = booleanOf(map.get("appealed"));
        boolean completed = booleanOf(map.get("completed"));
        String photoHash = stringOf(map.get("photoHash"));
        return new AiVerifyRecord(dateKey, atMillis, verdict, confidence, reason,
                degraded, appealed, completed, photoHash);
    }

    private static String stringOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long longOf(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value == null) return 0L;
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    private static boolean booleanOf(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }
}
