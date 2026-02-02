
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
        
        //create a new selector to gather the new incoming sockets
        Selector selector = Selector.open();

        ServerSocketChannel server = ServerSocketChannel.open();
        //the server start listening to the port
        server.bind(new InetSocketAddress(5000));
        //we use false to tell the channel to not block
        server.configureBlocking(false);
        // the server is registered to the selector to know where a user want to enter.
        server.register(selector, SelectionKey.OP_ACCEPT);

        System.out.println(" Server    5000 ...");

        while (true) {
            //here, we wait for the event
            //the select method selects a set of keys whose corresponding channels are ready for I/O operations.
            int ready = selector.select(200);

            if (ready == 0) {
                continue;
            }
            //here, we get the selection keys that require an operation to be performed by calling the .selectedKeys() method of the selector which return the keys as a set
            Set<SelectionKey> keys = selector.selectedKeys();
            //we use the itertor to traverse through all the keys in the set
            Iterator<SelectionKey> it = keys.iterator();

            // System.out.println(keys);
            System.out.println("111111111111");
            while (it.hasNext()) {
                SelectionKey key = it.next();
                it.remove();

                if (!key.isValid()) {
                    continue;
                }
                //check whether the client is ready to accept a new socket connection
                if (key.isAcceptable()) {
                    System.out.println("222222");
                    ServerSocketChannel srv = (ServerSocketChannel) key.channel();
                    SocketChannel client = srv.accept();
                    System.out.println("==" + client.getRemoteAddress());

                    // if (client == null) {
                    //     continue;
                    // }
                    // if(key.isReadable()) {
                        System.out.println("33333333333");
                        ByteBuffer buffer = ByteBuffer.allocate(1024);
                        int bytesRead = client.read(buffer);
                        System.out.println("lenght dyal l buffer " + bytesRead);
                        if(bytesRead == -1) {
                            client.close();
                            continue;
                        }
                        buffer.flip();
                        HttpRequest request = new HttpRequest();
                        request.consume(buffer);
                        if(request.isRequestCompleted()) {
                            System.out.println("11Method: " + request.getMethod());
                            System.out.println("22Path: "+ request.getPath());
                            System.out.println("33Headers: " + request.getHeaders());
                            System.out.println("44Body: " + request.getBody());
                        }
                    // }
                    // String msg = "Hello from ServerSocketChannel!\n";
                    // ByteBuffer buffer = ByteBuffer.wrap(msg.getBytes(StandardCharsets.UTF_8));

                    // while (buffer.hasRemaining()) {
                    //     client.write(buffer);
                    // }

                    client.close();

                }
            }
        }
    }
}
