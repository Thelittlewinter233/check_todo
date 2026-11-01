package test;

import com.checktodo.app.domain.AiVerifyRecord;
import com.checktodo.app.domain.AiVerdict;
import com.checktodo.app.domain.ExifFreshness;
import com.checktodo.app.domain.ImageHash;
import com.checktodo.app.domain.Task;
import com.checktodo.app.domain.VerificationPolicy;
import com.checktodo.app.domain.VerifyMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * AI 判定打卡（GLM-4.6V-Flash）域层核心测试：
 *
 * <ol>
 *   <li>{@link VerifyMode}：老数据无字段 → NONE，字符串容错。</li>
 *   <li>{@link ImageHash}：aHash 只依赖灰度像素矩阵（Bitmap→像素由壳层转换），
 *       尺度不变、汉明距离、hex 往返、平坦图判为不可用。</li>
 *   <li>{@link ExifFreshness}：拍摄时间新鲜度——只有「明确知道是旧图」才拦截。</li>
 *   <li>{@link VerificationPolicy}：L1 离线预检（必过闸）与 L2 判定结果 → 打卡结果映射，
 *       含网络失败/未配 key 的降级路径（永不阻塞）。</li>
 *   <li>{@link AiVerifyRecord}：判定历史值对象与 Map 往返（持久化口径）。</li>
 *   <li>{@link Task}：新增 AI 字段向后兼容，且各 copier 不得丢失 AI 配置。</li>
 * </ol>
 */
public final class AiVerifyCoreTest {
    private static final boolean[] EVERY_DAY =
            new boolean[]{true, true, true, true, true, true, true};
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        // 1. VerifyMode
        testVerifyModeDefaultsToNone();
        testVerifyModeParsing();
        testVerifyModeValueRoundTrip();

        // 2. ImageHash
        testGrayFromArgb();
        testAhashIdenticalImages();
        testAhashKnownBitLayout();
        testAhashScaleInvariance();
        testAhashNonMultipleOfEight();
        testHammingDistanceBasics();
        testDuplicateBoundary();
        testHexRoundTrip();
        testHexInvalid();
        testFlatImageHashUnusable();
        testMinDistance();

        // 3. ExifFreshness
        testFreshnessUnknownWhenNoExif();
        testFreshnessThresholdBoundary();
        testFreshnessStaleBlocks();
        testFreshnessFutureTolerance();
        testFreshnessInvalidThresholdFallsBack();
        testFreshnessReasonText();

        // 4. VerificationPolicy — L1 预检
        testPrecheckPassesWhenFreshAndUnique();
        testPrecheckBlocksStale();
        testPrecheckBlocksDuplicate();
        testPrecheckStaleWinsOverDuplicate();
        testPrecheckSkipsDedupWhenHashUnusable();
        testPrecheckIgnoresUnparsableHistory();
        testPrecheckFirstCheckInHasNoHistory();
        testDedupHashesOnlyCompleted();

        // 5. VerificationPolicy — L2 判定映射与降级
        testOutcomePass();
        testOutcomeFail();
        testOutcomeUncertainNeedsReview();
        testOutcomeDegradedNetworkFailure();
        testOutcomeDegradedMissingKey();
        testOutcomeNeverBlocksOnDegraded();

        // 6. AiVerifyRecord
        testRecordMapRoundTrip();
        testRecordAppeal();
        testRecordNeedsReview();
        testRecordFromMapToleratesMissing();

