package co.edu.corhuila.dlc.worker.application.model;

import java.util.Objects;

/** One email attachment, held in memory for a single delivery and never printed or stored. */
public record Attachment(String fileName, String contentType, byte[] content) {
    public Attachment {
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(contentType, "contentType");
        content = Objects.requireNonNull(content, "content").clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }

    @Override
    public String toString() {
        return "Attachment[" + contentType + ", content redacted]";
    }
}
