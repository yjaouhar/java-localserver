package handlers;

import http.HttpRequest;
import http.HttpResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import utils.json.AppConfig;

public class DeleteHandler {

    public static HttpResponse handleDelete(AppConfig.RouteConfig route,HttpRequest request, Map<Integer, String> errorPages) {

        if (request == null) {
            return HttpResponse.ErrorResponse(400, "Bad Request", "Request is null", errorPages.get(400));
        }
        if (request.getMethod() == null || !request.getMethod().equals("DELETE")) {
            return HttpResponse.ErrorResponse(405, "Method Not Allowed", "Only DELETE method is allowed", errorPages.get(405));
        }

        String filePath = request.getPath();
        if (filePath == null || filePath.isEmpty()) {
            return HttpResponse.ErrorResponse(400, "Bad Request", "File path is missing", errorPages.get(400));
        }
        Path uploadDir = Paths.get(route.root);
        Path target = uploadDir.resolve(filePath).normalize();
        if (!target.startsWith(uploadDir)) {
            return HttpResponse.ErrorResponse(403, "Forbidden", "Access to the specified path is forbidden", errorPages.get(403)); 
        }
        if (!Files.exists(target)) {
            return HttpResponse.ErrorResponse(404, "Not Found", "File not found", errorPages.get(404));
        }
        try {
            Files.delete(target);
        } catch (IOException e) {
            return HttpResponse.ErrorResponse(500, "Internal Server Error", "Failed to delete file: " + e.getMessage(), errorPages.get(500));
        }
        return HttpResponse.successResponse(200, "OK", "File deleted successfully");
    }
}
