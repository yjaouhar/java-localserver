package http;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class HttpResponse {

    private String version = "HTTP/1.1";
    private int statusCode;
    private String statusMessage;
    private Map<String, String> headers = new HashMap<>();
    private String body = "";
    
    // ✅ للملفات الكبيرة
    private Path bodyFile;
    private FileChannel bodyFileChannel;
    private long bodyFileSize;
    private long bodyFileSent = 0;
    
    // ✅ حالة الإرسال
    private boolean headersSent = false;
    // ✅ جديد: دعم البث التدريجي (chunked)
    private boolean chunked = false;

    // ✅ مخزن بيانات ديناميكي عند البث
    private java.io.ByteArrayOutputStream dynamicBody = new java.io.ByteArrayOutputStream();
    private boolean streamingFinished = false;

    public HttpResponse(int code, String message) {
        this.statusCode = code;
        this.statusMessage = message;
    }

    public void setHeaders(String key, String value) {
        headers.put(key, value);
    }

    public void setBody(String body) {
        this.body = body;
    }

    // ✅ Append bytes to the dynamic body (used for streaming CGI)
    public synchronized void appendBody(byte[] data, int len) {
        if (data == null || len <= 0) return;
        dynamicBody.write(data, 0, len);
    }

    // ✅ Mark streaming finished so the final chunk (if chunked) can be sent
    public synchronized void finishStreaming() {
        this.streamingFinished = true;
    }

    // ✅ Enable chunked transfer encoding
    public void enableChunked() {
        this.chunked = true;
        headers.put("Transfer-Encoding", "chunked");
    }
    
    // ✅ جديد: تحديد ملف كـ body
    public void setBodyFile(Path file) throws IOException {
        this.bodyFile = file;
        this.bodyFileSize = Files.size(file);
        this.bodyFileChannel = FileChannel.open(file, StandardOpenOption.READ);
        
        // تحديد Content-Length
        headers.put("Content-Length", String.valueOf(bodyFileSize));
    }

    // ✅ جديد: الحصول على ByteBuffer تدريجياً
    public ByteBuffer getNextChunk(int maxSize) throws IOException {
        // ✅ أولاً: إرسال الـ headers
        if (!headersSent) {
            ByteBuffer headerBuf = buildHeaders();
            headersSent = true;
            return headerBuf;
        }
        
        // ✅ ثانياً: إرسال الـ body
        if (bodyFile != null && bodyFileChannel != null) {
            // إرسال من ملف
            long remaining = bodyFileSize - bodyFileSent;
            if (remaining <= 0) {
                return null; // انتهى
            }
            
            int toRead = (int) Math.min(remaining, maxSize);
            ByteBuffer buf = ByteBuffer.allocate(toRead);
            
            int read = bodyFileChannel.read(buf);
            if (read > 0) {
                bodyFileSent += read;
                buf.flip();
                return buf;
            }
            
            return null;
            
        } else {
            // إرسال من String أو من مخزن ديناميكي
            // أولاً: إذا مفعل chunked
            if (chunked) {
                // إذا هناك بيانات في الـ dynamic buffer، نرسل chunk
                synchronized (this) {
                    byte[] data = dynamicBody.toByteArray();
                    if (data.length > 0) {
                        // نرسل بحد أقصى maxSize
                        int toSend = Math.min(data.length, maxSize);
                        byte[] part = Arrays.copyOfRange(data, 0, toSend);

                        // نُبقي الباقي
                        dynamicBody.reset();
                        if (toSend < data.length) {
                            dynamicBody.write(data, toSend, data.length - toSend);
                        }

                        String lenHex = Integer.toHexString(part.length);
                        byte[] header = (lenHex + "\r\n").getBytes(StandardCharsets.UTF_8);
                        byte[] footer = "\r\n".getBytes(StandardCharsets.UTF_8);

                        ByteBuffer buf = ByteBuffer.allocate(header.length + part.length + footer.length);
                        buf.put(header);
                        buf.put(part);
                        buf.put(footer);
                        buf.flip();
                        return buf;
                    } else {
                        // لا توجد بيانات الآن
                        if (streamingFinished) {
                            // نرسل آخر chunk صفر
                            ByteBuffer buf = ByteBuffer.wrap("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                            return buf;
                        }
                        return null;
                    }
                }
            } else {
                // إرسال من String
                if (body.isEmpty()) {
                    return null;
                }

                byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
                body = ""; // نفرغها باش ما نعاودش نرسلوها
                return ByteBuffer.wrap(bodyBytes);
            }
        }
    }
    
    // ✅ فحص إذا كان كل شيء تم إرساله
    public boolean isComplete() throws IOException {
        if (!headersSent) {
            return false;
        }
        
        if (bodyFile != null) {
            return bodyFileSent >= bodyFileSize;
        } else {
            return body.isEmpty();
        }
    }
    
    // ✅ تنظيف الموارد
    public void close() throws IOException {
        if (bodyFileChannel != null) {
            bodyFileChannel.close();
            bodyFileChannel = null;
        }
    }

    // ✅ بناء الـ headers
    private ByteBuffer buildHeaders() {
        StringBuilder headerBuilder = new StringBuilder();
        
        headerBuilder.append(version)
                .append(" ")
                .append(statusCode)
                .append(" ")
                .append(statusMessage)
                .append("\r\n");

        // إضافة Content-Length إذا لم يكن موجوداً ولم نستخدم chunked
        if (!headers.containsKey("Content-Length") && !headers.containsKey("Transfer-Encoding")) {
            if (bodyFile != null) {
                headers.put("Content-Length", String.valueOf(bodyFileSize));
            } else {
                byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
                headers.put("Content-Length", String.valueOf(bodyBytes.length));
            }
        }

        for (Map.Entry<String, String> h : headers.entrySet()) {
            headerBuilder.append(h.getKey())
                    .append(": ")
                    .append(h.getValue())
                    .append("\r\n");
        }

        headerBuilder.append("\r\n");

        return ByteBuffer.wrap(
            headerBuilder.toString().getBytes(StandardCharsets.UTF_8)
        );
    }

    // ✅ الطريقة القديمة (للتوافق)
    public ByteBuffer toByteBuffer() {
        try {
            // إذا كان body file، نستعمل الطريقة التدريجية
            if (bodyFile != null) {
                // نرجع الـ headers فقط
                // الباقي يتم عبر getNextChunk()
                return buildHeaders();
            }
            
            // الطريقة القديمة للـ body الصغير
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

            if (!headers.containsKey("Content-Length")) {
                headers.put("Content-Length", String.valueOf(bodyBytes.length));
            }

            ByteBuffer headerBuf = buildHeaders();
            
            ByteBuffer buffer = ByteBuffer.allocate(
                headerBuf.remaining() + bodyBytes.length
            );

            buffer.put(headerBuf);
            buffer.put(bodyBytes);
            buffer.flip();
            
            headersSent = true;
            body = ""; // نفرغها
            
            return buffer;
            
        } catch (Exception e) {
            e.printStackTrace();
            return ByteBuffer.allocate(0);
        }
    }

    // ✅ Static methods للأخطاء
    public static HttpResponse ErrorResponse(int code, String message, String body, String errorPage) {
        HttpResponse res = new HttpResponse(code, message);
        res.setHeaders("Content-Type", "text/html; charset=UTF-8");
        res.setHeaders("Connection", "close");
        
        if (errorPage != null && !errorPage.isEmpty()) {
            Path errorFile = Paths.get(errorPage);
            if (Files.exists(errorFile)) {
                try {
                    String errorContent = new String(
                        Files.readAllBytes(errorFile), 
                        StandardCharsets.UTF_8
                    );
                    res.setBody(errorContent);
                    return res;
                } catch (Exception ignored) {}
            }
        }
        
        // Default error page
        res.setBody(
            "<html><body><h1>" + code + " " + message + "</h1>" +
            (body != null && !body.isEmpty() ? "<p>" + body + "</p>" : "") +
            "</body></html>"
        );
        return res;
    }

    public static HttpResponse successResponse(int code, String message, String body) {
        HttpResponse res = new HttpResponse(code, message);
        res.setHeaders("Content-Type", "text/html; charset=UTF-8");
        res.setHeaders("Connection", "close");
        res.setBody(body);
        return res;
    }

    // Getters
    public int getStatusCode() { return statusCode; }
    public String getStatusMessage() { return statusMessage; }
    public Map<String, String> getHeaders() { return headers; }
    public String getBody() { return body; }
}