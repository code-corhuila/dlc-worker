package co.edu.corhuila.dlc.worker.adapter.out.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An owner API double on a local port. Like the owners, it checks the bearer service token on every call:
 * subject {@code dlc-worker}, its audience and the operation's scope (the signature is the owner's concern).
 */
final class FakeOwner implements AutoCloseable {
    record Call(String method, String path, String authorization, String correlationId, String body) { }

    /** A canned reply; {@code contract} false marks a deliberately malformed one, never checked against the contract. */
    record Reply(int status, String body, boolean contract) {
        Reply(int status, String body) {
            this(status, body, true);
        }

        static Reply offContract(int status, String body) {
            return new Reply(status, body, false);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    // Loads the contract before any server starts, so its parsing never counts against a client timeout.
    private static final boolean CONTRACT_CHECKED = OwnerContract.ENABLED;
    private final HttpServer server;
    final List<Call> calls = new ArrayList<>();
    final Map<String, Reply> replies = new ConcurrentHashMap<>();
    final Map<String, String> requiredScopes = new ConcurrentHashMap<>();
    private final List<String> violations = new CopyOnWriteArrayList<>();
    private final String audience;

    FakeOwner(String audience) throws IOException {
        this.audience = audience;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            Call call = new Call(exchange.getRequestMethod(), path, authorization,
                    exchange.getRequestHeaders().getFirst("X-Correlation-Id"), body);
            calls.add(call);
            Reply reply = authorized(path, authorization) ? replies.getOrDefault(path, new Reply(500, ""))
                    : new Reply(403, "{\"code\":\"FORBIDDEN\"}");
            violations.addAll(OwnerContract.violations(call, reply,
                    exchange.getRequestHeaders().getFirst("Content-Type")));
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private boolean authorized(String path, String authorization) {
        String scope = requiredScopes.entrySet().stream().filter(e -> path.endsWith(e.getKey()))
                .map(Map.Entry::getValue).findFirst().orElse(null);
        if (scope == null || authorization == null || !authorization.startsWith("Bearer ")) return false;
        try {
            String[] parts = authorization.substring(7).split("\\.");
            JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
            return "dlc-worker".equals(claims.path("sub").asText()) && audience.equals(claims.path("aud").asText())
                    && List.of(claims.path("scope").asText().split(" ")).contains(scope);
        } catch (IOException | RuntimeException malformed) {
            return false;
        }
    }

    /** A token shaped like a short-lived service JWT; only its claims matter to this double. */
    static String token(String audience, String scope) {
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        String header = base64.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String claims = base64.encodeToString(("{\"iss\":\"dlc-services\",\"sub\":\"dlc-worker\",\"aud\":\""
                + audience + "\",\"scope\":\"" + scope + "\"}").getBytes(StandardCharsets.UTF_8));
        return header + "." + claims + ".signature";
    }

    static Path tokenFile(Path dir, String name, String token) throws IOException {
        return Files.writeString(dir.resolve(name), token + "\n");
    }

    /** Stops the double and fails the test when a call or reply broke the contract (DLC_DOCS_DIR set). */
    @Override
    public void close() {
        server.stop(0);
        if (!violations.isEmpty()) {
            List<String> found = List.copyOf(violations);
            violations.clear();
            throw new AssertionError("Owner contract violations: " + found);
        }
    }

    /** Stops the double without checking the contract. */
    void discard() {
        violations.clear();
        server.stop(0);
    }
}
