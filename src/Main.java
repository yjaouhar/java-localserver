
import utils.AppConfig;

public class Main {

    public static void main(String[] args) throws Exception {
        System.out.println("Server is starting...");
        AppConfig cfg = ConfigLoader.loadFromFile("../config.json");
        // System.out.println("llllll   "+cfg.servers.size());
        // System.out.println("xxxxxxx"+cfg.servers.get(0).ports.get(0));
        // System.out.println("=== CONFIG LOADED ===");
        // System.out.println("Servers count: " + cfg.servers.size());

        // for (AppConfig.ServerConfig s : cfg.servers) {
        //     System.out.println("---- SERVER ----");
        //     System.out.println("name = " + s.name);
        //     System.out.println("host = " + s.host);
        //     System.out.println("ports = " + s.ports);
        //     System.out.println("default = " + s.defaultServer);
        //     System.out.println("client_max_body_size = " + s.clientMaxBodySize);

        //     System.out.println("error_pages:");
        //     for (Map.Entry<Integer, String> e : s.errorPages.entrySet()) {
        //         System.out.println("  " + e.getKey() + " -> " + e.getValue());
        //     }

        //     System.out.println("routes:");
        //     for (AppConfig.RouteConfig r : s.routes) {
        //         System.out.println("  path = " + r.path);
        //         System.out.println("    root = " + r.root);
        //         System.out.println("    methods = " + r.methods);
        //         System.out.println("    index = " + r.index);
        //         System.out.println("    dir_listing = " + r.directoryListing);
        //         System.out.println("    upload_dir = " + r.uploadDir);

        //         if (r.redirect != null) {
        //             System.out.println("    redirect: "
        //                 + r.redirect.code + " -> " + r.redirect.location);
        //         }

        //         if (r.cgi != null) {
        //             System.out.println("    cgi: "
        //                 + r.cgi.extension + " | " + r.cgi.interpreter);
        //         }
        //     }
        // }

        Server server = new Server(cfg);

    }
}
