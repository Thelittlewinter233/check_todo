package com.checktodo.app.wakeup_parser;

import java.util.ArrayList;
import java.util.List;

/** Result of one WakeUp-family parser run. */
public final class WakeupParserResult {
    public final List<WakeupCourse> courses = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();
    public String parserName = "";
    public int nodes;
    public int maxWeek;
    public String startDate = "";
    public String tableName = "";

    public boolean isEmpty() {
        return courses.isEmpty();
    }
}
