package com.checktodo.app.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AI 判定打卡的决策纯函数：L1 离线预检（必过闸）与 L2 判定结果 → 打卡结果映射。
 *
 * <p><b>流程（产品决策）</b>：拍照 → L1 离线预检 → （通过才）调 GLM → 映射打卡结果。</p>
 *
 * <p><b>L1 离线预检</b>是必过闸，无网络也要做，两项：</p>
 * <ol>
 *   <li>EXIF 拍摄时间新鲜度（{@link ExifFreshness}）：明确超过阈值 → 判为旧图拦截；</li>
 *   <li>aHash 感知哈希去重（{@link ImageHash}）：与该任务<b>已完成打卡</b>的历史照片
 *       汉明距离 ≤ {@link ImageHash#DUPLICATE_MAX_DISTANCE} → 判为疑似重复图拦截。</li>
 * </ol>
 * <p>两项都可能「无法判定」（无 EXIF、纯色图哈希不可用、历史脏数据）——此时一律
 * <b>放行</b>，由后面的 L2 视觉判定把关；拦截只在证据明确时发生，避免把正常打卡堵死。</p>
 *
 * <p><b>L2 映射</b>：pass → 完成；fail → 不完成、展示理由、可重拍；uncertain → 完成 + 待复核。</p>
 *
 * <p><b>降级铁律</b>：网络失败 / 超时 / 未配 key / 额度耗尽 / 响应不可解析，一律
 * {@link #degraded(String)}——完成打卡 + 待复核标记，<b>打卡流程永不阻塞</b>。
 * 降级绝不映射为 fail（那是把「我们联系不上模型」当成「用户作弊」）。</p>
 */
public final class VerificationPolicy {
    /** 预检结果码：通过。 */
    public static final String CODE_OK = "ok";
    /** 预检结果码：旧图。 */
    public static final String CODE_STALE = "stale";
    /** 预检结果码：疑似重复图。 */
    public static final String CODE_DUPLICATE = "duplicate";

    private VerificationPolicy() {
    }

    /** L1 离线预检结果。 */
    public static final class Precheck {
        /** 是否放行。 */
        public final boolean passed;
        /** 结果码：{@link #CODE_OK} / {@link #CODE_STALE} / {@link #CODE_DUPLICATE}。 */
        public final String code;
        /** 拦截原因文案（面向用户，放行时为空串）。 */
        public final String reason;
        /** 本次预检生效的照片渠道（审计与测试口径用；放行/拦截都记录）。 */
        public final PhotoChannel channel;
        /** 本次是否真的执行了 EXIF 新鲜度校验（相册渠道为 false）。 */
        public final boolean freshnessChecked;

        Precheck(boolean passed, String code, String reason) {
            this(passed, code, reason, PhotoChannel.CAMERA, true);
        }

        Precheck(boolean passed, String code, String reason,
                 PhotoChannel channel, boolean freshnessChecked) {
            this.passed = passed;
            this.code = code;
            this.reason = reason == null ? "" : reason;
            this.channel = PhotoChannel.orCamera(channel);
            this.freshnessChecked = freshnessChecked;
        }

        static Precheck ok() {
            return new Precheck(true, CODE_OK, "");
        }

        static Precheck blocked(String code, String reason) {
            return new Precheck(false, code, reason);
        }
    }

    /**
     * L1 离线预检（拍照渠道口径，保持既有行为不变）。
     *
     * <p>等价于 {@code precheck(PhotoChannel.CAMERA, ...)}——EXIF 新鲜度 + aHash 去重。</p>
     *
     * @param shotAtMillis     当前照 EXIF 拍摄时刻；null = 无 EXIF（不拦截）
     * @param nowMillis        当前时刻
     * @param thresholdMinutes 新鲜期分钟数；≤0 回落 {@link ExifFreshness#DEFAULT_THRESHOLD_MINUTES}
     * @param currentHash      当前照 aHash；null = 哈希不可用（纯色图，跳过重复判定）
     * @param historyHashes    该任务已完成打卡的历史 aHash hex；null/空 = 首次打卡
     */
    public static Precheck precheck(Long shotAtMillis, long nowMillis, int thresholdMinutes,
                                    Long currentHash, List<String> historyHashes) {
        return precheck(PhotoChannel.CAMERA, shotAtMillis, nowMillis, thresholdMinutes,
                currentHash, historyHashes);
    }

    /**
     * L1 离线预检（<b>渠道显式版</b>）。
     *
     * <p><b>渠道口径（产品决策，测试钉死）</b>：</p>
     * <ul>
     *   <li>{@link PhotoChannel#CAMERA}：新鲜度 + 去重都执行（既有行为）；</li>
     *   <li>{@link PhotoChannel#GALLERY}：<b>跳过新鲜度</b>——即使 EXIF 明确是旧图也放行
     *       （相册图本来就是历史图，新鲜期对它没有意义）；<b>保留去重</b>——与该任务
     *       已完成打卡的历史照片高度相似仍然拦截（防重复交同一张图）。</li>
     * </ul>
     *
     * <p>渠道为 null → 按拍照渠道处理（最严格口径，脏数据不放水）。</p>
     */
    public static Precheck precheck(PhotoChannel channel, Long shotAtMillis, long nowMillis,
                                    int thresholdMinutes, Long currentHash,
                                    List<String> historyHashes) {
        PhotoChannel safe = PhotoChannel.orCamera(channel);

        // ① 新鲜度：只有明确知道是旧图才拦；相册渠道整项跳过。
        boolean freshnessChecked = safe.enforcesFreshness();
        if (freshnessChecked) {
            ExifFreshness.State freshness =
                    ExifFreshness.evaluate(shotAtMillis, nowMillis, thresholdMinutes);
            if (ExifFreshness.blocks(freshness)) {
                long age = nowMillis - (shotAtMillis == null ? nowMillis : shotAtMillis);
                return new Precheck(false, CODE_STALE,
                        ExifFreshness.reasonText(freshness, age, thresholdMinutes), safe, true);
            }
        }

        // ② 重复图：当前哈希不可用或无历史时跳过（放行）。两个渠道都执行。
        int distance = minDistance(currentHash, historyHashes);
        if (distance >= 0 && distance <= ImageHash.DUPLICATE_MAX_DISTANCE) {
            return new Precheck(false, CODE_DUPLICATE,
                    "这张照片与该任务的历史打卡照片高度相似（汉明距离 " + distance
                            + "，阈值 " + ImageHash.DUPLICATE_MAX_DISTANCE
                            + "），判为疑似重复图。" + safe.duplicateHint(), safe, freshnessChecked);
        }
        return new Precheck(true, CODE_OK, "", safe, freshnessChecked);
    }

    /**
     * 当前照与该任务历史照片的最小汉明距离。
     *
     * @return 距离 0..64；无法比较（当前哈希不可用 / 无历史 / 历史全部脏数据）返回 -1
     */
    public static int minDistance(Long currentHash, List<String> historyHashes) {
        if (currentHash == null || historyHashes == null || historyHashes.isEmpty()) return -1;
        int min = -1;
        for (String history : historyHashes) {
            int distance = ImageHash.hamming(ImageHash.toHex(currentHash), history);
            if (distance < 0) continue;   // 历史脏数据：忽略，不误拦
            if (min < 0 || distance < min) min = distance;
        }
        return min;
    }

    /**
     * 从判定历史中提取重复判定的样本哈希：只取<b>已完成打卡</b>且哈希非空的记录。
     * fail 的照片从未成为「有效打卡」，不应作为重复判定的基准。
     */
    public static List<String> dedupHashes(List<AiVerifyRecord> records) {
        List<String> hashes = new ArrayList<>();
        if (records == null) return hashes;
        for (AiVerifyRecord record : records) {
            if (record == null || !record.completed) continue;
            if (record.photoHash == null || record.photoHash.isEmpty()) continue;
            hashes.add(record.photoHash);
        }
        return hashes;
    }

    /** 打卡结果映射。 */
    public static final class Outcome {
        /** 是否完成打卡。 */
        public final boolean complete;
        /** 是否打「待复核」标记。 */
        public final boolean needsReview;
        /** 是否为降级记录（模型不可用）。 */
        public final boolean degraded;
        /** 写入判定历史的 verdict 键（pass/fail/uncertain）。 */
        public final String verdictKey;
        /** 置信度（降级时为 0）。 */
        public final int confidence;
        /** 面向用户展示的理由。 */
        public final String reason;

        Outcome(boolean complete, boolean needsReview, boolean degraded,
                String verdictKey, int confidence, String reason) {
            this.complete = complete;
            this.needsReview = needsReview;
            this.degraded = degraded;
            this.verdictKey = verdictKey;
            this.confidence = confidence;
            this.reason = reason == null ? "" : reason;
        }
    }

    /**
     * L2 判定 → 打卡结果。null verdict（响应不可解析）走降级路径，不阻塞。
     */
    public static Outcome decideOrNull(AiVerdict verdict) {
        if (verdict == null) return degraded("AI 返回结果无法解析");
        return decide(verdict);
    }

    public static Outcome decide(AiVerdict verdict) {
        AiVerdict safe = verdict == null ? new AiVerdict(AiVerdict.Verdict.UNCERTAIN, 0, "") : verdict;
        switch (safe.verdict) {
            case PASS:
                return new Outcome(true, false, false, safe.key(), safe.confidence,
                        decorate(safe.reason, "AI 判定通过"));
            case FAIL:
                return new Outcome(false, false, false, safe.key(), safe.confidence,
                        decorate(safe.reason, "AI 判定未通过，可重新拍摄打卡") + "（可重拍）");
            case UNCERTAIN:
            default:
                return new Outcome(true, true, false, safe.key(), safe.confidence,
                        "待复核：" + decorate(safe.reason, "AI 无法确定"));
        }
    }

    /**
     * 降级路径：网络失败 / 超时 / 未配 key / 额度耗尽 / 响应不可解析。
     *
     * <p><b>永不阻塞</b>——完成打卡 + 待复核标记，verdict 记为 uncertain，
     * degraded=true 供审计区分「模型不确定」与「模型不可用」。</p>
     */
    public static Outcome degraded(String reason) {
        String why = reason == null || reason.trim().isEmpty() ? "AI 服务不可用" : reason.trim();
        return new Outcome(true, true, true, AiVerdict.Verdict.UNCERTAIN.key(), 0,
                "待复核：" + why + "（AI 判定不可用，已按本地预检完成打卡，可在 App 内人工复核）");
    }

    /**
     * 是否应当调用模型：配了非空 api key 才调。
     * 未配 key 时壳层直接走 {@link #degraded(String)}，不发起网络请求。
     */
    public static boolean shouldCallModel(String apiKey) {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    /**
     * 预检失败时写入历史的记录（未通过 L1 → 未完成打卡，不进重复样本）。
     */
    public static AiVerifyRecord precheckRecord(String dateKey, long atMillis, Precheck precheck) {
        return new AiVerifyRecord(dateKey, atMillis, AiVerdict.Verdict.FAIL.key(), 0,
                precheck.reason, false, false, false, "");
    }

    /** 不可变空列表，供壳层无历史时使用。 */
    public static List<String> noHistory() {
        return Collections.emptyList();
    }

    /** 空理由时用兜底文案，避免出现「待复核：」后面什么都没有。 */
    private static String decorate(String reason, String fallback) {
        return reason == null || reason.trim().isEmpty() ? fallback : reason.trim();
    }
}
