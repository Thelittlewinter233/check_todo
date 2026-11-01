package com.checktodo.app.wakeup_parser;

import java.util.Map;

/** One entry from WakeUp's encrypted school catalog. */
public final class SchoolInfo {
    public final String sortKey;
    public final String name;
    public final String url;
    public final String type;
    public final String importType;
    public final int minVersion;
    public final String mode;

    private SchoolInfo(String sortKey, String name, String url, String type,
                       String importType, int minVersion, String mode) {
        this.sortKey = safe(sortKey);
        this.name = safe(name);
        this.url = safe(url);
        this.type = safe(type);
        this.importType = safe(importType);
        this.minVersion = minVersion;
        this.mode = safe(mode);
    }

    public static SchoolInfo fromJson(Map<String, Object> object) {
        if (object == null) return null;
        String name = SimpleJson.string(object.get("name"));
        if (name.trim().isEmpty()) return null;
        return new SchoolInfo(SimpleJson.string(object.get("sortKey")), name,
                SimpleJson.string(object.get("url")), SimpleJson.string(object.get("type")),
                SimpleJson.string(object.get("importType")),
                (int) SimpleJson.number(object.get("minVersion")),
                SimpleJson.string(object.get("mode")));
    }

    public static SchoolInfo of(String sortKey, String name, String url, String type,
                                String importType, int minVersion, String mode) {
        return new SchoolInfo(sortKey, name, url, type, importType, minVersion, mode);
    }

    public String label() {
        return name + (type.trim().isEmpty() ? "" : " (" + type + ")");
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
