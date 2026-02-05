package http;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class HttpResponse {

    private String version = "HTTP/1.1";
    private int statusCode;
    private String statusMessage;
    private Map<String, String> headers = new HashMap<>();
    private String body = "";

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

    public ByteBuffer toByteBuffer() {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

        // تأكيد Content-Length هنا (المكان الصحيح)
        headers.put("Content-Length", String.valueOf(bodyBytes.length));

        StringBuilder headerBuilder = new StringBuilder();
        headerBuilder.append(version)
                .append(" ")
                .append(statusCode)
                .append(" ")
                .append(statusMessage)
                .append("\r\n");

        for (Map.Entry<String, String> h : headers.entrySet()) {
            headerBuilder.append(h.getKey())
                    .append(": ")
                    .append(h.getValue())
                    .append("\r\n");
        }

        headerBuilder.append("\r\n");

        byte[] headerBytes
                = headerBuilder.toString().getBytes(StandardCharsets.UTF_8);

        ByteBuffer buffer
                = ByteBuffer.allocate(headerBytes.length + bodyBytes.length);

        buffer.put(headerBytes);
        buffer.put(bodyBytes);
        buffer.flip();
        return buffer;
    }

    public static HttpResponse ErrorResponse(int code, String message, String body) {
        HttpResponse res = new HttpResponse(code, message);
        res.setHeaders("Content-Type", "text/plain; charset=UTF-8");
        res.setHeaders("Connection", "close");
        res.setBody(body);
        return res;
    }
    public static HttpResponse successResponse(int code, String message, String body) {
        HttpResponse res = new HttpResponse(code, message);
        res.setHeaders("Content-Type", "text/html; charset=UTF-8");
        res.setHeaders("Connection", "close");
        res.setBody(body);
        return res;
    }
}
