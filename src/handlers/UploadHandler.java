package handlers;

import http.HttpRequest;
import http.HttpResponse;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import utils.json.AppConfig;

public class UploadHandler {

    public static HttpResponse handleUpload(AppConfig.RouteConfig rout, HttpRequest request, Map<Integer, String> errorPages) {
        if (request == null) {
            return HttpResponse.ErrorResponse(400, "Bad Request", "Request is null", errorPages.get(400));
        }
        System.out.println("Handling upload for path: " + request.getPath() + " method: " + request.getMethod());
        if (request.getMethod() == null || !request.getMethod().equals("POST")) {
            return HttpResponse.ErrorResponse(405, "Method Not Allowed", "Only POST method is allowed", errorPages.get(405));
        }

        String contentType = HttpRequest.getHeaderIgnoreCase(request.getHeaders(), "Content-Type");
        if (contentType == null || !startsWithIgnoreCase(contentType, "multipart/form-data")) {
            return HttpResponse.ErrorResponse(415, "Unsupported Media Type",
                    "Content-Type must be multipart/form-data", errorPages.get(415));
        }

        String boundary = extractBoundary(contentType);
        if (boundary == null || boundary.isEmpty()) {
            return HttpResponse.ErrorResponse(400, "Bad Request",
                    "Boundary parameter is missing in Content-Type", errorPages.get(400));
        }

        Path bodyFile = request.getBodyFile();
        if (bodyFile == null) {
            return HttpResponse.ErrorResponse(400, "Bad Request",
                    "Missing request body file", errorPages.get(400));
        }

        Path uploadDir = resolveUploadDir(rout);
        try {
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }
        } catch (IOException e) {
            return HttpResponse.ErrorResponse(500, "Internal Server Error",
                    "Failed to create upload directory: " + e.getMessage(), errorPages.get(500));
        }

