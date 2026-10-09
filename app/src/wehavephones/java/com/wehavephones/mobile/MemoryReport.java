package com.wehavephones.mobile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON Lines codec for the diagnostics memory report.
 *
 * <p>It has no dependencies (no org.json, no Android) so the host self-test can
 * generate and parse report lines both ways. Format (one JSON object per line):
 * a header line with {@code "type":"header"}, then one line per sample with
 * {@code "type":"sample"}.</p>
 */
public final class MemoryReport {
    public static final String KIND = "wehavephones-memory-report";
    public static final int VERSION = 1;
    public static final String TYPE_HEADER = "header";
    public static final String TYPE_SAMPLE = "sample";

    public static final class ProcessRss {
        public final int pid;
        public final String name;
        public final long rssKb;

        public ProcessRss(int pid, String name, long rssKb) {
            this.pid = pid;
            this.name = name != null ? name : "";
            this.rssKb = rssKb;
        }
    }

    public static final class Sample {
        public long timeMillis;
        public long memAvailableKb = -1;
        public long swapFreeKb = -1;
        public double psiSomeAvg10 = -1.0;
        public double psiFullAvg10 = -1.0;
        public final ArrayList<ProcessRss> processes = new ArrayList<>();
    }

    private MemoryReport() {}

    public static String headerLine(String mode, String overrides, long timeMillis) {
        LinkedHashMap<String, Object> header = new LinkedHashMap<>();
        header.put("kind", KIND);
        header.put("version", VERSION);
        header.put("type", TYPE_HEADER);
        header.put("time", timeMillis);
        header.put("mode", mode);
        header.put("overrides", overrides);
        return toJson(header);
    }

    public static String sampleLine(Sample sample) {
        LinkedHashMap<String, Object> record = new LinkedHashMap<>();
        record.put("type", TYPE_SAMPLE);
        record.put("time", sample.timeMillis);
        record.put("memAvailableKb", sample.memAvailableKb);
        record.put("swapFreeKb", sample.swapFreeKb);
        record.put("psiSomeAvg10", sample.psiSomeAvg10);
        record.put("psiFullAvg10", sample.psiFullAvg10);
        List<Object> processes = new ArrayList<>();
        for (ProcessRss process : sample.processes) {
            LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
            entry.put("pid", process.pid);
            entry.put("name", process.name);
            entry.put("rssKb", process.rssKb);
            processes.add(entry);
        }
        record.put("processes", processes);
        return toJson(record);
    }

    /** Serialize maps/lists/strings/numbers/booleans/null as compact JSON. */
    public static String toJson(Object value) {
        StringBuilder builder = new StringBuilder();
        writeJson(builder, value);
        return builder.toString();
    }

