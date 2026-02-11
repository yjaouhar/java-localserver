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
        if (boundary == null || boundary.isEmpty()) {
            return HttpResponse.ErrorResponse(400, "Bad Request",
                    "Missing boundary", errorPages.get(400));
        }

        String startBoundary = "--" + boundary;
        String endBoundary = startBoundary + "--";

        Path bodyFile = request.getBodyFile();

        try (InputStream fin = new BufferedInputStream(Files.newInputStream(bodyFile));
             PushbackInputStream in = new PushbackInputStream(fin, 1024 * 128)) { // 128KB pushback

            String line;
            while ((line = readLine(in)) != null) {
                if (line.equals(startBoundary)) break;
            }
            if (line == null) {
                return HttpResponse.ErrorResponse(400, "Bad Request",
                        "No multipart boundary found in body", errorPages.get(400));
            }

            Path uploadDir = Paths.get(route.uploadDir);
            Files.createDirectories(uploadDir);

            while (true) {
                Map<String, String> headers = new HashMap<>();

                while ((line = readLine(in)) != null && !line.isEmpty()) {
                    int idx = line.indexOf(":");
                    if (idx != -1) {
                        headers.put(line.substring(0, idx).trim(),
                                    line.substring(idx + 1).trim());
                    }
                }

                if (headers.isEmpty()) break;

                String cd = headers.get("Content-Disposition");
                boolean isFile = cd != null && cd.contains("filename=");

                if (isFile) {
                    String filename = extractFilename(cd);
                    Path outFile = uploadDir.resolve(filename).normalize();

                    if (!outFile.startsWith(uploadDir)) {
                        return HttpResponse.ErrorResponse(400, "Bad Request",
                                "Invalid filename", errorPages.get(400));
                    }

                    try (OutputStream out = new BufferedOutputStream(
                            Files.newOutputStream(outFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {

                        copyPartBodyUntilBoundary(in, out, startBoundary);
                    }

                } else {
                    discardPartBodyUntilBoundary(in, startBoundary);
                }

                line = readLine(in);
                if (line == null) break;

                if (line.equals(endBoundary)) {
                    break; 
                } else if (line.equals(startBoundary)) {
                    continue; 
                } else {
                    break;
                }
            }

        } catch (IOException e) {
            return HttpResponse.ErrorResponse(500, "Internal Server Error",
                    e.getMessage(), errorPages.get(500));
        }

        return HttpResponse.successResponse(201, "Created", "Upload OK");
    }

    // ================= helpers =================
    private static String extractBoundary(String ct) {
        for (String part : ct.split(";")) {
            part = part.trim();
            if (part.startsWith("boundary=")) {
                String b = part.substring("boundary=".length()).trim();
                if (b.startsWith("\"") && b.endsWith("\"") && b.length() >= 2) {
                    b = b.substring(1, b.length() - 1);
                }
                return b;
            }
        }
        return null;
    }

    private static String extractFilename(String cd) {
        int s = cd.indexOf("filename=\"");
        if (s == -1) return "upload.bin";
        s += "filename=\"".length();
        int e = cd.indexOf("\"", s);
        if (e == -1) return "upload.bin";
        return Paths.get(cd.substring(s, e)).getFileName().toString();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') line.write(b);
        }
        if (line.size() == 0 && b == -1) return null;
        return line.toString(StandardCharsets.ISO_8859_1);
    }

    // ================= CORE (FIXED) =================


    private static void copyPartBodyUntilBoundary(PushbackInputStream in,
                                                 OutputStream out,
                                                 String startBoundary) throws IOException {

        byte[] delim = ("\r\n" + startBoundary).getBytes(StandardCharsets.ISO_8859_1);
        int keep = delim.length - 1;

        byte[] buf = new byte[8192];
        byte[] tail = new byte[0];

        int n;
        while ((n = in.read(buf)) != -1) {

            byte[] chunk = combine(tail, buf, n);

            int pos = indexOf(chunk, delim);
            if (pos >= 0) {
                out.write(chunk, 0, pos);

                int unreadFrom = pos + 2; // skip \r\n
                if (unreadFrom < chunk.length) {
                    in.unread(chunk, unreadFrom, chunk.length - unreadFrom);
                }
                return;
            }

            if (chunk.length > keep) {
                int writeLen = chunk.length - keep;
                out.write(chunk, 0, writeLen);
                tail = Arrays.copyOfRange(chunk, writeLen, chunk.length);
            } else {
                tail = chunk; 
            }
        }
    }
    private static void discardPartBodyUntilBoundary(PushbackInputStream in,
                                                    String startBoundary) throws IOException {
        byte[] delim = ("\r\n" + startBoundary).getBytes(StandardCharsets.ISO_8859_1);
        int keep = delim.length - 1;

        byte[] buf = new byte[8192];
        byte[] tail = new byte[0];

        int n;
        while ((n = in.read(buf)) != -1) {
            byte[] chunk = combine(tail, buf, n);

            int pos = indexOf(chunk, delim);
            if (pos >= 0) {
                int unreadFrom = pos + 2; 
                if (unreadFrom < chunk.length) {
                    in.unread(chunk, unreadFrom, chunk.length - unreadFrom);
                }
                return;
            }

            if (chunk.length > keep) {
                int keepStart = chunk.length - keep;
                tail = Arrays.copyOfRange(chunk, keepStart, chunk.length);
            } else {
                tail = chunk;
            }
        }
    }

    private static byte[] combine(byte[] a, byte[] b, int bLen) {
        byte[] r = new byte[a.length + bLen];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, bLen);
        return r;
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        if (pattern.length == 0) return 0;
        outer:
        for (int i = 0; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
