package co.edu.corhuila.dlc.worker.adapter.out.http;

import co.edu.corhuila.dlc.worker.application.port.out.OwnerUnavailableException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A short-lived service JWT provisioned as a one-line file (the platform convention: mounted read-only under
 * /run/secrets and renewed by a credential provider). Reread on every call, so an atomic replacement rotates it.
 * The worker holds no signing key.
 */
public final class FileServiceToken {
    private final Path file;

    public FileServiceToken(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    public String current() {
        try {
            String token = Files.readString(file).strip();
            if (token.isEmpty() || token.chars().anyMatch(Character::isWhitespace)) throw unavailable();
            return token;
        } catch (IOException missing) {
            throw unavailable();
        }
    }

    private static OwnerUnavailableException unavailable() {
        return new OwnerUnavailableException("SERVICE_TOKEN_UNAVAILABLE");
    }
}
