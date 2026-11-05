package co.edu.corhuila.dlc.worker.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Composition root of the Notifications worker (ADR-005): a background process with no HTTP surface. */
@SpringBootApplication
public class WorkerApplication {
    static final WebApplicationType WEB_APPLICATION_TYPE = WebApplicationType.NONE;

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(WorkerApplication.class);
        application.setWebApplicationType(WEB_APPLICATION_TYPE);
        application.run(args);
    }
}
