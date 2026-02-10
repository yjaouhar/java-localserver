package http;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import utils.json.AppConfig;

public class HttpRequest {

    private enum State {
        REQ_LINE_AND_HEADERS,
        BODY_FIXED,
        CHUNK_SIZE_LINE,
        CHUNK_DATA,
        CHUNK_DATA_CRLF,
        CHUNK_TRAILERS,
        DONE
    }

    private State state = State.REQ_LINE_AND_HEADERS;

    private final List<AppConfig.ServerConfig> serverCfgs = new ArrayList<>();
    private AppConfig.ServerConfig chosenServer = null;

    private final ByteArray headerBytes = new ByteArray(8192);
    private int headerEndIndex = -1;

    private final Map<String, String> headers = new HashMap<>();
    private String method, path, version;

    private boolean isChunked = false;
    private long contentLength = 0;

    private long maxBodyBytes;
    private long bodyWritten = 0;

    private Path bodyFile;
    private FileChannel bodyChannel; //  تغيير من OutputStream إلى FileChannel

    private final ByteArray lineBuf = new ByteArray(128);
    private int currentChunkSize = -1;
    private int remainingChunkBytes = 0;

    // ✅ للتحكم في معدل الكتابة
    private static final int MAX_WRITE_PER_CALL = 8192; // 8KB max per call

    public HttpRequest(List<AppConfig.ServerConfig> serverCfgs) {
        this.serverCfgs.addAll(serverCfgs);
    }

    public void consume(ByteBuffer buf) throws IOException {
        while (buf.hasRemaining() && state != State.DONE) {
            switch (state) {
                case REQ_LINE_AND_HEADERS:
                    readHeaders(buf);
                    break;
                case BODY_FIXED:
                    readFixedBody(buf);
                    break;
                case CHUNK_SIZE_LINE:
                    readChunkSizeLine(buf);
                    break;
                case CHUNK_DATA:
                    readChunkData(buf);
                    break;
                case CHUNK_DATA_CRLF:
                    readChunkDataCrlf(buf);
                    break;
                case CHUNK_TRAILERS:
                    readChunkTrailers(buf);
                    break;
                default:
                    state = State.DONE;
                    break;
            }
        }
    }

