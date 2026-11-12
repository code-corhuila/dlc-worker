package co.edu.corhuila.dlc.worker.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import co.edu.corhuila.dlc.worker.application.model.ScheduledRequest;
import co.edu.corhuila.dlc.worker.application.usecase.DeliverScheduledEmail.Result;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class RabbitScheduledConsumerTest {
    @Test void handlesValidEventAndUsesDedicatedQueue() {
        List<ScheduledRequest> handled = new ArrayList<>();
        var topology = RabbitScheduledConsumer.Topology.defaults();
        var consumer = new RabbitScheduledConsumer(null, topology, new ScheduledEventReader(),
                request -> { handled.add(request); return Result.SENT; }, 2, Duration.ofMillis(1), delay -> { });
        consumer.onMessage(new Message(ScheduledEventReaderTest.JSON.getBytes(StandardCharsets.UTF_8), new MessageProperties()));
        assertThat(handled).hasSize(1);
        assertThat(topology.queue()).isEqualTo("worker.appointment-scheduled");
        assertThat(topology.deadLetterQueue()).isEqualTo("worker.appointment-scheduled.dlq");
    }

    @Test void rejectsInvalidMessagesWithoutCallingHandler() {
        var consumer = new RabbitScheduledConsumer(null, RabbitScheduledConsumer.Topology.defaults(),
                new ScheduledEventReader(), request -> { throw new AssertionError(); }, 2, Duration.ofMillis(1), delay -> { });
        var bad = new Message("{}".getBytes(StandardCharsets.UTF_8), new MessageProperties());
        assertThatThrownBy(() -> consumer.onMessage(bad)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }
}
