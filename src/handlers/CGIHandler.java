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
    
    /**
     * بدء CGI (non-blocking)
     */
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
        
        ProcessBuilder pb = new ProcessBuilder(interpreter, "-u", scriptPath.toString());
        
        Map<String, String> env = pb.environment();
        env.put("REQUEST_METHOD", request.getMethod());
        env.put("SCRIPT_NAME", reqPath);
        env.put("PATH_INFO", extractPathInfo(request.getPath(), reqPath));
        
        String qs = extractQueryString(request.getPath());
        if (qs != null) {
            env.put("QUERY_STRING", qs);
        }
        
        Path bodyFile = request.getBodyFile();
        long contentLen = (bodyFile != null && Files.exists(bodyFile))
                ? Files.size(bodyFile)
                : 0;
        env.put("CONTENT_LENGTH", String.valueOf(contentLen));
        
        String ct = request.getHeader("Content-Type");
        if (ct != null) {
            env.put("CONTENT_TYPE", ct);
        }
        
        env.put("PYTHONUNBUFFERED", "1");
        
        Process process = pb.start();
        

        try {
            OutputStream stdin = process.getOutputStream();
            if (bodyFile != null && contentLen > 0) {
                byte[] bodyBytes = Files.readAllBytes(bodyFile);
                stdin.write(bodyBytes);
            }
            stdin.close();
        } catch (IOException e) {
            System.err.println("[CGI] Error writing stdin: " + e.getMessage());
        }
        
        CGIStreamingContext ctx = new CGIStreamingContext(
            process, request, System.currentTimeMillis()
        );
        pendingCGI.put(clientKey, ctx);
        
        clientKey.interestOps(0);
        
        System.out.println("[CGI] Started: " + scriptPath);
    }
    
   
    public void checkPendingCGI(SelectionKey clientKey, Map<Integer, String> errorPages) {
        CGIContext rawCtx = pendingCGI.get(clientKey);
        
        if (rawCtx == null || !(rawCtx instanceof CGIStreamingContext)) {
            return;
        }
        
        CGIStreamingContext ctx = (CGIStreamingContext) rawCtx;
        Process process = ctx.getProcess();
        long elapsed = System.currentTimeMillis() - ctx.getStartTime();
        
        if (elapsed > cgiTimeout * 1000) {
            System.err.println("[CGI] Timeout");
            process.destroyForcibly();
            
            String output = ctx.getCollectedOutput();
            if (!output.isEmpty()) {
                sendSimpleResponse(clientKey, output);
            } else {
                sendErrorResponse(clientKey, 504, "Gateway Timeout", "CGI timeout", errorPages.get(504));
            }
            
            pendingCGI.remove(clientKey);
            return;
        }
        
        try {
            ctx.readAvailableOutput();
            if (!process.isAlive()) {
                System.out.println("[CGI] Process finished");
                ctx.readRemainingOutput();
                
                String output = ctx.getCollectedOutput();
                HttpResponse response = parseCGIResponse(output);
                sendResponse(clientKey, response);
                
                pendingCGI.remove(clientKey);
                System.out.println("[CGI] Sent " + output.length() + " bytes");
            }
            
        } catch (IOException e) {
            System.err.println("[CGI] Error: " + e.getMessage());
            sendErrorResponse(clientKey, 500, "Internal Server Error", "CGI Error", errorPages.get(500));
            pendingCGI.remove(clientKey);
        }
    }
    
    // ================= CGI Context =================
    
    static class CGIStreamingContext extends CGIContext {
        private final StringBuilder outputBuffer = new StringBuilder();
        private final InputStream stdout;
        private final InputStream stderr;
        
        public CGIStreamingContext(Process process, HttpRequest request, long startTime) {
            super(process, request, startTime);
            this.stdout = process.getInputStream();
            this.stderr = process.getErrorStream();
        }
        
        public void readAvailableOutput() throws IOException {
            if (stdout.available() > 0) {
                byte[] buffer = new byte[stdout.available()];
                int read = stdout.read(buffer);
                if (read > 0) {
                    outputBuffer.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    System.out.print(new String(buffer, 0, read, StandardCharsets.UTF_8));
                }
            }
            
            if (stderr.available() > 0) {
                byte[] buffer = new byte[stderr.available()];
                int read = stderr.read(buffer);
                if (read > 0) {
                    System.err.print("[CGI stderr] " + new String(buffer, 0, read, StandardCharsets.UTF_8));
                }
            }
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
    }
    
    // ================= Helper Methods =================
    
    private HttpResponse parseCGIResponse(String out) {
        int sep = out.indexOf("\n\n");
        if (sep == -1) sep = out.indexOf("\r\n\r\n");
        
        if (sep == -1) {
            HttpResponse res = new HttpResponse(200, "OK");
            res.setHeaders("Content-Type", "text/plain; charset=UTF-8");
            res.setBody(out);
            return res;
        }

        String headerPart = out.substring(0, sep);
        String body = out.substring(sep + (out.charAt(sep) == '\r' ? 4 : 2));

        HttpResponse res = new HttpResponse(200, "OK");

        for (String line : headerPart.split("[\r\n]+")) {
            if (line.isEmpty()) continue;
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
        System.out.println("((((((((((((((((((( " + response.getBody()+ "  ))))))))))))))))");
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
    
    private void sendSimpleResponse(SelectionKey key, String output) {
        HttpResponse res = new HttpResponse(200, "OK");
        res.setHeaders("Content-Type", "text/plain; charset=UTF-8");
        res.setBody(output);
        sendResponse(key, res);
    }
    
    private void sendErrorResponse(SelectionKey key, int code, String message, String detail, String errorPage) {
        HttpResponse response = HttpResponse.ErrorResponse(code, message, detail, errorPage);
        sendResponse(key, response);
    }
    
    public boolean hasPending(SelectionKey key) {
        return pendingCGI.containsKey(key);
    }
    
    public void cleanup(SelectionKey key) {
        CGIContext ctx = pendingCGI.remove(key);
        if (ctx != null) {
            Process p = ctx.getProcess();
            if (p.isAlive()) {
                p.destroyForcibly();
            }
        }
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