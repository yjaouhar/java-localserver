import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

public class Server {

    Server() throws Exception {
        Selector selector = Selector.open();

        ServerSocketChannel server = ServerSocketChannel.open(); // open ServerSocketChannel 

        // add port
        server.bind(new InetSocketAddress(5000));

        // Non-Blocking mode
        server.configureBlocking(false);
        server.register(selector, SelectionKey.OP_ACCEPT);


        // System.out.println("server bdaaaaaaaaaa");

        while (true) {

                
                SocketChannel client = server.accept();

                if (client != null) {
                    System.out.println("✅ Client connected");

                    String msg = "Hello from ServerSocketChannel!";

                    ByteBuffer buffer = ByteBuffer.wrap(msg.getBytes());

                    client.write(buffer);

                   
                    client.close();
                }
            
            }
    }
}