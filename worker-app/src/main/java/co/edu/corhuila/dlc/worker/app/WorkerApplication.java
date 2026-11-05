package co.edu.corhuila.dlc.worker.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * Composition root of the Notifications worker (ADR-005): a background process with no HTTP surface. Broker and
 * database are wired explicitly in {@link WorkerConfiguration}, so Boot's defaults are excluded.
 */
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class, RabbitAutoConfiguration.class})
public class WorkerApplication {
    static final WebApplicationType WEB_APPLICATION_TYPE = WebApplicationType.NONE;

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(WorkerApplication.class);
        application.setWebApplicationType(WEB_APPLICATION_TYPE);
        application.run(args);
    }
}