        try (InputStream in = new BufferedInputStream(Files.newInputStream(bodyFile))) {
            boolean uploaded = parseMultipartAndSaveFirstFile(in, boundary, uploadDir);
            if (!uploaded) {
                return HttpResponse.ErrorResponse(400, "Bad Request",
                        "No file part found in the request", errorPages.get(400));
            }
            return HttpResponse.successResponse(201, "Created", "File uploaded successfully");
        } catch (IllegalArgumentException e) {
            return HttpResponse.ErrorResponse(400, "Bad Request", e.getMessage(), errorPages.get(400));
        } catch (IOException e) {
            return HttpResponse.ErrorResponse(500, "Internal Server Error",
                    "Failed to save uploaded file: " + e.getMessage(), errorPages.get(500));
        }
    }

    private static Path resolveUploadDir(AppConfig.RouteConfig rout) {
        if (rout != null) {
            if (rout.uploadDir != null && !rout.uploadDir.trim().isEmpty()) {
                return Paths.get(rout.uploadDir);
            }
            if (rout.root != null && !rout.root.trim().isEmpty()) {
                return Paths.get(rout.root);
            }
        }
        return Paths.get("uploads");
    }

    private static boolean parseMultipartAndSaveFirstFile(InputStream in, String boundary, Path uploadDir) throws IOException {
        byte[] boundaryLine = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] boundaryLineEnd = ("--" + boundary + "--").getBytes(StandardCharsets.ISO_8859_1);

        // 1) Read until first boundary line
        String line;
        do {
            line = readLineIso(in);
            if (line == null) return false;
        } while (!lineEqualsBytes(line, boundaryLine) && !lineEqualsBytes(line, boundaryLineEnd));

        if (lineEqualsBytes(line, boundaryLineEnd)) {
            return false;
        }

        while (true) {
            // 2) Read part headers until empty line
            List<String> partHeaders = new ArrayList<>();
            while (true) {
                String h = readLineIso(in);
                if (h == null) throw new IllegalArgumentException("Unexpected end of multipart body");
                if (h.isEmpty()) break;
                partHeaders.add(h);
            }

            String fileName = extractFileNameFromPartHeaders(partHeaders);
            boolean isFilePart = (fileName != null && !fileName.isEmpty());

            OutputStream fileOut = null;
            Path outPath = null;

            if (isFilePart) {
                fileName = sanitizeFileName(fileName);
                outPath = uploadDir.resolve(fileName);
                fileOut = new BufferedOutputStream(Files.newOutputStream(outPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
            }

            // 3) Read part data until boundary
            boolean foundBoundary = copyUntilBoundary(in, boundary, fileOut);

            if (fileOut != null) {
                fileOut.flush();
                fileOut.close();
                return true; // save first file only (simple)
            }

            if (!foundBoundary) {
                return false;
            }

            // After boundary, next line already handled inside copyUntilBoundary (it consumes CRLF around boundary)
            // We need to check if it's final boundary: copyUntilBoundary sets final flag via return.
            // Here we just continue to next part.
        }
    }

    private static boolean copyUntilBoundary(InputStream in, String boundary, OutputStream out) throws IOException {
        // We'll scan using a small sliding window for "\r\n--boundary"
        byte[] marker = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);

        ByteArrayOutputStream window = new ByteArrayOutputStream(marker.length + 16);

        int b;
        boolean seenFirstByte = false;

        while ((b = in.read()) != -1) {
            if (!seenFirstByte) {
                seenFirstByte = true;
            }

            window.write(b);

            byte[] w = window.toByteArray();
            int idx = indexOf(w, marker);
            if (idx != -1) {
                // write data before marker to out
                int dataLen = idx;
                if (out != null && dataLen > 0) {
                    out.write(w, 0, dataLen);
                }

                // We have consumed "\r\n--boundary" already in window.
                // Now we must read the rest of boundary line: either "--" (final) or "\r\n"
                // But note: marker begins with \r\n, so we should also discard those 2 bytes from content.
                // Since marker includes \r\n, data before idx is content WITHOUT that \r\n (good).

                // Now read next two bytes to detect final boundary
                int c1 = in.read();
                int c2 = in.read();
                if (c1 == -1 || c2 == -1) return true;

                if (c1 == '-' && c2 == '-') {
                    // final boundary. consume trailing CRLF if present
                    readLineIso(in); // consumes rest of line (usually empty)
                    return true;
                } else {
                    // likely \r\n, but could be something else; consume remainder of line
                    // We already read two bytes; put them into a line buffer and finish line
                    ByteArrayOutputStream rest = new ByteArrayOutputStream();
                    rest.write(c1);
                    rest.write(c2);
                    // consume until end of line
                    while (true) {
                        int x = in.read();
                        if (x == -1) break;
                        rest.write(x);
                        int n = rest.size();
                        byte[] rb = rest.toByteArray();
                        if (n >= 2 && rb[n - 2] == '\r' && rb[n - 1] == '\n') break;
                    }
                    return true;
                }
            }

            // If window larger than marker length + some slack, flush old bytes
            if (window.size() > marker.length + 32) {
                byte[] wb = window.toByteArray();
                int flushLen = wb.length - (marker.length + 16);
                if (flushLen > 0) {
                    if (out != null) out.write(wb, 0, flushLen);

                    window.reset();
                    window.write(wb, flushLen, wb.length - flushLen);
                }
            }
        }

        // EOF: write remaining
        if (out != null) {
            byte[] wb = window.toByteArray();
            out.write(wb);
        }
        return false;
    }

    private static String extractBoundary(String contentType) {
        List<String> params = manualSplit(contentType, ";");
        for (String p : params) {
            String t = p.trim();
            if (startsWithIgnoreCase(t, "boundary=")) {
                String b = t.substring(9).trim();
                if (b.startsWith("\"") && b.endsWith("\"") && b.length() >= 2) {
                    b = b.substring(1, b.length() - 1);
                }
                return b;
            }
        }
        return null;
    }

    private static String extractFileNameFromPartHeaders(List<String> partHeaders) {
        for (String h : partHeaders) {
            int idx = h.indexOf(':');
            if (idx == -1) continue;
            String key = h.substring(0, idx).trim();
            String val = h.substring(idx + 1).trim();

            if (key.equalsIgnoreCase("Content-Disposition")) {
                // example: form-data; name="file"; filename="a.txt"
                List<String> parts = manualSplit(val, ";");
                for (String p : parts) {
                    String t = p.trim();
                    if (startsWithIgnoreCase(t, "filename=")) {
                        String fn = t.substring(9).trim();
                        if (fn.startsWith("\"") && fn.endsWith("\"") && fn.length() >= 2) {
                            fn = fn.substring(1, fn.length() - 1);
                        }
                        return fn;
                    }
                }
            }
        }
        return null;
    }

    private static String sanitizeFileName(String fileName) {
        if (fileName == null) return "upload.bin";
        String base = Paths.get(fileName).getFileName().toString();
        if (base.isEmpty()) return "upload.bin";
        return base;
    }

    private static String readLineIso(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int b;
        boolean gotAny = false;

        while ((b = in.read()) != -1) {
            gotAny = true;
            if (b == '\n') break;
            if (b != '\r') bos.write(b);
        }

        if (!gotAny && bos.size() == 0) return null;
        return bos.toString(StandardCharsets.ISO_8859_1);
    }

    private static boolean lineEqualsBytes(String line, byte[] bytes) {
        byte[] l = line.getBytes(StandardCharsets.ISO_8859_1);
        return equalsBytes(l, bytes);
    }

    private static boolean equalsBytes(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) return false;
        }
        return true;
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        if (needle.length == 0) return 0;
        for (int i = 0; i + needle.length <= hay.length; i++) {
            boolean ok = true;
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    ok = false;
                    break;
                }
            }
            if (ok) return i;
        }
        return -1;
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

    private static boolean startsWithIgnoreCase(String s, String prefix) {
        if (s == null || prefix == null) return false;
        if (s.length() < prefix.length()) return false;
        for (int i = 0; i < prefix.length(); i++) {
            char a = Character.toLowerCase(s.charAt(i));
            char b = Character.toLowerCase(prefix.charAt(i));
            if (a != b) return false;
        }
        return true;
    }
}
