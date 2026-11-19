import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.math.BigDecimal;

public class Morrow {
    private static final int MAX_BODY_BYTES = 16 * 1024;
    private static final Set<String> FIELDS = Set.of("description", "category", "type", "amount", "date");
    private static final Set<String> EXPENSE_CATEGORIES = Set.of(
            "Food & dining", "Shopping", "Transport", "Bills & utilities",
            "Entertainment", "Health & wellness", "Other");
    private static final Set<String> INCOME_CATEGORIES = Set.of("Salary", "Freelance", "Other income");
    private static final String HEADER = "id\tdescription_base64\tcategory_base64\ttype\tamount\tdate";
    private static final Path PUBLIC_DIR = Path.of("public");

    private record Transaction(String id, String description, String category, String type,
                               BigDecimal amount, LocalDate date) { }

    private static final class ApiException extends Exception {
        final int status;

        ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private static final class Store {
        private final Path directory = Path.of("data");
        private final Path file = directory.resolve("transactions.tsv");
        private List<Transaction> transactions;

        Store() throws IOException {
            Files.createDirectories(directory);
            if (Files.exists(file)) {
                transactions = readFile();
            } else {
                transactions = sampleTransactions();
                writeFile(transactions);
            }
        }

        synchronized List<Transaction> all() {
            return List.copyOf(transactions);
        }

        synchronized Transaction create(Transaction input) throws IOException {
            Transaction saved = new Transaction(UUID.randomUUID().toString(), input.description(),
                    input.category(), input.type(), input.amount(), input.date());
            List<Transaction> next = new ArrayList<>(transactions);
            next.add(saved);
            writeFile(next);
            transactions = next;
            return saved;
        }

        synchronized Transaction update(String id, Transaction input) throws IOException, ApiException {
            int index = indexOf(id);
            if (index < 0) {
                throw new ApiException(404, "Transaction not found");
            }
            Transaction saved = new Transaction(id, input.description(), input.category(), input.type(),
                    input.amount(), input.date());
            List<Transaction> next = new ArrayList<>(transactions);
            next.set(index, saved);
            writeFile(next);
            transactions = next;
            return saved;
        }

        synchronized void delete(String id) throws IOException, ApiException {
            int index = indexOf(id);
            if (index < 0) {
                throw new ApiException(404, "Transaction not found");
            }
            List<Transaction> next = new ArrayList<>(transactions);
            next.remove(index);
            writeFile(next);
            transactions = next;
        }

        private int indexOf(String id) {
            for (int i = 0; i < transactions.size(); i++) {
                if (transactions.get(i).id().equals(id)) {
                    return i;
                }
            }
            return -1;
        }

        private List<Transaction> readFile() throws IOException {
            List<Transaction> result = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                int lineNumber = 0;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    if (lineNumber == 1 && line.equals(HEADER)) {
                        continue;
                    }
                    String[] parts = line.split("\t", -1);
                    try {
                        if (parts.length != 6 || !UUID.fromString(parts[0]).toString().equals(parts[0])
                                || !ids.add(parts[0])) {
                            throw new IllegalArgumentException("Invalid transaction ID or columns");
                        }
                        Map<String, String> fields = Map.of(
                                "description", decodeStoredText(parts[1]),
                                "category", decodeStoredText(parts[2]),
                                "type", parts[3], "amount", parts[4], "date", parts[5]);
                        Transaction validated = validate(fields);
                        result.add(new Transaction(parts[0], validated.description(), validated.category(),
                                validated.type(), validated.amount(), validated.date()));
                    } catch (IllegalArgumentException | ApiException e) {
                        throw new IOException("Invalid transaction data at line " + lineNumber, e);
                    }
                }
            }
            return result;
        }

