package session;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class Cookies {

    private final String name;
    private final String value;
    private final Long maxAgeSeconds;

    public Cookies(String name, String value, long maxAgeSeconds) {
        this.name = name;
        this.value = value;
        this.maxAgeSeconds = maxAgeSeconds;
    }

    public String getName() {
        return this.name;
    }

    public String getValue() {
        return this.value;
    }

    public boolean isExpired(Instant createdAt) {
        return Instant.now().isAfter(createdAt.plusSeconds(maxAgeSeconds));
    }

    public String generateCookieString() {
        String cookie = name + "=" + value + "; Max-Age=" + maxAgeSeconds + "; Path=/; HttpOnly";
        return cookie;

    }

    public static Map<String, String> parseCookies(String cookieHeader) {
        Map<String, String> cookies = new HashMap<>();
        if (cookieHeader == null) {
            return cookies;
        }

        String[] pairs = cookieHeader.split(";");
        for (String pair : pairs) {
            String[] kv = pair.trim().split("=", 2);
            if (kv.length == 2) {
                cookies.put(kv[0], kv[1]);
            }
        }
        return cookies;
    }

}
