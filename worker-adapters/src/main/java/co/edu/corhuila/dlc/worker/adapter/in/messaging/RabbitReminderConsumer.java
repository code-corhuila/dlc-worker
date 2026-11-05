package co.edu.corhuila.dlc.worker.adapter.in.messaging;

import co.edu.corhuila.dlc.worker.application.model.ReminderRequest;
import co.edu.corhuila.dlc.worker.application.port.out.Backoff;
import co.edu.corhuila.dlc.worker.application.usecase.DeliverReminder.Result;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionListener;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;

/**
 * Consumer of AppointmentReminderRequested. Appointments only declares its exchange, so the worker declares its own
 * durable queue, its binding by the event name and a dead-letter queue through a direct DLX (the topology Appointments
 * uses for its consumers, D16). The message is acknowledged only after the handler has recorded the result in the
 * worker store. Owner or store outages are retried with bounded exponential backoff; invalid or exhausted messages are
 * dead-lettered and never requeued. Logs carry identifiers and codes only.
 */
public final class RabbitReminderConsumer {
    private static final Logger LOG = LoggerFactory.getLogger(RabbitReminderConsumer.class);
    private final CachingConnectionFactory connections;
    private final Topology topology;
    private final ReminderEventReader reader;
    private final Function<ReminderRequest, Result> handler;
    private final int attempts;
    private final Duration backoff;
    private final Backoff pause;

    public RabbitReminderConsumer(CachingConnectionFactory connections, Topology topology, ReminderEventReader reader,
            Function<ReminderRequest, Result> handler, int attempts, Duration backoff, Backoff pause) {
        this.connections = connections;
        this.topology = Objects.requireNonNull(topology, "topology");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.handler = Objects.requireNonNull(handler, "handler");
        if (attempts < 1 || attempts > 10) throw new IllegalArgumentException("Attempts must be 1 to 10.");
        this.attempts = attempts;
        this.backoff = Objects.requireNonNull(backoff, "backoff");
        this.pause = Objects.requireNonNull(pause, "pause");
    }

    /** Producer exchange and the worker's queue prefix. */
    public record Topology(String exchange, String prefix) {
        public Topology {
            Objects.requireNonNull(exchange, "exchange");
            Objects.requireNonNull(prefix, "prefix");
        }

        public static Topology defaults() {
            return new Topology("appointments.events", "worker");
        }

        public String queue() {
            return prefix + ".appointment-reminder-requested";
        }

        public String deadLetterQueue() {
            return queue() + ".dlq";
        }

        public String deadLetterExchange() {
            return prefix + ".consumers.dlx";
        }
    }

    /** Declares the topology on every new connection, so a broker that starts later or loses state is repaired. */
    public SimpleMessageListenerContainer container() {
        Objects.requireNonNull(connections, "connections");
        connections.addConnectionListener((ConnectionListener) connection -> {
            try (Channel channel = connection.createChannel(false)) {
                declare(channel);
            } catch (IOException | TimeoutException failed) {
                throw new AmqpIOException(new IOException("Consumer topology declaration failed."));
            }
        });
        var container = new SimpleMessageListenerContainer(connections);
        container.setQueueNames(topology.queue());
        container.setAcknowledgeMode(AcknowledgeMode.AUTO);
        container.setDefaultRequeueRejected(false);
        container.setMissingQueuesFatal(false);
        container.setPrefetchCount(1);
        container.setRecoveryInterval(5000);
        container.setMessageListener(this::onMessage);
        return container;
    }

    void onMessage(Message message) {
        ReminderRequest request;
        try {
            request = reader.read(message.getBody());
        } catch (InvalidEventException invalid) {
            LOG.warn("Reminder event dead-lettered: {}", invalid.getMessage());
            throw new AmqpRejectAndDontRequeueException(invalid.getMessage());
        }
        MDC.put("correlationId", request.correlationId().toString());
        MDC.put("eventId", request.eventId().toString());
        MDC.put("deliveryId", request.deliveryId().toString());
        try {
            for (int attempt = 1; ; attempt++) {
                try {
                    Result result = handler.apply(request);
                    LOG.info("Reminder event handled: {}", result);
                    return;
                } catch (RuntimeException transientFailure) {
                    if (attempt >= attempts) {
                        LOG.warn("Reminder event dead-lettered after {} attempts: {}", attempts,
                                transientFailure.getMessage());
                        throw new AmqpRejectAndDontRequeueException("RETRIES_EXHAUSTED");
                    }
                    pause.await(backoff.multipliedBy(1L << (attempt - 1)));
                }
            }
        } finally {
            MDC.remove("correlationId");
            MDC.remove("eventId");
            MDC.remove("deliveryId");
        }
    }

    private void declare(Channel channel) throws IOException {
        channel.exchangeDeclare(topology.exchange(), "topic", true);
        channel.exchangeDeclare(topology.deadLetterExchange(), "direct", true);
        channel.queueDeclare(topology.deadLetterQueue(), true, false, false, null);
        channel.queueBind(topology.deadLetterQueue(), topology.deadLetterExchange(), topology.queue());
        channel.queueDeclare(topology.queue(), true, false, false, Map.of("x-dead-letter-exchange",
                topology.deadLetterExchange(), "x-dead-letter-routing-key", topology.queue()));
        channel.queueBind(topology.queue(), topology.exchange(), ReminderEventReader.EVENT_TYPE);
    }
}
