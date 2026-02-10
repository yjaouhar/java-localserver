package utils.json;

import java.util.*;

public class AppConfig {

    public Timeouts timeouts = new Timeouts();
    // public Limits limits = new Limits();
    public List<ServerConfig> servers = new ArrayList<>();

    public static class Timeouts {

        public int headerMs = 10000;
        public int bodyMs = 30000;
        public int idleKeepAliveMs = 60000;
    }


    public static class ServerConfig {

        public String name;
        public String host;
        public List<Integer> ports = new ArrayList<>();
        public boolean defaultServer;
        public long clientMaxBodySize = 1048576L; // default 1 MB
        public Map<Integer, String> errorPages = new HashMap<>();
        public List<RouteConfig> routes = new ArrayList<>();
    }

    public static class RouteConfig {

        public String path;
        public String root;
        public List<String> methods = new ArrayList<>();
        public String index;
        public Boolean directoryListing;

        public String uploadDir;

        public CgiConfig cgi;
        public Redirect redirect;
    }

    public static class CgiConfig {

        public String extension;
        public String interpreter;
    }

    public static class Redirect {

        public int code;
        public String location;
    }
}