    private void readHeaders(ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            byte b = buf.get();
            headerBytes.add(b);

            if (headerBytes.size() >= 4) {
                int n = headerBytes.size();
                if (headerBytes.get(n - 4) == '\r' && headerBytes.get(n - 3) == '\n'
                        && headerBytes.get(n - 2) == '\r' && headerBytes.get(n - 1) == '\n') {
                    headerEndIndex = n - 4;
                    parseHeaders();
                    decideBodyMode();
                    return;
                }
            }

            if (headerBytes.size() > 64 * 1024) {
                throw new IllegalArgumentException("400 Header too large");
            }
        }
    }

    private void parseHeaders() {
        String headerText = new String(headerBytes.toArray(0, headerEndIndex),
                StandardCharsets.ISO_8859_1);
        String[] lines = headerText.split("\r\n");

        if (lines.length == 0) {
            throw new IllegalArgumentException("400 Bad request");
        }

        String[] reqLine = lines[0].split(" ");
        if (reqLine.length < 3) {
            throw new IllegalArgumentException("400 Bad request line");
        }

        method = reqLine[0].toUpperCase();
        path = reqLine[1];
        version = reqLine[2];

        if (!version.startsWith("HTTP/")) {
            throw new IllegalArgumentException("400 Bad HTTP version");
        }

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                break;
            }

            int idx = line.indexOf(':');
            if (idx == -1) {
                continue;
            }

            String k = line.substring(0, idx).trim();
            String v = line.substring(idx + 1).trim();
            headers.put(k, v);
        }

        String hostHeader = HttpRequest.getHeaderIgnoreCase(this.getHeaders(), "Host");

        this.chosenServer = HttpRequest.chooseServerByHost(
                this.serverCfgs, hostHeader
        );
        System.out.println("server chosen:" + this.chosenServer.name);
        this.maxBodyBytes = (this.chosenServer != null)
                ? this.chosenServer.clientMaxBodySize
                : 1048576L; // default 1 MB
    }

    private void decideBodyMode() throws IOException {

        String te = getHeaderIgnoreCase(headers, "Transfer-Encoding");
        if (te != null && te.equalsIgnoreCase("chunked")) {
            isChunked = true;
            openBodyFile();
            state = State.CHUNK_SIZE_LINE;
            return;
        }

        String cl = getHeaderIgnoreCase(headers, "Content-Length");
        if (cl != null) {
            try {
                contentLength = Long.parseLong(cl.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("400 Bad Content-Length");
            }

            if (contentLength < 0) {
                throw new IllegalArgumentException("400 Bad Content-Length");
            }
            if (contentLength == 0) {
                state = State.DONE;
                return;
            }
            if (contentLength > maxBodyBytes) {
                throw new IllegalArgumentException("413 Payload Too Large");
            }
            openBodyFile();
            state = State.BODY_FIXED;
            return;
        }

        state = State.DONE;
    }

    private void openBodyFile() throws IOException {
        if (bodyChannel != null) {
            return;
        }

        Path tempDir = Paths.get("myapp_tmp");
        if (!Files.exists(tempDir)) {
            Files.createDirectories(tempDir);
        }

        bodyFile = Files.createTempFile(tempDir, "reqbody_", ".txt");

        // ✅ استخدام FileChannel مباشرة (non-blocking)
        bodyChannel = FileChannel.open(
                bodyFile,
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    private void readFixedBody(ByteBuffer buf) throws IOException {
        long remaining = contentLength - bodyWritten;
        if (remaining <= 0) {
            finishBody();
            return;
        }

        // ✅ نكتب فقط كمية محدودة في كل مرة
        int toWrite = (int) Math.min(remaining, buf.remaining());
        toWrite = Math.min(toWrite, MAX_WRITE_PER_CALL);

        byte[] chunk = new byte[toWrite];
        buf.get(chunk);
        writeBodyBytes(chunk, 0, toWrite);

        if (bodyWritten >= contentLength) {
            finishBody();
        }
    }

    private void readChunkSizeLine(ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            byte b = buf.get();
            lineBuf.add(b);

            int n = lineBuf.size();
            if (n >= 2 && lineBuf.get(n - 2) == '\r' && lineBuf.get(n - 1) == '\n') {
                String line = new String(lineBuf.toArray(0, n - 2),
                        StandardCharsets.ISO_8859_1).trim();
                lineBuf.clear();

                int semiIdx = line.indexOf(';');
                if (semiIdx != -1) {
                    line = line.substring(0, semiIdx).trim();
                }

                try {
                    currentChunkSize = Integer.parseInt(line, 16);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("400 Bad chunk size");
                }

                if (currentChunkSize < 0) {
                    throw new IllegalArgumentException("400 Bad chunk size");
                }

                if (currentChunkSize == 0) {
                    state = State.CHUNK_TRAILERS;
                    return;
                }

                remainingChunkBytes = currentChunkSize;
                state = State.CHUNK_DATA;
                return;
            }

            if (lineBuf.size() > 64) {
                throw new IllegalArgumentException("400 Chunk size line too long");
            }
        }
    }

    private void readChunkData(ByteBuffer buf) throws IOException {
        int toWrite = Math.min(remainingChunkBytes, buf.remaining());
        toWrite = Math.min(toWrite, MAX_WRITE_PER_CALL); // ✅ حد الكتابة

        if (toWrite > 0) {
            byte[] chunk = new byte[toWrite];
            buf.get(chunk);
            writeBodyBytes(chunk, 0, toWrite);
            remainingChunkBytes -= toWrite;
        }

        if (remainingChunkBytes == 0) {
            state = State.CHUNK_DATA_CRLF;
        }
    }

    private void readChunkDataCrlf(ByteBuffer buf) {
        if (buf.remaining() < 2) {
            return;
        }

        byte r = buf.get();
        byte n = buf.get();

        if (r != '\r' || n != '\n') {
            throw new IllegalArgumentException("400 Bad chunk ending");
        }

        state = State.CHUNK_SIZE_LINE;
    }

    private void readChunkTrailers(ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            byte b = buf.get();
            lineBuf.add(b);

            int n = lineBuf.size();
            if (n >= 4
                    && lineBuf.get(n - 4) == '\r' && lineBuf.get(n - 3) == '\n'
                    && lineBuf.get(n - 2) == '\r' && lineBuf.get(n - 1) == '\n') {
                lineBuf.clear();
                finishBody();
                return;
            }

            if (lineBuf.size() > 8 * 1024) {
                throw new IllegalArgumentException("400 Trailers too large");
            }
        }
    }

    private void writeBodyBytes(byte[] b, int off, int len) throws IOException {
        bodyWritten += len;
        if (bodyWritten > maxBodyBytes) {
            throw new IllegalArgumentException("413 Payload Too Large");
        }

        // ✅ كتابة مباشرة باستخدام FileChannel
        ByteBuffer buffer = ByteBuffer.wrap(b, off, len);
        while (buffer.hasRemaining()) {
            bodyChannel.write(buffer);
        }
    }

    private void finishBody() throws IOException {
        if (bodyChannel != null) {
            bodyChannel.force(false); // sync to disk
            bodyChannel.close();
            bodyChannel = null;
        }
        state = State.DONE;
    }

    public void closeBodyStreamIfOpen() throws IOException {
        if (bodyChannel != null) {
            try {
                bodyChannel.close();
            } catch (Exception ignored) {
            }

            bodyChannel = null;
        }

        if (bodyFile != null && Files.exists(bodyFile)) {
            try {
                Files.delete(bodyFile);
            } catch (Exception ignored) {
            }
        }
    }

    public boolean isRequestCompleted() {
        return state == State.DONE;
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public String getVersion() {
        return version;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getHeader(String key) {
        return getHeaderIgnoreCase(headers, key);
    }

    public Path getBodyFile() {
        return bodyFile;
    }

    public long getContentLength() {
        return contentLength;
    }

    public boolean isChunked() {
        return isChunked;
    }

    public AppConfig.ServerConfig getChosenServer() {
        return chosenServer;
    }

    public static String getHeaderIgnoreCase(Map<String, String> headers, String key) {
        if (headers == null || key == null) {
            return null;
        }

        for (String k : headers.keySet()) {
            if (k != null && k.equalsIgnoreCase(key)) {
                return headers.get(k);
            }
        }
        return null;
    }

    public static AppConfig.ServerConfig chooseServerByHost(
            List<AppConfig.ServerConfig> cfgs, String hostHeader) {

        String host = normalizeHost(hostHeader);

        if (host != null) {
            for (AppConfig.ServerConfig sc : cfgs) {
                if (sc.name != null && host.equals(sc.name)) {
                    return sc;
                }
            }
        }

        for (AppConfig.ServerConfig sc : cfgs) {
            if (sc.defaultServer) {
                return sc;
            }
        }

        return cfgs.isEmpty() ? null : cfgs.get(0);
    }

    private static String normalizeHost(String hostHeader) {
        if (hostHeader == null) {
            return null;
        }

        String h = hostHeader.trim().toLowerCase();
        int idx = h.indexOf(':');
        if (idx != -1) {
            h = h.substring(0, idx);
        }
        return h;
    }

    private static final class ByteArray {

        private byte[] a;
        private int n;

        ByteArray(int cap) {
            a = new byte[Math.max(16, cap)];
            n = 0;
        }

        void add(byte b) {
            if (n == a.length) {
                a = grow(a);
            }
            a[n++] = b;
        }

        int size() {
            return n;
        }

        byte get(int i) {
            return a[i];
        }

        byte[] toArray(int from, int to) {
            int len = Math.max(0, to - from);
            byte[] out = new byte[len];
            System.arraycopy(a, from, out, 0, len);
            return out;
        }

        void clear() {
            n = 0;
        }

        private static byte[] grow(byte[] old) {
            byte[] nw = new byte[old.length * 2];
            System.arraycopy(old, 0, nw, 0, old.length);
            return nw;
        }
    }
}
