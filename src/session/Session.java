package session;
import java.security.SecureRandom;

public class Session {

    private static final SecureRandom random = new SecureRandom();

    public static String generateSessionId() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        System.out.println("====> session id: " + sb.toString());
        return sb.toString();
    }
}
