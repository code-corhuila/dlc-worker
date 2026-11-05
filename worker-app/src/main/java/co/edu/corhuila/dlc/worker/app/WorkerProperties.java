package co.edu.corhuila.dlc.worker.app;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Worker settings (see README for the environment variables). Secrets come from files or the environment. */
@ConfigurationProperties("worker")
public record WorkerProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue Consumer consumer,
        @DefaultValue Database database,
        @DefaultValue Rabbit rabbitmq,
        @DefaultValue Owner appointments,
        @DefaultValue Owner patients,
        @DefaultValue("PT3S") Duration ownerTimeout,
        @DefaultValue Smtp smtp,
        @DefaultValue("PT2S") Duration emailBackoff) {

    public record Consumer(@DefaultValue("true") boolean autoStartup) { }

    public record Database(String url, String username, String password) {
        @Override
        public String toString() {
            return "Database[url=" + url + ", username=" + username + ", password redacted]";
        }
    }

    public record Rabbit(String uri, @DefaultValue("appointments.events") String exchange,
            @DefaultValue("worker") String queuePrefix, @DefaultValue("5") int attempts,
            @DefaultValue("PT1S") Duration backoff) {
        @Override
        public String toString() {
            return "Rabbit[exchange=" + exchange + ", queuePrefix=" + queuePrefix + ", uri redacted]";
        }
    }

    public record Owner(String internalUrl, String serviceTokenFile) { }

    public record Smtp(String host, @DefaultValue("587") int port, String username, String passwordFile,
            @DefaultValue("true") boolean startTls, String from, @DefaultValue("PT10S") Duration timeout) { }

    static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required setting " + name + ".");
        }
        return value;
    }
}
