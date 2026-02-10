package handlers;

import http.HttpRequest;
import http.HttpResponse;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import utils.json.AppConfig.RouteConfig;

public class CGIHandler {
    
    // Map bach nkhzen pending CGI requests
    private final Map<SelectionKey, CGIContext> pendingCGI;
    private final int cgiTimeout;
    
    public CGIHandler(int timeoutSeconds) {
        this.pendingCGI = new HashMap<>();
        this.cgiTimeout = timeoutSeconds;
    }
    
    /**
     * Bda CGI process (non-blocking)
     */
    public void executeCGI(SelectionKey clientKey, RouteConfig route, 
                          HttpRequest request, Map<Integer, String> errorPages) throws IOException {
        
        String reqPath = stripQuery(request.getPath());
        Path scriptPath = Paths.get(route.root, reqPath).normalize();
        
        if (!scriptPath.startsWith(Paths.get(route.root))
                || !Files.exists(scriptPath)
                || Files.isDirectory(scriptPath)) {
            // Send error immediately
            sendErrorResponse(clientKey, 404, "Not Found", "CGI script not found", errorPages.get(404));
            return;
        }
        
        String interpreter = (route.cgi != null && route.cgi.interpreter != null)
                ? route.cgi.interpreter
                : "python3";
        
        // Setup process
        ProcessBuilder pb = new ProcessBuilder(interpreter, scriptPath.toString());
        
        // Setup environment variables
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
        
        pb.redirectErrorStream(true);
        
        // Start process
        Process process = pb.start();
        
        // Kteb input ila kan (blocking walakin sghir)
        try (OutputStream stdin = process.getOutputStream()) {
            if (bodyFile != null && contentLen > 0) {
                try (InputStream in = Files.newInputStream(bodyFile)) {
                    pipe(in, stdin);
                }
            }
        }
        
        // Khzen context
        CGIContext ctx = new CGIContext(process, request, System.currentTimeMillis());
        pendingCGI.put(clientKey, ctx);
        
        // Clear interests temporarily (maghadich nktbou ba3d)
        clientKey.interestOps(0);
    }
    
    /**
     * Check kola pending processes (f main loop)
     */
    public void checkPendingCGI(SelectionKey clientKey, Map<Integer, String> errorPages) {
        CGIContext ctx = pendingCGI.get(clientKey);
        
        if (ctx == null) {
            return;
        }
        
        Process process = ctx.getProcess();
        
        // Check timeout
        long elapsed = System.currentTimeMillis() - ctx.getStartTime();
        if (elapsed > cgiTimeout * 1000) {
            process.destroyForcibly();
            sendErrorResponse(clientKey, 504, "Gateway Timeout", "CGI timeout", errorPages.get(504));
            pendingCGI.remove(clientKey);
            return;
        }
        
        // Check ila process kamel
        if (!process.isAlive()) {
            try {
                // 9ra output
                byte[] output = readProcessOutput(process);
                
                // Parse w sift response
                HttpResponse response = parseCGIResponse(new String(output, StandardCharsets.UTF_8));
                sendResponse(clientKey, response);
                
                pendingCGI.remove(clientKey);
                
            } catch (IOException e) {
                sendErrorResponse(clientKey, 500, "Internal Server Error", "CGI Error", errorPages.get(500));
                pendingCGI.remove(clientKey);
            }
        }
        // Ila ba9i kaykhdem, khallih (ghadi nchekkiwh f iteration jdida)
    }
    
    /**
     * 9ra kol output dyal process (ba3d ma ykmmel)
     */
    private byte[] readProcessOutput(Process process) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream in = process.getInputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
        return output.toByteArray();
    }
    
    /**
     * Parse CGI output
     */
    private HttpResponse parseCGIResponse(String out) {
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
    
    /**
     * Sift response l client
     */
    private void sendResponse(SelectionKey key, HttpResponse response) {
        Object attachment = key.attachment();
        if (attachment != null) {
            try {
                // Store response f ConnCtx
                java.lang.reflect.Field field = attachment.getClass().getDeclaredField("writeBuf");
                field.setAccessible(true);
                field.set(attachment, response.toByteBuffer());
                
                key.interestOps(SelectionKey.OP_WRITE);
            } catch (Exception e) {
                System.err.println("Failed to send response: " + e.getMessage());
            }
        }
    }
    
    /**
     * Sift error response
     */
    private void sendErrorResponse(SelectionKey key, int code, String message, String detail, String errorPage) {
        HttpResponse response = HttpResponse.ErrorResponse(code, message, detail, errorPage);
        sendResponse(key, response);
    }
    
    /**
     * Check ila kayn pending CGI l had key
     */
    public boolean hasPending(SelectionKey key) {
        return pendingCGI.containsKey(key);
    }
    
    /**
     * Cleanup resources
     */
    public void cleanup(SelectionKey key) {
        CGIContext ctx = pendingCGI.remove(key);
        if (ctx != null && ctx.getProcess().isAlive()) {
            ctx.getProcess().destroyForcibly();
        }
    }
    
    // ================= helpers =================
    private static void pipe(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        out.flush();
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
}