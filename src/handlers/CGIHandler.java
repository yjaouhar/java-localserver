package handlers;

import http.HttpRequest;
import http.HttpResponse;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;
import utils.json.AppConfig.RouteConfig;

public class CGIHandler {

    private static final int TIMEOUT_SECONDS = 5;

    public static HttpResponse handleCGI(
            RouteConfig route,
            HttpRequest request,
            Map<Integer, String> errorPages
    ) {
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {

            String reqPath = stripQuery(request.getPath());
            Path scriptPath = Paths.get(route.root, reqPath).normalize();
            if (!scriptPath.startsWith(Paths.get(route.root))
                    || !Files.exists(scriptPath)
                    || Files.isDirectory(scriptPath)) {
                return HttpResponse.ErrorResponse(
                        404, "Not Found", "CGI script not found", errorPages.get(404)
                );
            }

            String interpreter = (route.cgi != null && route.cgi.interpreter != null)
                    ? route.cgi.interpreter
                    : "python3";

            ProcessBuilder pb = new ProcessBuilder(interpreter, scriptPath.toString());
            // pb.directory(scriptPath.getParent().toFile());

            // ===== ENV =====
            Map<String, String> env = pb.environment();
            env.put("REQUEST_METHOD", request.getMethod());
            env.put("SCRIPT_NAME", reqPath);
            String pathInfo = extractPathInfo(request.getPath(), reqPath);
            env.put("PATH_INFO", pathInfo == null ? "" : pathInfo);
            String qs = extractQueryString(request.getPath());
            if (qs != null) {
                env.put("QUERY_STRING", qs);
            }
            Path bodyFile = request.getBodyFile();
            long contentLen = (bodyFile != null && Files.exists(bodyFile))
                    ? Files.size(bodyFile)
                    : 0;
            env.put("CONTENT_LENGTH", String.valueOf(contentLen));

            String ct = HttpRequest.getHeaderIgnoreCase(
                    request.getHeaders(), "Content-Type"
            );
            if (ct != null) {
                env.put("CONTENT_TYPE", ct);
            }

            // ==========================
            // ===== Start process =====
            Process process = pb.start();

            // ===== stdin (raw body) =====
            try (OutputStream os = process.getOutputStream()) {
                if (bodyFile != null && contentLen > 0) {
                    try (InputStream in = Files.newInputStream(bodyFile)) {
                        pipe(in, os);
                    }
                }
            }

            // ===== async stdout / stderr =====
            Future<String> stdoutFuture
                    = executor.submit(() -> readAll(process.getInputStream()));

            Future<String> stderrFuture
                    = executor.submit(() -> readAll(process.getErrorStream()));

            // ===== timeout =====
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return HttpResponse.ErrorResponse(
                        504, "Gateway Timeout", "CGI timeout", errorPages.get(504)
                );
            }

            String stdout = stdoutFuture.get(1, TimeUnit.SECONDS);
            String stderr = stderrFuture.get(1, TimeUnit.SECONDS);

            if (process.exitValue() != 0 || !stderr.isEmpty()) {
                System.out.println("CGI stderr: " + stderr);
                return HttpResponse.ErrorResponse(
                        500,
                        "Internal Server Error",
                        "CGI error: " + safeOneLine(stderr),
                        errorPages.get(500)
                );
            }

            return parseCGIResponse(stdout);

        } catch (TimeoutException e) {
            return HttpResponse.ErrorResponse(
                    504, "Gateway Timeout", "CGI IO timeout", errorPages.get(504)
            );

        } catch (Exception e) {
            System.out.println("CGIHandler error: " + e.getMessage());
            return HttpResponse.ErrorResponse(
                    500,
                    "Internal Server Error",
                    "CGI failed: " + e.getMessage(),
                    errorPages.get(500)
            );
        } finally {
            executor.shutdownNow();
        }
    }

    // ================= helpers =================
    private static HttpResponse parseCGIResponse(String out) {
        int sep = out.indexOf("\r\n\r\n");
        if (sep == -1) {
            return HttpResponse.successResponse(200, "OK", out);
        }

        String headerPart = out.substring(0, sep);
        String body = out.substring(sep + 4);

        HttpResponse res = new HttpResponse(200, "OK");

        for (String line : headerPart.split("\r\n")) {
            int idx = line.indexOf(':');
            if (idx != -1) {
                res.setHeaders(
                        line.substring(0, idx).trim(),
                        line.substring(idx + 1).trim()
                );
            }
        }

        res.setBody(body);
        return res;
    }

    private static void pipe(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        out.flush();
    }

    private static String readAll(InputStream is) throws IOException {
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String stripQuery(String path) {
        int q = path.indexOf('?');
        return q == -1 ? path : path.substring(0, q);
    }

    private static String extractQueryString(String path) {
        int q = path.indexOf('?');
        return q == -1 ? null : path.substring(q + 1);
    }

    private static String extractPathInfo(String fullPath, String scriptName) {
        String clean = stripQuery(fullPath);
        if (clean.startsWith(scriptName)) {
            return clean.substring(scriptName.length());
        }
        return "";
    }

    private static String safeOneLine(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\r", " ").replace("\n", " ").trim();
    }
}
