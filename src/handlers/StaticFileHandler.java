package handlers;

import http.HttpRequest;
import http.HttpResponse;
import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.Map;
import utils.json.AppConfig;

public class StaticFileHandler {

    public static HttpResponse handle(HttpRequest request, AppConfig.RouteConfig route, Map<Integer, String> errorPages) {

        if (route.root == null) {
            return HttpResponse.ErrorResponse(500, "Server Error", "No root directory defined", errorPages.get(500));
        }

        Path rootDir = Paths.get(route.root).toAbsolutePath();
        Path requestedPath = rootDir.resolve(request.getPath().substring(route.path.length())).normalize();

        if (!requestedPath.startsWith(rootDir)) {
            return HttpResponse.ErrorResponse(403, "Forbidden", "Access denied", errorPages.get(403));
        }

        try {
            if (Files.isDirectory(requestedPath) && route.directoryListing) {
                if (!route.directoryListing) {
                    return HttpResponse.ErrorResponse(403, "Forbidden", "Directory listing not allowed", errorPages.get(403));
                }

                String requestPath = request.getPath();
                final String requestPathNormalized = requestPath.endsWith("/") ? requestPath : requestPath + "/";

                StringBuilder listing = new StringBuilder();
                listing.append("<html><head><title>Index of ")
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
                res.setBody(listing.toString());
                return res;

            } else if (Files.exists(requestedPath)) {
                // single file
                if (Files.isDirectory(requestedPath)) {
                    requestedPath =rootDir.resolve(route.index).normalize();
                }
                byte[] fileBytes = Files.readAllBytes(requestedPath);

                HttpResponse res = new HttpResponse(200, "OK");

                // determine content type by extension simple check
                String fileName = requestedPath.getFileName().toString();
                if (fileName.endsWith(".html")) {
                    res.setHeaders("Content-Type", "text/html; charset=UTF-8");
                } else if (fileName.endsWith(".css")) {
                    res.setHeaders("Content-Type", "text/css; charset=UTF-8");
                } else if (fileName.endsWith(".js")) {
                    res.setHeaders("Content-Type", "application/javascript; charset=UTF-8");
                } else if (fileName.endsWith(".png")) {
                    res.setHeaders("Content-Type", "image/png");
                } else if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
                    res.setHeaders("Content-Type", "image/jpeg");
                } else {
                    res.setHeaders("Content-Type", "application/octet-stream");
                }

                res.setBody(new String(fileBytes));
                return res;
            } else {
                return HttpResponse.ErrorResponse(404, "Not Found", "File not found", errorPages.get(404));
            }

        } catch (IOException e) {
            return HttpResponse.ErrorResponse(500, "Server Error", e.getMessage(), errorPages.get(500));
        }
    }
}
