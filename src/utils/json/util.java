package utils.json;

public class util {

    public static boolean isValidIPv4(String ip) {
        if (ip == null) {
            return false;
        }

        ip = ip.trim();
        if (ip.isEmpty()) {
            return false;
        }

        String[] parts = ip.split("\\.");

        if (parts.length != 4) {
            return false;
        }

        for (String part : parts) {
            if (part.isEmpty()) {
                return false;
            }

            int value = 0;
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c < '0' || c > '9') {
                    return false;
                }
                value = value * 10 + (c - '0');
            }

            if (value < 0 || value > 255) {
                return false;
            }
        }

        return true;
    }

}
