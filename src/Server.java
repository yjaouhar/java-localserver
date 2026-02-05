
import http.HttpRequest;
import http.HttpResponse;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
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
                ////sc.host,
                server.bind(new InetSocketAddress(sc.host,port));
                server.configureBlocking(false);

                SelectionKey key = server.register(selector, SelectionKey.OP_ACCEPT);

                ListenerInfo info = new ListenerInfo(port);
                info.addServer(sc);

                key.attach(info);
                openedPorts.put(port, key);
            }
        }

        while (true) {
            int ready = selector.select(200);
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

                if (key.isAcceptable()) {
                    ListenerInfo info = (ListenerInfo) key.attachment();

                    ServerSocketChannel srv = (ServerSocketChannel) key.channel();
                    SocketChannel client = srv.accept();
                    if (client == null) {
                        continue;
                    }

                    System.out.println("== " + client.getRemoteAddress()
                            + " | port=" + info.port
                            + " | servers=" + info.serverNames);

                    ByteBuffer buffer = ByteBuffer.allocate(1024);
                    int bytesRead = client.read(buffer);
                    if (bytesRead == -1) {
                        client.close();
                        continue;
                    }

                    buffer.flip();
                    HttpRequest request = new HttpRequest();
                    request.consume(buffer);

                    if (request.isRequestCompleted()) {
                        System.out.println("Method: " + request.getMethod());
                        System.out.println("Path: " + request.getPath());
                        System.out.println("Headers: " + request.getHeaders());
                        System.out.println("Body: " + request.getBody());

                        AppConfig.ServerConfig chosen = chooseServerByPath(info.serverCfgs,
                                request.getHeaders().get("Host"));

                        Router router = new Router(chosen, request);
                        HttpResponse response = router.route();
                        client.write(response.toByteBuffer());
                    }

                    client.close();
                }
            }
        }
    }

    private static AppConfig.ServerConfig chooseServerByPath(List<AppConfig.ServerConfig> cfgs, String host) {
        if (host != null) {
            for (AppConfig.ServerConfig sc : cfgs) {
                  if(sc.name.equals(host)){
                    return  sc ;
                  }
            }
        }
        return cfgs.isEmpty() ? null : cfgs.get(0);
    }
}
