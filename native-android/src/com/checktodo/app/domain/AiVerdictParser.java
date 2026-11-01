package com.checktodo.app.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GLM 判定响应的容错解析（纯 Java，自带最小 JSON 读取器——域层不依赖 org.json）。
 *
 * <p>模型被要求严格输出
 * {@code {"verdict":"pass|fail|uncertain","confidence":0-100,"reason":"简短中文理由"}}，
 * 但真实响应常见以下污染，全部要能兜住：</p>
 * <ul>
 *   <li>markdown 代码栅栏（```json ... ```）；</li>
 *   <li>JSON 前后夹带解释性文字；</li>
 *   <li>verdict 大小写/空白不一致、未知取值；</li>
 *   <li>confidence 是字符串、小数或越界；</li>
 *   <li>reason 内含转义引号、换行、\\u 转义、花括号；</li>
 *   <li>OpenAI 兼容响应的 content 是部件数组而非字符串。</li>
 * </ul>
 *
 * <p><b>解析不出来一律返回 null</b>，由调用方走降级路径（完成打卡 + 待复核），
 * 绝不把「解析失败」误判成 FAIL。</p>
 */
public final class AiVerdictParser {

    private AiVerdictParser() {
    }

    /**
     * 从模型 content 文本中解析判定。无法解析返回 null。
     */
    public static AiVerdict parse(String content) {
        if (content == null || content.trim().isEmpty()) return null;
        // 依次尝试文本中每一个平衡的 {...} 候选：栅栏、前后杂文、干扰花括号都能跳过。
        for (int start = content.indexOf('{'); start >= 0; start = content.indexOf('{', start + 1)) {
            String candidate = extractBalanced(content, start);
            if (candidate == null) continue;
            Object parsed = Json.read(candidate);
            if (!(parsed instanceof Map)) continue;
            Map<?, ?> object = (Map<?, ?>) parsed;
            if (!object.containsKey("verdict") && !object.containsKey("confidence")
                    && !object.containsKey("reason")) {
                continue;   // 干扰对象（如 {"error":...} 内层），继续找下一个
            }
            return toVerdict(object);
        }
        return null;
    }

    /**
     * 提取文本中第一个平衡的 JSON 对象子串（考虑字符串与转义）；找不到返回 null。
     * 仅用于测试与诊断断言，不参与判定语义。
     */
    public static String extractJsonObject(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        return start < 0 ? null : extractBalanced(text, start);
    }

