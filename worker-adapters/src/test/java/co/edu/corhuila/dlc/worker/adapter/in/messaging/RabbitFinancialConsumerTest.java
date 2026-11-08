package co.edu.corhuila.dlc.worker.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.dlc.worker.application.model.FinancialRequest;
import co.edu.corhuila.dlc.worker.application.port.out.OwnerUnavailableException;
import co.edu.corhuila.dlc.worker.application.usecase.DeliverFinancialEmail.Result;
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

/** Acknowledgement, retry and dead-letter decisions of the Billing consumer, without a broker. */
class RabbitFinancialConsumerTest {
    private final List<FinancialRequest> handled = new ArrayList<>();
    private final List<Duration> pauses = new ArrayList<>();
    private final List<String> correlations = new ArrayList<>();
    private int failuresLeft;

    private final Function<FinancialRequest, Result> handler = request -> {
        handled.add(request);
        correlations.add(MDC.get("correlationId"));
        if (failuresLeft-- > 0) throw new OwnerUnavailableException("BILLING_UNAVAILABLE");
        return Result.SENT;
    };

    private RabbitFinancialConsumer consumer(int attempts) {
        return new RabbitFinancialConsumer(null, RabbitFinancialConsumer.Topology.defaults(),
                new FinancialEventReader(), handler, attempts, Duration.ofSeconds(1), pauses::add);
    }

    private static Message message(String body) {
        return new Message(body.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }

    @Test
    void aValidEventIsHandledOnceWithItsCorrelationAndThenAcknowledged() {
        consumer(5).onMessage(message(FinancialEventReaderTest.issued()));

        assertThat(handled).hasSize(1);
        assertThat(correlations).containsExactly(FinancialEventReaderTest.CORRELATION);
        assertThat(MDC.get("correlationId")).isNull();
        assertThat(MDC.get("eventId")).isNull();
    }

    @Test
    void anInvalidEventIsDeadLetteredWithoutHandling() {
        assertThatThrownBy(() -> consumer(5).onMessage(message("{\"eventType\":\"InvoiceIssued\"}")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class).hasMessageContaining("INVALID_EVENT");
        assertThat(handled).isEmpty();
    }

    @Test
    void aTransientFailureIsRetriedWithExponentialBackoff() {
        failuresLeft = 2;

        consumer(5).onMessage(message(FinancialEventReaderTest.payment("45000.00")));

        assertThat(handled).hasSize(3);
        assertThat(pauses).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Test
    void exhaustedRetriesAreDeadLettered() {
        failuresLeft = 10;

        assertThatThrownBy(() -> consumer(3).onMessage(message(FinancialEventReaderTest.issued())))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class).hasMessageContaining("RETRIES_EXHAUSTED");
        assertThat(handled).hasSize(3);
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void topologyBindsBothBillingFactsToOneQueue() {
        RabbitFinancialConsumer.Topology topology = RabbitFinancialConsumer.Topology.defaults();

        assertThat(topology.exchange()).isEqualTo("billing.events");
        assertThat(topology.queue()).isEqualTo("worker.billing-financial-emails");
        assertThat(topology.deadLetterQueue()).isEqualTo("worker.billing-financial-emails.dlq");
        assertThat(topology.deadLetterExchange()).isEqualTo("worker.consumers.dlx");
        assertThat(RabbitFinancialConsumer.ROUTING_KEYS).containsExactly("InvoiceIssued", "PaymentRecorded");
        assertThatThrownBy(() -> new RabbitFinancialConsumer(null, topology, new FinancialEventReader(), handler, 0,
                Duration.ofSeconds(1), pauses::add)).isInstanceOf(IllegalArgumentException.class);
    }
}
