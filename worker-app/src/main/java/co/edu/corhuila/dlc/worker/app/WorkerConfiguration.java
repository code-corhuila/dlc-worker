package co.edu.corhuila.dlc.worker.app;

import static co.edu.corhuila.dlc.worker.app.WorkerProperties.required;

import co.edu.corhuila.dlc.worker.adapter.in.messaging.RabbitReminderConsumer;
import co.edu.corhuila.dlc.worker.adapter.in.messaging.ReminderEventReader;
import co.edu.corhuila.dlc.worker.adapter.out.email.SmtpEmailSender;
import co.edu.corhuila.dlc.worker.adapter.out.http.FileServiceToken;
import co.edu.corhuila.dlc.worker.adapter.out.http.HttpAppointmentNotifications;
import co.edu.corhuila.dlc.worker.adapter.out.http.HttpPatientContacts;
import co.edu.corhuila.dlc.worker.adapter.out.persistence.JdbcDeliveryStore;
import co.edu.corhuila.dlc.worker.application.usecase.DeliverReminder;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the reminder pipeline: RabbitMQ consumer, use case, owner clients, SMTP adapter and notifications store. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkerProperties.class)
@ConditionalOnProperty(name = "worker.enabled", havingValue = "true", matchIfMissing = true)
public class WorkerConfiguration {

    /** Runtime role of the notifications schema; Norma 5.3.10 pool, wait and statement limits. Lazy connect. */
    @Bean(destroyMethod = "close")
    HikariDataSource workerDataSource(WorkerProperties properties) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(required(properties.database().url(), "worker.database.url"));
        dataSource.setUsername(required(properties.database().username(), "worker.database.username"));
        dataSource.setPassword(properties.database().password());
        dataSource.setMaximumPoolSize(4);
        dataSource.setConnectionTimeout(5000);
        dataSource.setConnectionInitSql("SET statement_timeout = '5s'");
        return dataSource;
    }

    @Bean
    DeliverReminder deliverReminder(WorkerProperties properties, HikariDataSource workerDataSource) {
        Duration timeout = properties.ownerTimeout();
        var appointments = new HttpAppointmentNotifications(
                required(properties.appointments().internalUrl(), "worker.appointments.internal-url"),
                token(properties.appointments().serviceTokenFile(), "worker.appointments.service-token-file"),
                timeout);
        var patients = new HttpPatientContacts(
                required(properties.patients().internalUrl(), "worker.patients.internal-url"),
                token(properties.patients().serviceTokenFile(), "worker.patients.service-token-file"), timeout);
        return new DeliverReminder(appointments, patients, new SmtpEmailSender(smtp(properties.smtp())),
                new JdbcDeliveryStore(new JdbcTemplate(workerDataSource)), WorkerConfiguration::sleep,
                properties.emailBackoff(), Clock.systemUTC());
    }

    @Bean(destroyMethod = "destroy")
    CachingConnectionFactory workerRabbitConnections(WorkerProperties properties) {
        return new CachingConnectionFactory(URI.create(required(properties.rabbitmq().uri(), "worker.rabbitmq.uri")));
    }

    @Bean
    SimpleMessageListenerContainer reminderConsumer(WorkerProperties properties,
            CachingConnectionFactory workerRabbitConnections, DeliverReminder deliverReminder) {
        WorkerProperties.Rabbit rabbit = properties.rabbitmq();
        SimpleMessageListenerContainer container = new RabbitReminderConsumer(workerRabbitConnections,
                new RabbitReminderConsumer.Topology(rabbit.exchange(), rabbit.queuePrefix()),
                new ReminderEventReader(), deliverReminder::handle, rabbit.attempts(), rabbit.backoff(),
                WorkerConfiguration::sleep).container();
        container.setAutoStartup(properties.consumer().autoStartup());
        return container;
    }

    private static FileServiceToken token(String file, String name) {
        return new FileServiceToken(Path.of(required(file, name)));
    }

    private static SmtpEmailSender.Settings smtp(WorkerProperties.Smtp smtp) {
        String password = null;
        boolean auth = smtp.username() != null && !smtp.username().isBlank();
        if (auth) {
            try {
                password = Files.readString(Path.of(required(smtp.passwordFile(), "worker.smtp.password-file")))
                        .strip();
            } catch (IOException unreadable) {
                throw new UncheckedIOException("Unreadable worker.smtp.password-file.", unreadable);
            }
        }
        return new SmtpEmailSender.Settings(required(smtp.host(), "worker.smtp.host"), smtp.port(),
                auth ? smtp.username() : null, password, smtp.startTls(), required(smtp.from(), "worker.smtp.from"),
                smtp.timeout());
    }

    /** Backoff between attempts; an interrupted wait stops the current attempt cycle. */
    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Worker stopping.");
        }
    }
}
