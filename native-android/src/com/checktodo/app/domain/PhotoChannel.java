package com.checktodo.app.domain;

/**
 * AI 判定的照片来源渠道（纯枚举，零依赖）。
 *
 * <p><b>产品决策</b>：拍照与相册上传并存，但两者在 L1 预检上口径不同——</p>
 * <ul>
 *   <li>{@link #CAMERA}：EXIF 新鲜度<b>有效</b>（现拍的图必须新鲜）+ aHash 去重；</li>
 *   <li>{@link #GALLERY}：EXIF 新鲜度<b>跳过</b>（相册图多为历史图，拍摄时刻对
 *       「这次是否完成」没有意义，按新鲜期拦会把正常打卡堵死）+ aHash 去重<b>保留</b>
 *       （防同一张图反复交差）。</li>
 * </ul>
 *
 * <p>两个渠道之后完全同链：L1 通过 → L2 GLM 视觉判定（含三模型失败转移）→
 * 结果映射，降级铁律不变。</p>
 *
 * <p>渠道要显式传进 {@link VerificationPolicy#precheck}，不靠「shotAtMillis 是否为
 * null」隐式推断——null 在拍照渠道也有合法含义（相机不写 EXIF），语义必须分开。</p>
 */
public enum PhotoChannel {
    /** 系统相机现拍（{@code ACTION_IMAGE_CAPTURE}）。 */
    CAMERA("camera", "拍照", true),
    /** 相册选图（{@code ACTION_GET_CONTENT}，壳层先拷进私有目录）。 */
    GALLERY("gallery", "从相册选择", false);

    /** 持久化/日志用的稳定键。 */
    private final String key;
    /** 界面文案（按钮与选择对话框项）。 */
    private final String label;
    /** 该渠道是否执行 EXIF 新鲜度校验。 */
    private final boolean enforcesFreshness;

    PhotoChannel(String key, String label, boolean enforcesFreshness) {
        this.key = key;
        this.label = label;
        this.enforcesFreshness = enforcesFreshness;
    }

    /** 「选择打卡图片来源」对话框标题（产品决策文案）。 */
    public static final String PICKER_TITLE = "选择打卡图片来源";

    /** 稳定键：camera / gallery。 */
    public String key() {
        return key;
    }

    /** 界面文案：拍照 / 从相册选择。 */
    public String label() {
        return label;
    }

    /** 该渠道是否校验 EXIF 新鲜度（相册渠道 false）。 */
    public boolean enforcesFreshness() {
        return enforcesFreshness;
    }

    /** 两个渠道都保留 aHash 去重（防重复交图），因此恒为 true。 */
    public boolean enforcesDedup() {
        return true;
    }

    /** 拷贝进私有目录时的文件名前缀：gal-ref-* / gal-shot-*。 */
    public String filePrefix(String base) {
        String safe = base == null || base.trim().isEmpty() ? "img" : base.trim();
        return this == GALLERY ? "gal-" + safe : safe;
    }

    /**
     * 宽松解析：未知/空值一律回落 {@link #CAMERA}（最严格口径）——
     * 脏数据或未来新增渠道都不得让预检变松。
     */
    public static PhotoChannel parse(String raw) {
        return parse(raw, CAMERA);
    }

    /** 宽松解析 + 指定回落渠道。 */
    public static PhotoChannel parse(String raw, PhotoChannel fallback) {
        PhotoChannel safeFallback = fallback == null ? CAMERA : fallback;
        if (raw == null) return safeFallback;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return safeFallback;
        for (PhotoChannel channel : values()) {
            if (channel.key.equalsIgnoreCase(trimmed) || channel.name().equalsIgnoreCase(trimmed)) {
                return channel;
            }
        }
        return safeFallback;
    }

    /** null 安全：null → {@link #CAMERA}（拍照渠道行为一字不改）。 */
    public static PhotoChannel orCamera(PhotoChannel channel) {
        return channel == null ? CAMERA : channel;
    }

    /** 重复图拦截文案按渠道给「下一步怎么做」：相册渠道也允许重拍。 */
    public String duplicateHint() {
        return this == GALLERY
                ? "请换一张照片，或重新拍摄当前实况照片。"
                : "请重新拍摄当前实况照片。";
    }
}
