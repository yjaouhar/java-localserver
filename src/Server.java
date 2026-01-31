
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Set;

public class Server {

    public Server() throws Exception {
        Selector selector = Selector.open();

        ServerSocketChannel server = ServerSocketChannel.open();

        server.bind(new InetSocketAddress(5000));

        server.configureBlocking(false);

        server.register(selector, SelectionKey.OP_ACCEPT);

        System.out.println(" Server    5000 ...");

        while (true) {
            int ready = selector.select(200);

            if (ready == 0) {
                continue;
            }

            Set<SelectionKey> keys = selector.selectedKeys();
            Iterator<SelectionKey> it = keys.iterator();

            System.out.println(keys);

            while (it.hasNext()) {
                SelectionKey key = it.next();
                it.remove();

                if (!key.isValid()) {
                    continue;
                }

                if (key.isAcceptable()) {
                    ServerSocketChannel srv = (ServerSocketChannel) key.channel();
                    SocketChannel client = srv.accept();
                    System.out.println("==" + client.getRemoteAddress());

                    if (client == null) {
                        continue;
                    }

                    String msg = "Hello from ServerSocketChannel!\n";
                    ByteBuffer buffer = ByteBuffer.wrap(msg.getBytes(StandardCharsets.UTF_8));

                    while (buffer.hasRemaining()) {
                        client.write(buffer);
                    }

                    client.close();

                }
            }
        }
    }
}
