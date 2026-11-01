package com.checktodo.app.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class Task {
    /** 老数据缺失该字段时的默认完成所需时长（分钟）。 */
    public static final int DEFAULT_REQUIRED_MINUTES = 60;

    public final String id;
    public final String name;
    public final boolean[] weekdays;
    public final String startTime;
    public final String endTime;
    public final Set<String> leaveDates;
    public final long createdAt;
    public final int requiredMinutes;
    /** 打卡验证策略；老数据无该字段 → NONE（向后兼容）。 */
    public final VerifyMode verifyMode;
    /** AI 判定模式：用户写的打卡特征描述（作为证据发给视觉模型）。 */
    public final String aiFeature;
    /** AI 判定模式：标准照在 app 私有目录的路径；空串 = 未拍。 */
    public final String aiReferencePhoto;

    public Task(String id, String name, boolean[] weekdays, String startTime, String endTime, long createdAt) {
        this(id, name, weekdays, startTime, endTime, Collections.emptySet(), createdAt, DEFAULT_REQUIRED_MINUTES);
    }

    public Task(String id, String name, boolean[] weekdays, String startTime, String endTime,
                Set<String> leaveDates, long createdAt) {
        this(id, name, weekdays, startTime, endTime, leaveDates, createdAt, DEFAULT_REQUIRED_MINUTES);
    }

    public Task(String id, String name, boolean[] weekdays, String startTime, String endTime,
                Set<String> leaveDates, long createdAt, int requiredMinutes) {
        this(id, name, weekdays, startTime, endTime, leaveDates, createdAt, requiredMinutes,
                VerifyMode.NONE, "", "");
    }

    /**
     * 全参构造。aiFeature / aiReferencePhoto 为 null 时归一为空串，
     * verifyMode 为 null 时回落 NONE——持久化脏数据不得让任务变成非法状态。
     */
    public Task(String id, String name, boolean[] weekdays, String startTime, String endTime,
                Set<String> leaveDates, long createdAt, int requiredMinutes,
                VerifyMode verifyMode, String aiFeature, String aiReferencePhoto) {
        this.id = id;
        this.name = name;
        this.weekdays = weekdays.clone();
        this.startTime = startTime;
        this.endTime = endTime;
        this.leaveDates = leaveDates == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(leaveDates));
        this.createdAt = createdAt;
        this.requiredMinutes = requiredMinutes;
        this.verifyMode = verifyMode == null ? VerifyMode.NONE : verifyMode;
        this.aiFeature = aiFeature == null ? "" : aiFeature.trim();
        this.aiReferencePhoto = aiReferencePhoto == null ? "" : aiReferencePhoto.trim();
    }

    public Task withLeaveDates(Set<String> nextLeaveDates) {
        return new Task(id, name, weekdays, startTime, endTime, nextLeaveDates, createdAt,
                requiredMinutes, verifyMode, aiFeature, aiReferencePhoto);
    }

    public Task withRequiredMinutes(int nextRequiredMinutes) {
        return new Task(id, name, weekdays, startTime, endTime, leaveDates, createdAt,
                nextRequiredMinutes, verifyMode, aiFeature, aiReferencePhoto);
    }

    /** 设置/更换 AI 判定配置（编辑器保存用）；返回新对象，原任务不变。 */
    public Task withVerify(VerifyMode nextMode, String nextFeature, String nextReferencePhoto) {
        return new Task(id, name, weekdays, startTime, endTime, leaveDates, createdAt,
                requiredMinutes, nextMode, nextFeature, nextReferencePhoto);
    }

    /** 是否为 AI 拍照判定任务。 */
    public boolean isAiPhoto() {
        return verifyMode.isAiPhoto();
    }

    /** AI 判定配置是否完整（已拍标准照）。 */
    public boolean hasReferencePhoto() {
        return !aiReferencePhoto.isEmpty();
    }

    public List<Integer> selectedWeekdays() {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < weekdays.length; i++) {
            if (weekdays[i]) result.add(i);
        }
        return Collections.unmodifiableList(result);
    }
}
