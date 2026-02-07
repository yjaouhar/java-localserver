package handlers;

import http.HttpRequest;
import http.HttpResponse;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import utils.json.AppConfig.RouteConfig;

public class CGIHandler {

    private static final int TIMEOUT_SECONDS = 5;

    public static HttpResponse handleCGI(RouteConfig rout, HttpRequest request, Map<Integer, String> errorPages) {
        try {
            // مثال: test.py
            // Path root = Paths.get(rout.root).normalize();     // www/cgi-bin
            String path = request.getPath();
            Path scriptPath = Paths.get(rout.root, path).normalize();

            System.err.println("cript path"+scriptPath.startsWith(rout.root) + "   "+Files.exists(scriptPath) + "  "+Files.isDirectory(scriptPath));
            if (!scriptPath.startsWith(rout.root) || !Files.exists(scriptPath) || Files.isDirectory(scriptPath)) {
                return HttpResponse.ErrorResponse(404, "Not Found", "CGI script not found", errorPages.get(404));
            }

            String interpreter = (rout.cgi != null && rout.cgi.interpreter != null)
                    ? rout.cgi.interpreter
                    : "python3";

            ProcessBuilder pb = new ProcessBuilder(interpreter, scriptPath.toString());

            // ENV
            pb.environment().put("REQUEST_METHOD", request.getMethod() == null ? "" : request.getMethod());
            pb.environment().put("PATH_INFO", scriptPath.toString());

            long contentLen = 0;
            Path bodyFile = request.getBodyFile();
            if (bodyFile != null) {
                try {
                    contentLen = Files.size(bodyFile);
                } catch (IOException ignored) {}
            }
            pb.environment().put("CONTENT_LENGTH", String.valueOf(contentLen));

            String ct = HttpRequest.getHeaderIgnoreCase(request.getHeaders(), "Content-Type");
            if (ct != null) {
                pb.environment().put("CONTENT_TYPE", ct);
            }

            // (اختياري) QUERY_STRING إذا كان عندك ?a=b
            String qs = extractQueryString(request.getPath());
            if (qs != null) {
                pb.environment().put("QUERY_STRING", qs);
            }

            Process process = pb.start();

            // Write stdin from body file (streaming)
            try (OutputStream os = process.getOutputStream()) {
                if (bodyFile != null && contentLen > 0) {
                    try (InputStream in = new BufferedInputStream(Files.newInputStream(bodyFile))) {
                        pipe(in, os);
                    }
                }
            } catch (IOException e) {
                if (!isIgnorableStdinError(e)) {
                    throw e;
                }
            }

            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return HttpResponse.ErrorResponse(504, "Gateway Timeout", "CGI script timed out", errorPages.get(504));
            }

            int exitCode = process.exitValue();

            String stdout = readFully(process.getInputStream());
            String stderr = readFully(process.getErrorStream());

            if (exitCode != 0) {
                return HttpResponse.ErrorResponse(500, "Internal Server Error",
                        "CGI script error (exit=" + exitCode + "): " + safeOneLine(stderr),
                        errorPages.get(500));
            }
            if (stderr != null && !stderr.isEmpty()) {
                return HttpResponse.ErrorResponse(500, "Internal Server Error",
                        "CGI script error: " + safeOneLine(stderr),
                        errorPages.get(500));
            }

            // stdout غالبًا يكون HTML/JSON.. رجّعو كـ body
            return HttpResponse.successResponse(200, "OK", stdout);

        } catch (Exception e) {
            return HttpResponse.ErrorResponse(500, "Internal Server Error",
                    "CGI execution failed: " + e.getMessage(), errorPages.get(500));
        }
    }

    // يحوّل "/cgi-bin/test.py" مع base "/cgi-bin" -> "test.py"
    private static String toRelativePath(String reqPath, String baseRoutePath) {
        if (reqPath == null) return "";
        String p = stripQuery(reqPath);

        if (baseRoutePath == null || baseRoutePath.isEmpty() || baseRoutePath.equals("/")) {
            if (p.startsWith("/")) p = p.substring(1);
            return p;
        }

        if (p.equals(baseRoutePath)) {
            return "";
        }
        String prefix = baseRoutePath.endsWith("/") ? baseRoutePath : baseRoutePath + "/";
        if (p.startsWith(prefix)) {
            return p.substring(prefix.length());
        }

        // fallback
        if (p.startsWith("/")) p = p.substring(1);
        return p;
    }

    private static String stripQuery(String path) {
        int q = path.indexOf('?');
        return (q == -1) ? path : path.substring(0, q);
    }

    private static String extractQueryString(String path) {
        if (path == null) return null;
        int q = path.indexOf('?');
        if (q == -1) return null;
        return path.substring(q + 1);
    }

    private static void pipe(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        out.flush();
    }

    private static String readFully(InputStream is) throws IOException {
        // بدون threads: نقرأ بعد انتهاء process غالبًا كيكون safe
        byte[] data = is.readAllBytes();
        return new String(data, StandardCharsets.UTF_8);
    }

    private static boolean isIgnorableStdinError(IOException e) {
        String m = e.getMessage();
        if (m == null) return false;
        m = m.toLowerCase();
        return m.contains("broken pipe") || m.contains("stream closed") || m.contains("epipe");
    }

    private static String safeOneLine(String s) {
        if (s == null) return "";
        s = s.replace("\r", " ").replace("\n", " ").trim();
        if (s.length() > 300) s = s.substring(0, 300) + "...";
        return s;
    }
}