        // 7. Task 兼容与 copier
        testTaskLegacyConstructorsDefaultNone();
        testTaskFullConstructor();
        testTaskCopiersPreserveAiConfig();
        testTaskWithVerify();
        report();
    }

    // ------------------------------------------------------------------
    // 1. VerifyMode
    // ------------------------------------------------------------------

    private static void testVerifyModeDefaultsToNone() {
        check("★老数据无 verifyMode → NONE", VerifyMode.fromString(null) == VerifyMode.NONE);
        check("空串 → NONE", VerifyMode.fromString("") == VerifyMode.NONE);
        check("★未知值 → NONE（向前兼容）", VerifyMode.fromString("ai_video") == VerifyMode.NONE);
        check("NONE 不是 AI 判定", !VerifyMode.NONE.isAiPhoto());
    }

    private static void testVerifyModeParsing() {
        check("ai_photo → AI_PHOTO", VerifyMode.fromString("ai_photo") == VerifyMode.AI_PHOTO);
        check("大小写不敏感", VerifyMode.fromString("AI_PHOTO") == VerifyMode.AI_PHOTO);
        check("首尾空白容错", VerifyMode.fromString("  ai_photo \n") == VerifyMode.AI_PHOTO);
        check("★AI_PHOTO 判定为 AI 模式", VerifyMode.AI_PHOTO.isAiPhoto());
    }

    private static void testVerifyModeValueRoundTrip() {
        check("NONE.value=none", "none".equals(VerifyMode.NONE.value()));
        check("AI_PHOTO.value=ai_photo", "ai_photo".equals(VerifyMode.AI_PHOTO.value()));
        for (VerifyMode mode : VerifyMode.values()) {
            check("value 往返：" + mode, VerifyMode.fromString(mode.value()) == mode);
        }
    }

    // ------------------------------------------------------------------
    // 2. ImageHash
    // ------------------------------------------------------------------

    private static void testGrayFromArgb() {
        int[] argb = {0xFFFFFFFF, 0xFF000000, 0xFFFF0000, 0xFF00FF00, 0xFF0000FF};
        int[] gray = ImageHash.grayFromArgb(argb);
        check("白 → 255", gray[0] == 255);
        check("黑 → 0", gray[1] == 0);
        check("红 → 76", gray[2] == 76);
        check("绿 → 149", gray[3] == 149);
        check("蓝 → 28", gray[4] == 28);
        check("灰度长度不变", gray.length == argb.length);
        check("★忽略 alpha 通道", ImageHash.grayFromArgb(new int[]{0x00FFFFFF})[0] == 255);
        check("null → 空数组", ImageHash.grayFromArgb(null).length == 0);
    }

    private static void testAhashIdenticalImages() {
        int[] image = checkerboard(64, 64, 8);
        long a = ImageHash.ahash(image, 64, 64);
        long b = ImageHash.ahash(image.clone(), 64, 64);
        check("★同一图片哈希相同", a == b);
        check("同一图片汉明距离 0", ImageHash.hamming(a, b) == 0);
        check("距离 0 → 判为重复", ImageHash.isDuplicate(a, b));
    }

    private static void testAhashKnownBitLayout() {
        // 左黑右白（按列切分）：每个 cell 均匀 → 低 4 列高于均值。
        int[] columnSplit = new int[64 * 64];
        int[] rowSplit = new int[64 * 64];
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                columnSplit[y * 64 + x] = x < 32 ? 0 : 255;
                rowSplit[y * 64 + x] = y < 32 ? 0 : 255;
            }
        }
        long columnHash = ImageHash.ahash(columnSplit, 64, 64);
        long rowHash = ImageHash.ahash(rowSplit, 64, 64);
        check("★列切分位模式 = 0x0F0F0F0F0F0F0F0F",
                "0f0f0f0f0f0f0f0f".equals(ImageHash.toHex(columnHash)));
        check("★行切分位模式 = 0x00000000FFFFFFFF",
                "00000000ffffffff".equals(ImageHash.toHex(rowHash)));
        check("列切分 vs 行切分 汉明距离 32", ImageHash.hamming(columnHash, rowHash) == 32);
        check("距离 32 → 不是重复图", !ImageHash.isDuplicate(columnHash, rowHash));
    }

    private static void testAhashScaleInvariance() {
        int[] big = checkerboard(64, 64, 8);
        int[] small = checkerboard(32, 32, 4);
        long bigHash = ImageHash.ahash(big, 64, 64);
        long smallHash = ImageHash.ahash(small, 32, 32);
        check("★同一图案不同分辨率 → 哈希相同（尺度不变）", bigHash == smallHash);

        int[] other = checkerboard(32, 32, 4);
        for (int i = 0; i < other.length; i++) {
            // 轻微亮度抖动（±3 灰阶）不应改变感知哈希。
            other[i] = Math.max(0, Math.min(255, other[i] + ((i % 7) - 3)));
        }
        check("★轻微亮度抖动 → 汉明距离 ≤ 5（不判重复也不误拦）",
                ImageHash.hamming(bigHash, ImageHash.ahash(other, 32, 32)) <= 5);
    }

    private static void testAhashNonMultipleOfEight() {
        int[] image = new int[65 * 33];
        for (int i = 0; i < image.length; i++) image[i] = (i * 37) % 256;
        long first = ImageHash.ahash(image, 65, 33);
        long second = ImageHash.ahash(image.clone(), 65, 33);
        check("非 8 倍数尺寸可计算", first == second);
        check("★小于 8x8 的图也可计算（不抛异常）",
                ImageHash.ahash(new int[]{10, 20, 30, 40}, 2, 2) == ImageHash.ahash(new int[]{10, 20, 30, 40}, 2, 2));
        check("尺寸非法（0） → 0", ImageHash.ahash(new int[0], 0, 0) == 0L);
        check("像素数不足 → 0", ImageHash.ahash(new int[]{1, 2}, 4, 4) == 0L);
        check("null 像素 → 0", ImageHash.ahash(null, 8, 8) == 0L);
    }

    private static void testHammingDistanceBasics() {
        check("hamming(0,0)=0", ImageHash.hamming(0L, 0L) == 0);
        check("hamming(0,-1)=64", ImageHash.hamming(0L, -1L) == 64);
        check("hamming(1,3)=1", ImageHash.hamming(1L, 3L) == 1);
        check("★对称性", ImageHash.hamming(0x0F0FL, 0xF0F0L) == ImageHash.hamming(0xF0F0L, 0x0F0FL));
    }

    private static void testDuplicateBoundary() {
        long base = 0x0000000000000000L;
        long five = 0x1FL;       // 5 bits
        long six = 0x3FL;        // 6 bits
        check("★汉明距离 5 → 疑似重复（≤5 拦截）", ImageHash.isDuplicate(base, five));
        check("★汉明距离 6 → 不算重复", !ImageHash.isDuplicate(base, six));
        check("阈值常量 = 5", ImageHash.DUPLICATE_MAX_DISTANCE == 5);
    }

    private static void testHexRoundTrip() {
        long[] samples = {0L, -1L, 0x0F0F0F0F0F0F0F0FL, 0x1234567890ABCDEFL, 1L};
        for (long sample : samples) {
            String hex = ImageHash.toHex(sample);
            check("hex 长度 16：" + hex, hex.length() == 16);
            check("hex 小写：" + hex, hex.equals(hex.toLowerCase(java.util.Locale.US)));
            check("★hex 往返：" + hex, ImageHash.fromHex(hex) != null && ImageHash.fromHex(hex) == sample);
        }
    }

    private static void testHexInvalid() {
        check("null hex → null", ImageHash.fromHex(null) == null);
        check("空串 hex → null", ImageHash.fromHex("") == null);
        check("非 hex 字符 → null", ImageHash.fromHex("zzzzzzzzzzzzzzzz") == null);
        check("长度不对 → null", ImageHash.fromHex("0f0f") == null);
        check("★hex 汉明距离：不可解析 → -1", ImageHash.hamming("0f0f0f0f0f0f0f0f", "bad") == -1);
        check("hex 汉明距离正常", ImageHash.hamming("0000000000000000", "000000000000001f") == 5);
        check("大写 hex 也可解析", ImageHash.fromHex("0F0F0F0F0F0F0F0F") != null);
    }

    private static void testFlatImageHashUnusable() {
        int[] flat = new int[64 * 64];
        Arrays.fill(flat, 128);
        check("★纯色图 → 哈希不可用（null），跳过重复图判定",
                ImageHash.ahashOrNull(flat, 64, 64) == null);
        int[] almostFlat = flat.clone();
        almostFlat[0] = 129;
        check("近乎纯色（标准差 < 阈值）同样不可用",
                ImageHash.ahashOrNull(almostFlat, 64, 64) == null);
        check("有内容的图 → 可用", ImageHash.ahashOrNull(checkerboard(64, 64, 8), 64, 64) != null);
        check("非法输入 → 不可用", ImageHash.ahashOrNull(new int[0], 0, 0) == null);
    }

    private static void testMinDistance() {
        String target = ImageHash.toHex(0L);
        List<String> history = Arrays.asList(ImageHash.toHex(0x1FL), ImageHash.toHex(0xFFFFFFFFL));
        check("★取历史最小汉明距离", VerificationPolicy.minDistance(0L, history) == 5);
        check("无历史 → -1", VerificationPolicy.minDistance(0L, Collections.<String>emptyList()) == -1);
        check("null 历史 → -1", VerificationPolicy.minDistance(0L, null) == -1);
        check("当前哈希不可用 → -1", VerificationPolicy.minDistance(null, history) == -1);
        check("历史里最近的正好等于目标", VerificationPolicy.minDistance(0L,
                Arrays.asList(target, ImageHash.toHex(0xFFFFFFFFL))) == 0);
    }

    // ------------------------------------------------------------------
    // 3. ExifFreshness
    // ------------------------------------------------------------------

    private static void testFreshnessUnknownWhenNoExif() {
        check("★无 EXIF 拍摄时间 → UNKNOWN", ExifFreshness.evaluate(null, now(), 10)
                == ExifFreshness.State.UNKNOWN);
        check("UNKNOWN 不拦截（很多相机/截图不写 DateTimeOriginal）",
                !ExifFreshness.blocks(ExifFreshness.State.UNKNOWN));
    }

    private static void testFreshnessThresholdBoundary() {
        long now = now();
        check("5 分钟前 → FRESH", ExifFreshness.evaluate(now - 5 * 60_000L, now, 10)
                == ExifFreshness.State.FRESH);
        check("★恰好 10 分钟 → FRESH（只有超过阈值才算旧图）",
                ExifFreshness.evaluate(now - 10 * 60_000L, now, 10) == ExifFreshness.State.FRESH);
        check("10 分 1 秒 → STALE", ExifFreshness.evaluate(now - (10 * 60_000L + 1_000L), now, 10)
                == ExifFreshness.State.STALE);
        check("默认阈值 = 10 分钟", ExifFreshness.DEFAULT_THRESHOLD_MINUTES == 10);
    }

    private static void testFreshnessStaleBlocks() {
        long now = now();
        ExifFreshness.State stale = ExifFreshness.evaluate(now - 60 * 60_000L, now, 10);
        check("★1 小时前拍摄 → STALE", stale == ExifFreshness.State.STALE);
        check("STALE 必须拦截", ExifFreshness.blocks(stale));
        check("FRESH 不拦截", !ExifFreshness.blocks(ExifFreshness.State.FRESH));
    }

    private static void testFreshnessFutureTolerance() {
        long now = now();
        check("未来 1 分钟（时钟误差内）→ FRESH",
                ExifFreshness.evaluate(now + 60_000L, now, 10) == ExifFreshness.State.FRESH);
        check("★未来 30 分钟（时钟明显不符）→ UNKNOWN，不拦截",
                ExifFreshness.evaluate(now + 30 * 60_000L, now, 10) == ExifFreshness.State.UNKNOWN);
        check("未来 30 分钟不拦截", !ExifFreshness.blocks(
                ExifFreshness.evaluate(now + 30 * 60_000L, now, 10)));
    }

    private static void testFreshnessInvalidThresholdFallsBack() {
        long now = now();
        check("阈值 ≤0 → 用默认 10 分钟（1 小时前仍 STALE）",
                ExifFreshness.evaluate(now - 60 * 60_000L, now, 0) == ExifFreshness.State.STALE);
        check("负阈值同样回落",
                ExifFreshness.evaluate(now - 60 * 60_000L, now, -5) == ExifFreshness.State.STALE);
        check("阈值 ≤0 时 5 分钟前仍 FRESH",
                ExifFreshness.evaluate(now - 5 * 60_000L, now, 0) == ExifFreshness.State.FRESH);
    }

    private static void testFreshnessReasonText() {
        long now = now();
        String stale = ExifFreshness.reasonText(ExifFreshness.State.STALE, 25 * 60_000L, 10);
        check("★STALE 文案含「旧图」", stale.contains("旧图"));
        check("STALE 文案含分钟数", stale.contains("25"));
        check("STALE 文案含阈值", stale.contains("10"));
        check("FRESH 文案为空", ExifFreshness.reasonText(ExifFreshness.State.FRESH, 0L, 10).isEmpty());
        check("UNKNOWN 文案为空（不拦截就不需要理由）",
                ExifFreshness.reasonText(ExifFreshness.State.UNKNOWN, 0L, 10).isEmpty());
    }

    // ------------------------------------------------------------------
    // 4. VerificationPolicy — L1 离线预检
    // ------------------------------------------------------------------

    private static void testPrecheckPassesWhenFreshAndUnique() {
        long now = now();
        VerificationPolicy.Precheck result = VerificationPolicy.precheck(
                now - 60_000L, now, 10, 0L,
                Arrays.asList(ImageHash.toHex(0xFFFFFFFFL)));
        check("★新鲜且不重复 → 通过", result.passed);
        check("通过时 code=ok", "ok".equals(result.code));
        check("通过时无拦截文案", result.reason.isEmpty());
    }

    private static void testPrecheckBlocksStale() {
        long now = now();
        VerificationPolicy.Precheck result = VerificationPolicy.precheck(
                now - 30 * 60_000L, now, 10, 0L, Collections.<String>emptyList());
        check("★旧图 → 拦截", !result.passed);
        check("旧图 code=stale", "stale".equals(result.code));
        check("★旧图给出明确文案（含「旧图」）", result.reason.contains("旧图"));
        check("旧图文案提示重拍", result.reason.contains("重新拍摄"));
    }

    private static void testPrecheckBlocksDuplicate() {
        long now = now();
        VerificationPolicy.Precheck result = VerificationPolicy.precheck(
                now, now, 10, 0x1FL,
                Arrays.asList(ImageHash.toHex(0L)));
        check("★重复图（汉明距离 5）→ 拦截", !result.passed);
        check("重复图 code=duplicate", "duplicate".equals(result.code));
        check("★重复图给出明确文案（含「重复」）", result.reason.contains("重复"));
        check("重复图文案含距离", result.reason.contains("5"));
    }

    private static void testPrecheckStaleWinsOverDuplicate() {
        long now = now();
        VerificationPolicy.Precheck result = VerificationPolicy.precheck(
                now - 60 * 60_000L, now, 10, 0L, Arrays.asList(ImageHash.toHex(0L)));
        check("旧图 + 重复图同时命中 → 先报旧图", "stale".equals(result.code));
        check("文案是旧图口径", result.reason.contains("旧图"));
    }

    private static void testPrecheckSkipsDedupWhenHashUnusable() {
        long now = now();
        VerificationPolicy.Precheck result = VerificationPolicy.precheck(
                now, now, 10, null, Arrays.asList(ImageHash.toHex(0L), ImageHash.toHex(0L)));
        check("★当前照哈希不可用（纯色图）→ 跳过重复判定，不拦截", result.passed);
        check("跳过时 code=ok", "ok".equals(result.code));
    }

    private static void testPrecheckIgnoresUnparsableHistory() {
        long now = now();
        VerificationPolicy.Precheck result = VerificationPolicy.precheck(
                now, now, 10, 0L, Arrays.asList(null, "", "bad", "0f0f"));
        check("★历史哈希不可解析 → 忽略，不误拦", result.passed);
        check("不可解析历史下最小距离 = -1",
                VerificationPolicy.minDistance(0L, Arrays.asList(null, "", "bad")) == -1);
    }

    private static void testPrecheckFirstCheckInHasNoHistory() {
        long now = now();
        check("首次打卡（无历史）→ 通过",
                VerificationPolicy.precheck(now, now, 10, 0L, null).passed);
        check("★无 EXIF + 无历史 → 通过（离线预检不阻塞首次打卡）",
                VerificationPolicy.precheck(null, now, 10, null, null).passed);
    }

    private static void testDedupHashesOnlyCompleted() {
        List<AiVerifyRecord> records = Arrays.asList(
                record("2026-10-28", "pass", true, false, "0f0f0f0f0f0f0f0f"),
                record("2026-10-29", "fail", false, false, "00000000000000ff"),
                record("2026-10-30", "uncertain", true, false, ""),
                record("2026-10-30", "uncertain", true, false, null));
        List<String> hashes = VerificationPolicy.dedupHashes(records);
        check("★只用「已完成打卡」的照片做重复判定", hashes.size() == 1);
        check("取到的是已完成那条的哈希", "0f0f0f0f0f0f0f0f".equals(hashes.get(0)));
        check("null 记录列表 → 空", VerificationPolicy.dedupHashes(null).isEmpty());
    }

    // ------------------------------------------------------------------
    // 5. VerificationPolicy — L2 判定映射与降级
    // ------------------------------------------------------------------

    private static void testOutcomePass() {
        VerificationPolicy.Outcome outcome = VerificationPolicy.decide(
                new AiVerdict(AiVerdict.Verdict.PASS, 92, "与标准照一致"));
        check("★pass → 完成打卡", outcome.complete);
        check("pass → 不需要复核", !outcome.needsReview);
        check("pass → 非降级", !outcome.degraded);
        check("pass → verdict 记录为 pass", "pass".equals(outcome.verdictKey));
        check("pass → 保留置信度", outcome.confidence == 92);
        check("pass → 展示模型理由", outcome.reason.contains("与标准照一致"));
    }

    private static void testOutcomeFail() {
        VerificationPolicy.Outcome outcome = VerificationPolicy.decide(
                new AiVerdict(AiVerdict.Verdict.FAIL, 88, "照片内容与任务无关"));
        check("★fail → 不完成打卡", !outcome.complete);
        check("fail → 展示理由", outcome.reason.contains("照片内容与任务无关"));
        check("fail → verdict 记录为 fail", "fail".equals(outcome.verdictKey));
        check("fail 文案提示可重拍", outcome.reason.contains("重拍"));
    }

    private static void testOutcomeUncertainNeedsReview() {
        VerificationPolicy.Outcome outcome = VerificationPolicy.decide(
                new AiVerdict(AiVerdict.Verdict.UNCERTAIN, 40, "图片模糊"));
        check("★uncertain → 完成打卡", outcome.complete);
        check("★uncertain → 打「待复核」标记", outcome.needsReview);
        check("uncertain → 非降级（模型确实回答了）", !outcome.degraded);
        check("uncertain 文案含「待复核」", outcome.reason.contains("待复核"));
        check("uncertain 文案保留模型理由", outcome.reason.contains("图片模糊"));
    }

    private static void testOutcomeDegradedNetworkFailure() {
        VerificationPolicy.Outcome outcome = VerificationPolicy.degraded("网络连接失败");
        check("★网络失败 → 降级完成打卡（永不阻塞）", outcome.complete);
        check("★降级 → 待复核", outcome.needsReview);
        check("降级标记为 degraded", outcome.degraded);
        check("降级 verdict 记录为 uncertain", "uncertain".equals(outcome.verdictKey));
        check("★降级文案含原因", outcome.reason.contains("网络连接失败"));
        check("降级文案含「待复核」", outcome.reason.contains("待复核"));
        check("降级置信度 0", outcome.confidence == 0);
    }

    private static void testOutcomeDegradedMissingKey() {
        VerificationPolicy.Outcome outcome = VerificationPolicy.degraded("未配置 API Key");
        check("未配 key → 降级完成", outcome.complete && outcome.needsReview && outcome.degraded);
        check("未配 key 文案含 key 提示", outcome.reason.contains("API Key"));
        check("额度耗尽同样降级", VerificationPolicy.degraded("额度耗尽").complete);
        check("★未配 key 时不需要调模型（isConfigured 判定）",
                !VerificationPolicy.shouldCallModel(null));
        check("空白 key 同样不调模型", !VerificationPolicy.shouldCallModel("   "));
        check("配了 key 才调模型", VerificationPolicy.shouldCallModel("abc.def"));
    }

    private static void testOutcomeNeverBlocksOnDegraded() {
        String[] reasons = {"网络超时", "HTTP 500", "响应不是 JSON", "额度耗尽", "未配置 API Key", ""};
        boolean allComplete = true;
        boolean allReview = true;
        for (String reason : reasons) {
            VerificationPolicy.Outcome outcome = VerificationPolicy.degraded(reason);
            allComplete &= outcome.complete;
            allReview &= outcome.needsReview;
        }
        check("★任何降级原因都完成打卡", allComplete);
        check("★任何降级原因都标待复核", allReview);
        check("verdict 为 null 时走降级（不是 pass）",
                VerificationPolicy.decideOrNull(null).degraded);
        check("verdict 为 null 时仍完成打卡",
                VerificationPolicy.decideOrNull(null).complete);
    }

    // ------------------------------------------------------------------
    // 6. AiVerifyRecord
    // ------------------------------------------------------------------

    private static void testRecordMapRoundTrip() {
        AiVerifyRecord original = new AiVerifyRecord("2026-10-30", 1_700_000_000_000L,
                "uncertain", 55, "图片模糊", false, true, true, "0f0f0f0f0f0f0f0f");
        Map<String, Object> map = original.toMap();
        AiVerifyRecord restored = AiVerifyRecord.fromMap(map);
        check("★Map 往返：dateKey", original.dateKey.equals(restored.dateKey));
        check("Map 往返：atMillis", original.atMillis == restored.atMillis);
        check("Map 往返：verdict", original.verdict.equals(restored.verdict));
        check("Map 往返：confidence", original.confidence == restored.confidence);
        check("Map 往返：reason", original.reason.equals(restored.reason));
        check("Map 往返：degraded", original.degraded == restored.degraded);
        check("Map 往返：appealed", original.appealed == restored.appealed);
        check("Map 往返：completed", original.completed == restored.completed);
        check("Map 往返：photoHash", original.photoHash.equals(restored.photoHash));
        check("Map 含全部审计字段", new HashSet<>(map.keySet()).containsAll(Arrays.asList(
                "date", "at", "verdict", "confidence", "reason", "degraded",
                "appealed", "completed", "photoHash")));
        check("★Map 值都是可 JSON 序列化的标量",
                map.get("at") instanceof Long && map.get("confidence") instanceof Integer
                        && map.get("degraded") instanceof Boolean);
    }

    private static void testRecordAppeal() {
        AiVerifyRecord record = record("2026-10-30", "fail", true, false, "0f0f0f0f0f0f0f0f");
        check("初始未申诉", !record.appealed);
        AiVerifyRecord appealed = record.withAppeal(true);
        check("★withAppeal 返回新对象（不可变）", appealed != record);
        check("申诉标记写入", appealed.appealed);
        check("★申诉不改动原记录", !record.appealed);
        check("申诉保留其余字段", appealed.dateKey.equals(record.dateKey)
                && appealed.confidence == record.confidence
                && appealed.photoHash.equals(record.photoHash));
        check("withAppeal(false) 可撤销", appealed.withAppeal(false).appealed == false);
    }

    private static void testRecordNeedsReview() {
        check("pass 不需要复核", !record("d", "pass", true, false, "h").needsReview());
        check("★uncertain 需要复核", record("d", "uncertain", true, false, "h").needsReview());
        check("★降级记录需要复核", record("d", "uncertain", true, true, "h").needsReview());
        check("★申诉完成的 fail 需要复核", record("d", "fail", true, false, "h").withAppeal(true).needsReview());
        check("未完成的 fail 记录不进入待复核展示",
                !record("d", "fail", false, false, "h").needsReview());
    }

    private static void testRecordFromMapToleratesMissing() {
        AiVerifyRecord record = AiVerifyRecord.fromMap(Collections.<String, Object>emptyMap());
        check("★空 Map → null（不产生脏记录）", record == null);
        Map<String, Object> partial = new java.util.LinkedHashMap<>();
        partial.put("date", "2026-10-30");
        AiVerifyRecord partialRecord = AiVerifyRecord.fromMap(partial);
        check("只有 date 也能构造（其余取默认）", partialRecord != null);
        check("缺 verdict → uncertain（保守）", "uncertain".equals(partialRecord.verdict));
        check("缺 confidence → 0", partialRecord.confidence == 0);
        check("缺 reason → 空串", "".equals(partialRecord.reason));
        check("缺 photoHash → 空串", "".equals(partialRecord.photoHash));
        check("缺 completed → false", !partialRecord.completed);
        check("null Map → null", AiVerifyRecord.fromMap(null) == null);
        check("★verdict 未知值 → uncertain",
                "uncertain".equals(AiVerifyRecord.fromMap(mapOf("date", "d", "verdict", "maybe")).verdict));
    }

    // ------------------------------------------------------------------
    // 7. Task 兼容与 copier
    // ------------------------------------------------------------------

    private static void testTaskLegacyConstructorsDefaultNone() {
        Task sixArg = new Task("a", "A", EVERY_DAY, "08:00", "22:00", 0L);
        check("★6 参构造 → verifyMode=NONE", sixArg.verifyMode == VerifyMode.NONE);
        check("6 参构造 → 特征描述为空", sixArg.aiFeature.isEmpty());
        check("6 参构造 → 标准照路径为空", sixArg.aiReferencePhoto.isEmpty());

        Task sevenArg = new Task("b", "B", EVERY_DAY, "08:00", "22:00",
                new HashSet<>(Arrays.asList("2026-10-01")), 0L);
        check("7 参构造 → NONE", sevenArg.verifyMode == VerifyMode.NONE);

        Task eightArg = new Task("c", "C", EVERY_DAY, "08:00", "22:00", null, 0L, 30);
        check("★8 参构造（既有测试与提醒预览用）→ NONE", eightArg.verifyMode == VerifyMode.NONE);
        check("8 参构造保留 requiredMinutes", eightArg.requiredMinutes == 30);
        check("★老任务不是 AI 判定任务", !sixArg.isAiPhoto());
    }

    private static void testTaskFullConstructor() {
        Task task = new Task("d", "D", EVERY_DAY, "08:00", "22:00",
                new HashSet<>(Arrays.asList("2026-10-01")), 123L, 60,
                VerifyMode.AI_PHOTO, "书桌整洁、台灯亮着", "/files/ai/ref.jpg");
        check("★全参构造写入 verifyMode", task.verifyMode == VerifyMode.AI_PHOTO);
        check("★isAiPhoto()", task.isAiPhoto());
        check("特征描述写入", "书桌整洁、台灯亮着".equals(task.aiFeature));
        check("标准照路径写入", "/files/ai/ref.jpg".equals(task.aiReferencePhoto));
        check("null 特征描述 → 空串", new Task("e", "E", EVERY_DAY, "08:00", "22:00", null, 0L, 60,
                VerifyMode.AI_PHOTO, null, null).aiFeature.isEmpty());
        check("null 标准照 → 空串", new Task("e", "E", EVERY_DAY, "08:00", "22:00", null, 0L, 60,
                VerifyMode.AI_PHOTO, "x", null).aiReferencePhoto.isEmpty());
        check("null verifyMode → NONE", new Task("e", "E", EVERY_DAY, "08:00", "22:00", null, 0L, 60,
                null, "x", "y").verifyMode == VerifyMode.NONE);
        check("有标准照 → hasReferencePhoto", task.hasReferencePhoto());
        check("无标准照 → !hasReferencePhoto",
                !new Task("f", "F", EVERY_DAY, "08:00", "22:00", null, 0L, 60,
                        VerifyMode.AI_PHOTO, "x", "").hasReferencePhoto());
    }

    private static void testTaskCopiersPreserveAiConfig() {
        Task task = new Task("g", "G", EVERY_DAY, "08:00", "22:00", null, 0L, 45,
                VerifyMode.AI_PHOTO, "特征", "/ref.jpg");
        Task withLeave = task.withLeaveDates(new HashSet<>(Arrays.asList("2026-10-05")));
        check("★withLeaveDates 保留 verifyMode", withLeave.verifyMode == VerifyMode.AI_PHOTO);
        check("★withLeaveDates 保留特征描述", "特征".equals(withLeave.aiFeature));
        check("★withLeaveDates 保留标准照", "/ref.jpg".equals(withLeave.aiReferencePhoto));
        check("withLeaveDates 写入请假", withLeave.leaveDates.contains("2026-10-05"));

        Task withRequired = task.withRequiredMinutes(90);
        check("★withRequiredMinutes 保留 verifyMode", withRequired.verifyMode == VerifyMode.AI_PHOTO);
        check("★withRequiredMinutes 保留特征描述", "特征".equals(withRequired.aiFeature));
        check("★withRequiredMinutes 保留标准照", "/ref.jpg".equals(withRequired.aiReferencePhoto));
        check("withRequiredMinutes 写入分钟数", withRequired.requiredMinutes == 90);
    }

    private static void testTaskWithVerify() {
        Task plain = new Task("h", "H", EVERY_DAY, "08:00", "22:00", null, 0L, 60);
        Task ai = plain.withVerify(VerifyMode.AI_PHOTO, "  特征描述  ", " /ref.jpg ");
        check("★withVerify 切换模式", ai.verifyMode == VerifyMode.AI_PHOTO);
        check("withVerify 去首尾空白", "特征描述".equals(ai.aiFeature));
        check("withVerify 路径去首尾空白", "/ref.jpg".equals(ai.aiReferencePhoto));
        check("★withVerify 不改动原对象", plain.verifyMode == VerifyMode.NONE);
        check("withVerify 保留其余字段", ai.id.equals(plain.id) && ai.requiredMinutes == plain.requiredMinutes);
        Task backToNone = ai.withVerify(VerifyMode.NONE, "", "");
        check("切回 NONE 清空特征", backToNone.verifyMode == VerifyMode.NONE
                && backToNone.aiFeature.isEmpty());
        check("null 模式 → NONE", ai.withVerify(null, "x", "y").verifyMode == VerifyMode.NONE);
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private static long now() {
        return 1_761_800_000_000L;   // 固定时钟，测试可复现
    }

    private static int[] checkerboard(int width, int height, int block) {
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = ((x / block + y / block) % 2 == 0) ? 255 : 0;
            }
        }
        return pixels;
    }

    private static AiVerifyRecord record(String dateKey, String verdict, boolean completed,
                                         boolean degraded, String photoHash) {
        return new AiVerifyRecord(dateKey, 1_700_000_000_000L, verdict, 70, "理由",
                degraded, false, completed, photoHash);
    }

    private static Map<String, Object> mapOf(String... keyValues) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) map.put(keyValues[i], keyValues[i + 1]);
        return map;
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
