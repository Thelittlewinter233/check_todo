package com.checktodo.app.wakeup_parser;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Selects a rewritten parser family from WakeUp school type/importType. */
public final class WakeupParserFactory {
    public static final String TYPE_ZF = "zf";
    public static final String TYPE_JZ = "jz";
    public static final String TYPE_URP_NEW = "urp_new";
    public static final String TYPE_KINGOSOFT = "kingosoft";
    public static final String TYPE_ELEMENT_UI = "element_ui";

    private WakeupParserFactory() {
    }

    public static boolean supports(String type, String importType) {
        String key = family(type, importType);
        return supportedTypes().contains(key);
    }

    public static WakeupParser create(String type, String importType) {
        String key = family(type, importType);
        switch (key) {
            case TYPE_ZF: return new ZhengFangParser();
            case TYPE_JZ: return new JinZhiParser();
            case TYPE_URP_NEW: return new UrpNewParser();
            case TYPE_KINGOSOFT: return new KingosoftParser();
            case TYPE_ELEMENT_UI: return new ElementUiParser();
            default: return null;
        }
    }

    public static List<String> supportedTypes() {
        return Arrays.asList(TYPE_ZF, TYPE_JZ, TYPE_URP_NEW, TYPE_KINGOSOFT, TYPE_ELEMENT_UI);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String family(String type, String importType) {
        String key = normalize(type).isEmpty() ? normalize(importType) : normalize(type);
        if (key.equals("kingo_new")) return TYPE_KINGOSOFT;
        return key;
    }
}
