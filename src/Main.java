
import utils.json.AppConfig;

public class Main {

    public static void main(String[] args) throws Exception {
        String configPath = "config.json";
        if (args.length > 0) {
            configPath = args[0];
        }
        System.out.println("Server is starting... ");
        AppConfig cfg = ConfigLoader.loadFromFile(configPath);

        Server server = new Server(cfg);

    }
}

 