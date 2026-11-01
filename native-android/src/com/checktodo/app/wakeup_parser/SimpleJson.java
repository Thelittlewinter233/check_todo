package com.checktodo.app.wakeup_parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tiny JSON parser for decoded school data and Kingosoft course JSON. */
final class SimpleJson {
    private SimpleJson() {
    }

    static Object parse(String text) {
        Parser parser = new Parser(text == null ? "" : text);
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.position != parser.text.length()) {
            throw new IllegalArgumentException("Trailing JSON data");
        }
        return value;
    }

    static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    static double number(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(string(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    static Map<String, Object> object(Object value) {
        return value instanceof Map ? castMap(value) : new LinkedHashMap<>();
    }

    static List<Object> array(Object value) {
        return value instanceof List ? castList(value) : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object value) {
        return (List<Object>) value;
    }

    private static final class Parser {
        private final String text;
        private int position;

        Parser(String text) {
            this.text = text;
        }

        Object readValue() {
            skipWhitespace();
            if (position >= text.length()) throw new IllegalArgumentException("Unexpected JSON end");
            char current = text.charAt(position);
            if (current == '{') return readObject();
            if (current == '[') return readArray();
            if (current == '"') return readString();
            if (text.startsWith("true", position)) return consume(Boolean.TRUE);
            if (text.startsWith("false", position)) return consume(Boolean.FALSE);
            if (text.startsWith("null", position)) return consume(null);
            return readNumber();
        }

        private Object consume(Object value) {
            position += value == null ? 4 : String.valueOf(value).length();
            return value;
        }

        private Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (match('}')) return result;
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                result.put(key, readValue());
                skipWhitespace();
                if (match('}')) return result;
                expect(',');
            }
        }

        private List<Object> readArray() {
            expect('[');
            List<Object> result = new ArrayList<>();
            skipWhitespace();
            if (match(']')) return result;
            while (true) {
                result.add(readValue());
                skipWhitespace();
                if (match(']')) return result;
                expect(',');
            }
        }

        private String readString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (position < text.length()) {
                char current = text.charAt(position++);
                if (current == '"') return out.toString();
                if (current != '\\') {
                    out.append(current);
                    continue;
                }
                if (position >= text.length()) throw new IllegalArgumentException("Unexpected escape end");
                char escaped = text.charAt(position++);
                switch (escaped) {
                    case '"': case '\\': case '/':
                        out.append(escaped);
                        break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        out.append(readUnicode());
                        break;
                    default:
                        throw new IllegalArgumentException("Bad JSON escape: \\" + escaped);
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        private char readUnicode() {
            if (position + 4 > text.length()) throw new IllegalArgumentException("Bad unicode escape");
            String hex = text.substring(position, position + 4);
            position += 4;
            return (char) Integer.parseInt(hex, 16);
        }

        private Number readNumber() {
            int start = position;
            while (position < text.length() && "+-0123456789.eE".indexOf(text.charAt(position)) >= 0) {
                position++;
            }
            String value = text.substring(start, position);
            if (value.isEmpty()) throw new IllegalArgumentException("Bad JSON number");
            if (value.indexOf('.') < 0 && value.indexOf('e') < 0 && value.indexOf('E') < 0) {
                try {
                    return Long.parseLong(value);
                } catch (Exception ignored) {
                }
            }
            return Double.parseDouble(value);
        }

        private void skipWhitespace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) position++;
        }

        private void expect(char expected) {
            skipWhitespace();
            if (position >= text.length() || text.charAt(position) != expected) {
                throw new IllegalArgumentException("Expected " + expected);
            }
            position++;
        }

        private boolean match(char expected) {
            if (position < text.length() && text.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }
    }
}
