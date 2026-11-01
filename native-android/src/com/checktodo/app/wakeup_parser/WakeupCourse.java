package com.checktodo.app.wakeup_parser;

/** WakeUp parser intermediate course, mapped later to the app's timetable models. */
public final class WakeupCourse {
    public final String name;
    public final int day;
    public final String room;
    public final String teacher;
    public final int startNode;
    public final int endNode;
    public final int startWeek;
    public final int endWeek;
    public final int type;
    public final double credit;

    public WakeupCourse(String name, int day, String room, String teacher, int startNode,
                        int endNode, int startWeek, int endWeek, int type, double credit) {
        this.name = safe(name);
        this.day = day;
        this.room = safe(room);
        this.teacher = safe(teacher);
        this.startNode = startNode;
        this.endNode = endNode;
        this.startWeek = startWeek;
        this.endWeek = endWeek;
        this.type = type;
        this.credit = credit;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