        private void writeFile(List<Transaction> items) throws IOException {
            Path temporary = Files.createTempFile(directory, "transactions-", ".tmp");
            try {
                try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                    writer.write(HEADER);
                    writer.newLine();
                    for (Transaction item : items) {
                        writer.write(item.id());
                        writer.write('\t');
                        writer.write(encodeStoredText(item.description()));
                        writer.write('\t');
                        writer.write(encodeStoredText(item.category()));
                        writer.write('\t');
                        writer.write(item.type());
                        writer.write('\t');
                        writer.write(item.amount().toPlainString());
                        writer.write('\t');
                        writer.write(item.date().toString());
                        writer.newLine();
                    }
                }
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        int port = 8080;
        String configuredPort = System.getenv("PORT");
        if (configuredPort != null && !configuredPort.isBlank()) {
            try {
                port = Integer.parseInt(configuredPort);
                if (port < 0 || port > 65535) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("PORT must be an integer from 0 to 65535");
            }
        }

        Store store = new Store();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 64);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        server.setExecutor(executor);
        server.createContext("/", exchange -> handle(exchange, store));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            executor.shutdown();
        }));
        server.start();
        System.out.println("Morrow listening at http://localhost:" + server.getAddress().getPort());
    }

    private static void handle(HttpExchange exchange, Store store) {
        String path = exchange.getRequestURI().getRawPath();
        boolean api = path.equals("/api") || path.startsWith("/api/");
        try {
            requireLocalOrigin(exchange);
            byte[] body = readBody(exchange);
            String method = exchange.getRequestMethod();
            if (path.equals("/api/transactions")) {
                if (method.equals("GET")) {
                    List<Transaction> items = new ArrayList<>(store.all());
                    items.sort(Comparator.comparing(Transaction::date).reversed());
                    StringBuilder json = new StringBuilder("[");
                    for (int i = 0; i < items.size(); i++) {
                        if (i > 0) json.append(',');
                        json.append(transactionJson(items.get(i)));
                    }
                    json.append(']');
                    send(exchange, 200, "application/json; charset=utf-8", json.toString());
                } else if (method.equals("POST")) {
                    Transaction item = store.create(validate(parseForm(exchange, body)));
                    send(exchange, 201, "application/json; charset=utf-8", transactionJson(item));
                } else {
                    methodNotAllowed(exchange, "GET, POST");
                }
            } else if (path.startsWith("/api/transactions/")
                    && path.length() > "/api/transactions/".length()
                    && path.indexOf('/', "/api/transactions/".length()) < 0) {
                String id = path.substring("/api/transactions/".length());
                if (method.equals("PUT")) {
                    Transaction item = store.update(id, validate(parseForm(exchange, body)));
                    send(exchange, 200, "application/json; charset=utf-8", transactionJson(item));
                } else if (method.equals("DELETE")) {
                    store.delete(id);
                    exchange.getResponseHeaders().set("Cache-Control", "no-store");
                    exchange.sendResponseHeaders(204, -1);
                } else {
                    methodNotAllowed(exchange, "PUT, DELETE");
                }
            } else if (path.equals("/api/export")) {
                if (method.equals("GET")) {
                    exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"transactions.csv\"");
                    send(exchange, 200, "text/csv; charset=utf-8", exportCsv(store.all()));
                } else {
                    methodNotAllowed(exchange, "GET");
                }
            } else if (api) {
                throw new ApiException(404, "Not found");
            } else {
                serveStatic(exchange, path, method);
            }
        } catch (ApiException e) {
            try {
                if (api) {
                    sendError(exchange, e.status, e.getMessage());
                } else {
                    send(exchange, e.status, "text/plain; charset=utf-8", e.getMessage());
                }
            } catch (IOException ignored) {
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("Request failed: " + e.getMessage());
            try {
                if (api) {
                    sendError(exchange, 500, "Internal server error");
                } else {
                    send(exchange, 500, "text/plain; charset=utf-8", "Internal server error");
                }
            } catch (IOException ignored) {
            }
        } finally {
            exchange.close();
        }
    }

    private static void requireLocalOrigin(HttpExchange exchange) throws ApiException {
        String host = exchange.getRequestHeaders().getFirst("Host");
        int port = exchange.getLocalAddress().getPort();
        boolean localHost = host != null && (host.equalsIgnoreCase("localhost:" + port)
                || host.equalsIgnoreCase("127.0.0.1:" + port)
                || (port == 80 && (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1"))));
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        String fetchSite = exchange.getRequestHeaders().getFirst("Sec-Fetch-Site");
        if (!localHost || (origin != null && !origin.equalsIgnoreCase("http://" + host))
                || (origin == null && "cross-site".equalsIgnoreCase(fetchSite))) {
            throw new ApiException(403, "Only requests from this local application are allowed");
        }
    }

    private static byte[] readBody(HttpExchange exchange) throws IOException, ApiException {
        String length = exchange.getRequestHeaders().getFirst("Content-Length");
        if (length != null) {
            try {
                long declared = Long.parseLong(length);
                if (declared < 0) throw new NumberFormatException();
                if (declared > MAX_BODY_BYTES) throw new ApiException(413, "Request body too large");
            } catch (NumberFormatException e) {
                throw new ApiException(400, "Invalid Content-Length");
            }
        }
        try (InputStream input = exchange.getRequestBody()) {
            byte[] body = input.readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) throw new ApiException(413, "Request body too large");
            return body;
        }
    }

    private static Map<String, String> parseForm(HttpExchange exchange, byte[] body) throws ApiException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null) throw new ApiException(415, "Content-Type must be application/x-www-form-urlencoded");
        String[] mediaType = contentType.split(";");
        if (!mediaType[0].trim().equalsIgnoreCase("application/x-www-form-urlencoded")) {
            throw new ApiException(415, "Content-Type must be application/x-www-form-urlencoded");
        }
        for (int i = 1; i < mediaType.length; i++) {
            String parameter = mediaType[i].trim();
            int equals = parameter.indexOf('=');
            if (equals < 1) throw new ApiException(415, "Invalid Content-Type parameter");
            if (parameter.substring(0, equals).trim().equalsIgnoreCase("charset")) {
                String charset = parameter.substring(equals + 1).trim();
                if (charset.length() >= 2 && charset.startsWith("\"") && charset.endsWith("\"")) {
                    charset = charset.substring(1, charset.length() - 1);
                }
                if (!charset.equalsIgnoreCase("utf-8")) throw new ApiException(415, "Only UTF-8 is supported");
            }
        }
        Map<String, String> fields = new HashMap<>();
        int start = 0;
        while (start < body.length) {
            int end = start;
            while (end < body.length && body[end] != '&') end++;
            int equals = start;
            while (equals < end && body[equals] != '=') equals++;
            if (equals == start || equals == end) throw new ApiException(400, "Malformed form data");
            String key = decodeFormPart(body, start, equals);
            String value = decodeFormPart(body, equals + 1, end);
            if (!FIELDS.contains(key) || fields.putIfAbsent(key, value) != null) {
                throw new ApiException(400, "Unexpected or duplicate form field");
            }
            start = end + 1;
            if (end < body.length && start == body.length) throw new ApiException(400, "Malformed form data");
        }
        if (!fields.keySet().equals(FIELDS)) throw new ApiException(400, "All transaction fields are required");
        return fields;
    }

    private static String decodeFormPart(byte[] bytes, int start, int end) throws ApiException {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream(end - start);
        for (int i = start; i < end; i++) {
            int value = bytes[i] & 0xff;
            if (value == '+') {
                decoded.write(' ');
            } else if (value == '%') {
                if (i + 2 >= end) throw new ApiException(400, "Malformed form encoding");
                int high = hexDigit(bytes[++i]);
                int low = hexDigit(bytes[++i]);
                if (high < 0 || low < 0) throw new ApiException(400, "Malformed form encoding");
                decoded.write((high << 4) | low);
            } else {
                decoded.write(value);
            }
        }
        try {
            return decodeUtf8(decoded.toByteArray());
        } catch (CharacterCodingException e) {
            throw new ApiException(400, "Form data must be valid UTF-8");
        }
    }

    private static int hexDigit(byte value) {
        int c = value & 0xff;
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private static Transaction validate(Map<String, String> fields) throws ApiException {
        if (!fields.keySet().equals(FIELDS)) throw new ApiException(400, "All transaction fields are required");
        String description = fields.get("description").strip();
        if (description.isEmpty() || description.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))
                || description.codePointCount(0, description.length()) > 80
                || description.codePoints().anyMatch(Character::isISOControl)) {
            throw new ApiException(400, "Description must be nonblank, at most 80 characters, and contain no control characters");
        }
        String type = fields.get("type").strip();
        String category = fields.get("category").strip();
        if (!type.equals("income") && !type.equals("expense")) {
            throw new ApiException(400, "Type must be income or expense");
        }
        if (!(type.equals("income") ? INCOME_CATEGORIES : EXPENSE_CATEGORIES).contains(category)) {
            throw new ApiException(400, "Category does not match transaction type");
        }
        String amountText = fields.get("amount").strip();
        if (!amountText.matches("[0-9]+(?:\\.[0-9]{1,2})?")) {
            throw new ApiException(400, "Amount must be positive dollars with at most two decimal places");
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(amountText).setScale(2);
            if (amount.signum() <= 0 || amount.compareTo(new BigDecimal("999999999.99")) > 0) throw new NumberFormatException();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new ApiException(400, "Amount must be between 0.01 and 999999999.99");
        }
        String dateText = fields.get("date").strip();
        if (!dateText.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            throw new ApiException(400, "Date must be a valid ISO date");
        }
        LocalDate date;
        try {
            date = LocalDate.parse(dateText);
        } catch (DateTimeParseException e) {
            throw new ApiException(400, "Date must be a valid ISO date");
        }
        return new Transaction("", description, category, type, amount, date);
    }

    private static void serveStatic(HttpExchange exchange, String path, String method) throws IOException, ApiException {
        String name;
        String contentType;
        switch (path) {
            case "/" -> { name = "index.html"; contentType = "text/html; charset=utf-8"; }
            case "/styles.css" -> { name = "styles.css"; contentType = "text/css; charset=utf-8"; }
            case "/app.js" -> { name = "app.js"; contentType = "application/javascript; charset=utf-8"; }
            default -> throw new ApiException(404, "Not found");
        }
        if (!method.equals("GET")) {
            exchange.getResponseHeaders().set("Allow", "GET");
            throw new ApiException(405, "Method not allowed");
        }
        Path file = PUBLIC_DIR.resolve(name);
        if (!Files.isRegularFile(file)) throw new ApiException(404, "Not found");
        sendBytes(exchange, 200, contentType, Files.readAllBytes(file));
    }

    private static void methodNotAllowed(HttpExchange exchange, String allow) throws ApiException {
        exchange.getResponseHeaders().set("Allow", allow);
        throw new ApiException(405, "Method not allowed");
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", "{\"error\":" + jsonString(message) + "}");
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        sendBytes(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendBytes(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static String transactionJson(Transaction item) {
        return "{\"id\":" + jsonString(item.id()) + ",\"description\":" + jsonString(item.description())
                + ",\"category\":" + jsonString(item.category()) + ",\"type\":" + jsonString(item.type())
                + ",\"amount\":" + item.amount().toPlainString() + ",\"date\":" + jsonString(item.date().toString()) + "}";
    }

    private static String jsonString(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 0x20 || c == '\u2028' || c == '\u2029') {
                        result.append(String.format("\\u%04x", (int) c));
                    } else {
                        result.append(c);
                    }
                }
            }
        }
        return result.append('"').toString();
    }

    private static String exportCsv(List<Transaction> items) {
        StringBuilder csv = new StringBuilder("id,description,category,type,amount,date\r\n");
        for (Transaction item : items) {
            csv.append(csvCell(item.id())).append(',')
                    .append(csvCell(item.description())).append(',')
                    .append(csvCell(item.category())).append(',')
                    .append(csvCell(item.type())).append(',')
                    .append(item.amount().toPlainString()).append(',')
                    .append(csvCell(item.date().toString())).append("\r\n");
        }
        return csv.toString();
    }

    private static String csvCell(String value) {
        int offset = 0;
        while (offset < value.length()) {
            int c = value.codePointAt(offset);
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c) && c != 0xfeff) break;
            offset += Character.charCount(c);
        }
        boolean dangerous = !value.isEmpty() && (value.charAt(0) == '\t' || value.charAt(0) == '\r' || value.charAt(0) == '\n');
        if (offset < value.length() && "=+-@".indexOf(value.charAt(offset)) >= 0) dangerous = true;
        String safe = dangerous ? "'" + value : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private static String encodeStoredText(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeStoredText(String value) {
        try {
            return decodeUtf8(Base64.getDecoder().decode(value));
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Invalid UTF-8 in transaction data", e);
        }
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static List<Transaction> sampleTransactions() {
        List<Transaction> items = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int monthsAgo = 5; monthsAgo >= 0; monthsAgo--) {
            YearMonth month = YearMonth.from(today).minusMonths(monthsAgo);
            int lastDay = monthsAgo == 0 ? today.getDayOfMonth() : month.lengthOfMonth();
            addSample(items, month, lastDay, 1, "Monthly salary", "Salary", "income", "5200.00");
            addSample(items, month, lastDay, 3, "Apartment rent", "Bills & utilities", "expense", "1550.00");
            addSample(items, month, lastDay, 5, "Green Market groceries", "Food & dining", "expense", monthsAgo % 2 == 0 ? "126.45" : "118.90");
            addSample(items, month, lastDay, 7, "Metro pass", "Transport", "expense", "92.00");
            addSample(items, month, lastDay, 9, "Internet & phone", "Bills & utilities", "expense", "104.00");
            addSample(items, month, lastDay, 11, "Morning coffee", "Food & dining", "expense", "8.75");
            addSample(items, month, lastDay, 13, "Freelance project", "Freelance", "income", monthsAgo % 2 == 0 ? "650.00" : "480.00");
            addSample(items, month, lastDay, 16, "Neighborhood dinner", "Food & dining", "expense", "64.20");
            addSample(items, month, lastDay, 18, "Bookshop", "Shopping", "expense", "38.50");
            addSample(items, month, lastDay, 20, "Movie night", "Entertainment", "expense", "32.00");
            addSample(items, month, lastDay, 23, "Pharmacy", "Health & wellness", "expense", "24.95");
            addSample(items, month, lastDay, 26, "Monthly giving", "Other", "expense", "35.00");
        }
        return items;
    }

    private static void addSample(List<Transaction> items, YearMonth month, int lastDay, int day,
                                  String description, String category, String type, String amount) {
        // Keep a few essentials visible early in the month without pulling every later activity forward.
        if (day > lastDay && day > 7) return;
        items.add(new Transaction(UUID.randomUUID().toString(), description, category, type,
                new BigDecimal(amount), month.atDay(Math.min(day, lastDay))));
    }
}
