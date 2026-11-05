package co.edu.corhuila.dlc.worker.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** The worker is a background process: it starts without an embedded web server (pipeline off here). */
@SpringBootTest(properties = "worker.enabled=false")
class WorkerApplicationTest {
    @Autowired
    private ApplicationContext context;

    @Test
    void startsAsNonWebApplication() {
        assertThat(context.getBean(WorkerApplication.class)).isNotNull();
        assertThat(WorkerApplication.WEB_APPLICATION_TYPE).isEqualTo(WebApplicationType.NONE);
    }
}
