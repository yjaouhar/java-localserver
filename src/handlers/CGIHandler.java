package handlers;

import http.HttpRequest;
import http.HttpResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import utils.json.AppConfig.RouteConfig;

public class CGIHandler {

    private static final int TIMEOUT_SECONDS = 5;

    public static HttpResponse handleCGI(RouteConfig rout, HttpRequest request) {
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {

            String path = request.getPath();
            Path scriptPath = Paths.get(rout.root, path).normalize();

            if (!scriptPath.startsWith(rout.root) || !Files.exists(scriptPath)) {
                return HttpResponse.ErrorResponse(404, "Not Found", "CGI script not found");
            }

            ProcessBuilder pb = new ProcessBuilder("python3", scriptPath.toString());

            byte[] body = request.getBody().getBytes(StandardCharsets.UTF_8);

            pb.environment().put("REQUEST_METHOD", request.getMethod());
            pb.environment().put("CONTENT_LENGTH", String.valueOf(body.length));
            pb.environment().put("PATH_INFO", scriptPath.toString());

            Process process = pb.start();

            Future<String> stdoutFuture = executor.submit(()
                    -> readFully(process.getInputStream())
            );

            Future<String> stderrFuture = executor.submit(()
                    -> readFully(process.getErrorStream())
            );

            try {
                OutputStream os = process.getOutputStream();
                os.write(body);
                os.flush();
                os.close();
            } catch (IOException e) {
                if (!isIgnorableStdinError(e)) {
                    throw e;
                } else {
                    System.out.println("Ignored stdin error: " + e.getMessage());
                }
            }

            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return HttpResponse.ErrorResponse(504, "Gateway Timeout", "CGI script timed out");
            }

            String stdout = stdoutFuture.get(1, TimeUnit.SECONDS);
            String stderr = stderrFuture.get(1, TimeUnit.SECONDS);

            int exitCode = process.exitValue();

            if (exitCode != 0) {
                return HttpResponse.ErrorResponse(500, "Internal Server Error", "CGI script error");
            }
            System.out.println("CGI Error: " + stderr);
            return HttpResponse.successResponse(200, "OK", stdout);
        } catch (Exception e) {
            return HttpResponse.ErrorResponse(500, "Internal Server Error", "CGI execution failed: " + e.getMessage());
        } finally {
            executor.shutdownNow();
        }
    }

    private static String readFully(InputStream is) throws IOException {
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static boolean isIgnorableStdinError(IOException e) {
        String m = e.getMessage();
        if (m == null) {
            return false;
        }
        m = m.toLowerCase();
        return m.contains("broken pipe")
                || m.contains("stream closed")
                || m.contains("epipe");
    }
}
