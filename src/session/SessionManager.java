package session;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SessionManager {

    private static Map<String, Session> sessions = new ConcurrentHashMap<>();

    public static Session createSession(long expirationTime) {
        String sessionId = UUID.randomUUID().toString();
        Session session = new Session(sessionId, expirationTime);
        sessions.put(sessionId, session);
        return session;
    }
    
    public static Session getSession(String sessionId) {
        Session session = sessions.get(sessionId);
        if (session != null && !session.isExpired()) {
            return session;
        }
        sessions.remove(sessionId);
        return null;
    }

    public static void cleanUpExpiredSessions() {
        sessions.values().removeIf(Session::isExpired);
    }
    
    
}
