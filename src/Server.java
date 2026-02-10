import http.HttpRequest;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import utils.json.AppConfig;

public class Server {

    private final AppConfig appConfig;
    private final handlers.CGIHandler cgiHandler;

    static class ListenerInfo {

        final int port;
        final List<String> serverNames = new ArrayList<>();
        final List<AppConfig.ServerConfig> serverCfgs = new ArrayList<>();

        ListenerInfo(int port) {
            this.port = port;
        }

        void addServer(AppConfig.ServerConfig sc) {
            serverNames.add(sc.name);
            serverCfgs.add(sc);
        }
    }

    static class ConnCtx {

        final ListenerInfo listenerInfo;
        final SocketChannel client;
        final ByteBuffer readBuf;
        final HttpRequest request;

        ByteBuffer writeBuf;
        long connectedAt;
        long lastActivityAt;
        boolean responseReady;
        AppConfig.ServerConfig chosenServer;

        long uploadBytesThisSecond = 0;
        long lastSecondTimestamp = 0;
        static final long MAX_UPLOAD_PER_SECOND = 5 * 1024 * 1024; // 5 MB/s

        boolean shouldLimitUpload(int bytesAboutToRead) {

            long now = System.currentTimeMillis() / 1000;

            if (now != lastSecondTimestamp) {
                uploadBytesThisSecond = 0;
                lastSecondTimestamp = now;
            }

            uploadBytesThisSecond += bytesAboutToRead;

            return uploadBytesThisSecond > MAX_UPLOAD_PER_SECOND;
        }

        ConnCtx(ListenerInfo info, SocketChannel client, int bufSize, List<AppConfig.ServerConfig> serverCfgs) {
            this.listenerInfo = info;
            this.client = client;
            this.readBuf = ByteBuffer.allocate(bufSize);
            this.request = new HttpRequest(serverCfgs);
            this.connectedAt = System.currentTimeMillis();
            this.lastActivityAt = this.connectedAt;
            this.responseReady = false;
        }

        void updateActivity() {
            this.lastActivityAt = System.currentTimeMillis();
        }
    }

    public Server(AppConfig appConfig) throws Exception {
        this.appConfig = appConfig;
        this.cgiHandler = new handlers.CGIHandler(30); // 30 seconds timeout

        Selector selector = Selector.open();
        Map<Integer, SelectionKey> openedPorts = new HashMap<>();

        for (AppConfig.ServerConfig sc : appConfig.servers) {
            for (int port : sc.ports) {
                if (openedPorts.containsKey(port)) {
                    SelectionKey existingKey = openedPorts.get(port);
                    ListenerInfo info = (ListenerInfo) existingKey.attachment();
                    info.addServer(sc);
                    continue;
                }

                ServerSocketChannel server = ServerSocketChannel.open();
                server.bind(new InetSocketAddress(sc.host, port));
                server.configureBlocking(false);

                SelectionKey key = server.register(selector, SelectionKey.OP_ACCEPT);

                ListenerInfo info = new ListenerInfo(port);
                info.addServer(sc);

                key.attach(info);
                openedPorts.put(port, key);

                System.out.println(" Listening on " + sc.host + ":" + port);
            }
        }

        System.out.println("\n Server started successfully!\n");

        while (true) {
            // MOHIM: Check pending CGI 9BEL select
            checkAllPendingCGI(selector);
            
            int ready = selector.select(200);

            // check timeouts
            checkTimeouts(selector);

            if (ready == 0) {
                continue;
            }

            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while (it.hasNext()) {
                SelectionKey key = it.next();
                it.remove();

                if (!key.isValid()) {
                    continue;
                }

                try {
                    if (key.isAcceptable()) {
                        onAccept(selector, key);
                    } else if (key.isReadable()) {
                        onRead(key);
                    } else if (key.isWritable()) {
                        onWrite(key);
                    }
                } catch (Exception e) {
                    System.err.println("Event error: " + e.getMessage());
                    safeCleanup(key);
                }
            }
        }
    }

    private void checkAllPendingCGI(Selector selector) {
        for (SelectionKey key : selector.keys()) {
            if (key.isValid() && key.attachment() instanceof ConnCtx) {
                ConnCtx ctx = (ConnCtx) key.attachment();
                if (cgiHandler.hasPending(key)) {
                    Map<Integer, String> errorPages = ctx.chosenServer != null && ctx.chosenServer.errorPages != null 
                        ? ctx.chosenServer.errorPages 
                        : new HashMap<>();
                    cgiHandler.checkPendingCGI(key, errorPages);
                }
            }
        }
    }

    private void onAccept(Selector selector, SelectionKey key) throws Exception {
        ListenerInfo info = (ListenerInfo) key.attachment();
        ServerSocketChannel srv = (ServerSocketChannel) key.channel();

        SocketChannel client = srv.accept();
        if (client == null) {
            return;
        }

        client.configureBlocking(false);

        ConnCtx ctx = new ConnCtx(info, client, 8192, info.serverCfgs);

        SelectionKey ckey = client.register(selector, SelectionKey.OP_READ);
        ckey.attach(ctx);

    }

