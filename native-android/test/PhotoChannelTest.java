package test;

import com.checktodo.app.domain.AiVerdict;
import com.checktodo.app.domain.AiVerifyRecord;
import com.checktodo.app.domain.ImageHash;
import com.checktodo.app.domain.PhotoChannel;
import com.checktodo.app.domain.VerificationPolicy;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * AI 判定「相册上传」渠道（与拍照双渠道并存）测试：{@link PhotoChannel} 渠道语义 +
 * {@link VerificationPolicy#precheck} 的渠道口径。
 *
 * <p><b>产品决策（本测试钉死）</b>：</p>
 * <ol>
 *   <li>相册渠道<b>跳过 EXIF 新鲜度</b>：相册图 + 明确旧 EXIF → 通过预检；
 *       同一份数据在拍照渠道 → 拦截为 stale（差异只来自渠道，不是阈值放宽）。</li>
 *   <li>相册渠道<b>保留 aHash 去重</b>：相册图 + 与该任务历史完成照高度相似 → 拦截
 *       duplicate，且文案给出「换一张照片，或重新拍摄」的下一步。</li>
 *   <li>拍照渠道行为<b>一字不改</b>：旧签名 precheck(...) 等价于 CAMERA 渠道，
 *       新鲜度、去重、文案口径全部保持原样。</li>
 *   <li>渠道脏数据（null / 未知串）回落拍照（最严格口径），绝不因此放宽预检。</li>
 *   <li>渠道只影响 L1：L2 映射与降级铁律对两个渠道完全一致，aHash 历史口径不变
 *       （只取已完成打卡记录的照片哈希）。</li>
 * </ol>
 */
public final class PhotoChannelTest {
    private static int passed;
    private static int failed;

    /** 时间基准（2026-10 附近的绝对毫秒）。 */
    private static final long NOW = 1_792_000_000_000L;
    /** 明确过期的旧图：一小时前拍的。 */
    private static final long STALE_SHOT = NOW - 60 * 60_000L;
    /** 刚拍的：一分钟前。 */
    private static final long FRESH_SHOT = NOW - 60_000L;
    /** 一张已完成打卡的历史照片指纹。 */
    private static final long HISTORY_HASH = 0x0123456789abcdefL;
    /** 一张 fail（未完成）记录的照片指纹：与完成照差 32 位，绝不参与去重。 */
    private static final long FAILED_HASH = HISTORY_HASH ^ 0x0000ffffffffffffL;

    public static void main(String[] args) {
        testChannelEnum();
        testChannelParse();
        testChannelFilePrefix();
        testCameraChannelUnchanged();
        testLegacySignatureIsCamera();
        testGallerySkipsFreshness();
        testGalleryKeepsDedup();
        testGalleryWithoutHashPasses();
        testGalleryDedupHintDiffers();
        testCameraFreshnessBoundaries();
        testUnknownChannelFallsBackToCamera();
        testDedupHistoryUnchanged();
        testDecideChainUnchangedForBothChannels();
        testShellWiringBySource();
        report();
    }

    // ------------------------------------------------------------------
    // 渠道语义
    // ------------------------------------------------------------------

    private static void testChannelEnum() {
        check("渠道：拍照键 = camera", "camera".equals(PhotoChannel.CAMERA.key()));
        check("渠道：相册键 = gallery", "gallery".equals(PhotoChannel.GALLERY.key()));
        check("渠道：拍照文案 =「拍照」", "拍照".equals(PhotoChannel.CAMERA.label()));
        check("渠道：相册文案 =「从相册选择」", "从相册选择".equals(PhotoChannel.GALLERY.label()));
        check("渠道：对话框标题 =「选择打卡图片来源」",
                "选择打卡图片来源".equals(PhotoChannel.PICKER_TITLE));
        check("渠道：只有拍照执行新鲜度校验",
                PhotoChannel.CAMERA.enforcesFreshness() && !PhotoChannel.GALLERY.enforcesFreshness());
        check("渠道：两个渠道都执行去重",
                PhotoChannel.CAMERA.enforcesDedup() && PhotoChannel.GALLERY.enforcesDedup());
        check("渠道：枚举恰好两项（新增渠道必须显式处理口径）", PhotoChannel.values().length == 2);
    }

    private static void testChannelParse() {
        check("解析：camera → CAMERA", PhotoChannel.parse("camera") == PhotoChannel.CAMERA);
        check("解析：gallery → GALLERY", PhotoChannel.parse("gallery") == PhotoChannel.GALLERY);
        check("解析：大小写不敏感（GALLERY）", PhotoChannel.parse("GALLERY") == PhotoChannel.GALLERY);
        check("解析：枚举名同样接受（Gallery）", PhotoChannel.parse("Gallery") == PhotoChannel.GALLERY);
        check("解析：带空白容忍", PhotoChannel.parse("  gallery  ") == PhotoChannel.GALLERY);
        check("解析：null → 拍照（最严格）", PhotoChannel.parse(null) == PhotoChannel.CAMERA);
        check("解析：空串 → 拍照", PhotoChannel.parse("") == PhotoChannel.CAMERA);
        check("解析：未知值 → 拍照", PhotoChannel.parse("dropbox") == PhotoChannel.CAMERA);
        check("解析：可指定回落渠道",
                PhotoChannel.parse("未知", PhotoChannel.GALLERY) == PhotoChannel.GALLERY);
        check("解析：回落渠道为 null 时仍回落拍照",
                PhotoChannel.parse(null, null) == PhotoChannel.CAMERA);
        check("渠道：orCamera(null) → CAMERA", PhotoChannel.orCamera(null) == PhotoChannel.CAMERA);
        check("渠道：orCamera(GALLERY) 保持 GALLERY",
                PhotoChannel.orCamera(PhotoChannel.GALLERY) == PhotoChannel.GALLERY);
        check("渠道：key 往返一致", PhotoChannel.parse(PhotoChannel.GALLERY.key())
                == PhotoChannel.GALLERY);
    }

    private static void testChannelFilePrefix() {
        check("文件名前缀：拍照标准照保持 ref（既有文件名不变）",
                "ref".equals(PhotoChannel.CAMERA.filePrefix("ref")));
        check("文件名前缀：拍照打卡照保持 shot", "shot".equals(PhotoChannel.CAMERA.filePrefix("shot")));
        check("文件名前缀：相册标准照 → gal-ref",
                "gal-ref".equals(PhotoChannel.GALLERY.filePrefix("ref")));
        check("文件名前缀：相册打卡照 → gal-shot",
                "gal-shot".equals(PhotoChannel.GALLERY.filePrefix("shot")));
        check("文件名前缀：相册渠道空白基础名兜底 gal-img",
                "gal-img".equals(PhotoChannel.GALLERY.filePrefix("  ")));
        check("文件名前缀：拍照渠道 null 基础名兜底 img",
                "img".equals(PhotoChannel.CAMERA.filePrefix(null)));
        check("文件名前缀：相册前缀与拍照不冲突（便于审计区分来源）",
                !PhotoChannel.GALLERY.filePrefix("shot").equals(PhotoChannel.CAMERA.filePrefix("shot")));
    }

    // ------------------------------------------------------------------
    // 预检口径
    // ------------------------------------------------------------------

    private static List<String> historyOf(long hash) {
        return new ArrayList<>(Collections.singletonList(ImageHash.toHex(hash)));
    }

    private static void testCameraChannelUnchanged() {
        VerificationPolicy.Precheck ok = VerificationPolicy.precheck(PhotoChannel.CAMERA,
                FRESH_SHOT, NOW, 10, 0x1111111111111111L, historyOf(HISTORY_HASH));
        check("拍照渠道：新鲜图 + 不同图 → 放行", ok.passed
                && VerificationPolicy.CODE_OK.equals(ok.code));
        check("拍照渠道：放行时记录渠道 = camera", ok.channel == PhotoChannel.CAMERA);
        check("拍照渠道：放行时确实执行了新鲜度校验", ok.freshnessChecked);
        check("拍照渠道：放行理由为空串", "".equals(ok.reason));

        VerificationPolicy.Precheck stale = VerificationPolicy.precheck(PhotoChannel.CAMERA,
                STALE_SHOT, NOW, 10, null, null);
        check("拍照渠道：旧图 → 拦截 stale", !stale.passed
                && VerificationPolicy.CODE_STALE.equals(stale.code));
        check("拍照渠道：stale 文案含「旧图」与实际分钟数",
                stale.reason.contains("旧图") && stale.reason.contains("60 分钟前"));
        check("拍照渠道：stale 文案仍要求重新拍摄", stale.reason.contains("请重新拍摄当前实况照片"));

        VerificationPolicy.Precheck duplicate = VerificationPolicy.precheck(PhotoChannel.CAMERA,
                FRESH_SHOT, NOW, 10, HISTORY_HASH, historyOf(HISTORY_HASH));
        check("拍照渠道：重复图 → 拦截 duplicate", !duplicate.passed
                && VerificationPolicy.CODE_DUPLICATE.equals(duplicate.code));
        check("拍照渠道：duplicate 文案含汉明距离与阈值",
                duplicate.reason.contains("汉明距离 0")
                        && duplicate.reason.contains("阈值 " + ImageHash.DUPLICATE_MAX_DISTANCE));
        check("拍照渠道：duplicate 时新鲜度已执行（顺序：先新鲜度后去重）",
                duplicate.freshnessChecked);
    }

    private static void testLegacySignatureIsCamera() {
        VerificationPolicy.Precheck legacy = VerificationPolicy.precheck(STALE_SHOT, NOW, 10, null, null);
        VerificationPolicy.Precheck explicit = VerificationPolicy.precheck(
                PhotoChannel.CAMERA, STALE_SHOT, NOW, 10, null, null);
        check("兼容：旧签名 = 拍照渠道（同样拦截旧图）",
                !legacy.passed && legacy.code.equals(explicit.code));
        check("兼容：旧签名文案与新签名完全一致", legacy.reason.equals(explicit.reason));
        check("兼容：旧签名结果记录渠道 = camera", legacy.channel == PhotoChannel.CAMERA);
        check("兼容：旧签名 freshnessChecked = true", legacy.freshnessChecked);
        check("兼容：旧签名新鲜图仍放行", VerificationPolicy.precheck(
                FRESH_SHOT, NOW, 10, null, null).passed);
        check("兼容：旧签名无 EXIF（null）仍放行", VerificationPolicy.precheck(
                (Long) null, NOW, 10, null, null).passed);
        VerificationPolicy.Precheck legacyDup = VerificationPolicy.precheck(
                FRESH_SHOT, NOW, 10, HISTORY_HASH, historyOf(HISTORY_HASH));
        check("兼容：旧签名重复图仍拦截 duplicate", !legacyDup.passed
                && VerificationPolicy.CODE_DUPLICATE.equals(legacyDup.code));
    }

    private static void testGallerySkipsFreshness() {
        // 相册渠道 + 明确旧 EXIF：产品决策 = 通过预检（新鲜期对相册图无意义）。
        VerificationPolicy.Precheck gallery = VerificationPolicy.precheck(PhotoChannel.GALLERY,
                STALE_SHOT, NOW, 10, null, null);
        check("相册渠道：旧 EXIF 也放行（跳过新鲜度）",
                gallery.passed && VerificationPolicy.CODE_OK.equals(gallery.code));
        check("相册渠道：放行结果记录渠道 = gallery", gallery.channel == PhotoChannel.GALLERY);
        check("相册渠道：freshnessChecked=false（新鲜度整项未执行）", !gallery.freshnessChecked);

        // 极旧（一年前）同样放行：证明是整项跳过，不是把阈值放大。
        check("相册渠道：一年前的图同样放行", VerificationPolicy.precheck(PhotoChannel.GALLERY,
                NOW - 365L * 24 * 60 * 60_000L, NOW, 10, null, null).passed);

        // 同一份数据在拍照渠道被拦：差异只来自渠道。
        VerificationPolicy.Precheck camera = VerificationPolicy.precheck(PhotoChannel.CAMERA,
                STALE_SHOT, NOW, 10, null, null);
        check("对照：同数据拍照渠道被拦为 stale",
                !camera.passed && VerificationPolicy.CODE_STALE.equals(camera.code));
        check("对照：拍照渠道 freshnessChecked=true", camera.freshnessChecked);

        // EXIF 时刻超前设备时钟（时钟不符）在相册渠道同样无所谓。
        check("相册渠道：EXIF 超前设备时钟也放行", VerificationPolicy.precheck(
                PhotoChannel.GALLERY, NOW + 10 * 60 * 60_000L, NOW, 10, null, null).passed);
        // 阈值参数对相册渠道无效（不因为传了很小的阈值就拦）。
        check("相册渠道：阈值=1 分钟也不拦旧图", VerificationPolicy.precheck(
                PhotoChannel.GALLERY, STALE_SHOT, NOW, 1, null, null).passed);
    }

    private static void testGalleryKeepsDedup() {
        // 相册渠道 + 与历史完成照完全相同 → 拦截（防重复交同一张图）。
        VerificationPolicy.Precheck exact = VerificationPolicy.precheck(PhotoChannel.GALLERY,
                STALE_SHOT, NOW, 10, HISTORY_HASH, historyOf(HISTORY_HASH));
        check("相册渠道：重复 aHash → 拦截 duplicate", !exact.passed
                && VerificationPolicy.CODE_DUPLICATE.equals(exact.code));
        check("相册渠道：拦截结果记录渠道 = gallery", exact.channel == PhotoChannel.GALLERY);
        check("相册渠道：拦截时新鲜度同样未执行", !exact.freshnessChecked);
        check("相册渠道：duplicate 文案含「疑似重复图」", exact.reason.contains("疑似重复图"));

        // 阈值边界：距离恰好等于阈值 → 拦截；超出阈值一位 → 放行。
        long near = HISTORY_HASH ^ 0b11111L;    // 5 位差 = 恰好阈值
        long far = HISTORY_HASH ^ 0b111111L;    // 6 位差 = 超阈值
        check("相册渠道：距离=阈值仍拦截", !VerificationPolicy.precheck(PhotoChannel.GALLERY,
                STALE_SHOT, NOW, 10, near, historyOf(HISTORY_HASH)).passed);
        check("相册渠道：距离>阈值放行", VerificationPolicy.precheck(PhotoChannel.GALLERY,
                STALE_SHOT, NOW, 10, far, historyOf(HISTORY_HASH)).passed);
        check("阈值口径与 ImageHash 一致（距离 5 = DUPLICATE_MAX_DISTANCE）",
                ImageHash.hamming(HISTORY_HASH, near) == ImageHash.DUPLICATE_MAX_DISTANCE
                        && ImageHash.isDuplicate(HISTORY_HASH, near)
                        && !ImageHash.isDuplicate(HISTORY_HASH, far));

        // 无历史（首次打卡）→ 放行，两个渠道一致。
        check("相册渠道：无历史时放行", VerificationPolicy.precheck(PhotoChannel.GALLERY,
                STALE_SHOT, NOW, 10, HISTORY_HASH, null).passed);
        check("相册渠道：历史为空列表时放行", VerificationPolicy.precheck(PhotoChannel.GALLERY,
                STALE_SHOT, NOW, 10, HISTORY_HASH, Collections.<String>emptyList()).passed);
        // 多条历史里只要有一条足够近就拦。
        List<String> many = new ArrayList<>(historyOf(0x7777777777777777L));
        many.add(ImageHash.toHex(HISTORY_HASH));
        check("相册渠道：多条历史命中其一即拦截", !VerificationPolicy.precheck(
                PhotoChannel.GALLERY, STALE_SHOT, NOW, 10, HISTORY_HASH, many).passed);
    }

    private static void testGalleryWithoutHashPasses() {
        // 纯色图 / 解码失败 → 哈希不可用，两个渠道都跳过去重放行（口径一致）。
        check("相册渠道：哈希不可用 → 放行", VerificationPolicy.precheck(PhotoChannel.GALLERY,
                STALE_SHOT, NOW, 10, null, historyOf(HISTORY_HASH)).passed);
        check("拍照渠道：哈希不可用 + 新鲜 → 放行", VerificationPolicy.precheck(PhotoChannel.CAMERA,
                FRESH_SHOT, NOW, 10, null, historyOf(HISTORY_HASH)).passed);
        check("相册渠道：历史全是脏数据 → 放行（不误拦）", VerificationPolicy.precheck(
                PhotoChannel.GALLERY, STALE_SHOT, NOW, 10, HISTORY_HASH,
                Arrays.asList("不是十六进制", "", null)).passed);
        check("minDistance：无历史返回 -1", VerificationPolicy.minDistance(HISTORY_HASH, null) == -1);
        check("minDistance：脏历史返回 -1",
                VerificationPolicy.minDistance(HISTORY_HASH, Arrays.asList("zz", null)) == -1);
        check("minDistance：取最小距离", VerificationPolicy.minDistance(HISTORY_HASH,
                Arrays.asList(ImageHash.toHex(HISTORY_HASH ^ 0b111L),
                        ImageHash.toHex(HISTORY_HASH))) == 0);
    }

    private static void testGalleryDedupHintDiffers() {
        String gallery = VerificationPolicy.precheck(PhotoChannel.GALLERY, STALE_SHOT, NOW, 10,
                HISTORY_HASH, historyOf(HISTORY_HASH)).reason;
        String camera = VerificationPolicy.precheck(PhotoChannel.CAMERA, FRESH_SHOT, NOW, 10,
                HISTORY_HASH, historyOf(HISTORY_HASH)).reason;
        check("文案：相册渠道提示「换一张照片」", gallery.contains("换一张照片"));
        check("文案：拍照渠道提示「请重新拍摄当前实况照片」",
                camera.contains("请重新拍摄当前实况照片"));
        check("文案：两个渠道的重复图文案不同（下一步动作不同）", !gallery.equals(camera));
        check("文案：前半段口径一致（都说明与历史完成照高度相似）",
                gallery.startsWith("这张照片与该任务的历史打卡照片高度相似")
                        && camera.startsWith("这张照片与该任务的历史打卡照片高度相似"));
    }

    private static void testCameraFreshnessBoundaries() {
        long at9 = NOW - 9 * 60_000L;
        long at11 = NOW - 11 * 60_000L;
        check("拍照渠道：9 分钟前放行", VerificationPolicy.precheck(PhotoChannel.CAMERA,
                at9, NOW, 10, null, null).passed);
        check("拍照渠道：11 分钟前拦截", !VerificationPolicy.precheck(PhotoChannel.CAMERA,
                at11, NOW, 10, null, null).passed);
        check("拍照渠道：阈值 ≤0 回落默认 10 分钟", !VerificationPolicy.precheck(
                PhotoChannel.CAMERA, at11, NOW, 0, null, null).passed);
        check("拍照渠道：EXIF 超前 2 分钟算时钟误差放行", VerificationPolicy.precheck(
                PhotoChannel.CAMERA, NOW + 2 * 60_000L, NOW, 10, null, null).passed);
        check("拍照渠道：EXIF 超前 30 分钟视为时钟不可信 → 放行", VerificationPolicy.precheck(
                PhotoChannel.CAMERA, NOW + 30 * 60_000L, NOW, 10, null, null).passed);
    }

    private static void testUnknownChannelFallsBackToCamera() {
        VerificationPolicy.Precheck nullChannel = VerificationPolicy.precheck(null,
                STALE_SHOT, NOW, 10, null, null);
        check("脏渠道：null 渠道 → 按拍照拦截旧图", !nullChannel.passed
                && VerificationPolicy.CODE_STALE.equals(nullChannel.code));
        check("脏渠道：null 渠道结果记录为 camera", nullChannel.channel == PhotoChannel.CAMERA);
        check("脏渠道：null 渠道执行了新鲜度校验", nullChannel.freshnessChecked);
        check("脏渠道：回落不放水（与显式拍照同码同文案）",
                nullChannel.reason.equals(VerificationPolicy.precheck(PhotoChannel.CAMERA,
                        STALE_SHOT, NOW, 10, null, null).reason));
    }

    private static void testDedupHistoryUnchanged() {
        AiVerifyRecord done = new AiVerifyRecord("2026-10-20", NOW, "pass", 90, "ok",
                false, false, true, ImageHash.toHex(HISTORY_HASH));
        AiVerifyRecord failed = new AiVerifyRecord("2026-10-19", NOW, "fail", 80, "内容无关",
                false, false, false, ImageHash.toHex(FAILED_HASH));
        AiVerifyRecord degradedNoHash = new AiVerifyRecord("2026-10-18", NOW, "uncertain", 0,
                "AI 服务不可用", true, false, true, "");
        List<String> hashes = VerificationPolicy.dedupHashes(
                Arrays.asList(done, failed, degradedNoHash, null));
        check("历史口径：只取已完成打卡且哈希非空的记录", hashes.size() == 1
                && ImageHash.toHex(HISTORY_HASH).equals(hashes.get(0)));
        check("历史口径：fail 的照片不作为重复基准", !hashes.contains(ImageHash.toHex(FAILED_HASH)));
        check("历史口径：null 记录列表 → 空", VerificationPolicy.dedupHashes(null).isEmpty());
        check("历史口径：noHistory() 为空列表", VerificationPolicy.noHistory().isEmpty());

        // 用真实历史口径直接喂给相册渠道预检：完成过的图能拦，fail 过的图拦不到。
        check("历史口径 + 相册渠道：完成照重复被拦", !VerificationPolicy.precheck(
                PhotoChannel.GALLERY, STALE_SHOT, NOW, 10, HISTORY_HASH, hashes).passed);
        check("历史口径 + 相册渠道：fail 照不参与去重（同图放行）", VerificationPolicy.precheck(
                PhotoChannel.GALLERY, STALE_SHOT, NOW, 10, FAILED_HASH, hashes).passed);
        check("历史口径 + 拍照渠道：同结论（渠道不改历史口径）", !VerificationPolicy.precheck(
                PhotoChannel.CAMERA, FRESH_SHOT, NOW, 10, HISTORY_HASH, hashes).passed);
    }

    private static void testDecideChainUnchangedForBothChannels() {
        // 渠道只影响 L1；L2 映射与降级铁律对两个渠道完全一致（渠道不进 Outcome）。
        VerificationPolicy.Outcome pass = VerificationPolicy.decide(
                new AiVerdict(AiVerdict.Verdict.PASS, 92, "桌面整洁"));
        VerificationPolicy.Outcome fail = VerificationPolicy.decide(
                new AiVerdict(AiVerdict.Verdict.FAIL, 88, "内容无关"));
        VerificationPolicy.Outcome uncertain = VerificationPolicy.decide(
                new AiVerdict(AiVerdict.Verdict.UNCERTAIN, 40, "画面模糊"));
        VerificationPolicy.Outcome degraded = VerificationPolicy.degraded("网络超时（20 秒）");
        check("L2：pass → 完成且不待复核", pass.complete && !pass.needsReview && !pass.degraded);
        check("L2：fail → 不完成且提示可重拍", !fail.complete && fail.reason.contains("可重拍"));
        check("L2：uncertain → 完成 + 待复核", uncertain.complete && uncertain.needsReview);
        check("L2：降级 → 完成 + 待复核 + degraded",
                degraded.complete && degraded.needsReview && degraded.degraded);
        check("L2：降级绝不映射为 fail", !AiVerdict.Verdict.FAIL.key().equals(degraded.verdictKey));
        check("L2：null verdict 走降级不阻塞", VerificationPolicy.decideOrNull(null).degraded);
        check("L2：shouldCallModel 与渠道无关（只看 key）",
                VerificationPolicy.shouldCallModel("sk-x")
                        && !VerificationPolicy.shouldCallModel("  "));

        VerificationPolicy.Precheck blocked = VerificationPolicy.precheck(PhotoChannel.GALLERY,
                STALE_SHOT, NOW, 10, HISTORY_HASH, historyOf(HISTORY_HASH));
        AiVerifyRecord record = VerificationPolicy.precheckRecord("2026-10-20", NOW, blocked);
        check("预检拦截记录：未完成打卡", !record.completed);
        check("预检拦截记录：verdict=fail 但无照片哈希（不进重复样本）",
                AiVerdict.Verdict.FAIL.key().equals(record.verdict) && record.photoHash.isEmpty());
        check("预检拦截记录：理由即拦截文案", blocked.reason.equals(record.reason));
        check("预检拦截记录：不进历史样本（dedupHashes 为空）",
                VerificationPolicy.dedupHashes(Collections.singletonList(record)).isEmpty());
    }

    // ------------------------------------------------------------------
    // 壳层接线（源码字符串断言：JVM 跑不了 Android 类，用源码校验接线是否落地）
    // ------------------------------------------------------------------

    private static void testShellWiringBySource() {
        String store = readSource("src/com/checktodo/app/ai/AiPhotoStore.java");
        check("壳层：AiPhotoStore 有 copyFromUri", store.contains("copyFromUri("));
        check("壳层：拷贝落在私有目录 ai_photos", store.contains("DIR_NAME"));
        check("壳层：拷贝流式（分块读写，不整图进内存）",
                store.contains("byte[] buffer") && store.contains("read(buffer)")
                        && store.contains("write(buffer"));
        check("壳层：拷贝用 ContentResolver 打开临时授权 Uri",
                store.contains("getContentResolver().openInputStream(uri)"));
        check("壳层：拷贝失败返回 null（不把失败当成有图）", store.contains("return null"));
        check("壳层：空内容判为失败并清理残留", store.contains("total <= 0"));
        check("壳层：拷贝异常走 catch 且不吞日志",
                store.contains("catch (Exception exception)") && store.contains("Log.w("));
        check("壳层：拷贝完成才记 INFO 日志且不打印 Uri/key",
                store.contains("album photo copied: bytes=")
                        && !logLineMentionsSecret(store));
        check("壳层：关闭输入输出流", store.contains("closeQuietly(input)")
                && store.contains("closeQuietly(output)"));
        check("壳层：相册文件带 gal- 前缀", store.contains("filePrefix("));
        check("壳层：既有拍照文件名不变（shot/ref）",
                store.contains("newFile(context, \"ref\")") && store.contains("newFile(context, \"shot\")"));

        String layout = readSource("res/layout/dialog_task_editor.xml");
        check("壳层：任务编辑器新增「从相册选择」标准照按钮",
                layout.contains("button_ai_reference_gallery"));
        check("壳层：任务编辑器保留「拍摄标准照」按钮", layout.contains("button_ai_reference"));
        check("壳层：相册按钮文案 =「从相册选择」", layout.contains("从相册选择"));

        String activity = readSource("src/com/checktodo/app/MainActivity.java");
        check("壳层：四个相册请求码（1006~1009）", activity.contains("= 1006")
                && activity.contains("= 1007") && activity.contains("= 1008")
                && activity.contains("= 1009"));
        check("壳层：相册用 ACTION_GET_CONTENT", activity.contains("Intent.ACTION_GET_CONTENT"));
        check("壳层：相册限定 image/*", activity.contains("\"image/*\""));
        check("壳层：对话框标题用域层常量（选择打卡图片来源）",
                activity.contains("PhotoChannel.PICKER_TITLE"));
        check("壳层：渠道选项用域层文案（拍照 / 从相册选择）",
                activity.contains("PhotoChannel.CAMERA.label()")
                        && activity.contains("PhotoChannel.GALLERY.label()"));
        check("壳层：预检显式传渠道（任务）", activity.contains("runAiPrecheck(task, shot, PhotoChannel.CAMERA)")
                && activity.contains("runAiPrecheck(task, shot, PhotoChannel.GALLERY)"));
        check("壳层：预检显式传渠道（待办）",
                activity.contains("runAiTodoPrecheck(todo, shot, PhotoChannel.CAMERA)")
                        && activity.contains("runAiTodoPrecheck(todo, shot, PhotoChannel.GALLERY)"));
        check("壳层：预检调用域层渠道版签名",
                activity.contains("VerificationPolicy.precheck(channel, shotAt"));
        check("壳层：相册结果先拷贝再进既有链路",
                activity.contains("AiPhotoStore.copyFromUri("));
        check("壳层：相册结果在 onActivityResult 路由",
                activity.contains("REQUEST_AI_GALLERY_CHECKIN_PHOTO")
                        && activity.contains("REQUEST_AI_GALLERY_TODO_CHECKIN_PHOTO")
                        && activity.contains("REQUEST_AI_GALLERY_REFERENCE_PHOTO")
                        && activity.contains("REQUEST_AI_GALLERY_TODO_REFERENCE_PHOTO"));
        check("壳层：拷贝失败有用户提示且不崩", activity.contains("读取相册图片失败"));
        check("壳层：拍照渠道代码保留（ACTION_IMAGE_CAPTURE 四处仍在）",
                countOccurrences(activity, "MediaStore.ACTION_IMAGE_CAPTURE") >= 4);
        check("壳层：拍照渠道请求码不变（1002~1005）", activity.contains("= 1002")
                && activity.contains("= 1003") && activity.contains("= 1004")
                && activity.contains("= 1005"));
        check("壳层：卡片 AI 行入口与 App 内同一交互（都走来源选择）",
                activity.contains("maybeStartAiCheckInFromWidget")
                        && countOccurrences(activity, "pickAiCheckInSource(") >= 3);
        check("壳层：判定链未回归（仍 ≥2 处 requestAsync）",
                countOccurrences(activity, "GlmHttp.requestAsync(") >= 2);
        check("壳层：标准照相册结果回填编辑器状态（任务 + 待办）",
                activity.contains("editorReferencePhoto = reference.getAbsolutePath()")
                        && activity.contains("editorTodoReferencePhoto = reference.getAbsolutePath()"));
        check("壳层：待办编辑器也有相册按钮",
                activity.contains("aiTodoReferenceGalleryButton")
                        && activity.contains("startAiGalleryReference(true)"));
        check("壳层：任务编辑器相册按钮接线", activity.contains("startAiGalleryReference(false)"));
        check("壳层：重复选图被挡（pendingGalleryPick）",
                activity.contains("pendingGalleryPick"));
        check("壳层：渠道不做状态（显式参数传递，脏状态不可能放宽预检）",
                !activity.contains("pendingShotChannel")
                        && countOccurrences(activity, "PhotoChannel.CAMERA") >= 3);
        check("壳层：预检拦截对话框保留重拍出口", activity.contains("重新拍摄"));
        check("壳层：判定失败对话框也给了相册出口",
                countOccurrences(activity, "startAiGalleryCheckIn(") >= 4);
        check("壳层：API key 不进日志", !logLineMentionsSecret(activity));
    }

    /** 源码里所有 Log.* 行都不得出现 api key 字样（BYOK 安全纪律）。 */
    private static boolean logLineMentionsSecret(String source) {
        String[] lines = source.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("Log.")) continue;
            String lower = trimmed.toLowerCase(java.util.Locale.US);
            if (lower.contains("apikey") || lower.contains("api key")
                    || lower.contains("authorization")) {
                return true;
            }
        }
        return false;
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int index = text.indexOf(needle, from);
            if (index < 0) return count;
            count++;
            from = index + needle.length();
        }
    }

    private static String readSource(String path) {
        try {
            java.io.InputStream stream = new java.io.FileInputStream(new File(path));
            try {
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = stream.read(chunk)) >= 0) buffer.write(chunk, 0, read);
                return new String(buffer.toByteArray(), "UTF-8");
            } finally {
                stream.close();
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("读取源码失败：" + path, exception);
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

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
