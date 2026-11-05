package co.edu.corhuila.dlc.worker.app;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.corhuila.dlc.worker.application.usecase.DeliverReminder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Composition of the worker from configuration; nothing connects while the consumer does not start. */
class WorkerConfigurationTest {
    @TempDir
    Path secrets;

    private ApplicationContextRunner runner() throws IOException {
        Path appointments = Files.writeString(secrets.resolve("worker-to-appointments"), "token-a");
        Path patients = Files.writeString(secrets.resolve("worker-to-patients"), "token-p");
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(WorkerConfiguration.class)
                .withPropertyValues(
                        "worker.consumer.auto-startup=false",
                        "worker.database.url=jdbc:postgresql://dlc-postgres:5432/di_lucca",
                        "worker.database.username=notifications_runtime",
                        "worker.database.password=unused",
                        "worker.rabbitmq.uri=amqp://worker:unused@dlc-rabbitmq:5672",
                        "worker.appointments.internal-url=http://dlc-appointments-api:8080",
                        "worker.appointments.service-token-file=" + appointments,
                        "worker.patients.internal-url=http://dlc-patient-api:8080",
                        "worker.patients.service-token-file=" + patients,
                        "worker.smtp.host=mailpit",
                        "worker.smtp.port=1025",
                        "worker.smtp.start-tls=false",
                        "worker.smtp.from=recordatorios@dilucca.test");
    }

    @Test
    void composesTheDeliveryPipelineWithoutConnecting() throws IOException {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DeliverReminder.class);
            assertThat(context.getBean(SimpleMessageListenerContainer.class).isRunning()).isFalse();
            assertThat(context.getBean(SimpleMessageListenerContainer.class).getQueueNames())
                    .containsExactly("worker.appointment-reminder-requested");
        });
    }

    @Test
    void aMissingRequiredSettingStopsStartupWithItsName() throws IOException {
        runner().withPropertyValues("worker.rabbitmq.uri=").run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("worker.rabbitmq.uri"));
        runner().withPropertyValues("worker.smtp.from=").run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("worker.smtp.from"));
    }

    @Test
    void smtpCredentialsRequireAPasswordFile() throws IOException {
        runner().withPropertyValues("worker.smtp.username=relay").run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("worker.smtp.password-file"));
        Path password = Files.writeString(secrets.resolve("smtp-password"), "relay-secret\n");
        runner().withPropertyValues("worker.smtp.username=relay", "worker.smtp.password-file=" + password)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void theWorkerCanBeDisabled() throws IOException {
        runner().withPropertyValues("worker.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(DeliverReminder.class));
    }
}
