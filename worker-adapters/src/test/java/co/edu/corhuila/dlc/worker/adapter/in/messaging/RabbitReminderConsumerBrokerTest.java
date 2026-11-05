package co.edu.corhuila.dlc.worker.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import co.edu.corhuila.dlc.worker.application.model.ReminderRequest;
import co.edu.corhuila.dlc.worker.application.usecase.DeliverReminder.Result;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;

/**
 * The worker's own queue, binding and DLQ against real RabbitMQ (TEST_RABBITMQ_URL; skipped when absent). The
 * producer side publishes like Appointments: durable topic exchange, persistent JSON, messageId = eventId.
 */
class RabbitReminderConsumerBrokerTest {
    private static final String URL = System.getenv("TEST_RABBITMQ_URL");
    private final BlockingQueue<ReminderRequest> handled = new LinkedBlockingQueue<>();
    private RabbitReminderConsumer.Topology topology;
    private CachingConnectionFactory connections;
    private SimpleMessageListenerContainer container;
    private RabbitTemplate producer;

    @BeforeEach
    void start() {
        assumeTrue(URL != null && !URL.isBlank(), "TEST_RABBITMQ_URL not set");
        String run = UUID.randomUUID().toString().substring(0, 8);
        topology = new RabbitReminderConsumer.Topology("it-" + run + ".appointments.events", "it-" + run + ".worker");
        connections = new CachingConnectionFactory(URI.create(URL));
        container = new RabbitReminderConsumer(connections, topology, new ReminderEventReader(), request -> {
            handled.add(request);
            return Result.SENT_REPORTED;
        }, 2, Duration.ofMillis(10), delay -> { }).container();
        container.start();
        producer = new RabbitTemplate(connections);
    }

    @AfterEach
    void stop() {
        if (container == null) return;
        container.stop();
        RabbitAdmin admin = new RabbitAdmin(connections);
        admin.deleteQueue(topology.queue());
        admin.deleteQueue(topology.deadLetterQueue());
        admin.deleteExchange(topology.exchange());
        admin.deleteExchange(topology.deadLetterExchange());
        connections.destroy();
    }

    private void publish(String routingKey, String body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(ReminderEventReaderTest.EVENT);
        producer.send(topology.exchange(), routingKey, new Message(body.getBytes(StandardCharsets.UTF_8), properties));
    }

    @Test
    void consumesTheReminderFromItsOwnBindingAndAcknowledges() throws Exception {
        awaitConsumer();
        publish("AppointmentReminderRequested", ReminderEventReaderTest.valid());

        ReminderRequest request = handled.poll(10, TimeUnit.SECONDS);

        assertThat(request).isNotNull();
        assertThat(request.eventId()).isEqualTo(UUID.fromString(ReminderEventReaderTest.EVENT));
        Thread.sleep(300);
        assertThat(new RabbitAdmin(connections).getQueueInfo(topology.queue()).getMessageCount()).isZero();
    }

    @Test
    void otherEventsAreNotDeliveredToTheWorker() throws Exception {
        awaitConsumer();
        publish("AppointmentNoShow", ReminderEventReaderTest.valid());

        assertThat(handled.poll(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void anInvalidMessageEndsInTheDeadLetterQueue() throws Exception {
        awaitConsumer();
        publish("AppointmentReminderRequested", "{\"eventType\":\"AppointmentReminderRequested\"}");

        Message dead = null;
        for (int i = 0; i < 50 && dead == null; i++) {
            dead = producer.receive(topology.deadLetterQueue(), 200);
        }
        assertThat(dead).isNotNull();
        assertThat(handled).isEmpty();
    }

    private void awaitConsumer() throws InterruptedException {
        RabbitAdmin admin = new RabbitAdmin(connections);
        for (int i = 0; i < 100; i++) {
            var info = admin.getQueueInfo(topology.queue());
            if (info != null && info.getConsumerCount() > 0) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Consumer did not start");
    }
}
