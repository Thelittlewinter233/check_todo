package com.checktodo.app.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 判定失败转移链（纯函数决策，零依赖，JVM 可测）。
 *
 * <p><b>产品决策</b>：AI 判定按用户配置的顺序最多尝试 {@value #MAX_MODELS} 个模型；
 * 单个模型失败（网络异常 / 非 2xx / 响应不可解析 / 文本模型不支持图片）→ 自动转移
 * 下一个；全部失败 → 走既有降级路径（完成 + 待复核），打卡永不阻塞。</p>
 *
 * <p><b>例外</b>：401/403（api key 无效）换模型也无济于事——不转移，直接终局降级。
 * 429（限流/额度）是<b>可重试失败</b>，换下一个模型。</p>
 *
 * <p><b>空位纪律</b>：链里的空白位（设置界面留空的输入框）不发起请求——
 * {@link #sanitize} 直接剔除，{@link #decide} 只会返回非空模型。</p>
 *
 * <p>本类只做决策，不发请求：壳层（GlmHttp）按 {@link Decision} 逐个尝试，
 * 把每次尝试结果记为 {@link Attempt} 追加进来再问下一步。</p>
 */
public final class ModelChain {

    /** 判定链最多 3 个模型（产品决策）。 */
    public static final int MAX_MODELS = 3;

    private ModelChain() {
    }

    /** 单次尝试的结果分类。 */
    public enum Outcome {
        /** 2xx 且判定可解析：链终止（成功）。 */
        SUCCESS,
        /** 可重试失败：网络异常 / 5xx / 429 / 400 / 响应不可解析 / 不支持图片 → 转移下一个。 */
        RETRYABLE,
        /** 401/403：key 无效，换模型没用 → 终局降级。 */
        AUTH_FAILURE
    }

    /** 一次模型尝试的记录（不可变值对象）。 */
    public static final class Attempt {
        /** 实际请求的模型名（非空）。 */
        public final String model;
        /** 尝试结果分类。 */
        public final Outcome outcome;
        /** 失败原因文案（成功时为空串）。 */
        public final String why;
        /** HTTP 状态码；0 = 没有拿到 HTTP 响应（网络异常 / 不可解析）。 */
        public final int status;

        public Attempt(String model, Outcome outcome, String why, int status) {
            this.model = model == null ? "" : model.trim();
            this.outcome = outcome == null ? Outcome.RETRYABLE : outcome;
            this.why = why == null ? "" : why;
            this.status = status;
        }

        public static Attempt success(String model) {
            return new Attempt(model, Outcome.SUCCESS, "", 200);
        }

        public static Attempt failure(String model, Outcome outcome, String why, int status) {
            return new Attempt(model, outcome, why, status);
        }
    }

    /** 决策结果（不可变值对象）。 */
    public static final class Decision {
        /** 链是否已终止（成功或终局降级）。 */
        public final boolean finished;
        /** 终止且失败 → 走既有降级路径（完成 + 待复核）。 */
        public final boolean degraded;
        /** 未终止时应尝试的下一个模型（非空；终止时为 null）。 */
        public final String nextModel;
        /** 终止且成功时的模型名（其余为 null）。 */
        public final String successModel;
        /** 终局降级原因（其余为空串）。 */
        public final String failureWhy;

        private Decision(boolean finished, boolean degraded, String nextModel,
                         String successModel, String failureWhy) {
            this.finished = finished;
            this.degraded = degraded;
            this.nextModel = nextModel;
            this.successModel = successModel;
            this.failureWhy = failureWhy == null ? "" : failureWhy;
        }

        static Decision next(String model) {
            return new Decision(false, false, model, null, "");
        }

        static Decision success(String model) {
            return new Decision(true, false, null, model, "");
        }

        static Decision degrade(String why) {
            return new Decision(true, true, null, null, why);
        }
    }

    /**
     * 归一化链：逐项 trim、剔除 null 与空白位（空位不发起请求）、截断到
     * {@value #MAX_MODELS} 个。null 链 → 空列表。
     */
    public static List<String> sanitize(List<String> chain) {
        List<String> result = new ArrayList<>();
        if (chain == null) return result;
        for (String raw : chain) {
            if (raw == null) continue;
            String model = raw.trim();
            if (model.isEmpty()) continue;
            if (result.size() >= MAX_MODELS) break;
            result.add(model);
        }
        return result;
    }

    /** 实际生效的链：sanitize 后仍为空 → 回落单模型（默认链首）。 */
    public static List<String> effective(List<String> chain) {
        List<String> models = sanitize(chain);
        if (models.isEmpty()) {
            // 空链（未配置/全空位）整体回落默认三模型链，而不是单元素——
            // 否则链位 2/3 的失败转移能力会被悄悄丢失。
            return AiVerifyConfig.defaultModels();
        }
        return models;
    }

    /**
     * 核心决策：给定链与「已完成尝试」，决定下一个模型或终局。
     *
     * <ul>
     *   <li>没有尝试 → 链首；</li>
     *   <li>最后一次成功 → 终止（successModel）；</li>
     *   <li>最后一次 401/403 → 终止降级（不转移）；</li>
     *   <li>最后一次可重试失败且还有下一个非空位 → 转移；</li>
     *   <li>可重试失败且无下一个 → 终止降级（全部 N 个模型失败 + 最后原因）。</li>
     * </ul>
     *
     * <p>位置推进按尝试次数计（而非按模型名查找），链中重名模型也不会错位。</p>
     */
    public static Decision decide(List<String> chain, List<Attempt> attempts) {
        List<String> models = effective(chain);
        int done = attempts == null ? 0 : attempts.size();
        if (done <= 0) return Decision.next(models.get(0));
        Attempt last = attempts.get(done - 1);
        if (last == null) return Decision.next(models.get(0));
        if (last.outcome == Outcome.SUCCESS) return Decision.success(last.model);
        if (last.outcome == Outcome.AUTH_FAILURE) return Decision.degrade(authWhy(last));
        if (done < models.size()) return Decision.next(models.get(done));
        return Decision.degrade(allFailedWhy(models, last));
    }

    /** HTTP 状态码 → 失败分类：401/403 终局，其余（含 429/5xx/400/0）可重试转移。 */
    public static Outcome classifyFailure(int httpStatus) {
        return isAuthStatus(httpStatus) ? Outcome.AUTH_FAILURE : Outcome.RETRYABLE;
    }

    /** 401/403 = key 无效（换模型没用）。 */
    public static boolean isAuthStatus(int httpStatus) {
        return httpStatus == 401 || httpStatus == 403;
    }

    /**
     * 判定成功后给 reason 加实际使用模型的前缀（如「[glm-4v-flash] 桌面整洁」），
     * 让用户知道是谁判的。verdict 为 null / 模型名为空时原样返回。
     */
    public static AiVerdict prefixReason(AiVerdict verdict, String model) {
        if (verdict == null) return null;
        String name = model == null ? "" : model.trim();
        if (name.isEmpty()) return verdict;
        String reason = verdict.reason == null || verdict.reason.trim().isEmpty()
                ? "[" + name + "]"
                : "[" + name + "] " + verdict.reason.trim();
        return new AiVerdict(verdict.verdict, verdict.confidence, reason);
    }

    private static String authWhy(Attempt attempt) {
        return "API key 鉴权失败（HTTP " + attempt.status + "），不转移判定链：" + whyOf(attempt);
    }

    private static String allFailedWhy(List<String> models, Attempt last) {
        return "判定链全部 " + models.size() + " 个模型失败（最后 " + last.model + "：" + whyOf(last) + "）";
    }

    private static String whyOf(Attempt attempt) {
        return attempt.why == null || attempt.why.trim().isEmpty() ? "未知原因" : attempt.why.trim();
    }
}
