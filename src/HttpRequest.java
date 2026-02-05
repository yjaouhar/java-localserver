import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

public class HttpRequest {

    private StringBuilder headerBuffer = new StringBuilder();
    private StringBuilder bodyBuffer = new StringBuilder();
    private StringBuilder chunkBuffer = new StringBuilder();

    private boolean headersComplete = false;
    private boolean bodyComplete = false;

    private int contentLength = 0;
    private boolean isChunked = false;

    private int currentChunkSize = -1;
    private boolean readingChunkSize = true;

    private Map<String, String> headers = new HashMap<>();
    private String method, path, version;

    public void consume(ByteBuffer byteBuffer) {
        while (byteBuffer.hasRemaining() && !bodyComplete) {
            char c = (char) byteBuffer.get();
            if (!headersComplete) {
                headerBuffer.append(c);

                if (headerBuffer.indexOf("\r\n\r\n") != -1) {
                    headersComplete = true;
                    parseHeaders();

                    if ("chunked".equalsIgnoreCase(headers.get("Transfer-Encoding"))) {
                        isChunked = true;
                    } else if (headers.containsKey("Content-Length")) {
                        contentLength = Integer.parseInt(headers.get("Content-Length"));
                        if (contentLength == 0) {
                            bodyComplete = true;
                        }
                    }
                    int idx = headerBuffer.indexOf("\r\n\r\n") + 4;
                    if (idx < headerBuffer.length()) {
                        chunkBuffer.append(headerBuffer.substring(idx));
                    }
                }
            }

            /* ================= BODY ================= */
            else {
                chunkBuffer.append(c);

                if (isChunked) {
                    readChunkedBody();
                } else {
                    bodyBuffer.append(c);
                    if (bodyBuffer.length() >= contentLength) {
                        bodyComplete = true;
                    }
                }
            }
        }
    }
    private void readChunkedBody() {
        while (true) {
            if (readingChunkSize) {
                int rn = chunkBuffer.indexOf("\r\n");
                if (rn == -1) return;

                String sizeLine = chunkBuffer.substring(0, rn);
                currentChunkSize = Integer.parseInt(sizeLine.trim(), 16);
                chunkBuffer.delete(0, rn + 2);

                if (currentChunkSize == 0) {
                    bodyComplete = true;
                    return;
                }

                readingChunkSize = false;
            }

            if (!readingChunkSize) {
                if (chunkBuffer.length() < currentChunkSize + 2) return;

                bodyBuffer.append(chunkBuffer.substring(0, currentChunkSize));
                chunkBuffer.delete(0, currentChunkSize + 2);

                readingChunkSize = true;
                currentChunkSize = -1;
            }
        }
    }

    private void parseHeaders() {
        String[] lines = headerBuffer.toString().split("\r\n");
        String[] requestLine = lines[0].split(" ");

        method = requestLine[0];
        path = requestLine[1];
        version = requestLine[2];
        System.out.println("line 0  = " + lines[0]);
        for (int i = 1; i < lines.length; i++) {
            if(lines[i].isEmpty()) break;
            System.out.println("line " + i + " = " + lines[i]);
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
}
