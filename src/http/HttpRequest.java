package http;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

public class HttpRequest {

    private StringBuilder buffer = new StringBuilder(), bodyBuffer = new StringBuilder();
    private boolean headersComplete, bodyComplete = false;
    private int contentLength = 0;
    private Map<String, String> headers = new HashMap<>();
    private String method, path, version, body;

    public void consume(ByteBuffer byteBuffer) {
        while (byteBuffer.hasRemaining()) {
            char c = (char) byteBuffer.get();
            if (!headersComplete) {
                buffer.append(c);
                if (buffer.indexOf("\r\n\r\n") != -1) {
                    headersComplete = true;
                    parseHeaders();
                    if (headers.containsKey("Content-Length")) {
                        contentLength = Integer.parseInt(headers.get("Content-Length"));
                    }
                    if (contentLength == 0) {
                        bodyComplete = true;
                        break;
                    }
                }
            } else {
                bodyBuffer.append(c);
                if (bodyBuffer.length() >= contentLength) {
                    bodyComplete = true;
                    break;
                }
            }
        }
    }

    private void parseHeaders() {
        String[] lines = buffer.toString().split("\r\n");
        String[] requestLine = lines[0].split(" ");
        method = requestLine[0];
        path = requestLine[1];
        version = requestLine[2];
        // System.out.println("line 0  = " + lines[0]);
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) {
                break;
            }
            // System.out.println("line " + i + " = " + lines[i]);
            String[] parts = lines[i].split(": ", 2);
            headers.put(parts[0], parts[1]);
        }
    }

    public boolean isRequestCompleted() {
        return headersComplete && bodyComplete;
    }

    public String getBody() {
        return bodyBuffer.toString();
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }
    public String getHeader(String key) {
        return headers.get(key);
    }
}