    /** Parse one report line; returns null when the line is not valid JSON. */
    public static Object parse(String line) {
        if (line == null) return null;
        Parser parser = new Parser(line);
        try {
            Object value = parser.parseValue();
            parser.skipWhitespace();
            if (parser.index != line.length()) return null;
            return value;
        }
        catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void writeJson(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
        }
        else if (value instanceof String) {
            quote(builder, (String)value);
        }
        else if (value instanceof Boolean) {
            builder.append(value.toString());
        }
        else if (value instanceof Double || value instanceof Float) {
            double number = ((Number)value).doubleValue();
            if (Double.isNaN(number) || Double.isInfinite(number)) builder.append("null");
            else if (number == Math.rint(number) && Math.abs(number) < 1e15) {
                builder.append((long)number);
            }
            else builder.append(number);
        }
        else if (value instanceof Number) {
            builder.append(value.toString());
        }
        else if (value instanceof Map) {
            builder.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>)value).entrySet()) {
                if (!first) builder.append(',');
                first = false;
                quote(builder, String.valueOf(entry.getKey()));
                builder.append(':');
                writeJson(builder, entry.getValue());
            }
            builder.append('}');
        }
        else if (value instanceof Iterable) {
            builder.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>)value) {
                if (!first) builder.append(',');
                first = false;
                writeJson(builder, item);
            }
            builder.append(']');
        }
        else {
            quote(builder, String.valueOf(value));
        }
    }

    private static void quote(StringBuilder builder, String value) {
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"': builder.append("\\\""); break;
                case '\\': builder.append("\\\\"); break;
                case '\b': builder.append("\\b"); break;
                case '\f': builder.append("\\f"); break;
                case '\n': builder.append("\\n"); break;
                case '\r': builder.append("\\r"); break;
                case '\t': builder.append("\\t"); break;
                default:
                    if (character < 0x20) builder.append(String.format("\\u%04x", (int)character));
                    else builder.append(character);
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

        void skipWhitespace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
        }

        Object parseValue() {
            skipWhitespace();
            if (index >= text.length()) throw new IllegalArgumentException("empty");
            char character = text.charAt(index);
            if (character == '{') return parseObject();
            if (character == '[') return parseArray();
            if (character == '"') return parseString();
            if (character == 't') return parseLiteral("true", Boolean.TRUE);
            if (character == 'f') return parseLiteral("false", Boolean.FALSE);
            if (character == 'n') return parseLiteral("null", null);
            return parseNumber();
        }

        private Map<String, Object> parseObject() {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            index++;
            skipWhitespace();
            if (index < text.length() && text.charAt(index) == '}') {
                index++;
                return result;
            }
            while (true) {
                skipWhitespace();
                if (index >= text.length() || text.charAt(index) != '"') {
                    throw new IllegalArgumentException("object key expected");
                }
                String key = parseString();
                skipWhitespace();
                if (index >= text.length() || text.charAt(index) != ':') {
                    throw new IllegalArgumentException("colon expected");
                }
                index++;
                result.put(key, parseValue());
                skipWhitespace();
                if (index >= text.length()) throw new IllegalArgumentException("unterminated object");
                char character = text.charAt(index++);
                if (character == '}') return result;
                if (character != ',') throw new IllegalArgumentException("comma expected");
            }
        }

        private List<Object> parseArray() {
            ArrayList<Object> result = new ArrayList<>();
            index++;
            skipWhitespace();
            if (index < text.length() && text.charAt(index) == ']') {
                index++;
                return result;
            }
            while (true) {
                result.add(parseValue());
                skipWhitespace();
                if (index >= text.length()) throw new IllegalArgumentException("unterminated array");
                char character = text.charAt(index++);
                if (character == ']') return result;
                if (character != ',') throw new IllegalArgumentException("comma expected");
            }
        }

        private String parseString() {
            StringBuilder builder = new StringBuilder();
            index++;
            while (index < text.length()) {
                char character = text.charAt(index++);
                if (character == '"') return builder.toString();
                if (character != '\\') {
                    builder.append(character);
                    continue;
                }
                if (index >= text.length()) throw new IllegalArgumentException("escape");
                char escaped = text.charAt(index++);
                switch (escaped) {
                    case '"': builder.append('"'); break;
                    case '\\': builder.append('\\'); break;
                    case '/': builder.append('/'); break;
                    case 'b': builder.append('\b'); break;
                    case 'f': builder.append('\f'); break;
                    case 'n': builder.append('\n'); break;
                    case 'r': builder.append('\r'); break;
                    case 't': builder.append('\t'); break;
                    case 'u':
                        if (index + 4 > text.length()) throw new IllegalArgumentException("unicode escape");
                        builder.append((char)Integer.parseInt(text.substring(index, index + 4), 16));
                        index += 4;
                        break;
                    default: throw new IllegalArgumentException("bad escape");
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }

        private Object parseNumber() {
            int start = index;
            while (index < text.length()) {
                char character = text.charAt(index);
                if (character == '-' || character == '+' || character == '.' || character == 'e'
                        || character == 'E' || Character.isDigit(character)) index++;
                else break;
            }
            if (start == index) throw new IllegalArgumentException("number expected");
            String token = text.substring(start, index);
            try {
                if (token.indexOf('.') < 0 && token.indexOf('e') < 0 && token.indexOf('E') < 0) {
                    return Long.valueOf(token);
                }
                return Double.valueOf(token);
            }
            catch (NumberFormatException e) {
                throw new IllegalArgumentException("bad number");
            }
        }

        private Object parseLiteral(String literal, Object value) {
            if (!text.startsWith(literal, index)) throw new IllegalArgumentException("literal expected");
            index += literal.length();
            return value;
        }
    }
}
