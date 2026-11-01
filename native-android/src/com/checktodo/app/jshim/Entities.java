package com.checktodo.app.jshim;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Entities {
    private static final Pattern ENTITY = Pattern.compile("&#(x[0-9a-fA-F]+|[0-9]+);|&([a-zA-Z][a-zA-Z0-9]*);");
    private static final Map<String, String> NAMED = build();

    private Entities() {
    }

    static String decode(String value) {
        if (value == null || value.indexOf('&') < 0) return value == null ? "" : value;
        Matcher matcher = ENTITY.matcher(value);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            out.append(value, last, matcher.start());
            String replacement;
            if (matcher.group(1) != null) {
                String digits = matcher.group(1);
                int code;
                try {
                    code = digits.charAt(0) == 'x' || digits.charAt(0) == 'X'
                            ? Integer.parseInt(digits.substring(1), 16)
                            : Integer.parseInt(digits);
                } catch (NumberFormatException ignored) {
                    code = -1;
                }
                replacement = code >= 0 ? new String(Character.toChars(code)) : matcher.group();
            } else {
                replacement = NAMED.getOrDefault(matcher.group(2).toLowerCase(), matcher.group());
            }
            out.append(replacement);
            last = matcher.end();
        }
        out.append(value.substring(last));
        return out.toString();
    }

    private static Map<String, String> build() {
        Map<String, String> map = new HashMap<>();
        map.put("nbsp", " ");
        map.put("amp", "&");
        map.put("lt", "<");
        map.put("gt", ">");
        map.put("quot", "\"");
        map.put("apos", "'");
        map.put("ensp", " ");
        map.put("emsp", " ");
        map.put("hellip", "...");
        return map;
    }
}
