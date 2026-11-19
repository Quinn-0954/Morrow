import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class IntegrationTest {
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private static final Pattern ID = Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([a-zA-Z0-9-]+)\\\"");
    private static Path root;
    private static Path temporary;
    private static int port;
    private static Process server;

    public static void main(String[] args) throws Exception {
        root = Path.of("").toAbsolutePath();
        temporary = Files.createTempDirectory("morrow-integration-");
        try {
            Files.createDirectory(temporary.resolve("public"));
            for (String asset : new String[]{"index.html", "styles.css", "app.js"}) {
                Files.copy(root.resolve("public").resolve(asset), temporary.resolve("public").resolve(asset));
            }
            try (ServerSocket socket = new ServerSocket(0)) {
                port = socket.getLocalPort();
            }
            startServer();
            check(request("GET", "/", null).body().contains("Morrow"), "The dashboard should be served");
            var seeded = request("GET", "/api/transactions", null);
            check(seeded.statusCode() == 200 && ID.matcher(seeded.body()).find(), "First launch should include sample data");

            Map<String, String> fields = new LinkedHashMap<>(Map.of(
                "description", "Integration test", "category", "Food & dining", "type", "expense",
                "amount", "12.50", "date", "2025-01-04"));
            var crossOrigin = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/transactions"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", "http://example.invalid")
                .POST(HttpRequest.BodyPublishers.ofString(form(fields))).build();
            check(CLIENT.send(crossOrigin, HttpResponse.BodyHandlers.ofString()).statusCode() == 403, "Cross-origin changes should be rejected");
            var created = request("POST", "/api/transactions", form(fields));
            check(created.statusCode() == 201, "A valid transaction should be created");
            Matcher idMatcher = ID.matcher(created.body());
            check(idMatcher.find(), "The created transaction should have an ID");
            String id = idMatcher.group(1);
            fields.put("description", "Updated test");
            fields.put("amount", "25.30");
            check(request("PUT", "/api/transactions/" + id, form(fields)).statusCode() == 200, "A transaction should be editable");

            for (String amount : new String[]{"0", "-1", "10.001", "1000000000.00", "not-a-number"}) {
                fields.put("amount", amount);
                check(clientError(request("POST", "/api/transactions", form(fields))), "Invalid amount should be rejected: " + amount);
            }
            fields.put("amount", "12.50");
            fields.put("date", "2025-02-30");
            check(clientError(request("POST", "/api/transactions", form(fields))), "Invalid dates should be rejected");
            fields.put("date", "2025-01-04");
            fields.put("category", "Salary");
            check(clientError(request("POST", "/api/transactions", form(fields))), "Mismatched categories should be rejected");
            fields.put("category", "Other");
            fields.put("description", "=2+2");
            var formula = request("POST", "/api/transactions", form(fields));
            check(formula.statusCode() == 201, "Formula-like text should be accepted as a description");
            String csv = request("GET", "/api/export", null).body();
            check(csv.contains("'=2+2") || csv.contains("\t=2+2"), "CSV export should neutralize spreadsheet formulas");

            stopServer();
            startServer();
            String reloaded = request("GET", "/api/transactions", null).body();
            check(reloaded.contains("Updated test") && reloaded.contains(id), "Edits should persist after restarting");
            check(request("DELETE", "/api/transactions/" + id, null).statusCode() == 204, "A transaction should be deletable");
            check(request("DELETE", "/api/transactions/" + id, null).statusCode() == 404, "A deleted transaction should not exist");

            String remaining = request("GET", "/api/transactions", null).body();
            Matcher remainingIds = ID.matcher(remaining);
            while (remainingIds.find()) {
                check(request("DELETE", "/api/transactions/" + remainingIds.group(1), null).statusCode() == 204, "Every transaction should be deletable");
            }
            stopServer();
            startServer();
            check(request("GET", "/api/transactions", null).body().trim().equals("[]"), "An empty ledger should stay empty after restarting");
            System.out.println("Integration tests passed.");
        } finally {
            stopServer();
            if (temporary != null && Files.exists(temporary)) {
                try (var paths = Files.walk(temporary)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void startServer() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(java, "--add-modules", "jdk.httpserver", root.resolve("src/Morrow.java").toString());
        builder.directory(temporary.toFile());
        builder.environment().put("PORT", Integer.toString(port));
        builder.redirectErrorStream(true);
        builder.redirectOutput(temporary.resolve("server.log").toFile());
        server = builder.start();
        for (int attempt = 0; attempt < 150; attempt++) {
            if (!server.isAlive()) throw new IOException("Server stopped during startup: " + Files.readString(temporary.resolve("server.log")));
            try {
                if (request("GET", "/api/transactions", null).statusCode() == 200) return;
            } catch (IOException ignored) {
                Thread.sleep(100);
            }
        }
        throw new IOException("Server did not start");
    }

    private static void stopServer() throws InterruptedException {
        if (server != null && server.isAlive()) {
            server.destroy();
            server.waitFor();
        }
        server = null;
    }

    private static HttpResponse<String> request(String method, String path, String body) throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(5));
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/x-www-form-urlencoded").method(method, HttpRequest.BodyPublishers.ofString(body));
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String form(Map<String, String> fields) {
        return fields.entrySet().stream().map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)).reduce((a, b) -> a + "&" + b).orElse("");
    }

    private static boolean clientError(HttpResponse<String> response) {
        return response.statusCode() >= 400 && response.statusCode() < 500;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
