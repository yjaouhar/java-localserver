package session;

import java.time.Instant;

public class Session {

    private String sessionId;
    private Instant expirationTime;

    public Session(String sessionId, long expirationTime) {
        this.sessionId = sessionId;
        this.expirationTime = Instant.now().plusSeconds(expirationTime);
    }

    public String getSessionId() {
        return this.sessionId;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expirationTime);
    }

    public void refreshExpiration(Long expirationTime) {
        this.expirationTime = Instant.now().plusSeconds(expirationTime);
    }
}
