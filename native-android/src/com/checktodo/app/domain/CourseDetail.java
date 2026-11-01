package com.checktodo.app.domain;

/** 一条具体排课：星期、节次、周次、教室与教师。 */
public final class CourseDetail {
    public static final int TYPE_ALL = 0;
    public static final int TYPE_ODD = 1;
    public static final int TYPE_EVEN = 2;

    public final String id;
    public final String baseId;
    /** 1=周一 ... 7=周日 */
    public final int day;
    public final int startNode;
    /** 占用节数，至少 1。 */
    public final int step;
    public final int startWeek;
    public final int endWeek;
    public final int type;
    public final String room;
    public final String teacher;
    /** 同一星期/节次多门课并存时的展示层级。 */
    public final int level;

    public CourseDetail(String id, String baseId, int day, int startNode, int step,
                        int startWeek, int endWeek, int type, String room, String teacher, int level) {
        this.id = id;
        this.baseId = baseId;
        this.day = day;
        this.startNode = startNode;
        this.step = Math.max(1, step);
        this.startWeek = startWeek;
        this.endWeek = endWeek;
        this.type = type;
        this.room = room == null ? "" : room.trim();
        this.teacher = teacher == null ? "" : teacher.trim();
        this.level = level;
    }

    public int endNode() {
        return startNode + step - 1;
    }
}
