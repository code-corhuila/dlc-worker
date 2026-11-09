package co.edu.corhuila.dlc.worker.adapter.out.http;

import co.edu.corhuila.dlc.worker.application.port.out.OwnerUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * Shared transport of the owner clients: bearer service token read per call, X-Correlation-Id from the logging
 * context, bounded timeouts and no redirects. Unreachable owners, 5xx and rejected credentials are transient for
 * the message (it is retried and finally dead-lettered); callers map the contract statuses they expect.
 */
final class OwnerClient {
    static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private final HttpClient http;
    private final String baseUrl;
    private final ServiceToken token;
    private final Duration timeout;
    private final String owner;

    OwnerClient(String owner, String baseUrl, ServiceToken token, Duration timeout) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl").replaceAll("/+$", "");
        this.token = Objects.requireNonNull(token, "token");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    HttpResponse<String> get(String path) {
        return send(request(path).header("Accept", "application/json").GET(), HttpResponse.BodyHandlers.ofString());
    }

    /** A binary representation such as a PDF; same transport rules as the JSON calls. */
    HttpResponse<byte[]> getBytes(String path, String accept) {
        return send(request(path).header("Accept", accept).GET(), HttpResponse.BodyHandlers.ofByteArray());
    }

    HttpResponse<String> post(String path, String json) {
        return send(request(path).header("Accept", "application/json").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout)
                .header("Authorization", "Bearer " + token.current());
        String correlation = MDC.get("correlationId");
        if (correlation != null && UUID_TEXT.matcher(correlation).matches()) {
            builder.header("X-Correlation-Id", correlation);
        }
        return builder;
    }

    private <T> HttpResponse<T> send(HttpRequest.Builder request, HttpResponse.BodyHandler<T> handler) {
        HttpResponse<T> response;
        try {
            response = http.send(request.build(), handler);
        } catch (IOException unreachable) {
            throw unavailable("UNAVAILABLE");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable("UNAVAILABLE");
        }
        if (response.statusCode() == 401) token.invalidate();
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw unavailable("REJECTED_CREDENTIALS");
        }
        return response;
    }

    OwnerUnavailableException unavailable(String reason) {
        return new OwnerUnavailableException(owner + "_" + reason);
    }

    /** Strict reading of a 200 body; any deviation from the contract is treated as an owner failure. */
    JsonNode body(HttpResponse<String> response) {
        try {
            JsonNode node = JSON.readTree(response.body());
            if (node == null || !node.isObject()) throw unavailable("CONTRACT_MISMATCH");
            return node;
        } catch (IOException malformed) {
            throw unavailable("CONTRACT_MISMATCH");
        }
    }

    String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw unavailable("CONTRACT_MISMATCH");
        return value.asText();
    }

    UUID uuid(JsonNode node, String field) {
        String value = text(node, field);
        if (!UUID_TEXT.matcher(value).matches()) throw unavailable("CONTRACT_MISMATCH");
        return UUID.fromString(value);
    }

    Instant instant(JsonNode node, String field) {
        try {
            return OffsetDateTime.parse(text(node, field)).toInstant();
        } catch (RuntimeException notRfc3339) {
            throw unavailable("CONTRACT_MISMATCH");
        }
    }

    RuntimeException unexpected(HttpResponse<?> response) {
        return unavailable(response.statusCode() >= 500 ? "UNAVAILABLE" : "CONTRACT_MISMATCH");
    }
}
