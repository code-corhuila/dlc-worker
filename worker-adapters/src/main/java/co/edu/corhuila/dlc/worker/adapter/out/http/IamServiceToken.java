package co.edu.corhuila.dlc.worker.adapter.out.http;

import co.edu.corhuila.dlc.worker.application.port.out.OwnerUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

/**
 * ADR-020: a service JWT issued by IAM for one audience through client credentials
 * (POST /internal/v1/service-tokens). Cached until two thirds of its lifetime, renewed after an owner rejects it.
 * The client secret is read from a mounted file on each renewal so it can rotate.
 */
public final class IamServiceToken implements ServiceToken {
    private final HttpClient http;
    private final URI endpoint;
    private final String clientId;
    private final Path secretFile;
    private final String body;
    private final Duration timeout;
    private final Clock clock;
    private String cached;
    private Instant renewAt = Instant.MIN;

    public IamServiceToken(String iamUrl, String clientId, Path secretFile, String audience, String scope,
                           Duration timeout, Clock clock) {
        this.endpoint = URI.create(Objects.requireNonNull(iamUrl, "iamUrl").replaceAll("/+$", "")
            + "/internal/v1/service-tokens");
        this.clientId = Objects.requireNonNull(clientId, "clientId");
        this.secretFile = Objects.requireNonNull(secretFile, "secretFile");
        try {
            this.body = OwnerClient.JSON.writeValueAsString(Map.of("audience", audience, "scope", scope));
        } catch (IOException impossible) {
            throw new IllegalArgumentException(impossible);
        }
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public synchronized String current() {
        Instant now = clock.instant();
        if (cached != null && now.isBefore(renewAt)) return cached;
        try {
            String secret = Files.readString(secretFile).strip();
            String basic = Base64.getEncoder().encodeToString((clientId + ":" + secret)
                .getBytes(StandardCharsets.UTF_8));
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Authorization", "Basic " + basic).header("Content-Type", "application/json")
                .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw unavailable();
            JsonNode node = OwnerClient.JSON.readTree(response.body());
            JsonNode token = node.get("accessToken");
            JsonNode expiresIn = node.get("expiresIn");
            if (token == null || !token.isTextual() || token.asText().isBlank()
                || expiresIn == null || !expiresIn.canConvertToLong() || expiresIn.asLong() < 3) {
                throw unavailable();
            }
            cached = token.asText();
            renewAt = now.plusSeconds(expiresIn.asLong() * 2 / 3);
            return cached;
        } catch (IOException unreachable) {
            throw unavailable();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
    }

    @Override
    public synchronized void invalidate() {
        cached = null;
    }

    private static OwnerUnavailableException unavailable() {
        return new OwnerUnavailableException("SERVICE_TOKEN_UNAVAILABLE");
    }
}
