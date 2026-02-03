package session;

import java.time.Instant;

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
    public  String generateCookieString() {
        String cookie = name + "=" + value + "; Max-Age="+ maxAgeSeconds+"; Path=/; HttpOnly";
        return cookie;

    }   
}
