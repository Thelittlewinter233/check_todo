package com.checktodo.app.widget;

import com.checktodo.app.domain.CourseBase;
import com.checktodo.app.domain.CourseDetail;
import com.checktodo.app.domain.TimeNode;
import com.checktodo.app.domain.TimeTableSettings;
import com.checktodo.app.domain.TimetableLogic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 卡片「今日课程」取数与文案的纯函数，三态卡片（{@link CheckTodoWidgetService}）与
 * 独立「今日课程」卡片（{@link TodayCourseWidgetService}）共用，避免两处渲染漂移。
 *
 * <p>不依赖 Android，可直接单测。</p>
 */
public final class CourseWidgetItems {
    private CourseWidgetItems() {
    }

    /** 单条卡片行：课程详情 + 已解析的课程名与节次时间文案。 */
    public static final class Item {
        public final String detailId;
        public final String title;
        public final String subtitle;

        Item(String detailId, String title, String subtitle) {
            this.detailId = detailId;
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    /**
     * 今天有课列表，按开始节次 → level → baseId 排序（与 {@link TimetableLogic#todayCourses} 一致）。
     */
    public static List<Item> build(List<CourseDetail> details, List<CourseBase> bases,
                                   List<TimeNode> timeNodes, Date now) {
        List<Item> items = new ArrayList<>();
        if (details == null) return items;
        List<CourseDetail> sorted = new ArrayList<>(details);
        Collections.sort(sorted, (left, right) -> {
            int byNode = Integer.compare(left.startNode, right.startNode);
            if (byNode != 0) return byNode;
            int byLevel = Integer.compare(left.level, right.level);
            if (byLevel != 0) return byLevel;
            return left.baseId.compareTo(right.baseId);
        });
        for (CourseDetail detail : sorted) {
            items.add(new Item(detail.id, title(detail, bases), subtitle(detail, timeNodes)));
        }
        return items;
    }

    public static String title(CourseDetail detail, List<CourseBase> bases) {
        if (bases != null) {
            for (CourseBase base : bases) {
                if (base != null && base.id.equals(detail.baseId)) return base.name;
            }
        }
        return "未命名课程";
    }

    /**
     * 副标题：{@code 1-2节 08:00-09:40 · 教一201}；
     * 时间表缺失时省略时间，教室为空时省略教室。
     */
    public static String subtitle(CourseDetail detail, List<TimeNode> timeNodes) {
        StringBuilder builder = new StringBuilder();
        builder.append(detail.startNode);
        if (detail.endNode() != detail.startNode) builder.append('-').append(detail.endNode());
        builder.append("节");
        String span = TimeTableSettings.spanLabel(timeNodes, detail.startNode, detail.endNode());
        if (!span.isEmpty()) builder.append(' ').append(span);
        if (!detail.room.isEmpty()) builder.append(" · ").append(detail.room);
        return builder.toString();
    }
}
