
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
            return HttpResponse.ErrorResponse(400, "Bad Request", "Invalid HTTP request");
        }

        String path = request.getPath();
        RouteConfig matchedRoute = null;

        for (RouteConfig route : config.routes) {
            if (path.startsWith(route.path)) {
                matchedRoute = route;
                break;
            }
        }

        // 3️⃣ no route → 404
        if (matchedRoute == null) {
            return HttpResponse.ErrorResponse(404, "Not Found", "No matching route");
        }

        // 4️⃣ method check → 405
        if (matchedRoute.methods != null
                && !matchedRoute.methods.contains(request.getMethod())) {
            return HttpResponse.ErrorResponse(405, "Method Not Allowed", "Method not allowed for this route");
        }

        // 5️⃣ redirect
        if (matchedRoute.redirect != null) {
            HttpResponse redirectResponse = new HttpResponse(matchedRoute.redirect.code, "Redirect");
            redirectResponse.setHeaders("Location", matchedRoute.redirect.location);
            return redirectResponse;
        }

        // 6️⃣ CGI
        if (matchedRoute.cgi != null) {
            return CGIHandler.handleCGI(matchedRoute, request);
        }

        // 7️⃣ upload
        if ("POST".equals(request.getMethod())
                && matchedRoute.root != null) {
            return handlers.UploadHandler.handleUpload(matchedRoute, request);
        }

        // 8️⃣ delete
        if ("DELETE".equals(request.getMethod())) {
            return handlers.DeleteHandler.handleDelete(matchedRoute, request);
        }
        if ("GET".equals(request.getMethod()) && matchedRoute.root != null) {
            return StaticFileHandler.handle(request, matchedRoute);
        }
        return HttpResponse.ErrorResponse(501, "Not Implemented", "Static file serving not implemented yet");
    }

}
