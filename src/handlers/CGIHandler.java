package handlers;

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

public class CGIHandler {

    private static final Path CGI_DIR = Paths.get("cgi-bin");
    private static final int TIMEOUT_SECONDS = 5;

    public static void handleCGI(UploadHandler.HttpRequest request) {
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Path scriptPath = Paths.get("cgi/script.py").normalize();

            if (!scriptPath.startsWith("cgi") || !Files.exists(scriptPath)) {
                System.out.println("404 Not Found: CGI script does not exist.");
                return;
            }

            ProcessBuilder pb = new ProcessBuilder("python3", scriptPath.toString());

            byte[] body = "abc".getBytes(StandardCharsets.UTF_8);

            pb.environment().put("REQUEST_METHOD", "POST");
            pb.environment().put("CONTENT_LENGTH", String.valueOf(body.length));
            pb.environment().put("PATH_INFO", "/test/path");

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
                System.out.println("504 Gateway Timeout");
                return;
            }

            String stdout = stdoutFuture.get(1, TimeUnit.SECONDS);
            String stderr = stderrFuture.get(1, TimeUnit.SECONDS);

            int exitCode = process.exitValue();

            if (exitCode != 0) {
                System.out.println("500 Internal Server Error");
                System.out.println("stderr:\n" + stderr);
                return;
            }

            System.out.println("=== CGI OUTPUT ===");
            System.out.println(stdout);

        } catch (Exception e) {
            System.out.println("500 Internal Server Error: " + e.getMessage());
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
