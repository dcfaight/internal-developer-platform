package io.github.dcfaight.platformlab.worker;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dcfaight.platformlab.security.TestUsers;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import io.github.dcfaight.platformlab.kubernetes.KindNamespaceAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "PLATFORM_KIND_TEST", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "platform.worker.enabled=true",
        "platform.worker.poll-delay-ms=100",
        "platform.worker.readiness-poll-interval=1s"
})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class KindWorkerFlowTests {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        io.github.dcfaight.platformlab.TestDatabase.migrate(POSTGRES);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        TestUsers.register(registry);
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;

    @Test
    void acceptedHttpRequestRunsThroughScheduledWorkerToRealKindReadinessAndDeletion() throws Exception {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        var accepted = client.send(HttpRequest.newBuilder(url("/api/v1/environments"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "kind-worker-full-flow")
                .header("Authorization", TestUsers.basic(TestUsers.OPERATOR))
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"name":"Kind worker flow","templateId":"sample-service","ttlHours":1}
                        """)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(202, accepted.statusCode(), accepted.body());
        System.out.println("KIND_HTTP_ACCEPTED " + accepted.body());
        JsonNode acceptance = mapper.readTree(accepted.body());
        String operationId = acceptance.path("operationId").asText();
        String environmentId = acceptance.path("environmentId").asText();
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        JsonNode status;
        do {
            status = get(client, "/api/v1/operations/" + operationId);
            if ("SUCCEEDED".equals(status.path("status").asText())
                    || "FAILED".equals(status.path("status").asText())) {
                break;
            }
            Thread.sleep(500);
        } while (System.nanoTime() < deadline);
        System.out.println("KIND_HTTP_OPERATION " + status);
        assertEquals("SUCCEEDED", status.path("status").asText(), status::toString);
        assertEquals(accepted.headers().firstValue("X-Request-Id").orElseThrow(),
                status.path("correlationId").asText());
        assertEquals(1, status.path("attemptCount").asInt());
        assertEquals("READY", status.path("progressStep").asText());
        assertFalse(status.path("completedAt").isNull());
        assertEquals("CLAIMED", status.path("events").get(0).path("type").asText());
        assertEquals("SUCCEEDED", status.path("events").get(status.path("events").size() - 1)
                .path("type").asText());
        assertEquals(java.util.List.of("PROVISIONING_STARTED", "NAMESPACE_CREATED", "WORKLOAD_CREATED",
                "PROVISIONED", "ENVIRONMENT_READY"), milestones(status));

        JsonNode environment = get(client, "/api/v1/environments/" + environmentId);
        System.out.println("KIND_HTTP_ENVIRONMENT " + environment);
        assertEquals("READY", environment.path("status").asText());
        assertEquals("env-" + environmentId.replace("-", ""), environment.path("namespace").asText());
        var deletion = client.send(HttpRequest.newBuilder(url("/api/v1/environments/" + environmentId))
                .timeout(Duration.ofSeconds(10)).header("Authorization", TestUsers.basic(TestUsers.OPERATOR))
                .DELETE().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(202, deletion.statusCode(), deletion.body());
        JsonNode deleteAcceptance = mapper.readTree(deletion.body());
        assertEquals("DELETING", deleteAcceptance.path("environmentStatus").asText());
        assertEquals("PENDING", deleteAcceptance.path("operationStatus").asText());
        String deleteLocation = deletion.headers().firstValue("Location").orElseThrow();
        deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        do {
            status = get(client, deleteLocation);
            if ("SUCCEEDED".equals(status.path("status").asText())
                    || "FAILED".equals(status.path("status").asText())) {
                break;
            }
            Thread.sleep(500);
        } while (System.nanoTime() < deadline);
        System.out.println("KIND_HTTP_DELETE_OPERATION " + status);
        assertEquals("SUCCEEDED", status.path("status").asText(), status::toString);
        assertEquals("DELETE_ENVIRONMENT", status.path("type").asText());
        assertEquals(java.util.List.of("DELETION_STARTED", "NAMESPACE_DELETE_REQUESTED", "ENVIRONMENT_DELETED"),
                milestones(status));
        assertEquals("DELETED", get(client, "/api/v1/environments/" + environmentId).path("status").asText());
        assertEquals(2, get(client, "/api/v1/environments/" + environmentId + "/operations")
                .path("totalElements").asInt());
        assertEquals("SUCCEEDED", get(client, "/api/v1/operations/" + operationId).path("status").asText());
        KindNamespaceAssertions.assertAbsent(environment.path("namespace").asText());
    }

    private static java.util.List<String> milestones(JsonNode operation) {
        // Readiness and deletion polls add a variable number of CLAIMED/WAITING events between milestones.
        var codes = new java.util.ArrayList<String>();
        operation.path("events").forEach(event -> {
            String type = event.path("type").asText();
            if ("PROGRESS".equals(type) || "PROVISIONED".equals(type) || "SUCCEEDED".equals(type)) {
                codes.add(event.path("code").asText());
            }
        });
        return codes;
    }

    private JsonNode get(HttpClient client, String path) throws Exception {
        var response = client.send(HttpRequest.newBuilder(url(path)).timeout(Duration.ofSeconds(10))
                .header("Authorization", TestUsers.basic(TestUsers.OPERATOR)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return mapper.readTree(response.body());
    }

    private URI url(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
