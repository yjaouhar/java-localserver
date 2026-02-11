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
    
    private final Map<SelectionKey, CGIContext> pendingCGI;
    private final int cgiTimeout;
    
    public CGIHandler(int timeoutSeconds) {
        this.pendingCGI = new HashMap<>();
        this.cgiTimeout = timeoutSeconds;
    }
    
    public void executeCGI(SelectionKey clientKey, RouteConfig route, 
                          HttpRequest request, Map<Integer, String> errorPages) throws IOException {
        
        String reqPath = stripQuery(request.getPath());
        Path scriptPath = Paths.get(route.root, reqPath).normalize();
        
        if (!scriptPath.startsWith(Paths.get(route.root))
                || !Files.exists(scriptPath)
                || Files.isDirectory(scriptPath)) {
            sendErrorResponse(clientKey, 404, "Not Found", "CGI script not found", errorPages.get(404));
            return;
        }
        
        String interpreter = (route.cgi != null && route.cgi.interpreter != null)
                ? route.cgi.interpreter
                : "python3";
        
        ProcessBuilder pb = new ProcessBuilder(interpreter, scriptPath.toString());
        
        // Setup environment
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
        
        // Start process
        Process process = pb.start();
        
        // ✅ اكتب الـ input
        try (OutputStream stdin = process.getOutputStream()) {
            if (bodyFile != null && contentLen > 0) {
                try (InputStream in = Files.newInputStream(bodyFile)) {
                    pipe(in, stdin);
                }
            }
        }
        
        // ✅ إنشاء CGI context مع streaming
        CGIStreamingContext ctx = new CGIStreamingContext(process, request, System.currentTimeMillis());
        pendingCGI.put(clientKey, ctx);
        
        clientKey.interestOps(0);
        
        System.out.println("[CGI] Started streaming CGI process");
    }
    
    public void checkPendingCGI(SelectionKey clientKey, Map<Integer, String> errorPages) {
        Object rawCtx = pendingCGI.get(clientKey);
        
        if (rawCtx == null) {
            return;
        }
        
        // ✅ استخدام الـ streaming context
        if (rawCtx instanceof CGIStreamingContext) {
            CGIStreamingContext ctx = (CGIStreamingContext) rawCtx;
            Process process = ctx.getProcess();
            
            long elapsed = System.currentTimeMillis() - ctx.getStartTime();
            
            // ✅ فحص timeout
            if (elapsed > cgiTimeout * 10000) {
                System.err.println("[CGI] Timeout - destroying process");
                process.destroyForcibly();
                
                // إرسال ما تم جمعه حتى الآن
                String output = ctx.getCollectedOutput();
                if (!output.isEmpty()) {
                    HttpResponse response = createStreamedResponse(output);
                    sendResponse(clientKey, response);
                } else {
                    sendErrorResponse(clientKey, 504, "Gateway Timeout", "CGI timeout", errorPages.get(504));
                }
                
                pendingCGI.remove(clientKey);
                return;
            }
            
            try {
                // ✅ قراءة الـ output المتاح
                boolean hasNewData = ctx.readAvailableOutput();
                
                // ✅ فحص إذا كان الـ process انتهى
                if (!process.isAlive()) {
                    System.out.println("[CGI] Process finished");
                    
                    // قراءة نهائية
                    ctx.readRemainingOutput();
                    
                    // إرسال الـ response
                    String output = ctx.getCollectedOutput();
                    HttpResponse response = parseCGIResponse(output);
                    sendResponse(clientKey, response);
                    
                    pendingCGI.remove(clientKey);
                    System.out.println("[CGI] Completed - sent " + output.length() + " bytes");
                    
                } else if (hasNewData) {
                    // ✅ لو عندنا output جديد، نتحقق إذا كان كافي لإرسال
                    String output = ctx.getCollectedOutput();
                    
                    // إذا عندنا headers كاملة + شوية data، نبدأ نرسلو
                    if (!ctx.isHeadersSent() && output.contains("\n\n")) {
                        System.out.println("[CGI] Starting to stream response...");
                        
                        HttpResponse response = parseCGIResponse(output);
                        sendResponse(clientKey, response);
                        ctx.markHeadersSent();
                        
                        // ملاحظة: هنا في implementation أبسط نرسلو كامل
                        // للـ true streaming، نحتاج chunked transfer encoding
                        pendingCGI.remove(clientKey);
                    }
                }
                
            } catch (IOException e) {
                System.err.println("[CGI] Error: " + e.getMessage());
                sendErrorResponse(clientKey, 500, "Internal Server Error", "CGI Error", errorPages.get(500));
                pendingCGI.remove(clientKey);
            }
        }
    }
    
    // ✅ Streaming context جديد
    static class CGIStreamingContext extends CGIContext {
        private final StringBuilder outputBuffer = new StringBuilder();
        private final InputStream stdout;
        private final InputStream stderr;
        private boolean headersSent = false;
        
        public CGIStreamingContext(Process process, HttpRequest request, long startTime) {
            super(process, request, startTime);
            this.stdout = process.getInputStream();
            this.stderr = process.getErrorStream();
        }
        
        public boolean readAvailableOutput() throws IOException {
            boolean hadData = false;
            
            // قراءة stdout
            if (stdout.available() > 0) {
                byte[] buffer = new byte[stdout.available()];
                int read = stdout.read(buffer);
                if (read > 0) {
                    outputBuffer.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    hadData = true;
                    System.out.print(new String(buffer, 0, read, StandardCharsets.UTF_8)); // ✅ طباعة للـ console
                }
            }
            
            // قراءة stderr (للـ debugging)
            if (stderr.available() > 0) {
                byte[] buffer = new byte[stderr.available()];
                int read = stderr.read(buffer);
                if (read > 0) {
                    System.err.print("[CGI stderr] " + new String(buffer, 0, read, StandardCharsets.UTF_8));
                }
            }
            
            return hadData;
        }
        
        public void readRemainingOutput() throws IOException {
            byte[] buffer = new byte[8192];
            int read;
            
            while ((read = stdout.read(buffer)) != -1) {
                outputBuffer.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
        }
        
        public String getCollectedOutput() {
            return outputBuffer.toString();
        }
        
        public boolean isHeadersSent() {
            return headersSent;
        }
        
        public void markHeadersSent() {
            headersSent = true;
        }
    }
    
    // ✅ إنشاء response من streaming
    private HttpResponse createStreamedResponse(String output) {
        if (output.contains("\n\n")) {
            return parseCGIResponse(output);
        } else {
            // لو ما فيهش headers، نرسلو كـ plain text
            HttpResponse res = new HttpResponse(200, "OK");
            res.setHeaders("Content-Type", "text/plain; charset=UTF-8");
            res.setBody(output);
            return res;
        }
    }
    
    private HttpResponse parseCGIResponse(String out) {
        int sep = out.indexOf("\n\n");
        if (sep == -1) {
            sep = out.indexOf("\r\n\r\n");
        }
        
        if (sep == -1) {
            return HttpResponse.successResponse(200, "OK", out);
        }

        String headerPart = out.substring(0, sep);
        String body = out.substring(sep + (out.charAt(sep) == '\r' ? 4 : 2));

        HttpResponse res = new HttpResponse(200, "OK");

        for (String line : headerPart.split("[\r\n]+")) {
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
    
    private void sendResponse(SelectionKey key, HttpResponse response) {
        Object attachment = key.attachment();
        if (attachment != null) {
            try {
                java.lang.reflect.Field field = attachment.getClass().getDeclaredField("writeBuf");
                field.setAccessible(true);
                field.set(attachment, response.toByteBuffer());
                
                key.interestOps(SelectionKey.OP_WRITE);
            } catch (Exception e) {
                System.err.println("Failed to send response: " + e.getMessage());
            }
        }
    }
    
    private void sendErrorResponse(SelectionKey key, int code, String message, String detail, String errorPage) {
        HttpResponse response = HttpResponse.ErrorResponse(code, message, detail, errorPage);
        sendResponse(key, response);
    }
    
    public boolean hasPending(SelectionKey key) {
        return pendingCGI.containsKey(key);
    }
    
    public void cleanup(SelectionKey key) {
        Object ctx = pendingCGI.remove(key);
        if (ctx instanceof CGIContext) {
            Process p = ((CGIContext) ctx).getProcess();
            if (p.isAlive()) {
                p.destroyForcibly();
            }
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