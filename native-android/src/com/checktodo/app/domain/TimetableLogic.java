package com.checktodo.app.domain;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;

/** 课表核心规则：周次、单双周、冲突与今日课程。 */
public final class TimetableLogic {
    private TimetableLogic() {
    }

    /** 今天是第几周；未开学/已超期时钳制到 1..maxWeek，由 UI 提示真实边界。 */
    public static int currentWeek(TimetableConfig config, Date now) {
        if (config == null || now == null) return 1;
        Calendar start = calendarFromKey(config.startDate);
        Calendar today = Calendar.getInstance();
        today.setTime(now);
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);
        long diff = today.getTimeInMillis() - start.getTimeInMillis();
        int week = (int) Math.floor(diff / (7.0 * 24 * 60 * 60 * 1000)) + 1;
        return clamp(week, 1, Math.max(1, config.maxWeek));
    }

    public static boolean isBeforeTermStart(TimetableConfig config, Date now) {
        if (config == null || now == null) return false;
        Calendar start = calendarFromKey(config.startDate);
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        Calendar today = Calendar.getInstance();
        today.setTime(now);
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);
        return today.before(start);
    }

    public static boolean isAfterTermEnd(TimetableConfig config, Date now) {
        return currentWeek(config, now) >= Math.max(1, config.maxWeek)
                && !isBeforeTermStart(config, now)
                && weekStart(config, currentWeek(config, now)).after(weekStart(config, config.maxWeek));
    }

    public static Calendar weekStart(TimetableConfig config, int week) {
        Calendar start = calendarFromKey(config.startDate);
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        start.add(Calendar.WEEK_OF_YEAR, week - 1);
        return start;
    }

    public static boolean isCourseInWeek(CourseDetail detail, int week) {
        if (detail == null) return false;
        if (week < detail.startWeek || week > detail.endWeek) return false;
        if (detail.type == CourseDetail.TYPE_ODD) return week % 2 == 1;
        if (detail.type == CourseDetail.TYPE_EVEN) return week % 2 == 0;
        return true;
    }

    public static boolean isCourseOnDay(CourseDetail detail, int day) {
        return detail != null && detail.day == day;
    }

    public static boolean overlaps(CourseDetail left, CourseDetail right) {
        if (left == null || right == null) return false;
        if (left.day != right.day) return false;
        return left.startNode <= right.endNode() && right.startNode <= left.endNode();
    }

    /** 同一星期、同一天、节次重叠即冲突；单双周不相交不算冲突。 */
    public static boolean hasConflict(List<CourseDetail> details, CourseDetail target, int week) {
        if (details == null || target == null) return false;
        for (CourseDetail detail : details) {
            if (detail == target || detail.id.equals(target.id)) continue;
            if (!isCourseInWeek(detail, week) || !isCourseInWeek(target, week)) continue;
            if (overlaps(detail, target)) return true;
        }
        return false;
    }

    public static List<CourseDetail> todayCourses(List<CourseDetail> details, TimetableConfig config, Date now) {
        List<CourseDetail> result = new ArrayList<>();
        if (details == null || config == null || now == null) return result;
        int week = currentWeek(config, now);
        int day = dayOfWeek(now);
        for (CourseDetail detail : details) {
            if (isCourseInWeek(detail, week) && isCourseOnDay(detail, day)) result.add(detail);
        }
        Collections.sort(result, (left, right) -> {
            int byNode = Integer.compare(left.startNode, right.startNode);
            if (byNode != 0) return byNode;
            int byLevel = Integer.compare(left.level, right.level);
            if (byLevel != 0) return byLevel;
            return left.baseId.compareTo(right.baseId);
        });
        return result;
    }

    public static int dayOfWeek(Date date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        int javaDay = calendar.get(Calendar.DAY_OF_WEEK);
        return javaDay == Calendar.SUNDAY ? 7 : javaDay - 1;
    }

    public static String dateKey(Date date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        return String.format(Locale.US, "%04d-%02d-%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH));
    }

    public static Date date(int year, int month, int day) {
        Calendar calendar = new GregorianCalendar(year, month - 1, day, 0, 0, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }

    private static Calendar calendarFromKey(String key) {
        Calendar calendar = Calendar.getInstance();
        if (key != null && key.matches("\\d{4}-\\d{2}-\\d{2}")) {
            calendar.set(Integer.parseInt(key.substring(0, 4)),
                    Integer.parseInt(key.substring(5, 7)) - 1,
                    Integer.parseInt(key.substring(8, 10)));
        }
        return calendar;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
