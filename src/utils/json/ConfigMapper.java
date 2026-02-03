package utils.json;

import java.util.List;
import java.util.Map;


public class ConfigMapper {

    public  ConfigMapper() {}

    public static AppConfig buildAppConfig(Map<String, Object> obj) {
        AppConfig cfg = new AppConfig();

        if (obj.containsKey("limits")) {
            Map<String, Object> limits = asObject(obj.get("limits"), "limits");
            if (limits.containsKey("max_header_bytes")) {
                cfg.limits.maxHeaderBytes = asInt(limits.get("max_header_bytes"), "limits.max_header_bytes");
            }
        }

        if (obj.containsKey("timeouts")) {
            Map<String, Object> t = asObject(obj.get("timeouts"), "timeouts");
            if (t.containsKey("body_ms")) {
                cfg.timeouts.bodyMs = asInt(t.get("body_ms"), "timeouts.body_ms");
            }
            if (t.containsKey("idle_keep_alive_ms")) {
                cfg.timeouts.idleKeepAliveMs = asInt(t.get("idle_keep_alive_ms"), "timeouts.idle_keep_alive_ms");
            }
        }

        if (!obj.containsKey("servers")) {
            throw new IllegalArgumentException("Missing required key: servers");
        }

        List<Object> servers = asArray(obj.get("servers"), "servers");
        for (int i = 0; i < servers.size(); i++) {
            Map<String, Object> s = asObject(servers.get(i), "servers[" + i + "]");
            cfg.servers.add(parseServer(s, "servers[" + i + "]"));
        }

        return cfg;
    }

    private static AppConfig.ServerConfig parseServer(Map<String, Object> s, String path) {
        AppConfig.ServerConfig sc = new AppConfig.ServerConfig();

        sc.name = asString(required(s, "name", path), path + ".name");
        String host = asString(required(s, "host", path), path + ".host");

        if (!util.isValidIPv4(host)) {
            throw new IllegalArgumentException("Invalid host IP at " + path + ".host: " + host);
        }
        sc.host = host;

        List<Object> ports = asArray(required(s, "ports", path), path + ".ports");
        for (int i = 0; i < ports.size(); i++) {
            int port = asInt(ports.get(i), path + ".ports[" + i + "]");
            if (sc.ports.contains(port)) {
                throw new IllegalArgumentException("Duplicate port in same server at " + path + ": " + port);
            }
            sc.ports.add(port);
        }

        sc.defaultServer = asBoolean(required(s, "default_server", path), path + ".default_server");
        sc.clientMaxBodySize = asLong(required(s, "client_max_body_size", path), path + ".client_max_body_size");

        if (s.containsKey("error_pages")) {
            Map<String, Object> eps = asObject(s.get("error_pages"), path + ".error_pages");
            for (Map.Entry<String, Object> e : eps.entrySet()) {
                int code = parseIntKey(e.getKey(), path + ".error_pages key");
                sc.errorPages.put(code, asString(e.getValue(), path + ".error_pages[" + e.getKey() + "]"));
            }
        }

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

        if (r.containsKey("root")) {
            rc.root = asString(r.get("root"), path + ".root");
        }

        if (r.containsKey("methods")) {
            List<Object> ms = asArray(r.get("methods"), path + ".methods");
            for (int i = 0; i < ms.size(); i++) {
                rc.methods.add(asString(ms.get(i), path + ".methods[" + i + "]"));
            }
        }

        if (r.containsKey("index")) {
            rc.index = asString(r.get("index"), path + ".index");
        }

        if (r.containsKey("directory_listing")) {
            rc.directoryListing = asBoolean(r.get("directory_listing"), path + ".directory_listing");
        }

        if (r.containsKey("upload_dir")) {
            rc.uploadDir = asString(r.get("upload_dir"), path + ".upload_dir");
        }

        if (r.containsKey("cgi")) {
            Map<String, Object> c = asObject(r.get("cgi"), path + ".cgi");
            AppConfig.CgiConfig cg = new AppConfig.CgiConfig();
            cg.extension = asString(required(c, "extension", path + ".cgi"), path + ".cgi.extension");
            cg.interpreter = asString(required(c, "interpreter", path + ".cgi"), path + ".cgi.interpreter");
            rc.cgi = cg;
        }

        if (r.containsKey("redirect")) {
            Map<String, Object> d = asObject(r.get("redirect"), path + ".redirect");
            AppConfig.Redirect red = new AppConfig.Redirect();
            red.code = asInt(required(d, "code", path + ".redirect"), path + ".redirect.code");
            red.location = asString(required(d, "location", path + ".redirect"), path + ".redirect.location");
            rc.redirect = red;
        }

        return rc;
    }

    private static Object required(Map<String, Object> obj, String key, String path) {
        if (!obj.containsKey(key)) {
            throw new IllegalArgumentException("Missing required key: " + path + "." + key);
        }
        return obj.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object v, String path) {
        if (v instanceof Map) return (Map<String, Object>) v;
        throw new IllegalArgumentException("Expected object at " + path);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asArray(Object v, String path) {
        if (v instanceof List) return (List<Object>) v;
        throw new IllegalArgumentException("Expected array at " + path);
    }

    private static String asString(Object v, String path) {
        if (v instanceof String) return (String) v;
        throw new IllegalArgumentException("Expected string at " + path);
    }

    private static boolean asBoolean(Object v, String path) {
        if (v instanceof Boolean) return (Boolean) v;
        throw new IllegalArgumentException("Expected boolean at " + path);
    }

    private static int asInt(Object v, String path) {
        if (v instanceof Long) {
            long x = (Long) v;
            if (x > Integer.MAX_VALUE) throw new IllegalArgumentException("Integer overflow at " + path);
            return (int) x;
        }
        throw new IllegalArgumentException("Expected integer at " + path);
    }

    private static long asLong(Object v, String path) {
        if (v instanceof Long) return (Long) v;
        throw new IllegalArgumentException("Expected integer/long at " + path);
    }

    private static int parseIntKey(String key, String path) {
        if (key == null || key.isEmpty()) throw new IllegalArgumentException("Empty key at " + path);
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < '0' || c > '9') throw new IllegalArgumentException("Non-numeric key '" + key + "' at " + path);
        }
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Bad numeric key '" + key + "' at " + path);
        }
    }
}

