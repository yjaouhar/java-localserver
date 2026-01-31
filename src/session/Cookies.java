package session;

import java.util.HashMap;
import java.util.Map;

public class Cookies {

    public static String generate(String name, String value, int maxAge, String path, boolean httpOnly) {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append("=").append(value);

        if (maxAge >= 0) {
            sb.append("; Max-Age=").append(maxAge);
        }

        if (path != null && !path.isEmpty()) {
            sb.append("; Path=").append(path);
        }

        if (httpOnly) {
            sb.append("; HttpOnly");
        }
        System.out.println("====> cookie string: " + sb.toString());
        return sb.toString();
    }

    public static Map<String, String> parse(String cookieHeader) {
        Map<String, String> cookies = new HashMap<>();
        if (cookieHeader == null || cookieHeader.isEmpty()) {
            return cookies;
        }

        String[] pairs = cookieHeader.split(";");
        for (String pair : pairs) {
            pair = pair.trim();
            int eqIndex = pair.indexOf('=');
            if (eqIndex > 0) {
                String name = pair.substring(0, eqIndex).trim();
                String value = pair.substring(eqIndex + 1).trim();
                cookies.put(name, value);
            }
        }
        return cookies;
    }
}
