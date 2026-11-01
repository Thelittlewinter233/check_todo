package com.checktodo.app.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Pure Java manager for multiple timetable sheets and the selected sheet. */
public final class TimetableSheets {
    private final List<TimetableSheet> sheets = new ArrayList<>();
    private String currentId;

    public TimetableSheets(TimetableSheet initial) {
        if (initial == null) throw new IllegalArgumentException("Initial timetable is required");
        sheets.add(initial);
        currentId = initial.id;
    }

    public List<TimetableSheet> all() {
        return Collections.unmodifiableList(new ArrayList<>(sheets));
    }

    public TimetableSheet current() {
        return find(currentId);
    }

    public String currentId() {
        return currentId;
    }

    public TimetableSheet find(String id) {
        if (id == null) return null;
        for (TimetableSheet sheet : sheets) {
            if (sheet.id.equals(id)) return sheet;
        }
        return null;
    }

    public boolean switchTo(String id) {
        if (find(id) == null) return false;
        currentId = id;
        return true;
    }

    /** Creates a sheet from the current one and selects it. */
    public TimetableSheet create(String name, boolean copyCourses) {
        TimetableSheet created = current().copy(UUID.randomUUID().toString(), name, copyCourses);
        sheets.add(created);
        currentId = created.id;
        return created;
    }

    /** Appends a parsed sheet without changing the selection; used by repository loading. */
    public void add(TimetableSheet sheet) {
        if (sheet != null && find(sheet.id) == null) sheets.add(sheet);
    }

    public boolean rename(String id, String name) {
        TimetableSheet sheet = find(id);
        if (sheet == null) return false;
        int index = sheets.indexOf(sheet);
        sheets.set(index, sheet.withName(name));
        return true;
    }

    /** Only a non-current sheet can be deleted; at least one sheet must remain. */
    public boolean delete(String id) {
        if (id == null || id.equals(currentId) || sheets.size() <= 1) return false;
        TimetableSheet sheet = find(id);
        if (sheet == null) return false;
        sheets.remove(sheet);
        return true;
    }

    public boolean replace(TimetableSheet next) {
        if (next == null || find(next.id) == null) return false;
        int index = sheets.indexOf(find(next.id));
        sheets.set(index, next);
        return true;
    }

    /** Legacy single-timetable migration: one sheet, selected by definition. */
    public static TimetableSheets fromLegacy(TimetableSheet legacy) {
        return new TimetableSheets(legacy);
    }
}
