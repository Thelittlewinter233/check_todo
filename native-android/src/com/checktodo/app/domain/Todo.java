package com.checktodo.app.domain;

/**
 * 待办（不可变值对象）。
 *
 * <p>AI 判定字段（verifyMode / aiFeature / aiReferencePhoto）与打卡任务
 * {@link Task} 完全同构：{@code verifyMode} 为 null 时回落 NONE——持久化脏数据
 * 不得让待办变成非法状态。老数据没有这些字段 → NONE（行为与旧版一致）。</p>
 */
public final class Todo {
    public final String id;
    public final String title;
    public final long startTime;
    public final long endTime;
    public final int durationMinutes;
    public final boolean completed;
    public final long createdAt;
    /** 验证策略：NONE = 自报完成（默认）；AI_PHOTO = 完成需拍照并经 GLM 判定。 */
    public final VerifyMode verifyMode;
    /** 打卡特征描述（AI 判定的【证据】，非指令）。 */
    public final String aiFeature;
    /** 标准照路径（建待办时拍摄）。 */
    public final String aiReferencePhoto;

    public Todo(String id, String title, long startTime, long endTime,
                int durationMinutes, boolean completed, long createdAt) {
        this(id, title, startTime, endTime, durationMinutes, completed, createdAt,
                VerifyMode.NONE, "", "");
    }

    public Todo(String id, String title, long startTime, long endTime,
                int durationMinutes, boolean completed, long createdAt,
                VerifyMode verifyMode, String aiFeature, String aiReferencePhoto) {
        this.id = id;
        this.title = title;
        this.startTime = startTime;
        this.endTime = endTime;
        this.durationMinutes = durationMinutes;
        this.completed = completed;
        this.createdAt = createdAt;
        this.verifyMode = verifyMode == null ? VerifyMode.NONE : verifyMode;
        this.aiFeature = aiFeature == null ? "" : aiFeature;
        this.aiReferencePhoto = aiReferencePhoto == null ? "" : aiReferencePhoto;
    }

    /** 是否需要拍照 AI 判定。 */
    public boolean isAiPhoto() {
        return verifyMode.isAiPhoto();
    }

    /** 是否已拍标准照（未拍时判定走降级：完成 + 待复核）。 */
    public boolean hasReferencePhoto() {
        return aiReferencePhoto != null && !aiReferencePhoto.isEmpty();
    }

    public Todo withCompletion(boolean value) {
        return new Todo(id, title, startTime, endTime, durationMinutes, value, createdAt,
                verifyMode, aiFeature, aiReferencePhoto);
    }

    public Todo withFields(String nextTitle, long nextStart, long nextEnd,
                           int nextDuration, boolean nextCompleted) {
        return new Todo(id, nextTitle, nextStart, nextEnd, nextDuration, nextCompleted, createdAt,
                verifyMode, aiFeature, aiReferencePhoto);
    }

    /** AI 配置变更（编辑器保存）：返回新对象，原对象不变（不可变纪律）。 */
    public Todo withAi(VerifyMode nextMode, String nextFeature, String nextReferencePhoto) {
        return new Todo(id, title, startTime, endTime, durationMinutes, completed, createdAt,
                nextMode, nextFeature, nextReferencePhoto);
    }
}
