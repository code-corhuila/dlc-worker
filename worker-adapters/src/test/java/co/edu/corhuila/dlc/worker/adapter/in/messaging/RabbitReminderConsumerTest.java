package co.edu.corhuila.dlc.worker.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.dlc.worker.application.model.ReminderRequest;
import co.edu.corhuila.dlc.worker.application.port.out.OwnerUnavailableException;
import co.edu.corhuila.dlc.worker.application.usecase.DeliverReminder.Result;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/** Acknowledgement, retry and dead-letter decisions of the consumer, without a broker. */
class RabbitReminderConsumerTest {
    private final List<ReminderRequest> handled = new ArrayList<>();
    private final List<Duration> pauses = new ArrayList<>();
    private final List<String> correlations = new ArrayList<>();
    private int failuresLeft;

    private final Function<ReminderRequest, Result> handler = request -> {
        handled.add(request);
        correlations.add(MDC.get("correlationId"));
        if (failuresLeft-- > 0) throw new OwnerUnavailableException("APPOINTMENTS_UNAVAILABLE");
        return Result.SENT_REPORTED;
    };

    private RabbitReminderConsumer consumer(int attempts) {
        return new RabbitReminderConsumer(null, RabbitReminderConsumer.Topology.defaults(), new ReminderEventReader(),
                handler, attempts, Duration.ofSeconds(1), pauses::add);
    }

    private static Message message(String body) {
        return new Message(body.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }

    @Test
    void aValidEventIsHandledOnceWithItsCorrelationAndThenAcknowledged() {
        consumer(5).onMessage(message(ReminderEventReaderTest.valid()));

        assertThat(handled).hasSize(1);
        assertThat(correlations).containsExactly(ReminderEventReaderTest.CORRELATION);
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void anInvalidEventIsDeadLetteredWithoutHandling() {
        assertThatThrownBy(() -> consumer(5).onMessage(message("{\"eventType\":\"AppointmentReminderRequested\"}")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class).hasMessageContaining("INVALID_EVENT");
        assertThat(handled).isEmpty();
    }

    @Test
    void aTransientFailureIsRetriedWithExponentialBackoff() {
        failuresLeft = 2;

        consumer(5).onMessage(message(ReminderEventReaderTest.valid()));

        assertThat(handled).hasSize(3);
        assertThat(pauses).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Test
    void exhaustedRetriesAreDeadLettered() {
        failuresLeft = 10;

        assertThatThrownBy(() -> consumer(3).onMessage(message(ReminderEventReaderTest.valid())))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class).hasMessageContaining("RETRIES_EXHAUSTED");
        assertThat(handled).hasSize(3);
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void topologyNamesFollowTheConsumerConvention() {
        RabbitReminderConsumer.Topology topology = RabbitReminderConsumer.Topology.defaults();

        assertThat(topology.exchange()).isEqualTo("appointments.events");
        assertThat(topology.queue()).isEqualTo("worker.appointment-reminder-requested");
        assertThat(topology.deadLetterQueue()).isEqualTo("worker.appointment-reminder-requested.dlq");
        assertThat(topology.deadLetterExchange()).isEqualTo("worker.consumers.dlx");
    }
}
