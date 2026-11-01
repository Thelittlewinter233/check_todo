package com.checktodo.app.wakeup_parser;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Decodes WakeUp's school catalog: reverse, Base64, AES-CBC, Base64, GZIP.
 * The catalog remains encrypted inside the APK and is decoded only in memory.
 */
public final class SchoolInfoDecoder {
    private static final byte[] KEY = "wakeup-yzune1215".getBytes(StandardCharsets.UTF_8);

    private SchoolInfoDecoder() {
    }

    public static String decode(String encoded) throws Exception {
        String compact = encoded == null ? "" : encoded.replaceAll("\\s+", "");
        if (compact.isEmpty()) throw new IllegalArgumentException("Empty school catalog");
        byte[] first = decodeBase64(reverse(compact));
        byte[] decrypted = decrypt(first);
        String encodedGzip = new String(decrypted, StandardCharsets.UTF_8).replaceAll("\\s+", "");
        byte[] second = decodeBase64(encodedGzip);
        return gunzip(second);
    }

    public static List<SchoolInfo> parse(String json) throws Exception {
        Map<String, Object> root = SimpleJson.object(SimpleJson.parse(json == null ? "{}" : json));
        List<Object> data = SimpleJson.array(root.get("data"));
        List<SchoolInfo> result = new ArrayList<>();
        for (Object item : data) {
            SchoolInfo school = SchoolInfo.fromJson(SimpleJson.object(item));
            if (school != null) result.add(school);
        }
        return result;
    }

    public static List<SchoolInfo> search(List<SchoolInfo> schools, String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<SchoolInfo> result = new ArrayList<>();
        if (schools == null) return result;
        if (needle.isEmpty()) {
            result.addAll(schools);
            return result;
        }
        for (SchoolInfo school : schools) {
            String haystack = (school.name + " " + school.sortKey + " "
                    + school.type + " " + school.importType).toLowerCase(Locale.ROOT);
            if (haystack.contains(needle)) result.add(school);
        }
        return result;
    }

    private static byte[] decrypt(byte[] input) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY, "AES"),
                new IvParameterSpec(KEY));
        return cipher.doFinal(input);
    }

    private static String gunzip(byte[] input) throws Exception {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(input))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Lenient Base64 decoder used instead of java.util.Base64: the app supports API 24,
     * while java.util.Base64 only exists from API 26.
     */
    private static byte[] decodeBase64(String value) {
        String compact = value.replaceAll("\\s+", "");
        if (compact.isEmpty()) throw new IllegalArgumentException("Empty Base64 input");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int bits = 0;
        int bitCount = 0;
        for (int i = 0; i < compact.length(); i++) {
            char current = compact.charAt(i);
            if (current == '=') break;
            int decoded = base64Char(current);
            if (decoded < 0) throw new IllegalArgumentException("Invalid Base64 character: " + current);
            bits = (bits << 6) | decoded;
            bitCount += 6;
            if (bitCount >= 8) {
                bitCount -= 8;
                out.write((bits >> bitCount) & 0xff);
            }
        }
        return out.toByteArray();
    }

    private static int base64Char(char current) {
        if (current >= 'A' && current <= 'Z') return current - 'A';
        if (current >= 'a' && current <= 'z') return current - 'a' + 26;
        if (current >= '0' && current <= '9') return current - '0' + 52;
        if (current == '+' || current == '-') return 62;
        if (current == '/' || current == '_') return 63;
        return -1;
    }

    private static String reverse(String value) {
        return new StringBuilder(value).reverse().toString();
    }
}
