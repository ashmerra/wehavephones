package com.wehavephones.mobile.addons.repo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal dependency-free JSON reader/writer for the addon index.
 *
 * Deliberately not org.json: this class is compiled and exercised by the host
 * JVM self-test (scripts/test-wehavephones-addons.sh) with no Android classpath.
 */
public final class Json {
    private Json() {}

    public static final class JsonException extends Exception {
        public JsonException(String message) {
            super(message);
        }
    }

    public static Object parse(String text) throws JsonException {
        if (text == null) throw new JsonException("empty JSON");
        Parser parser = new Parser(text);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) throw new JsonException("trailing JSON content at " + parser.position());
        return value;
    }

    public static String write(Object value) {
        StringBuilder builder = new StringBuilder();
        writeValue(builder, value);
        return builder.toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>)value : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object value) {
        return value instanceof List ? (List<Object>)value : null;
    }

    public static Map<String, Object> newObject() {
        return new LinkedHashMap<>();
    }

    public static String optString(Map<String, Object> map, String key, String fallback) {
        if (map == null) return fallback;
        Object value = map.get(key);
        return value instanceof String ? (String)value : fallback;
    }

    public static long optLong(Map<String, Object> map, String key, long fallback) {
        if (map == null) return fallback;
        Object value = map.get(key);
        if (value instanceof Number) return ((Number)value).longValue();
        if (value instanceof String) {
            try {
                return Long.parseLong((String)value);
            }
            catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static boolean optBoolean(Map<String, Object> map, String key, boolean fallback) {
        if (map == null) return fallback;
        Object value = map.get(key);
        if (value instanceof Boolean) return (Boolean)value;
        if (value instanceof String) return Boolean.parseBoolean((String)value);
        return fallback;
    }

    public static List<String> optStringList(Map<String, Object> map, String key) {
        ArrayList<String> result = new ArrayList<>();
        if (map == null) return result;
        List<Object> list = asList(map.get(key));
        if (list == null) return result;
        for (Object item : list) {
            if (item instanceof String) result.add((String)item);
        }
        return result;
    }

    private static void writeValue(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
        }
        else if (value instanceof String) {
            writeString(builder, (String)value);
        }
        else if (value instanceof Boolean) {
            builder.append(value.toString());
        }
        else if (value instanceof Number) {
            builder.append(value.toString());
        }
        else if (value instanceof Map) {
            writeMap(builder, (Map<?, ?>)value);
        }
        else if (value instanceof List) {
            writeList(builder, (List<?>)value);
        }
        else {
            writeString(builder, value.toString());
        }
    }

    private static void writeMap(StringBuilder builder, Map<?, ?> map) {
        builder.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) builder.append(',');
            first = false;
            writeString(builder, String.valueOf(entry.getKey()));
            builder.append(':');
            writeValue(builder, entry.getValue());
        }
        builder.append('}');
    }

    private static void writeList(StringBuilder builder, List<?> list) {
        builder.append('[');
        boolean first = true;
        for (Object item : list) {
            if (!first) builder.append(',');
            first = false;
            writeValue(builder, item);
        }
        builder.append(']');
    }

    private static void writeString(StringBuilder builder, String value) {
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"': builder.append("\\\""); break;
                case '\\': builder.append("\\\\"); break;
                case '\n': builder.append("\\n"); break;
                case '\r': builder.append("\\r"); break;
                case '\t': builder.append("\\t"); break;
                case '\b': builder.append("\\b"); break;
                case '\f': builder.append("\\f"); break;
                default:
                    if (character < 0x20) {
                        builder.append(String.format(java.util.Locale.US, "\\u%04x", (int)character));
                    }
                    else {
                        builder.append(character);
                    }
            }
        }
        builder.append('"');
    }

    private static final class Parser {
        private final String text;
        private int index;

        Parser(String text) {
            this.text = text;
        }

        int position() {
            return index;
        }

        boolean atEnd() {
            return index >= text.length();
        }

        void skipWhitespace() {
            while (index < text.length()) {
                char character = text.charAt(index);
                if (character == ' ' || character == '\t' || character == '\n' || character == '\r') index++;
                else break;
            }
        }

        Object parseValue() throws JsonException {
            skipWhitespace();
            if (atEnd()) throw new JsonException("unexpected end of JSON");
            char character = text.charAt(index);
            if (character == '{') return parseObject();
            if (character == '[') return parseArray();
            if (character == '"') return parseString();
            if (character == 't' || character == 'f') return parseBoolean();
            if (character == 'n') return parseNull();
            return parseNumber();
        }

        private Map<String, Object> parseObject() throws JsonException {
            expect('{');
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                index++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') throw new JsonException("expected object key at " + index);
                String key = parseString();
                skipWhitespace();
                expect(':');
                map.put(key, parseValue());
                skipWhitespace();
                char character = next();
                if (character == '}') return map;
                if (character != ',') throw new JsonException("expected ',' or '}' at " + (index - 1));
            }
        }

        private List<Object> parseArray() throws JsonException {
            expect('[');
            ArrayList<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                index++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                char character = next();
                if (character == ']') return list;
                if (character != ',') throw new JsonException("expected ',' or ']' at " + (index - 1));
            }
        }

        private String parseString() throws JsonException {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (true) {
                if (atEnd()) throw new JsonException("unterminated string");
                char character = text.charAt(index++);
                if (character == '"') return builder.toString();
                if (character != '\\') {
                    builder.append(character);
                    continue;
                }
                if (atEnd()) throw new JsonException("unterminated escape");
                char escape = text.charAt(index++);
                switch (escape) {
                    case '"': builder.append('"'); break;
                    case '\\': builder.append('\\'); break;
                    case '/': builder.append('/'); break;
                    case 'n': builder.append('\n'); break;
                    case 'r': builder.append('\r'); break;
                    case 't': builder.append('\t'); break;
                    case 'b': builder.append('\b'); break;
                    case 'f': builder.append('\f'); break;
                    case 'u':
                        if (index + 4 > text.length()) throw new JsonException("bad unicode escape");
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            int digit = Character.digit(text.charAt(index++), 16);
                            if (digit < 0) throw new JsonException("bad unicode escape");
                            code = code * 16 + digit;
                        }
                        builder.append((char)code);
                        break;
                    default:
                        throw new JsonException("unknown escape \\" + escape);
                }
            }
        }

        private Object parseNumber() throws JsonException {
            int start = index;
            while (index < text.length()) {
                char character = text.charAt(index);
                if (character == '-' || character == '+' || character == '.' || character == 'e'
                        || character == 'E' || Character.isDigit(character)) index++;
                else break;
            }
            String token = text.substring(start, index);
            if (token.isEmpty()) throw new JsonException("expected value at " + start);
            try {
                if (token.indexOf('.') < 0 && token.indexOf('e') < 0 && token.indexOf('E') < 0) {
                    return Long.parseLong(token);
                }
                return Double.parseDouble(token);
            }
            catch (NumberFormatException e) {
                throw new JsonException("bad number: " + token);
            }
        }

        private Boolean parseBoolean() throws JsonException {
            if (text.startsWith("true", index)) {
                index += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", index)) {
                index += 5;
                return Boolean.FALSE;
            }
            throw new JsonException("bad literal at " + index);
        }

        private Object parseNull() throws JsonException {
            if (text.startsWith("null", index)) {
                index += 4;
                return null;
            }
            throw new JsonException("bad literal at " + index);
        }

        private char peek() throws JsonException {
            if (atEnd()) throw new JsonException("unexpected end of JSON");
            return text.charAt(index);
        }

        private char next() throws JsonException {
            if (atEnd()) throw new JsonException("unexpected end of JSON");
            return text.charAt(index++);
        }

        private void expect(char expected) throws JsonException {
            if (atEnd() || text.charAt(index) != expected) {
                throw new JsonException("expected '" + expected + "' at " + index);
            }
            index++;
        }
    }
}
