package utils.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
public class MiniJsonParser {

    private final String s;
    private int i;

   public MiniJsonParser(String s) {
        this.s = s;
        this.i = 0;
    }

  public Object parse() {
        skipWs();
        Object v = parseValue();
        skipWs();
        if (i != s.length()) {
            throw error("Extra content after JSON");
        }
        return v;
    }

    private Object parseValue() {
        skipWs();
        if (i >= s.length()) {
            throw error("Unexpected end");
        }

        char c = s.charAt(i);
        if (c == '{') return parseObject();
        if (c == '[') return parseArray();
        if (c == '"') return parseString();
        if (c == 't') return parseLiteral("true", Boolean.TRUE);
        if (c == 'f') return parseLiteral("false", Boolean.FALSE);
        if (c == 'n') return parseLiteral("null", null);
        if (c >= '0' && c <= '9') return parseNonNegativeInteger();

        throw error("Unexpected character: " + c);
    }

    private Map<String, Object> parseObject() {
        expect('{');
        skipWs();
        Map<String, Object> m = new LinkedHashMap<>();
        if (peek('}')) {
            i++;
            return m;
        }

        while (true) {
            skipWs();
            String key = parseString();
            skipWs();
            expect(':');
            Object val = parseValue();
            m.put(key, val);
            skipWs();
            if (peek(',')) {
                i++;
                continue;
            }
            if (peek('}')) {
                i++;
                break;
            }
            throw error("Expected ',' or '}'");
        }
        return m;
    }

    private List<Object> parseArray() {
        expect('[');
        skipWs();
        List<Object> a = new ArrayList<>();
        if (peek(']')) {
            i++;
            return a;
        }

        while (true) {
            Object val = parseValue();
            a.add(val);
            skipWs();
            if (peek(',')) {
                i++;
                continue;
            }
            if (peek(']')) {
                i++;
                break;
            }
            throw error("Expected ',' or ']'");
        }
        return a;
    }

    private String parseString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();

            if (c == '\\') {
                if (i >= s.length()) throw error("Bad escape");
                char e = s.charAt(i++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    default: throw error("Unsupported escape: \\" + e);
                }
            } else {
                sb.append(c);
            }
        }
        throw error("Unterminated string");
    }

    private Object parseLiteral(String lit, Object value) {
        if (s.startsWith(lit, i)) {
            i += lit.length();
            return value;
        }
        throw error("Invalid literal");
    }

    private Long parseNonNegativeInteger() {
        int start = i;
        if (s.charAt(i) == '0') {
            i++;
            if (i < s.length()) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') {
                    throw error("Leading zeros not allowed");
                }
            }
            return 0L;
        }

        while (i < s.length()) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') break;
            i++;
        }

        String num = s.substring(start, i);
        try {
            return Long.parseLong(num);
        } catch (NumberFormatException e) {
            throw error("Number too large");
        }
    }

    private void skipWs() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++;
            else break;
        }
    }

    private void expect(char c) {
        if (i >= s.length() || s.charAt(i) != c) {
            throw error("Expected '" + c + "'");
        }
        i++;
    }

    private boolean peek(char c) {
        return i < s.length() && s.charAt(i) == c;
    }

    private IllegalArgumentException error(String msg) {
        return new IllegalArgumentException(msg + " at index " + i);
    }
}
