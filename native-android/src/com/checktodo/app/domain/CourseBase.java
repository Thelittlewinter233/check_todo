package com.checktodo.app.domain;

/** 课程主数据：同一门课可对应多条排课明细。 */
public final class CourseBase {
    public final String id;
    public final String name;
    /** 0 表示未指定，显示时按 id 落入 9 色色板。 */
    public final int color;
    public final String note;
    public final double credit;

    public CourseBase(String id, String name, int color, String note, double credit) {
        this.id = id;
        this.name = name == null ? "" : name.trim();
        this.color = color;
        this.note = note == null ? "" : note.trim();
        this.credit = credit;
    }
}
