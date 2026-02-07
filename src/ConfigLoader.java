
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import utils.JsonFormatValidator;
import utils.JsonFormatValidator.InnerJsonFormatValidator;
import utils.json.AppConfig;
import utils.json.ConfigMapper;
import utils.json.MiniJsonParser;


public final class ConfigLoader {

    private ConfigLoader() {}

    public static AppConfig loadFromFile(String path) throws IOException {
        String json = Files.readString(Path.of(path), StandardCharsets.UTF_8);
        return loadFromString(json);
    }

    public static AppConfig loadFromString(String json) {
        InnerJsonFormatValidator v = JsonFormatValidator.isValidJsonFormat(json);
        if (!v.status) {
            throw new IllegalArgumentException("Invalid JSON format: " + v.message
                    + (v.index != null ? (" at index " + v.index) : ""));
        }

        Object root = new MiniJsonParser(json).parse();
        if (!(root instanceof Map)) {
            throw new IllegalArgumentException("Root must be a JSON object");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> obj = (Map<String, Object>) root;

        return ConfigMapper.buildAppConfig(obj);


        
    }
}
