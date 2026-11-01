package com.checktodo.app.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 手动编辑课程（新增/编辑/删除）的纯 Java 合并逻辑。
 *
 * <p><b>2026-10-26 回归修复：</b>{@code TimetableRepository.saveCourse} 原实现构建新详情集合时
 * <b>只放了本次这一条</b>：</p>
 *
 * <pre>
 * // 旧代码（已删除）
 * List&lt;CourseDetail&gt; nextDetails = new ArrayList&lt;&gt;();
 * if (detail != null) nextDetails.add(...);       // ← 已导入的排课全部丢失
 * else for (旧排课) if (baseId 不同) nextDetails.add(...);
 * </pre>
 *
 * <p>后果：教务导入成功 → 手动「添加课程」→ 保存后 details 只剩新课一条，
 * 导入课表中其余课程的排课全部消失（bases 还在，但界面上不渲染任何课）。
 * 同时新课的 baseId 由仓库重新生成，而调用方传来的详情还指向空 baseId，
 * 即使不丢数据也会变成孤儿详情。</p>
 *
 * <p>本类把合并规则抽成纯函数（可单测）：只改动被编辑的那一门课，
 * 其余课程与排课原样保留；新课程生成 id 后，其详情重新绑定到新 id。</p>
 */
public final class TimetableEditor {

    private TimetableEditor() {
    }

    /**
     * 在原课表上应用一次「保存课程」。
     *
     * @param sheet      原课表（不可变）
     * @param base       课程主数据；{@code id} 为空表示新增
     * @param detail     本次编辑的排课；{@code null} 表示删除该课程的全部排课（保留主数据）
     * @param generatedBaseId 新增时由调用方生成的 baseId（已存在时忽略）
     * @param usedIds    需生成详情 id 时，生成结果追加到此列表（便于调用方统计/测试）
     */
    public static TimetableSheet saveCourse(TimetableSheet sheet, CourseBase base,
                                            CourseDetail detail, String generatedBaseId,
                                            List<String> usedIds) {
        if (sheet == null || base == null) return sheet;
        List<String> ids = usedIds == null ? new ArrayList<String>() : usedIds;

        String baseId = base.id == null || base.id.trim().isEmpty()
                ? (generatedBaseId == null || generatedBaseId.trim().isEmpty()
                ? java.util.UUID.randomUUID().toString() : generatedBaseId.trim())
                : base.id.trim();
        boolean isNew = findBase(sheet.bases, baseId) == null;

        List<CourseBase> nextBases = new ArrayList<>();
        for (CourseBase item : sheet.bases) {
            nextBases.add(item.id.equals(baseId) ? withEditedFields(item, base) : item);
        }
        if (isNew) {
            nextBases.add(new CourseBase(baseId, base.name, base.color, base.note, base.credit));
        }

        List<CourseDetail> nextDetails = new ArrayList<>(sheet.details);
        if (detail != null) {
            CourseDetail rebind = rebind(detail, baseId, ids);
            int index = indexOfDetail(nextDetails, detail.id, sheet.details, baseId);
            if (index >= 0) {
                nextDetails.set(index, rebind);
            } else {
                nextDetails.add(rebind);
            }
        } else if (!isNew) {
            // detail == null：删除该课程的全部排课（编辑弹窗的「删除课程」链路）。
            nextDetails.removeIf(item -> item.baseId.equals(baseId));
        }
        return sheet.withCourses(nextBases, nextDetails);
    }

    /**
     * 导入课程：为每门导入课生成新 id，追加到现有课程之后（不清空已手动添加的课程）。
     * 详情全部指向新生成的 baseId。
     */
    public static TimetableSheet importCourses(TimetableSheet sheet, List<CourseBase> importedBases,
                                               List<CourseDetail> importedDetails) {
        if (sheet == null || importedBases == null || importedDetails == null) return sheet;
        List<CourseBase> nextBases = new ArrayList<>(sheet.bases);
        List<CourseDetail> nextDetails = new ArrayList<>(sheet.details);
        java.util.Map<String, String> idMapping = new java.util.HashMap<>();
        for (CourseBase base : importedBases) {
            if (base == null || base.name.trim().isEmpty()) continue;
            String newId = java.util.UUID.randomUUID().toString();
            idMapping.put(base.id, newId);
            nextBases.add(new CourseBase(newId, base.name, base.color, base.note, base.credit));
        }
        for (CourseDetail detail : importedDetails) {
            if (detail == null) continue;
            String mappedBaseId = idMapping.get(detail.baseId);
            if (mappedBaseId == null) continue;
            nextDetails.add(new CourseDetail(java.util.UUID.randomUUID().toString(), mappedBaseId,
                    detail.day, detail.startNode, detail.step, detail.startWeek, detail.endWeek,
                    detail.type, detail.room, detail.teacher, detail.level));
        }
        return sheet.withCourses(nextBases, nextDetails);
    }

    /** 新课程只带一条排课；若调用方传的是空 baseId，重绑到生成的 id，并确保详情 id 唯一。 */
    private static CourseDetail rebind(CourseDetail detail, String baseId, List<String> usedIds) {
        String detailId = detail.id == null || detail.id.trim().isEmpty()
                ? nextDetailId(usedIds) : detail.id.trim();
        return new CourseDetail(detailId, baseId, detail.day, detail.startNode, detail.step,
                detail.startWeek, detail.endWeek, detail.type, detail.room, detail.teacher,
                detail.level);
    }

    /**
     * 定位要替换的排课：优先按 detail.id 在现有集合中找；找不到且该课程只有一条排课时，
     * 替换那一条（导入课程常常没有稳定 detail id）；否则追加。
     */
    private static int indexOfDetail(List<CourseDetail> current, String detailId,
                                     List<CourseDetail> original, String baseId) {
        if (detailId != null && !detailId.trim().isEmpty()) {
            for (int i = 0; i < current.size(); i++) {
                if (detailId.trim().equals(current.get(i).id)) return i;
            }
        }
        int only = -1;
        int count = 0;
        for (int i = 0; i < original.size(); i++) {
            if (original.get(i).baseId.equals(baseId)) {
                count++;
                only = i;
            }
        }
        return count == 1 ? only : -1;
    }

    /** 主数据：沿用调用方给的新字段（名称/备注/学分），id 与颜色保持不变。 */
    private static CourseBase withEditedFields(CourseBase previous, CourseBase next) {
        return new CourseBase(previous.id, next.name, previous.color, next.note, next.credit);
    }

    private static CourseBase findBase(List<CourseBase> bases, String baseId) {
        for (CourseBase base : bases) {
            if (base.id.equals(baseId)) return base;
        }
        return null;
    }

    private static String nextDetailId(List<String> usedIds) {
        String id = java.util.UUID.randomUUID().toString();
        usedIds.add(id);
        return id;
    }
}
