import utils.json.AppConfig;

public class RequestProcessor {
    public static HttpResponse handle(HttpRequest req, AppConfig cfg) {
        String strPort = req.getHeaders().get("Host").split(":")[1];
        int port = Integer.parseInt(strPort);
        AppConfig.ServerConfig matchedServer = null;
        for (AppConfig.ServerConfig s : cfg.servers) {
            if(s.ports.contains(port)) {
                matchedServer = s;
                break;
            }
        }

        if(matchedServer == null) {
            for(AppConfig.ServerConfig s: cfg.servers) {
                if(s.defaultServer) {
                    matchedServer = s;
                    break;
                }
            }
        }
        // if(matchedServer == null) {
            return HttpResponse.badRequest();
        // }


    }
}
