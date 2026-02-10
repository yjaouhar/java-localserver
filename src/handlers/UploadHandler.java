package handlers;

import http.HttpRequest;
import http.HttpResponse;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import utils.json.AppConfig;

public class UploadHandler {

    public static HttpResponse handleUpload(AppConfig.RouteConfig route,
            HttpRequest request,
            Map<Integer, String> errorPages) {

        if (request == null || !"POST".equals(request.getMethod())) {
            return HttpResponse.ErrorResponse(405, "Method Not Allowed",
                    "Only POST allowed", errorPages.get(405));
        }

        String contentType = request.getHeader("Content-Type");
        if (contentType == null || !contentType.contains("multipart/form-data")) {
            return HttpResponse.ErrorResponse(415, "Unsupported Media Type",
                    "Expected multipart/form-data", errorPages.get(415));
        }

        String boundary = extractBoundary(contentType);
        if (boundary == null) {
            return HttpResponse.ErrorResponse(400, "Bad Request",
                    "Missing boundary", errorPages.get(400));
        }

        String startBoundary = "--" + boundary;
        String endBoundary = startBoundary + "--";

        Path bodyFile = request.getBodyFile();

        try (InputStream in
                = new BufferedInputStream(Files.newInputStream(bodyFile))) {

            // 1️⃣ دور على أول boundary
            String line;
            while ((line = readLine(in)) != null) {
                if (line.equals(startBoundary)) {
                    break;
                }
            }

            // 2️⃣ loop على parts
            while (true) {
                Map<String, String> headers = new HashMap<>();

                // 3️⃣ قرا headers
                while ((line = readLine(in)) != null && !line.isEmpty()) {
                    int idx = line.indexOf(":");
                    if (idx != -1) {
                        headers.put(
                                line.substring(0, idx).trim(),
                                line.substring(idx + 1).trim()
                        );
                    }
                }

                if (headers.isEmpty()) {
                    break;
                }

                String cd = headers.get("Content-Disposition");
                boolean isFile = cd != null && cd.contains("filename=");

                if (isFile) {
                    String filename = extractFilename(cd);
                    Path outFile = Paths.get(route.uploadDir, filename).normalize();
                    writeFileData(in, outFile, startBoundary);
                } else {
                    skipPartData(in, startBoundary, endBoundary);
                }

                if (line == null || line.equals(endBoundary)) {
                    break;
                }
            }

        } catch (IOException e) {
            return HttpResponse.ErrorResponse(500, "Internal Server Error",
                    e.getMessage(), errorPages.get(500));
        }

        return HttpResponse.successResponse(201, "Created",
                "Upload OK");
    }

    // ================= helpers =================
    private static String extractBoundary(String ct) {
        for (String part : ct.split(";")) {
            part = part.trim();
            if (part.startsWith("boundary=")) {
                return part.substring(9);
            }
        }
        return null;
    }

    private static String extractFilename(String cd) {
        int s = cd.indexOf("filename=\"");
        if (s == -1) {
            return "upload.bin";
        }
        s += 10;
        int e = cd.indexOf("\"", s);
        return Paths.get(cd.substring(s, e)).getFileName().toString();
    }

    // ================= CORE =================
    // read line safely from InputStream
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                line.write(b);
            }
        }
        if (line.size() == 0 && b == -1) {
            return null;
        }
        return line.toString(StandardCharsets.ISO_8859_1);
    }

    private static void writeFileData(InputStream in,
            Path file,
            String boundary) throws IOException {

        try (OutputStream out
                = new BufferedOutputStream(Files.newOutputStream(file))) {

            byte[] buf = new byte[4096];
            ByteArrayOutputStream temp = new ByteArrayOutputStream();
            int n;

            while ((n = in.read(buf)) != -1) {
                temp.write(buf, 0, n);
                byte[] data = temp.toByteArray();
                String s = new String(data, StandardCharsets.ISO_8859_1);

                int idx = s.indexOf("\r\n" + boundary);
                if (idx != -1) {
                    out.write(data, 0, idx);
                    break;
                } else {
                    out.write(data);
                    temp.reset();
                }
            }
        }
    }

    private static void skipPartData(InputStream in,
            String boundary,
            String endBoundary) throws IOException {

        ByteArrayOutputStream temp = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;

        while ((n = in.read(buf)) != -1) {
            temp.write(buf, 0, n);
            String s = temp.toString(StandardCharsets.ISO_8859_1);

            if (s.contains("\r\n" + boundary) || s.contains("\r\n" + endBoundary)) {
                break;
            }
            temp.reset();
        }
    }
}
