package handlers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UploadHandler {

    public static int handleUpload(HttpRequest request) {
        if (request == null) {
            return 400;
        }
        if (request.getMethod() == null || !request.getMethod().equals("POST")) {
            return 405; // Method Not Allowed
        }

        String contentType = request.getHeader("Content-Type");
        if (contentType == null || !contentType.startsWith("multipart/form-data")) {
            return 415; // Unsupported Media Type
        }
        String boundary = null;
        for (String param : manualSplit(contentType, ";")) {
            if (param.trim().startsWith("boundary=")) {
                boundary = param.trim().substring(9);
            }
        }
        if (boundary == null || boundary.isEmpty()) {
            return 415;
        }
        String body = new String(request.getBody(), StandardCharsets.ISO_8859_1);
        List<String> parts = manualSplit(body, "--" + boundary);

        boolean uploaded = false;

        for (String p : parts) {
            if (p.contains("filename=\"")) {
                String fileName = extractFileName(p);
                if (fileName == null || fileName.isEmpty()) {
                    continue;
                }
                int separatorIndex = p.indexOf("\r\n\r\n");
                if (separatorIndex == -1) {
                    continue;
                }
                String bodyPart = p.substring(separatorIndex + 4);
                if (bodyPart.endsWith("\r\n")) {
                    bodyPart = bodyPart.substring(0, bodyPart.length() - 2);
                }
                byte[] fileData = bodyPart.getBytes(StandardCharsets.ISO_8859_1);
                Path uploadDir = Paths.get("uploads");
                try {
                    if (!Files.exists(uploadDir)) {
                        Files.createDirectories(uploadDir);
                    }

                    Files.write(uploadDir.resolve(fileName), fileData);
                    uploaded = true;

                } catch (IOException e) {
                    return 500;
                }

            }
        }
        if (!uploaded) {
            return 400;
        }

        return 201;
    }

    private static String extractFileName(String part) {
        int startIndex = part.indexOf("filename=\"") + 10;
        int endIndex = part.indexOf("\"", startIndex);
        if (startIndex >= 10 && endIndex > startIndex) {
            String filname = part.substring(startIndex, endIndex);
            return Paths.get(filname).getFileName().toString(); // Prevent directory traversal
        }
        return null;
    }

    public static List<String> manualSplit(String text, String separator) {
        List<String> parts = new ArrayList<>();
        int start = 0;

        while (true) {
            int index = text.indexOf(separator, start);
            if (index == -1) {
                parts.add(text.substring(start));
                break;
            }
            parts.add(text.substring(start, index));
            start = index + separator.length();
        }

        return parts;
    }

    public class HttpRequest {

        private final String method;
        private final String path;
        private final String version;
        private final Map<String, String> headers;
        private final byte[] body;

        public HttpRequest(String method, String path, String version, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.path = path;
            this.version = version;
            this.headers = (headers == null) ? Collections.emptyMap() : new HashMap<>(headers);
            this.body = body;
        }

        public String getMethod() {
            return method;
        }

        public String getPath() {
            return path;
        }

        public String getVersion() {
            return version;
        }

        public Map<String, String> getHeaders() {
            return Collections.unmodifiableMap(headers);
        }

        public byte[] getBody() {
            return body;
        }

        public String getHeader(String name) {
            return headers.get(name.toLowerCase());
        }

    }

}
