import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import utils.AppConfig;

public  class ConfigLoader {
    public static void readFile() throws IOException{
        AppConfig config = new AppConfig();
        AppConfig.RouteConfig route = new AppConfig.RouteConfig();

       String data = Files.readString(Path.of("/home/oyoussef/Desktop/java-localserver/config.json"), StandardCharsets.UTF_8);
       System.out.println(data);
    }
    
}




// START
// │
// ├─ اقرأ config.json من file
// │
// ├─ Parse JSON → Object (Map / Tree)
// │
// ├─ Parse global options (timeouts, limits)
// │
// ├─ FOR each server in servers[]
// │    ├─ Parse server fields
// │    ├─ Validate server
// │    ├─ Parse routes[]
// │    │    ├─ Validate route
// │    │    └─ Add to server
// │    └─ Add server to list
// │
// ├─ Validate global config (default_server واحد فقط)
// │
// └─ RETURN ConfigObject
// END
