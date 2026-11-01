package com.checktodo.app.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * One timetable sheet: an isolated config, time-node table, course set and detail set.
 *
 * <p>The object is immutable; repository updates build a new sheet and replace it in
 * {@link TimetableSheets}.</p>
 */
public final class TimetableSheet {
    public final String id;
    public final String name;
    public final TimetableConfig config;
    public final List<TimeNode> timeNodes;
    public final List<CourseBase> bases;
    public final List<CourseDetail> details;

    public TimetableSheet(String id, String name, TimetableConfig config,
                          List<TimeNode> timeNodes, List<CourseBase> bases,
                          List<CourseDetail> details) {
        this.id = id == null || id.trim().isEmpty() ? UUID.randomUUID().toString() : id.trim();
        this.name = name == null || name.trim().isEmpty() ? "课表" : name.trim();
        this.config = config;
        this.timeNodes = immutable(timeNodes);
        this.bases = immutable(bases);
        this.details = immutable(details);
    }

    public TimetableSheet withName(String nextName) {
        return new TimetableSheet(id, nextName, config, timeNodes, bases, details);
    }

    public TimetableSheet withConfig(TimetableConfig nextConfig) {
        return new TimetableSheet(id, name, nextConfig, timeNodes, bases, details);
    }

    public TimetableSheet withTimeNodes(List<TimeNode> nextNodes) {
        return new TimetableSheet(id, name, config, nextNodes, bases, details);
    }

    public TimetableSheet withCourses(List<CourseBase> nextBases, List<CourseDetail> nextDetails) {
        return new TimetableSheet(id, name, config, timeNodes, nextBases, nextDetails);
    }

    /**
     * Copies this sheet under a new id/name. When copyCourses is false the new sheet keeps the
     * config and time table but starts with an empty course set.
     */
    public TimetableSheet copy(String nextId, String nextName, boolean copyCourses) {
        List<CourseBase> nextBases = copyCourses ? new ArrayList<>(bases) : new ArrayList<>();
        List<CourseDetail> nextDetails = copyCourses ? new ArrayList<>(details) : new ArrayList<>();
        return new TimetableSheet(nextId, nextName, config, timeNodes, nextBases, nextDetails);
    }

    private static <T> List<T> immutable(List<T> source) {
        if (source == null) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(source));
    }
}
