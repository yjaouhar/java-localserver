package handlers;

import http.HttpRequest;
import http.HttpResponse;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import utils.json.AppConfig;

public class StaticFileHandler {

    public static HttpResponse handle(HttpRequest request, AppConfig.RouteConfig route, Map<Integer, String> errorPages) {

        if (route.root == null) {
            return HttpResponse.ErrorResponse(500, "Server Error", "No root directory defined", errorPages.get(500));
        }

        Path rootDir = Paths.get(route.root).toAbsolutePath();
        Path requestedPath = rootDir;

        if (!requestedPath.startsWith(rootDir)) {
            return HttpResponse.ErrorResponse(403, "Forbidden", "Access denied", errorPages.get(403));
        }

        try {
            if (Files.isDirectory(requestedPath)) {
                if (!route.directoryListing) {
                    if (route.index != null) {
                        requestedPath = rootDir.resolve(route.index).normalize();
                        if (!Files.exists(requestedPath)) {
                            return HttpResponse.ErrorResponse(404, "Not Found", "Index file not found", errorPages.get(404));
                        }
                    } else {
                        return HttpResponse.ErrorResponse(403, "Forbidden", "Directory listing not allowed", errorPages.get(403));
                    }
                } else {
                    String requestPath = request.getPath();
                    final String requestPathNormalized = requestPath.endsWith("/") ? requestPath : requestPath + "/";

                    StringBuilder listing = new StringBuilder();
                    listing.append("<!DOCTYPE html><html><head><title>Index of ")
                            .append(requestPathNormalized)
                            .append("</title></head><body>");

                    listing.append("<h1>Index of ").append(requestPathNormalized).append("</h1>");
                    listing.append("<ul>");

                    Files.list(requestedPath)
                            .sorted(Comparator.comparing(Path::getFileName))
                            .forEach(f -> {
                                String name = f.getFileName().toString();
                                String href = requestPathNormalized + name;
                                if (Files.isDirectory(f)) {
                                    href += "/";
                                    name += "/";
                                }
                                listing.append("<li>")
                                        .append("<a href=\"")
                                        .append(href)
                                        .append("\">")
                                        .append(name)
                                        .append("</a>")
                                        .append("</li>");
                            });

                    listing.append("</ul>");
                    listing.append("</body></html>");

                    HttpResponse res = new HttpResponse(200, "OK");
                    res.setHeaders("Content-Type", "text/html; charset=UTF-8");
                    res.setBody(listing.toString().getBytes());
                    return res;
                }
            }

            if (Files.exists(requestedPath) && !Files.isDirectory(requestedPath)) {
                long fileSize = Files.size(requestedPath);

                HttpResponse res = new HttpResponse(200, "OK");

                String fileName = requestedPath.getFileName().toString();
                String contentType = getContentType(fileName);

                res.setHeaders("Content-Type", contentType);
                res.setHeaders(
                        "Content-Disposition",
                        "attachment; filename=\"" + fileName + "\""
                );
                if (fileSize > 1024 * 1024) {
                    res.setBodyFile(requestedPath);
                } else {
                byte[] fileBytes = Files.readAllBytes(requestedPath);
                res.setBody(fileBytes);
                // res.setBodyBytes(fileBytes);
                }

                return res;

            } else {
                return HttpResponse.ErrorResponse(404, "Not Found", "File not found", errorPages.get(404));
            }

        } catch (IOException e) {
            return HttpResponse.ErrorResponse(500, "Server Error", e.getMessage(), errorPages.get(500));
        }
    }

    private static String getContentType(String fileName) {

        if (fileName.endsWith(".html") || fileName.endsWith(".htm")) {
            return "text/html; charset=UTF-8";
        } else if (fileName.endsWith(".css")) {
            return "text/css; charset=UTF-8";
        } else if (fileName.endsWith(".js")) {
            return "application/javascript; charset=UTF-8";
        } else if (fileName.endsWith(".png")) {
            return "image/png";
        } else if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (fileName.endsWith(".gif")) {
            return "image/gif";
        } else if (fileName.endsWith(".svg")) {
            return "image/svg+xml";
        } else if (fileName.endsWith(".json")) {
            return "application/json; charset=UTF-8";
        } else if (fileName.endsWith(".xml")) {
            return "application/xml; charset=UTF-8";
        } else if (fileName.endsWith(".pdf")) {
            return "application/pdf";
        } else if (fileName.endsWith(".txt")) {
            return "text/plain; charset=UTF-8";
        } else {
            return "application/octet-stream";
        }
    }
}
