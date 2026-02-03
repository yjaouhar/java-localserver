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
        setHeaders("Content-Length", Integer.toString(body.getBytes(StandardCharsets.UTF_8).length));
    }

    public ByteBuffer toByteBuffer() {
        StringBuilder response = new StringBuilder();
        response.append(version)
            .append(" ")
            .append(statusCode)
            .append(" ")
            .append(statusMessage)
            .append("\r\n");

        for( String key: headers.keySet()) {
            response.append(key + ": " + headers.get(key) + "\r\n");
        }
        response.append("\r\n");
        response.append(body);
        return ByteBuffer.wrap(response.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static HttpResponse badRequest() {
        HttpResponse res = new HttpResponse(400, "Bad Request");
        res.setHeaders("Content-Type", "text/plain; charset=UTF-8");
        res.setHeaders("Connection", "close");
        res.setBody("400 Bad Request");
        return res;
    }

    public static HttpResponse forbidden() {
        HttpResponse res = new HttpResponse(403, "Forbidden");
        res.setHeaders("Content-Type", "text/plain; charset=UTF-8");
        res.setHeaders("Connection", "close");
        res.setBody("403 Forbidden");
        return res;
    }

    public static HttpResponse notFound() {
        HttpResponse res = new HttpResponse(404, "Not Found");
        res.setHeaders("Content-Type", "text/plain; charset=UTF-8");
        res.setHeaders("Connection", "close");
        res.setBody("404 Not Found");
        return res;
    }

    public static HttpResponse methodNotAllowed() {
        HttpResponse res = new HttpResponse(405, "Method Not Allowed");
        res.setHeaders("Content-Type", "text/plain; charset=UTF-8");
        res.setHeaders("Connection", "close");
        res.setBody("405 Method Not Allowed");
        return res;
    }

    public static HttpResponse payloadTooLarge() {
        HttpResponse res = new HttpResponse(413, "Payload Too Large");
        res.setHeaders("Content-Type", "text/plain; charset=UTF-8");
        res.setHeaders("Connection", "close");
        res.setBody("413 Payload Too Large");
        return res;
    }

    public static HttpResponse internalServerError() {
        HttpResponse res = new HttpResponse(500, "Internal Server Error");
        res.setHeaders("Content-Type", "text/plain; charset=UTF-8");
        res.setHeaders("Connection", "close");
        res.setBody("500 Internal Server Error");
        return res;
    }

    

    
}