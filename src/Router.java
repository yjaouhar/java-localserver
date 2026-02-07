
import handlers.CGIHandler;
import handlers.StaticFileHandler;
import http.HttpRequest;
import http.HttpResponse;
import utils.json.AppConfig.RouteConfig;
import utils.json.AppConfig.ServerConfig;

public class Router {

    private final ServerConfig config;
    private final HttpRequest request;

    public Router(ServerConfig config, HttpRequest request) {
        this.config = config;
        this.request = request;
    }

    public HttpResponse route() {
        if (request == null || request.getPath() == null) {
            return HttpResponse.ErrorResponse(400, "Bad Request", "Invalid HTTP request", config.errorPages.get(400));
        }
        if (request.getBody() != null && request.getBody().length() > config.clientMaxBodySize) {
            return HttpResponse.ErrorResponse(413, "Payload Too Large", "Request body exceeds maximum allowed size", config.errorPages.get(413));
        }
        String path = request.getPath();
        RouteConfig matchedRoute = null;

        for (RouteConfig route : config.routes) {
            if (path.equals(route.path)) {
                matchedRoute = route;
                break;
            }
        }
        if (matchedRoute == null) {
            return HttpResponse.ErrorResponse(404, "Not Found", "No matching route", config.errorPages.get(404));
        }

        if (matchedRoute.methods != null
                && !matchedRoute.methods.contains(request.getMethod())) {
            return HttpResponse.ErrorResponse(405, "Method Not Allowed", "Method not allowed for this route", config.errorPages.get(405));
        }

        if (matchedRoute.redirect != null) {
            HttpResponse redirectResponse = new HttpResponse(matchedRoute.redirect.code, "Redirect");
            redirectResponse.setHeaders("Location", matchedRoute.redirect.location);
            return redirectResponse;
        }

        if (matchedRoute.cgi != null) {
            return CGIHandler.handleCGI(matchedRoute, request, config.errorPages);
        }

        if ("POST".equals(request.getMethod())
                && matchedRoute.root != null) {
            return handlers.UploadHandler.handleUpload(matchedRoute, request, config.errorPages);
        }

        if ("DELETE".equals(request.getMethod())) {
            return handlers.DeleteHandler.handleDelete(matchedRoute, request, config.errorPages);
        }
        if ("GET".equals(request.getMethod()) && matchedRoute.root != null) {
            return StaticFileHandler.handle(request, matchedRoute, config.errorPages);
        }
        return HttpResponse.ErrorResponse(501, "Not Implemented", "Static file serving not implemented yet", config.errorPages.get(501));
    }

}
