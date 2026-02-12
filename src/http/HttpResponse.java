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
    private byte[] body = new byte[0];

    private Path bodyFile;
    private FileChannel bodyFileChannel;
    public long bodyFileSize;
    private long bodyFileSent = 0;

    private boolean headersSent = false;

    private boolean chunked = false;

    private java.io.ByteArrayOutputStream dynamicBody = new java.io.ByteArrayOutputStream();
    private boolean streamingFinished = false;

    public HttpResponse(int code, String message) {
        this.statusCode = code;
        this.statusMessage = message;
    }

    public void setHeaders(String key, String value) {
        headers.put(key, value);
    }

    public void setBody(byte[] body) {
        this.body = body;
    }

    // public synchronized void setBodyBytes(byte[] data) {
    //     if (data == null) {
    //         data = new byte[0];
    //     }
    //     // نفرّغ أي body قديم
    //     this.body = "";
    //     this.bodyFile = null;
    //     this.bodyFileChannel = null;
    //     this.bodyFileSent = 0;
    //     // نفرّغ streaming buffer ونعمروه بالـ bytes
    //     this.dynamicBody.reset();
    //     this.dynamicBody.write(data, 0, data.length);
    //     // headers
    //     headers.remove("Transfer-Encoding");
    //     headers.put("Content-Length", String.valueOf(data.length));
    //     this.chunked = false;
    //     this.streamingFinished = true;
    // }
    public synchronized void appendBody(byte[] data, int len) {
        if (data == null || len <= 0) {
            return;
        }
        dynamicBody.write(data, 0, len);
    }

    public synchronized void finishStreaming() {
        this.streamingFinished = true;
    }

    public void enableChunked() {
        this.chunked = true;
        headers.put("Transfer-Encoding", "chunked");
    }

    public void setBodyFile(Path file) throws IOException {
        this.bodyFile = file;
        this.bodyFileSize = Files.size(file);
        this.bodyFileChannel = FileChannel.open(file, StandardOpenOption.READ);
        headers.put("Content-Length", String.valueOf(bodyFileSize));
    }

    public ByteBuffer getNextChunk(int maxSize) {
        if (!headersSent) {
            ByteBuffer headerBuf = buildHeaders();
            headersSent = true;
            return headerBuf;
        }
        System.out.println("_____________ " + bodyFile != null && bodyFileChannel != null);
        if (bodyFile != null && bodyFileChannel != null) {
            long remaining = bodyFileSize - bodyFileSent;
            if (remaining <= 0) {
                return null;
            }

            int toRead = (int) Math.min(remaining, maxSize);
            ByteBuffer buf = ByteBuffer.allocate(toRead);
            try {

                int read = bodyFileChannel.read(buf);
                if (read > 0) {
                    bodyFileSent += read;
                    buf.flip();
                    return buf;
                }

                return null;
            } catch (Exception e) {
                System.out.println("Error reading body file: " + e.getMessage());
                return null;
            }

        } else {
            if (chunked) {
                synchronized (this) {
                    byte[] data = dynamicBody.toByteArray();
                    if (data.length > 0) {
                        int toSend = Math.min(data.length, maxSize);
                        byte[] part = Arrays.copyOfRange(data, 0, toSend);
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
                        if (streamingFinished) {
                            ByteBuffer buf = ByteBuffer.wrap("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                            return buf;
                        }
                        return null;
                    }
                }
            } else {
                if (body.length == 0) {
                    return null;
                }

                byte[] bodyBytes = body;
                body = new byte[0];
                return ByteBuffer.wrap(bodyBytes);
            }
        }
    }

    // public boolean isComplete() throws IOException {
    //     if (!headersSent) {
    //         return false;
    //     }
    //     if (bodyFile != null) {
    //         return bodyFileSent >= bodyFileSize;
    //     } 
    //     else {
    //         return body.isEmpty();
    //     }
    // }
    public void close() throws IOException {
        if (bodyFileChannel != null) {
            bodyFileChannel.close();
            bodyFileChannel = null;
        }
    }

    private ByteBuffer buildHeaders() {
        StringBuilder headerBuilder = new StringBuilder();

        headerBuilder.append(version)
                .append(" ")
                .append(statusCode)
                .append(" ")
                .append(statusMessage)
                .append("\r\n");

        if (!headers.containsKey("Content-Length") && !headers.containsKey("Transfer-Encoding")) {
            if (bodyFile != null) {
                headers.put("Content-Length", String.valueOf(bodyFileSize));
            } else {
                byte[] bodyBytes = body;
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

    public ByteBuffer toByteBuffer() {
        try {
            if (bodyFile != null) {
                return buildHeaders();
            }

            byte[] bodyBytes = body;

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
            body = new byte[0];

            return buffer;

        } catch (Exception e) {
            e.printStackTrace();
            return ByteBuffer.allocate(0);
        }
    }

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
                    res.setBody(errorContent.getBytes());
                    return res;
                } catch (Exception ignored) {
                }
            }
        }

        res.setBody(
                ("<html><body><h1>" + code + " " + message + "</h1>"
                        + (body != null && !body.isEmpty() ? "<p>" + body + "</p>" : "")
                        + "</body></html>").getBytes()
        );
        return res;
    }

    public static HttpResponse successResponse(int code, String message, String body) {
        HttpResponse res = new HttpResponse(code, message);
        res.setHeaders("Content-Type", "text/html; charset=UTF-8");
        res.setHeaders("Connection", "close");
        res.setBody(body.getBytes());
        return res;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getBody() {
        return new String(body, StandardCharsets.UTF_8);
    }

    public Path getBodyFile() {
        return bodyFile;
    }
}
