package utils.json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import utils.json.AppConfig.ServerConfig;

public class ConfigMapper {

    private static final List<String> servernames = new ArrayList<>();

    public static AppConfig buildAppConfig(Map<String, Object> obj) {

        AppConfig cfg = new AppConfig();

        try {
            if (obj.containsKey("timeouts")) {
                Map<String, Object> t = asObject(obj.get("timeouts"), "timeouts");
                if (t != null) {
                    if (t.containsKey("body_ms")) cfg.timeouts.bodyMs = asInt(t.get("body_ms"), "timeouts.body_ms");
                    if (t.containsKey("header_ms")) cfg.timeouts.headerMs = asInt(t.get("header_ms"), "timeouts.header_ms");
                    if (t.containsKey("idle_keep_alive_ms")) cfg.timeouts.idleKeepAliveMs = asInt(t.get("idle_keep_alive_ms"), "timeouts.idle_keep_alive_ms");
                }
            }
        } catch (Exception ignored) {}

        List<Object> servers = null;
        try {
            servers = asArray(obj.get("servers"), "servers");
        } catch (Exception ignored) {}

        if (servers == null) {
            throw new IllegalArgumentException("Missing servers");
        }

        for (int i = 0; i < servers.size(); i++) {
            String path = "servers[" + i + "]";
            try {
                Map<String, Object> s = asObject(servers.get(i), path);
                ServerConfig sc = parseServerSafe(s, path);
                if (sc != null) cfg.servers.add(sc);
            } catch (Exception ignored) {
            }
        }

        if (cfg.servers.isEmpty()) {
            throw new IllegalArgumentException("No valid servers");
        }

        return cfg;
    }

    private static ServerConfig parseServerSafe(Map<String, Object> s, String path) {
        try {
            if (s == null) return null;

            String name = asString(required(s, "name"), path + ".name");
            if (name == null) return null;

            name = name.trim().toLowerCase();
            if (name.isEmpty() || name.contains(" ")) return null;
            if (servernames.contains(name)) return null;

            String host = asString(required(s, "host"), path + ".host");
            if (host == null) return null;
            if (!util.isValidIPv4(host)) return null;

            List<Object> ports = asArray(required(s, "ports"), path + ".ports");
            if (ports == null || ports.isEmpty()) return null;

            List<Integer> tmpPorts = new ArrayList<>();
            for (int i = 0; i < ports.size(); i++) {
                int p = asInt(ports.get(i), path + ".ports[" + i + "]");
                if (tmpPorts.contains(p)) return null;
                tmpPorts.add(p);
            }

            Object dl = s.get("default_server");
            if (dl == null) return null;

            BoolParse bdef = asBoolean(dl);
            if (!bdef.ok) return null;

            long maxBody = asLong(required(s, "client_max_body_size"), path + ".client_max_body_size");

            List<Object> routes = asArray(required(s, "routes"), path + ".routes");
            if (routes == null) return null;

            ServerConfig sc = new ServerConfig();
            sc.name = name;
            sc.host = host;
            sc.ports.addAll(tmpPorts);
            sc.clientMaxBodySize = maxBody;
            sc.defaultServer = bdef.value;

            if (s.containsKey("error_pages")) {
                Map<String, Object> eps = asObject(s.get("error_pages"), path + ".error_pages");
                if (eps == null) return null;
                for (Map.Entry<String, Object> e : eps.entrySet()) {
                    int code = parseIntKey(e.getKey(), path + ".error_pages");
                    String p = asString(e.getValue(), path + ".error_pages[" + e.getKey() + "]");
                    if (p == null) return null;
                    sc.errorPages.put(code, p);
                }
            }

            for (int i = 0; i < routes.size(); i++) {
                Map<String, Object> r = null;
                try {
                    r = asObject(routes.get(i), path + ".routes[" + i + "]");
                } catch (Exception ignored) {}
                AppConfig.RouteConfig rc = parseRouteSafe(r, path + ".routes[" + i + "]");
                if (rc != null) sc.routes.add(rc);
            }

            if (sc.routes.isEmpty()) return null;

            servernames.add(name);
            return sc;

        } catch (Exception ignored) {
            return null;
        }
    }

