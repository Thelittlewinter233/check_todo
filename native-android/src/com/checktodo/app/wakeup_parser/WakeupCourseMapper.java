package com.checktodo.app.wakeup_parser;

import com.checktodo.app.domain.CourseBase;
import com.checktodo.app.domain.CourseDetail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Maps WakeUp's intermediate Course model to this app's CourseBase/CourseDetail. */
public final class WakeupCourseMapper {
    private WakeupCourseMapper() {
    }

    public static Result map(List<WakeupCourse> courses) {
        Result result = new Result();
        Map<String, String> idsByName = new LinkedHashMap<>();
        for (WakeupCourse course : courses) {
            if (course == null || course.name.trim().isEmpty() || invalid(course)) continue;
            String baseId = idsByName.get(course.name);
            if (baseId == null) {
                baseId = "wakeup-" + result.bases.size();
                idsByName.put(course.name, baseId);
                result.bases.add(new CourseBase(baseId, course.name, 0, "", course.credit));
            }
            result.details.add(new CourseDetail("wakeup-detail-" + result.details.size(), baseId,
                    course.day, course.startNode, course.endNode - course.startNode + 1,
                    course.startWeek, course.endWeek, course.type, course.room, course.teacher, 0));
        }
        return result;
    }

    public static boolean invalid(WakeupCourse course) {
        return course.day < 1 || course.day > 7 || course.startNode < 1
                || course.endNode < course.startNode || course.startWeek < 1
                || course.endWeek < course.startWeek;
    }

    public static final class Result {
        public final List<CourseBase> bases = new ArrayList<>();
        public final List<CourseDetail> details = new ArrayList<>();
    }
}
