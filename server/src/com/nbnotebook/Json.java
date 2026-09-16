package com.nbnotebook;

import java.util.*;

/** 极简 JSON 解析/生成（无第三方依赖） */
public final class Json {
    private Json() {}

    // ---------- 生成 ----------
    public static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /** 把任意对象转为 JSON：Map/List/String/Number/Boolean/null */
    public static String stringify(Object o) {
        if (o == null) return "null";
        if (o instanceof String s) return quote(s);
        if (o instanceof Boolean b) return b ? "true" : "false";
        if (o instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) return String.valueOf(n.longValue());
            return String.valueOf(d);
        }
        if (o instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(quote(String.valueOf(e.getKey()))).append(':').append(stringify(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (o instanceof List<?> l) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object e : l) {
                if (!first) sb.append(',');
                first = false;
                sb.append(stringify(e));
            }
            return sb.append(']').toString();
        }
        if (o instanceof Object[] a) return stringify(Arrays.asList(a));
        return quote(String.valueOf(o));
    }

    // ---------- 解析 ----------
    public static Object parse(String s) {
        Parser p = new Parser(s);
        Object v = p.parseValue();
        p.skipWs();
        if (!p.eof()) throw new RuntimeException("JSON 尾部有多余字符 @" + p.pos);
        return v;
    }

    private static final class Parser {
        final String s; int pos = 0;
        Parser(String s) { this.s = s; }
        boolean eof() { return pos >= s.length(); }
        char peek() { return s.charAt(pos); }
        char next() { return s.charAt(pos++); }
        void skipWs() { while (!eof() && Character.isWhitespace(peek())) pos++; }

        Object parseValue() {
            skipWs();
            if (eof()) throw new RuntimeException("JSON 意外结束");
            char c = peek();
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't' || c == 'f') return parseBool();
            if (c == 'n') { expect("null"); return null; }
            return parseNumber();
        }

        Map<String, Object> parseObject() {
            expect("{");
            Map<String, Object> m = new LinkedHashMap<>();
            skipWs();
            if (!eof() && peek() == '}') { pos++; return m; }
            while (true) {
                skipWs();
                String k = parseString();
                skipWs();
                expect(":");
                m.put(k, parseValue());
                skipWs();
                if (eof()) throw new RuntimeException("JSON 对象未闭合");
                char c = next();
                if (c == ',') continue;
                if (c == '}') return m;
                throw new RuntimeException("JSON 对象期望 , 或 } 但遇到 " + c);
            }
        }

        List<Object> parseArray() {
            expect("[");
            List<Object> l = new ArrayList<>();
            skipWs();
            if (!eof() && peek() == ']') { pos++; return l; }
            while (true) {
                l.add(parseValue());
                skipWs();
                if (eof()) throw new RuntimeException("JSON 数组未闭合");
                char c = next();
                if (c == ',') continue;
                if (c == ']') return l;
                throw new RuntimeException("JSON 数组期望 , 或 ] 但遇到 " + c);
            }
        }

        String parseString() {
            expect("\"");
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) throw new RuntimeException("JSON 字符串未闭合");
                char c = next();
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (eof()) throw new RuntimeException("JSON 转义不完整");
                    char e = next();
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            if (pos + 4 > s.length()) throw new RuntimeException("JSON \\u 不完整");
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4; break;
                        default: throw new RuntimeException("JSON 非法转义 \\" + e);
                    }
                } else sb.append(c);
            }
        }

        Boolean parseBool() {
            if (s.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (s.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            throw new RuntimeException("JSON 非法布尔 @" + pos);
        }

        Double parseNumber() {
            int start = pos;
            while (!eof() && "+-0123456789.eE".indexOf(peek()) >= 0) pos++;
            if (start == pos) throw new RuntimeException("JSON 非法数字 @" + start);
            return Double.parseDouble(s.substring(start, pos));
        }

        void expect(String t) {
            if (s.startsWith(t, pos)) pos += t.length();
            else throw new RuntimeException("JSON 期望 " + t + " 但遇到 " + (eof() ? "EOF" : s.charAt(pos)));
        }
    }

    // ---------- 便利访问 ----------
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) { return (Map<String, Object>) o; }
    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) { return (List<Object>) o; }

    public static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }
    public static String str(Map<String, Object> m, String k, String def) {
        String v = str(m, k);
        return v == null ? def : v;
    }
    public static int num(Map<String, Object> m, String k, int def) {
        Object v = m.get(k);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) try { return Integer.parseInt(s.trim()); } catch (Exception ignored) {}
        return def;
    }

    /** 布尔读取：兼容 true/false、1/0、"1"/"true" */
    public static boolean bool(Map<String, Object> m, String k, boolean def) {
        Object v = m.get(k);
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        if (v instanceof String s) return s.equals("1") || s.equalsIgnoreCase("true") || s.equalsIgnoreCase("yes");
        return def;
    }
    public static List<Object> arr(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof List<?> l ? asList(l) : new ArrayList<>();
    }

    /** 取字符串数组（跳过非字符串元素） */
    public static List<String> strList(Map<String, Object> m, String k) {
        List<String> out = new ArrayList<>();
        for (Object o : arr(m, k)) {
            if (o != null) out.add(String.valueOf(o));
        }
        return out;
    }
}
