package com.checktodo.app.jshim;

import java.util.ArrayDeque;
import java.util.Deque;

/** Tolerant HTML tree builder for the Jsoup shim. */
final class HtmlTreeBuilder {
    private HtmlTreeBuilder() {
    }

    static void appendHtml(Element root, String html) {
        Element current = root;
        Deque<String> openTags = new ArrayDeque<>();
        int i = 0;
        while (i < html.length()) {
            int start = html.indexOf('<', i);
            if (start < 0) {
                current.appendText(html.substring(i));
                break;
            }
            if (start > i) current.appendText(html.substring(i, start));
            int end = findTagEnd(html, start);
            if (end < 0) {
                current.appendText(html.substring(start));
                break;
            }
            String raw = html.substring(start + 1, end);
            if (raw.startsWith("!--")) {
                int close = html.indexOf("-->", end + 1);
                i = close >= 0 ? close + 3 : html.length();
                continue;
            }
            String tagName = tagName(raw);
            boolean closing = raw.startsWith("/");
            boolean selfClosing = raw.endsWith("/") || isVoid(tagName);
            if (isRawText(tagName) && !closing) {
                Element element = new Element(tagName);
                applyAttributes(element, raw);
                current.appendChild(element);
                String closeTag = "</" + tagName;
                int close = indexOfIgnoreCase(html, closeTag, end + 1);
                int contentEnd = close < 0 ? html.length() : close;
                element.appendText(html.substring(end + 1, contentEnd));
                i = close < 0 ? html.length() : endOfTag(html, close) + 1;
                continue;
            }
            if (closing) {
                while (!openTags.isEmpty()) {
                    current = current.parent == null ? root : (Element) current.parent;
                    String opened = openTags.pop();
                    if (opened.equals(tagName)) break;
                }
            } else {
                Element element = new Element(tagName);
                applyAttributes(element, raw);
                current.appendChild(element);
                if (!selfClosing) {
                    current = element;
                    openTags.push(tagName);
                }
            }
            i = end + 1;
        }
    }

    private static void applyAttributes(Element element, String raw) {
        int space = indexOfWhitespace(raw, 0);
        String source = space >= 0 ? raw.substring(space + 1) : "";
        int i = 0;
        while (i < source.length()) {
            while (i < source.length() && Character.isWhitespace(source.charAt(i))) i++;
            int keyStart = i;
            while (i < source.length() && source.charAt(i) != '='
                    && !Character.isWhitespace(source.charAt(i))) i++;
            if (keyStart == i) break;
            String key = source.substring(keyStart, i).toLowerCase();
            String value = "";
            while (i < source.length() && Character.isWhitespace(source.charAt(i))) i++;
            if (i < source.length() && source.charAt(i) == '=') {
                i++;
                while (i < source.length() && Character.isWhitespace(source.charAt(i))) i++;
                if (i < source.length() && (source.charAt(i) == '"' || source.charAt(i) == '\'')) {
                    char quote = source.charAt(i++);
                    int valueStart = i;
                    while (i < source.length() && source.charAt(i) != quote) i++;
                    value = source.substring(valueStart, i);
                    if (i < source.length()) i++;
                } else {
                    int valueStart = i;
                    while (i < source.length() && !Character.isWhitespace(source.charAt(i))) i++;
                    value = source.substring(valueStart, i);
                }
                value = Entities.decode(value);
            }
            if (!key.isEmpty()) element.attributes.put(key, value);
        }
    }

    private static String tagName(String raw) {
        int start = raw.startsWith("/") ? 1 : 0;
        int end = start;
        while (end < raw.length() && Character.isLetterOrDigit(raw.charAt(end))) end++;
        return raw.substring(start, end).toLowerCase();
    }

    private static int findTagEnd(String html, int start) {
        char quote = 0;
        for (int i = start + 1; i < html.length(); i++) {
            char ch = html.charAt(i);
            if (quote != 0) {
                if (ch == quote) quote = 0;
            } else if (ch == '"' || ch == '\'') {
                quote = ch;
            } else if (ch == '>') {
                return i;
            }
        }
        return -1;
    }

    private static int endOfTag(String html, int start) {
        int end = html.indexOf('>', start);
        return end < 0 ? html.length() - 1 : end;
    }

    private static int indexOfIgnoreCase(String source, String target, int from) {
        for (int i = from; i <= source.length() - target.length(); i++) {
            if (source.regionMatches(true, i, target, 0, target.length())) return i;
        }
        return -1;
    }

    private static int indexOfWhitespace(String source, int from) {
        for (int i = from; i < source.length(); i++) {
            if (Character.isWhitespace(source.charAt(i))) return i;
        }
        return -1;
    }

    private static boolean isVoid(String tag) {
        return tag.equals("br") || tag.equals("img") || tag.equals("input")
                || tag.equals("hr") || tag.equals("meta") || tag.equals("link");
    }

    private static boolean isRawText(String tag) {
        return tag.equals("script") || tag.equals("style");
    }
}
