
import http.HttpRequest;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import utils.json.AppConfig;

public class Server {

    private final AppConfig appConfig;

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

        ConnCtx(ListenerInfo info, SocketChannel client, int bufSize) {
            this.listenerInfo = info;
            this.client = client;
            this.readBuf = ByteBuffer.allocate(bufSize);
            this.request = new HttpRequest();
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

                System.out.println("✓ Listening on " + sc.host + ":" + port);
            }
        }

        System.out.println("\n Server started successfully!\n");

        while (true) {
            int ready = selector.select(200);

            // check timeouts
            // checkTimeouts(selector);

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
                    System.err.println("⚠ Event error: " + e.getMessage());
                    safeCleanup(key);
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

        ConnCtx ctx = new ConnCtx(info, client, 8192);

        SelectionKey ckey = client.register(selector, SelectionKey.OP_READ);
        ckey.attach(ctx);

        // System.out.println("✓ Connection from " + client.getRemoteAddress()
        //         + " on port " + info.port);
    }

    private void onRead(SelectionKey key) {
        ConnCtx ctx = (ConnCtx) key.attachment();
        SocketChannel client = ctx.client;

        try {
            ctx.readBuf.clear();
            int n = client.read(ctx.readBuf);

            if (n == -1) {
                // client closed connection
                // System.out.println("Client closed connection");
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
                String hostHeader = HttpRequest.getHeaderIgnoreCase(ctx.request.getHeaders(), "Host");

                ctx.chosenServer = chooseServerByHost(
                        ctx.listenerInfo.serverCfgs, hostHeader
                );

                // set max body size based on chosen server
                long maxBodySize = ctx.chosenServer != null ? ctx.chosenServer.clientMaxBodySize : 1_000_000;
                ctx.request.setMaxBodyBytes(maxBodySize);

                // change interest to write
                key.interestOps(SelectionKey.OP_WRITE);
            }

        } catch (IllegalArgumentException e) {
            //error from request parsing (e.g. headers too large, invalid format, etc)
            handleHttpError(key, ctx, e.getMessage());
        } catch (Exception e) {
            // other errors (e.g. IO errors)
            System.err.println("⚠ Read error: " + e.getMessage());
            handleHttpError(key, ctx, "500 Internal Server Error");
        }
    }

    private void onWrite(SelectionKey key) {

        ConnCtx ctx = (ConnCtx) key.attachment();
        SocketChannel client = ctx.client;

        try {
            if (ctx.writeBuf == null) {
                Router router = new Router(ctx.chosenServer, ctx.request);
                http.HttpResponse resp = router.route();
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

        for (SelectionKey key : selector.keys()) {
            if (!key.isValid() || !(key.attachment() instanceof ConnCtx)) {
                continue;
            }

            ConnCtx ctx = (ConnCtx) key.attachment();
            long elapsed = now - ctx.connectedAt;
            long idle = now - ctx.lastActivityAt;

            // before request is fully read, check header timeout
            if (!ctx.request.isRequestCompleted() && elapsed > headerTimeout) {
                System.out.println("⏱ Header timeout");
                handleHttpError(key, ctx, "408 Request Timeout");
                continue;
            }

            // after headers read but before full body is read, check body timeout
            if (ctx.request.isRequestCompleted() && !ctx.responseReady
                    && elapsed > bodyTimeout) {
                System.out.println("⏱ Body timeout");
                handleHttpError(key, ctx, "408 Request Timeout");
                continue;
            }

            //Idle timeout
            if (idle > appConfig.timeouts.idleKeepAliveMs) {
                System.out.println("⏱ Idle timeout");
                safeCleanup(key);
            }
        }
    }

    // ✅ معالجة أخطاء HTTP
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

    private String buildSimpleResponse(ConnCtx ctx) {
        String body = "<html><body>"
                + "<h1>It Works!</h1>"
                + "<p>Method: " + ctx.request.getMethod() + "</p>"
                + "<p>Path: " + ctx.request.getPath() + "</p>"
                + "<p>Server: " + (ctx.chosenServer != null ? ctx.chosenServer.name : "default") + "</p>"
                + "</body></html>";

        return "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n"
                + "Content-Length: " + body.length() + "\r\n"
                + "Connection: close\r\n"
                + "\r\n"
                + body;
    }

    private void cleanup(SelectionKey key, SocketChannel client, ConnCtx ctx) {
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

    private static AppConfig.ServerConfig chooseServerByHost(
            List<AppConfig.ServerConfig> cfgs, String hostHeader) {

        String host = normalizeHost(hostHeader);

        // search for matching server name in Host header
        if (host != null) {
            for (AppConfig.ServerConfig sc : cfgs) {
                if (sc.name != null && host.equals(sc.name.toLowerCase())) {
                    return sc;
                }
            }
        }

        // serch for default server
        for (AppConfig.ServerConfig sc : cfgs) {
            if (sc.defaultServer) {
                return sc;
            }
        }

        // final fallback to first server
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
        // System.err.println("Host header: " + hostHeader + " → normalized: " + h);
        return h;
    }
}
