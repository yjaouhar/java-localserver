
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import utils.AppConfig;
import utils.JsonFormatValidator;

public final class ConfigLoader {

    private ConfigLoader() {
    }

    public static AppConfig loadFromFile(String path) throws IOException {
        String json = Files.readString(Path.of(path), StandardCharsets.UTF_8);
        return loadFromString(json);
    }

    public static AppConfig loadFromString(String json) {
        // 1) validate basic format (your validator)
        JsonFormatValidator.InnerJsonFormatValidator v = JsonFormatValidator.isValidJsonFormat(json);
        if (!v.status) {
            throw new IllegalArgumentException("Invalid JSON format: " + v.message
                    + (v.index != null ? (" at index " + v.index) : ""));
        }

        // 2) parse JSON into Java structures
        Object root = new MiniJsonParser(json).parse();
        if (!(root instanceof Map)) {
            throw new IllegalArgumentException("Root must be a JSON object");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> obj = (Map<String, Object>) root;

        // 3) build AppConfig
        AppConfig cfg = new AppConfig();

        // optional: timeouts / limits (if present later)
        if (obj.containsKey("limits")) {
            Map<String, Object> limits = asObject(obj.get("limits"), "limits");
            if (limits.containsKey("max_header_bytes")) {
                cfg.limits.maxHeaderBytes = asInt(limits.get("max_header_bytes"), "limits.max_header_bytes");
            }
        }

        if (obj.containsKey("timeouts")) {
            Map<String, Object> t = asObject(obj.get("timeouts"), "timeouts");
            // headerMs is final in your class; you can't set it. (it stays 10000)
            if (t.containsKey("body_ms")) {
                cfg.timeouts.bodyMs = asInt(t.get("body_ms"), "timeouts.body_ms");
            }
            if (t.containsKey("idle_keep_alive_ms")) {
                cfg.timeouts.idleKeepAliveMs = asInt(t.get("idle_keep_alive_ms"), "timeouts.idle_keep_alive_ms");
            }
        }

        // servers (required)
        if (obj.containsKey("servers")) {
            List<Object> servers = asArray(obj.get("servers"), "servers");
            for (int i = 0; i < servers.size(); i++) {
                Map<String, Object> s = asObject(servers.get(i), "servers[" + i + "]");
                cfg.servers.add(parseServer(s, "servers[" + i + "]"));
            }
        } else {
            throw new IllegalArgumentException("Missing required key: servers");
        }

        return cfg;
    }

    // -------------------- AppConfig mapping --------------------
    private static AppConfig.ServerConfig parseServer(Map<String, Object> s, String path) {
        AppConfig.ServerConfig sc = new AppConfig.ServerConfig();

        sc.name = asString(required(s, "name", path), path + ".name");
        sc.host = asString(required(s, "host", path), path + ".host");

        // ports
        List<Object> ports = asArray(required(s, "ports", path), path + ".ports");
        for (int i = 0; i < ports.size(); i++) {
            sc.ports.add(asInt(ports.get(i), path + ".ports[" + i + "]"));
        }

        sc.defaultServer = asBoolean(required(s, "default_server", path), path + ".default_server");
        sc.clientMaxBodySize = asLong(required(s, "client_max_body_size", path), path + ".client_max_body_size");

        // error_pages (optional)
        if (s.containsKey("error_pages")) {
            Map<String, Object> eps = asObject(s.get("error_pages"), path + ".error_pages");
            for (Map.Entry<String, Object> e : eps.entrySet()) {
                int code = parseIntKey(e.getKey(), path + ".error_pages key");
                sc.errorPages.put(code, asString(e.getValue(), path + ".error_pages[" + e.getKey() + "]"));
            }
        }

        // routes (required)
        List<Object> routes = asArray(required(s, "routes", path), path + ".routes");
        for (int i = 0; i < routes.size(); i++) {
            Map<String, Object> r = asObject(routes.get(i), path + ".routes[" + i + "]");
            sc.routes.add(parseRoute(r, path + ".routes[" + i + "]"));
        }

        return sc;
    }

    private static AppConfig.RouteConfig parseRoute(Map<String, Object> r, String path) {
        AppConfig.RouteConfig rc = new AppConfig.RouteConfig();

        rc.path = asString(required(r, "path", path), path + ".path");

        // root optional (redirect route may not have it)
        if (r.containsKey("root")) {
            rc.root = asString(r.get("root"), path + ".root");
        }

        // methods optional
        if (r.containsKey("methods")) {
            List<Object> ms = asArray(r.get("methods"), path + ".methods");
            for (int i = 0; i < ms.size(); i++) {
                rc.methods.add(asString(ms.get(i), path + ".methods[" + i + "]"));
            }
        }

        // index optional
        if (r.containsKey("index")) {
            rc.index = asString(r.get("index"), path + ".index");
        }

        // directory_listing optional
        if (r.containsKey("directory_listing")) {
            rc.directoryListing = asBoolean(r.get("directory_listing"), path + ".directory_listing");
        }

        // upload_dir optional
        if (r.containsKey("upload_dir")) {
            rc.uploadDir = asString(r.get("upload_dir"), path + ".upload_dir");
        }

        // cgi optional
        if (r.containsKey("cgi")) {
            Map<String, Object> c = asObject(r.get("cgi"), path + ".cgi");
            AppConfig.CgiConfig cg = new AppConfig.CgiConfig();
            cg.extension = asString(required(c, "extension", path + ".cgi"), path + ".cgi.extension");
            cg.interpreter = asString(required(c, "interpreter", path + ".cgi"), path + ".cgi.interpreter");
            rc.cgi = cg;
        }

        // redirect optional
        if (r.containsKey("redirect")) {
            Map<String, Object> d = asObject(r.get("redirect"), path + ".redirect");
            AppConfig.Redirect red = new AppConfig.Redirect();
            red.code = asInt(required(d, "code", path + ".redirect"), path + ".redirect.code");
            red.location = asString(required(d, "location", path + ".redirect"), path + ".redirect.location");
            rc.redirect = red;
        }

        return rc;
    }

    // -------------------- typed helpers --------------------
    private static Object required(Map<String, Object> obj, String key, String path) {
        if (!obj.containsKey(key)) {
            throw new IllegalArgumentException("Missing required key: " + path + "." + key);
        }
        return obj.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object v, String path) {
        if (v instanceof Map) {
            return (Map<String, Object>) v;
        }
        throw new IllegalArgumentException("Expected object at " + path);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asArray(Object v, String path) {
        if (v instanceof List) {
            return (List<Object>) v;
        }
        throw new IllegalArgumentException("Expected array at " + path);
    }

    private static String asString(Object v, String path) {
        if (v instanceof String) {
            return (String) v;
        }
        throw new IllegalArgumentException("Expected string at " + path);
    }

    private static boolean asBoolean(Object v, String path) {
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        throw new IllegalArgumentException("Expected boolean at " + path);
    }

    private static int asInt(Object v, String path) {
        if (v instanceof Long) {
            long x = (Long) v;
            if (x > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Integer overflow at " + path);
            }
            return (int) x;
        }
        throw new IllegalArgumentException("Expected integer at " + path);
    }

    private static long asLong(Object v, String path) {
        if (v instanceof Long) {
            return (Long) v;
        }
        throw new IllegalArgumentException("Expected integer/long at " + path);
    }

    private static int parseIntKey(String key, String path) {
        // keys in JSON objects are strings; for error_pages they are like "404"
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("Empty key at " + path);
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("Non-numeric key '" + key + "' at " + path);
            }
        }
        // no leading zeros restriction here; "404" ok
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Bad numeric key '" + key + "' at " + path);
        }
    }

    // -------------------- Minimal JSON parser --------------------
    // Supports: objects, arrays, strings (basic escapes), true/false/null, non-negative integers.
    private static final class MiniJsonParser {

        private final String s;
        private int i;

        MiniJsonParser(String s) {
            this.s = s;
            this.i = 0;
        }

        Object parse() {
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
            if (c == '{') {
                return parseObject();
            }
            if (c == '[') {
                return parseArray();
            }
            if (c == '"') {
                return parseString();
            }
            if (c == 't') {
                return parseLiteral("true", Boolean.TRUE);
            }
            if (c == 'f') {
                return parseLiteral("false", Boolean.FALSE);
            }
            if (c == 'n') {
                return parseLiteral("null", null);
            }
            if (c >= '0' && c <= '9') {
                return parseNonNegativeInteger();
            }
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
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (i >= s.length()) {
                        throw error("Bad escape");
                    }
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        default:
                            throw error("Unsupported escape: \\" + e);
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
                // prevent leading zeros: "01"
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
                if (c < '0' || c > '9') {
                    break;
                }
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
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    i++; 
                }else {
                    break;
                }
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

    // @Override
    // public String toString() {
    //     return "ServerConfig{" +
    //         "name='" + name + '\'' +
    //         ", host='" + host + '\'' +
    //         ", ports=" + ports +
    //         ", defaultServer=" + defaultServer +
    //         ", clientMaxBodySize=" + clientMaxBodySize +
    //         ", errorPages=" + errorPages +
    //         ", routes=" + routes +
    //         '}';
    // }
}
