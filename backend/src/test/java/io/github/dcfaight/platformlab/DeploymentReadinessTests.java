package io.github.dcfaight.platformlab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dcfaight.platformlab.worker.ProvisioningWorker;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.hikari.connection-timeout=1000")
@ActiveProfiles("deploy")
@Testcontainers
class DeploymentReadinessTests {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        TestDatabase.migrate(POSTGRES);
        registry.add("DB_URL", POSTGRES::getJdbcUrl);
        registry.add("DB_USERNAME", POSTGRES::getUsername);
        registry.add("DB_PASSWORD", POSTGRES::getPassword);
        io.github.dcfaight.platformlab.security.TestUsers.register(registry);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext context;

    @Test
    void probesAreMinimalAndReadinessRequiresDatabaseButLivenessDoesNot() throws Exception {
        assertEquals("0.0.0.0", context.getEnvironment().getProperty("server.address"));
        assertFalse(context.getEnvironment().getProperty("platform.worker.enabled", Boolean.class));
        assertTrue(context.getBeansOfType(ProvisioningWorker.class).isEmpty());
        assertHealth("liveness", 200, "UP");
        assertHealth("readiness", 200, "UP");
        assertEquals(401, get("/actuator/env").statusCode());
        assertEquals(401, get("/actuator/health").statusCode());
        assertEquals(200, get("/api/v1/environments").statusCode());

        POSTGRES.stop();

        assertHealth("readiness", 503, "DOWN");
        assertHealth("liveness", 200, "UP");
    }

    private void assertHealth(String probe, int status, String health) throws Exception {
        var response = get("/actuator/health/" + probe);
        assertEquals(status, response.statusCode());
        assertEquals("{\"status\":\"" + health + "\"}", response.body());
    }

    private HttpResponse<String> get(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10));
        if (path.startsWith("/api/")) {
            request.header("Authorization", io.github.dcfaight.platformlab.security.TestUsers.basic(
                    io.github.dcfaight.platformlab.security.TestUsers.OPERATOR));
        }
        return HTTP.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