    private static AppConfig.RouteConfig parseRouteSafe(Map<String, Object> r, String path) {
        try {
            if (r == null) return null;

            AppConfig.RouteConfig rc = new AppConfig.RouteConfig();

            rc.path = asString(required(r, "path"), path + ".path");
            if (rc.path == null || rc.path.trim().isEmpty()) return null;

            if (r.containsKey("root")) {
                rc.root = asString(r.get("root"), path + ".root");
                if (rc.root == null) return null;
            }

            boolean hasPost = false;

            if (r.containsKey("methods")) {
                List<Object> ms = asArray(r.get("methods"), path + ".methods");
                if (ms == null || ms.isEmpty()) return null;

                for (int i = 0; i < ms.size(); i++) {
                    String m = asString(ms.get(i), path + ".methods[" + i + "]");
                    if (m == null) return null;
                    m = m.trim().toUpperCase();
                    rc.methods.add(m);
                    if ("POST".equals(m)) hasPost = true;
                }
            }

            if (hasPost && !r.containsKey("upload_dir")) return null;

            if (r.containsKey("upload_dir")) {
                rc.uploadDir = asString(r.get("upload_dir"), path + ".upload_dir");
                if (rc.uploadDir == null) return null;
            }

            if (r.containsKey("index")) {
                rc.index = asString(r.get("index"), path + ".index");
                if (rc.index == null) return null;
            }

            if (r.containsKey("directory_listing")) {
                BoolParse bd = asBoolean(r.get("directory_listing"));
                if (!bd.ok) return null;
                rc.directoryListing = bd.value;
            }

            if (r.containsKey("cgi")) {
                Map<String, Object> c = asObject(r.get("cgi"), path + ".cgi");
                if (c == null) return null;

                AppConfig.CgiConfig cg = new AppConfig.CgiConfig();
                cg.extension = asString(required(c, "extension"), path + ".cgi.extension");
                cg.interpreter = asString(required(c, "interpreter"), path + ".cgi.interpreter");
                if (cg.extension == null || cg.interpreter == null) return null;

                cg.extension = cg.extension.trim();
                cg.interpreter = cg.interpreter.trim();
                if (cg.extension.isEmpty() || cg.interpreter.isEmpty()) return null;

                rc.cgi = cg;
            }

            if (r.containsKey("redirect")) {
                Map<String, Object> d = asObject(r.get("redirect"), path + ".redirect");
                if (d == null) return null;

                AppConfig.Redirect red = new AppConfig.Redirect();
                red.code = asInt(required(d, "code"), path + ".redirect.code");
                red.location = asString(required(d, "location"), path + ".redirect.location");
                if (red.location == null || red.location.trim().isEmpty()) return null;

                rc.redirect = red;
            }

            return rc;

        } catch (Exception ignored) {
            return null;
        }
    }

    private static Object required(Map<String, Object> obj, String key) {
        if (obj == null) return null;
        if (!obj.containsKey(key)) return null;
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
        return null;
    }

    private static final class BoolParse {
        final boolean value;
        final boolean ok;
        BoolParse(boolean value, boolean ok) { this.value = value; this.ok = ok; }
    }

    private static BoolParse asBoolean(Object v) {
        if (v instanceof Boolean) return new BoolParse((Boolean) v, true);
        return new BoolParse(false, false);
    }

    private static int asInt(Object v, String path) {
        if (v instanceof Long) {
            long x = (Long) v;
            if (x > Integer.MAX_VALUE || x < Integer.MIN_VALUE) throw new IllegalArgumentException("Overflow at " + path);
            return (int) x;
        }
        throw new IllegalArgumentException("Expected int at " + path);
    }

    private static long asLong(Object v, String path) {
        if (v instanceof Long) return (Long) v;
        throw new IllegalArgumentException("Expected long at " + path);
    }

    private static int parseIntKey(String key, String path) {
        if (key == null || key.isEmpty()) throw new IllegalArgumentException("Empty key at " + path);
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < '0' || c > '9') throw new IllegalArgumentException("Non-numeric key at " + path);
        }
        return Integer.parseInt(key);
    }
}
