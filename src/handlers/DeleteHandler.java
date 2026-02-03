package handlers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class DeleteHandler {

    public static int handleDelete(UploadHandler.HttpRequest request) {

        if (request == null) {
            return 400;
        }
        if (request.getMethod() == null || !request.getMethod().equals("DELETE")) {
            return 405;
        }

        String filePath = request.getPath();
        if (filePath == null || filePath.isEmpty()) {
            return 400;
        }
        Path uploadDir = Paths.get("uploads");
        Path target = uploadDir.resolve(filePath).normalize();
        if (!target.startsWith(uploadDir)) {
            return 403; // Forbidden
        }
        if (!Files.exists(target)) {
            return 404;
        }
        try {
            Files.delete(target);
        } catch (IOException e) {
            return 500;
        }
        return 200;
    }
}
