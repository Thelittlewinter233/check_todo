package com.checktodo.app.domain;

/** 课表基础配置；样式精简为影响排课与显示的核心项。 */
public final class TimetableConfig {
    public final String startDate;
    public final int maxWeek;
    public final int nodes;
    public final boolean showSat;
    public final boolean showSun;
    public final boolean showOtherWeekCourse;

    public TimetableConfig(String startDate, int maxWeek, int nodes, boolean showSat,
                           boolean showSun, boolean showOtherWeekCourse) {
        this.startDate = startDate;
        this.maxWeek = maxWeek;
        this.nodes = nodes;
        this.showSat = showSat;
        this.showSun = showSun;
        this.showOtherWeekCourse = showOtherWeekCourse;
    }
}
