package com.checktodo.app.domain;

/**
 * GLM 判定结果值对象（不可变）。
 *
 * <p>三态语义（产品决策）：</p>
 * <ul>
 *   <li>{@link Verdict#PASS}：判定通过 → 完成打卡。</li>
 *   <li>{@link Verdict#FAIL}：判定不通过 → 不完成，展示理由，可重拍。</li>
 *   <li>{@link Verdict#UNCERTAIN}：模型不确定 → 完成打卡但打「待复核」标记。</li>
 * </ul>
 *
 * <p>网络失败 / 未配 key / 响应不可解析都<b>不是</b> {@code FAIL}，而是走
 * {@link VerificationPolicy#degraded(String)} 的降级路径（等同 UNCERTAIN + degraded 标记），
 * 保证打卡流程永不阻塞。</p>
 */
public final class AiVerdict {
    /** 判定结论。 */
    public enum Verdict {
        PASS("pass"),
        FAIL("fail"),
        UNCERTAIN("uncertain");

        private final String key;

        Verdict(String key) {
            this.key = key;
        }

        /** 持久化 / 协议用的小写键。 */
        public String key() {
            return key;
        }
    }

    /** 判定结论；未知字符串一律保守回落 {@link Verdict#UNCERTAIN}。 */
    public final Verdict verdict;
    /** 置信度 0..100，解析时已截断。 */
    public final int confidence;
    /** 简短中文理由；模型没给则为空串。 */
    public final String reason;

    public AiVerdict(Verdict verdict, int confidence, String reason) {
        this.verdict = verdict == null ? Verdict.UNCERTAIN : verdict;
        this.confidence = confidence < 0 ? 0 : Math.min(100, confidence);
        this.reason = reason == null ? "" : reason;
    }

    /** 判定结论的持久化键。 */
    public String key() {
        return verdict.key();
    }

    /**
     * 解析持久化/协议字符串。null、空串、未知值 → {@link Verdict#UNCERTAIN}
     * （保守：宁可多一次人工复核，也不要把可疑照片当成通过）。
     */
    public static Verdict parseVerdict(String raw) {
        if (raw == null) return Verdict.UNCERTAIN;
        String normalized = raw.trim().toLowerCase(java.util.Locale.US);
        for (Verdict value : Verdict.values()) {
            if (value.key.equals(normalized)) return value;
        }
        return Verdict.UNCERTAIN;
    }
}
