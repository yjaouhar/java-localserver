package handlers;

import http.HttpRequest;
import http.HttpResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import utils.json.AppConfig;

public class UploadHandler {

    public static HttpResponse handleUpload(AppConfig.RouteConfig rout, HttpRequest request , Map<Integer, String> errorPages) {
        if (request == null) {
            return HttpResponse.ErrorResponse(400, "Bad Request", "Request is null", errorPages.get(400));
        }
        if (request.getMethod() == null || !request.getMethod().equals("POST")) {
            return HttpResponse.ErrorResponse(405, "Method Not Allowed", "Only POST method is allowed", errorPages.get(405));
        }

        String contentType = request.getHeader("Content-Type");
        if (contentType == null || !contentType.startsWith("multipart/form-data")) {
            return HttpResponse.ErrorResponse(415, "Unsupported Media Type", "Content-Type must be multipart/form-data", errorPages.get(415));
        }
        String boundary = null;
        for (String param : manualSplit(contentType, ";")) {
            if (param.trim().startsWith("boundary=")) {
                boundary = param.trim().substring(9);
            }
        }
        if (boundary == null || boundary.isEmpty()) {
            return HttpResponse.ErrorResponse(400, "Bad Request", "Boundary parameter is missing in Content-Type", errorPages.get(400));
        }
        String body = request.getBody();
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
                    return HttpResponse.ErrorResponse(500, "Internal Server Error", "Failed to save uploaded file: " + e.getMessage(), errorPages.get(500));
                }

            }
        }
        if (!uploaded) {
            return HttpResponse.ErrorResponse(400, "Bad Request", "No file part found in the request", errorPages.get(400));
        }

        return HttpResponse.successResponse(201, "Created", "File uploaded successfully");
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

}
