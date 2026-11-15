package co.edu.corhuila.dlc.worker.application.model;

import java.util.Objects;

/** Minimum recipient data resolved per delivery; held in memory only and never printed. */
public record Recipient(String email, String displayName) {
    public Recipient {
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(displayName, "displayName");
    }

    @Override
    public String toString() {
        return "Recipient[redacted]";
    }
}