    private void onRead(SelectionKey key) {
        ConnCtx ctx = (ConnCtx) key.attachment();
        SocketChannel client = ctx.client;

        try {
            ctx.readBuf.clear();
            if (ctx.shouldLimitUpload(ctx.readBuf.capacity())) {
                return;
            }
            int n = client.read(ctx.readBuf);

            if (n == -1) {

                cleanup(key, client, ctx);
                return;
            }

            // no data read, just return and wait for next event
            if (n == 0) {
                return;
            }

            ctx.updateActivity();
            ctx.readBuf.flip();

            ctx.request.consume(ctx.readBuf);

            if (ctx.request.isRequestCompleted()) {

                ctx.chosenServer = ctx.request.getChosenServer();

                key.interestOps(SelectionKey.OP_WRITE);
            }

        } catch (IllegalArgumentException e) {
            //error from request parsing (e.g. headers too large, invalid format, etc)

            if (ctx.request.getChosenServer() != null) {
                ctx.chosenServer = ctx.request.getChosenServer();
            }

            String errPage = "";
            if (ctx.chosenServer != null) {
                if (ctx.chosenServer.errorPages != null && ctx.chosenServer.errorPages.containsKey(400)) {
                    errPage = ctx.chosenServer.errorPages.get(400);
                }
            }

            ctx.writeBuf = http.HttpResponse.ErrorResponse(400, "Bad Request", "", errPage).toByteBuffer();
            ctx.responseReady = true;

            key.interestOps(SelectionKey.OP_WRITE);

        } catch (Exception e) {

            if (ctx.request.getChosenServer() != null) {
                ctx.chosenServer = ctx.request.getChosenServer();
            }

            String errPage = "";
            if (ctx.chosenServer != null) {
                if (ctx.chosenServer.errorPages != null && ctx.chosenServer.errorPages.containsKey(500)) {
                    errPage = ctx.chosenServer.errorPages.get(500);
                }
            }

            ctx.writeBuf = http.HttpResponse.ErrorResponse(500, "Bad Request", "", errPage).toByteBuffer();
            ctx.responseReady = true;

            key.interestOps(SelectionKey.OP_WRITE);

        }
    }

    private void onWrite(SelectionKey key) {

        ConnCtx ctx = (ConnCtx) key.attachment();
        SocketChannel client = ctx.client;

        try {
            if (ctx.writeBuf == null) {
                Router router = new Router(ctx.chosenServer, ctx.request, cgiHandler, key);
                http.HttpResponse resp = router.route();
                
                // Ila CGI, response ghadi yji later
                if (resp == null) {
                    return;
                }
                
                ctx.writeBuf = resp.toByteBuffer();
            }

            client.write(ctx.writeBuf);

            if (!ctx.writeBuf.hasRemaining()) {
                cleanup(key, client, ctx);
            }

        } catch (Exception e) {
            safeCleanup(key);
        }
    }

    private void checkTimeouts(Selector selector) {
        long now = System.currentTimeMillis();
        long headerTimeout = appConfig.timeouts.headerMs;
        long bodyTimeout = appConfig.timeouts.bodyMs;
        long idleTimeout = appConfig.timeouts.idleKeepAliveMs;

        for (SelectionKey key : selector.keys()) {
            if (!key.isValid() || !(key.attachment() instanceof ConnCtx)) {
                continue;
            }

            ConnCtx ctx = (ConnCtx) key.attachment();
            long elapsed = now - ctx.connectedAt;
            long idle = now - ctx.lastActivityAt;

            if (!ctx.request.isRequestCompleted() && elapsed > headerTimeout) {
                System.out.println("Header timeout (" + elapsed + "ms)");
                handleHttpError(key, ctx, "408 Request Timeout");
                continue;
            }

            if (ctx.request.isRequestCompleted() && !ctx.responseReady
                    && elapsed > bodyTimeout) {
                System.out.println("Body processing timeout (" + elapsed + "ms)");
                handleHttpError(key, ctx, "408 Request Timeout");
                continue;
            }

            if (idle > idleTimeout) {
                System.out.println("Idle timeout (" + idle + "ms)");
                safeCleanup(key);
            }
        }
    }

    private void handleHttpError(SelectionKey key, ConnCtx ctx, String error) {
        try {
            String[] parts = error.split(" ", 2);
            String code = parts[0];
            String message = parts.length > 1 ? parts[1] : "Error";

            String response = "HTTP/1.1 " + code + " " + message + "\r\n"
                    + "Content-Type: text/html; charset=UTF-8\r\n"
                    + "Connection: close\r\n"
                    + "Content-Length: ";

            String body = "<html><body><h1>" + code + " " + message + "</h1></body></html>";
            response += body.length() + "\r\n\r\n" + body;

            ctx.writeBuf = ByteBuffer.wrap(response.getBytes());
            ctx.responseReady = true;

            key.interestOps(SelectionKey.OP_WRITE);

        } catch (Exception e) {
            safeCleanup(key);
        }
    }

    private void cleanup(SelectionKey key, SocketChannel client, ConnCtx ctx) {
        // Cleanup CGI ila kan
        cgiHandler.cleanup(key);
        
        try {
            ctx.request.closeBodyStreamIfOpen();
        } catch (Exception ignored) {
        }

        try {
            key.cancel();
        } catch (Exception ignored) {
        }

        try {
            client.close();
        } catch (Exception ignored) {
        }
    }

    private void safeCleanup(SelectionKey key) {
        try {
            Object att = key.attachment();
            if (att instanceof ConnCtx) {
                ConnCtx ctx = (ConnCtx) att;
                cleanup(key, ctx.client, ctx);
            } else {
                key.cancel();
                key.channel().close();
            }
        } catch (Exception ignored) {
        }
    }
}