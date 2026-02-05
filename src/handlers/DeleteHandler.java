package handlers;

import http.HttpRequest;
import http.HttpResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import utils.json.AppConfig;

public class DeleteHandler {

    public static HttpResponse handleDelete(AppConfig.RouteConfig route,HttpRequest request) {

        if (request == null) {
            return HttpResponse.ErrorResponse(400, "Bad Request", "Request is null");
        }
        if (request.getMethod() == null || !request.getMethod().equals("DELETE")) {
            return HttpResponse.ErrorResponse(405, "Method Not Allowed", "Only DELETE method is allowed");
        }

        String filePath = request.getPath();
        if (filePath == null || filePath.isEmpty()) {
            return HttpResponse.ErrorResponse(400, "Bad Request", "File path is missing");
        }
        Path uploadDir = Paths.get(route.root);
        Path target = uploadDir.resolve(filePath).normalize();
        if (!target.startsWith(uploadDir)) {
            return HttpResponse.ErrorResponse(403, "Forbidden", "Access to the specified path is forbidden"); 
        }
        if (!Files.exists(target)) {
            return HttpResponse.ErrorResponse(404, "Not Found", "File not found");
        }
        try {
            Files.delete(target);
        } catch (IOException e) {
            return HttpResponse.ErrorResponse(500, "Internal Server Error", "Failed to delete file: " + e.getMessage());
        }
        return HttpResponse.successResponse(200, "OK", "File deleted successfully");
    }
}