    private static String extractBalanced(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char current = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) return text.substring(start, i + 1);
            }
        }
        return null;
    }

    private static AiVerdict toVerdict(Map<?, ?> object) {
        AiVerdict.Verdict verdict = AiVerdict.parseVerdict(asText(object.get("verdict")));
        int confidence = asConfidence(object.get("confidence"));
        String reason = object.get("reason") == null ? "" : String.valueOf(object.get("reason"));
        return new AiVerdict(verdict, confidence, reason);
    }

    private static String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** confidence 容错：数字直接取整、字符串数字解析、小数截断、非数字 → 0；越界截断。 */
    private static int asConfidence(Object value) {
        if (value == null) return 0;
        double parsed;
        if (value instanceof Number) {
            parsed = ((Number) value).doubleValue();
        } else {
            try {
                parsed = Double.parseDouble(String.valueOf(value).trim());
            } catch (NumberFormatException exception) {
                return 0;
            }
        }
        if (parsed < 0) return 0;
        if (parsed > 100) return 100;
        return (int) parsed;
    }

    /**
     * 从 OpenAI 兼容响应体中取出 {@code choices[0].message.content}。
     * content 为字符串时直接返回；为部件数组时拼接各部件的 text；取不到返回 null。
     */
    public static String extractContent(String responseBody) {
        if (responseBody == null || responseBody.trim().isEmpty()) return null;
        Object parsed = Json.read(responseBody);
        if (!(parsed instanceof Map)) return null;
        Object choices = ((Map<?, ?>) parsed).get("choices");
        if (!(choices instanceof List) || ((List<?>) choices).isEmpty()) return null;
        Object first = ((List<?>) choices).get(0);
        if (!(first instanceof Map)) return null;
        Object message = ((Map<?, ?>) first).get("message");
        if (!(message instanceof Map)) return null;
        Object content = ((Map<?, ?>) message).get("content");
        String text = flattenContent(content);
        return text == null || text.trim().isEmpty() ? null : text;
    }

    private static String flattenContent(Object content) {
        if (content instanceof String) return (String) content;
        if (!(content instanceof List)) return null;
        StringBuilder builder = new StringBuilder();
        for (Object part : (List<?>) content) {
            if (part instanceof String) {
                builder.append((String) part);
            } else if (part instanceof Map) {
                Object text = ((Map<?, ?>) part).get("text");
                if (text instanceof String) builder.append((String) text);
            }
        }
        return builder.toString();
    }

    /**
     * 端到端：响应体 → content → 判定。任一步失败返回 null（调用方降级）。
     */
    public static AiVerdict parseResponse(String responseBody) {
        return parse(extractContent(responseBody));
    }

    // ------------------------------------------------------------------
    // 最小 JSON 读取器（域层零依赖：不引 org.json，JVM 可直接测）
    // ------------------------------------------------------------------

    /** 递归下降的极小 JSON 读取器：非法输入返回 null 而不抛异常。 */
    static final class Json {
        private final String text;
        private int index;

        private Json(String text) {
            this.text = text;
        }

        static Object read(String raw) {
            if (raw == null) return null;
            Json reader = new Json(raw);
            reader.skipWhitespace();
            Object value = reader.readValue();
            if (value == null && !reader.atLiteralNull()) return null;
            reader.skipWhitespace();
            return reader.index >= reader.text.length() ? value : null;
        }

        private boolean atLiteralNull() {
            return text.startsWith("null", index);
        }

        private Object readValue() {
            if (index >= text.length()) return null;
            char current = text.charAt(index);
            if (current == '{') return readObject();
            if (current == '[') return readArray();
            if (current == '"') return readString();
            if (atLiteralNull()) {
                index += 4;
                return null;
            }
            if (current == 't' || current == 'f') return readBoolean();
            return readNumber();
        }

        private Map<String, Object> readObject() {
            Map<String, Object> result = new HashMap<>();
            index++;   // '{'
            skipWhitespace();
            if (index < text.length() && text.charAt(index) == '}') {
                index++;
                return result;
            }
            while (index < text.length()) {
                skipWhitespace();
                if (index >= text.length() || text.charAt(index) != '"') return null;
                String key = readString();
                if (key == null) return null;
                skipWhitespace();
                if (index >= text.length() || text.charAt(index) != ':') return null;
                index++;
                skipWhitespace();
                int before = index;
                Object value = readValue();
                if (value == null && !(index > before && atLiteralNullBefore(before))) return null;
                result.put(key, value);
                skipWhitespace();
                if (index >= text.length()) return null;
                char separator = text.charAt(index);
                index++;
                if (separator == '}') return result;
                if (separator != ',') return null;
            }
            return null;
        }

        private boolean atLiteralNullBefore(int position) {
            return text.startsWith("null", position);
        }

        private List<Object> readArray() {
            List<Object> result = new ArrayList<>();
            index++;   // '['
            skipWhitespace();
            if (index < text.length() && text.charAt(index) == ']') {
                index++;
                return result;
            }
            while (index < text.length()) {
                skipWhitespace();
                int before = index;
                Object value = readValue();
                if (value == null && !atLiteralNullBefore(before)) return null;
                result.add(value);
                skipWhitespace();
                if (index >= text.length()) return null;
                char separator = text.charAt(index);
                index++;
                if (separator == ']') return result;
                if (separator != ',') return null;
            }
            return null;
        }

        private String readString() {
            index++;   // 开引号
            StringBuilder builder = new StringBuilder();
            while (index < text.length()) {
                char current = text.charAt(index++);
                if (current == '"') return builder.toString();
                if (current != '\\') {
                    builder.append(current);
                    continue;
                }
                if (index >= text.length()) return null;
                char escape = text.charAt(index++);
                switch (escape) {
                    case '"': builder.append('"'); break;
                    case '\\': builder.append('\\'); break;
                    case '/': builder.append('/'); break;
                    case 'b': builder.append('\b'); break;
                    case 'f': builder.append('\f'); break;
                    case 'n': builder.append('\n'); break;
                    case 'r': builder.append('\r'); break;
                    case 't': builder.append('\t'); break;
                    case 'u':
                        if (index + 4 > text.length()) return null;
                        try {
                            builder.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
                        } catch (NumberFormatException exception) {
                            return null;
                        }
                        index += 4;
                        break;
                    default:
                        return null;
                }
            }
            return null;
        }

        private Object readBoolean() {
            if (text.startsWith("true", index)) {
                index += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", index)) {
                index += 5;
                return Boolean.FALSE;
            }
            return null;
        }

        private Object readNumber() {
            int start = index;
            while (index < text.length()) {
                char current = text.charAt(index);
                boolean numberChar = (current >= '0' && current <= '9') || current == '-' || current == '+'
                        || current == '.' || current == 'e' || current == 'E';
                if (!numberChar) break;
                index++;
            }
            if (start == index) return null;
            String token = text.substring(start, index);
            try {
                if (token.indexOf('.') >= 0 || token.indexOf('e') >= 0 || token.indexOf('E') >= 0) {
                    return Double.parseDouble(token);
                }
                long longValue = Long.parseLong(token);
                return longValue > Integer.MAX_VALUE || longValue < Integer.MIN_VALUE
                        ? (Object) longValue : (Object) Integer.valueOf((int) longValue);
            } catch (NumberFormatException exception) {
                return null;
            }
        }

        private void skipWhitespace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
        }
    }

    /** 供壳层与测试读取 JSON 对象为 Map（复用同一读取器）。 */
    public static Map<String, Object> readObjectOrNull(String json) {
        Object value = Json.read(json);
        if (!(value instanceof Map)) return null;
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }
}
