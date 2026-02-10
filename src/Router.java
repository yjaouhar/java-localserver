import handlers.CGIHandler;
import handlers.StaticFileHandler;
import http.HttpRequest;
import http.HttpResponse;
import java.nio.channels.SelectionKey;
import java.nio.file.Files;
import java.util.List;
import utils.json.AppConfig.RouteConfig;
import utils.json.AppConfig.ServerConfig;

public class Router {

    private final ServerConfig config;
    private final HttpRequest request;
    private final CGIHandler cgiHandler;
    private final SelectionKey clientKey;

    public Router(ServerConfig config, HttpRequest request, CGIHandler cgiHandler, SelectionKey clientKey) {
        this.config = config;
        this.request = request;
        this.cgiHandler = cgiHandler;
        this.clientKey = clientKey;
    }

    public HttpResponse route() {
        if (request == null || request.getPath() == null || request.getMethod() == null) {
            return HttpResponse.ErrorResponse(400, "Bad Request",
                    "Invalid HTTP request", errorPage(400));
        }

        if (request.getBodyFile() != null) {
            try {
                long size = Files.size(request.getBodyFile());
                if (size > config.clientMaxBodySize) {
                    return HttpResponse.ErrorResponse(413, "Payload Too Large",
                            "Request body exceeds maximum allowed size",
                            errorPage(413));
                }
            } catch (Exception e) {
                return HttpResponse.ErrorResponse(500, "Internal Server Error",
                        "Failed to read body file size",
                        errorPage(500));
            }
        }

        String path = stripQuery(request.getPath());
        String method = request.getMethod();

        RouteConfig matchedRoute = findBestRoute(config.routes, path);

        System.out.println("Routing request: " + method + " " + path + " Matched route: " + (matchedRoute == null ? "null" : matchedRoute.path));

        if (matchedRoute == null) {
            return HttpResponse.ErrorResponse(404, "Not Found",
                    "No matching route", errorPage(404));
        }

        if (matchedRoute.methods != null && !matchedRoute.methods.isEmpty()
                && !matchedRoute.methods.contains(method)) {
            return HttpResponse.ErrorResponse(405, "Method Not Allowed",
                    "Method not allowed for this route", errorPage(405));
        }

        if (matchedRoute.redirect != null) {
            HttpResponse redirectResponse = new HttpResponse(matchedRoute.redirect.code, "Redirect");
            redirectResponse.setHeaders("Location", matchedRoute.redirect.location);
            return redirectResponse;
        }

        // CGI handling - non-blocking
        if (matchedRoute.cgi != null) {
            try {
                cgiHandler.executeCGI(clientKey, matchedRoute, request, config.errorPages);
                // Return null - response ghadi yji later mn checkPendingCGI
                return null;
            } catch (Exception e) {
                return HttpResponse.ErrorResponse(500, "Internal Server Error",
                        "CGI execution failed: " + e.getMessage(), errorPage(500));
            }
        }

        if ("POST".equals(method) && matchedRoute.uploadDir != null) {
            return handlers.UploadHandler.handleUpload(matchedRoute, request, config.errorPages);
        }

        if ("DELETE".equals(method)) {
            return handlers.DeleteHandler.handleDelete(matchedRoute, request, config.errorPages);
        }

        if ("GET".equals(method) && matchedRoute.root != null) {
            if (Boolean.TRUE.equals(matchedRoute.directoryListing)) {
                RouteConfig effective = copyRoute(matchedRoute);
                effective.root = buildEffectiveRoot(matchedRoute.root, matchedRoute.path, path);
                System.out.println("Effective root for directory listing: " + effective.root);
                return StaticFileHandler.handle(request, effective, config.errorPages);
            }
            return StaticFileHandler.handle(request, matchedRoute, config.errorPages);
        }

        return HttpResponse.ErrorResponse(501, "Not Implemented",
                "Not implemented yet", errorPage(500));
    }

    private String errorPage(int code) {
        if (config == null || config.errorPages == null) {
            return null;
        }
        return config.errorPages.get(code);
    }

    private static RouteConfig findBestRoute(List<RouteConfig> routes, String reqPath) {
        if (routes == null || reqPath == null) {
            return null;
        }

        RouteConfig best = null;
        int bestLen = -1;

        for (RouteConfig r : routes) {
            if (r == null || r.path == null) {
                continue;
            }

            if (matchPath(reqPath, r.path)) {
                int len = r.path.length();
                if (len > bestLen) {
                    bestLen = len;
                    best = r;
                }
            }
        }
        return best;
    }

    private static boolean matchPath(String reqPath, String routePath) {
        if (reqPath == null || routePath == null) {
            return false;
        }

        if (routePath.equals("/")) {
            return true;
        }
        if (reqPath.equals(routePath)) {
            return true;
        }

        String p = routePath.endsWith("/") ? routePath : routePath + "/";
        return reqPath.startsWith(p);
    }

    private static String buildEffectiveRoot(String routeRoot, String routePath, String reqPath) {
        String base = routeRoot;
        if (base == null) {
            return null;
        }

        String rPath = routePath == null ? "" : routePath;
        String p = reqPath == null ? "" : reqPath;

        String rest;
        if ("/".equals(rPath)) {
            rest = p.startsWith("/") ? p.substring(1) : p;
        } else if (p.equals(rPath)) {
            rest = "";
        } else {
            String prefix = rPath.endsWith("/") ? rPath : rPath + "/";
            rest = p.startsWith(prefix) ? p.substring(prefix.length()) : "";
        }

        if (rest.isEmpty()) {
            return base;
        }
        return base.endsWith("/") ? (base + rest) : (base + "/" + rest);
    }

    private static RouteConfig copyRoute(RouteConfig r) {
        RouteConfig c = new RouteConfig();
        c.path = r.path;
        c.root = r.root;
        c.methods = r.methods;
        c.index = r.index;
        c.directoryListing = r.directoryListing;
        c.uploadDir = r.uploadDir;
        c.cgi = r.cgi;
        c.redirect = r.redirect;
        return c;
    }

    private static String stripQuery(String path) {
        int q = path.indexOf('?');
        return (q == -1) ? path : path.substring(0, q);
    }
}