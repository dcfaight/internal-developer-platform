package io.github.dcfaight.platformlab.operation;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dcfaight.platformlab.kubernetes.OperationProgress.Milestone;
import io.github.dcfaight.platformlab.security.TestUsers;
import io.github.dcfaight.platformlab.worker.WorkerProperties;
import io.github.dcfaight.platformlab.worker.WorkerRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OperationStatusTests {
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
    @Autowired WorkerRepository worker;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void isolate() {
        jdbc.execute("TRUNCATE audit_events, idempotency_requests, operation_events, operations, environments CASCADE");
    }

    @Test
    void statusContainsDurableFailureProgressEventsAndOriginalRequestCorrelation() throws Exception {
        var client = HttpClient.newHttpClient();
        var accepted = client.send(HttpRequest.newBuilder(url("/api/v1/environments"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "rich-status")
                .header("Authorization", TestUsers.basic(TestUsers.OPERATOR))
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"name":"Rich status","templateId":"sample-service","ttlHours":1}
                        """)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(202, accepted.statusCode());
        String correlation = accepted.headers().firstValue("X-Request-Id").orElseThrow();
        UUID id = UUID.fromString(mapper.readTree(accepted.body()).path("operationId").asText());
        var claim = worker.claim(new WorkerProperties(Duration.ofSeconds(30), 5, Duration.ofSeconds(2),
                Duration.ofSeconds(5), Duration.ofMinutes(5))).orElseThrow();
        assertEquals(id, claim.id());
        worker.provisioned(claim);
        worker.fail(claim, "KUBERNETES_FORBIDDEN", "Kubernetes access was denied.");

        var response = client.send(HttpRequest.newBuilder(url("/api/v1/operations/" + id))
                .header("Authorization", TestUsers.basic(TestUsers.OPERATOR)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        var status = mapper.readTree(response.body());
        assertEquals("FAILED", status.path("status").asText());
        assertEquals("FAILED", status.path("progressStep").asText());
        assertEquals("KUBERNETES_FORBIDDEN", status.path("failureCode").asText());
        assertEquals("Kubernetes access was denied.", status.path("failureMessage").asText());
        assertEquals(correlation, status.path("correlationId").asText());
        assertNotEquals(correlation, response.headers().firstValue("X-Request-Id").orElseThrow());
        assertEquals(1, status.path("attemptCount").asInt());
        assertFalse(status.path("startedAt").isNull());
        assertFalse(status.path("completedAt").isNull());
        assertTrue(status.path("leaseExpiresAt").isNull());
        assertEquals(3, status.path("events").size());
        assertEquals("CLAIMED", status.path("events").get(0).path("type").asText());
        assertEquals("PROVISIONED", status.path("events").get(1).path("type").asText());
        assertEquals("FAILED", status.path("events").get(2).path("type").asText());
        for (var event : status.path("events")) {
            assertEquals(correlation, event.path("correlationId").asText());
            assertFalse(event.path("code").asText().isEmpty());
            assertFalse(event.path("message").asText().isEmpty());
            assertFalse(event.path("createdAt").asText().isEmpty());
        }

        String environmentId = status.path("environmentId").asText();
        var history = get(client, "/api/v1/environments/" + environmentId + "/operations");
        assertEquals(200, history.statusCode());
        var page = mapper.readTree(history.body());
        assertEquals(1, page.path("totalElements").asInt());
        assertEquals(1, page.path("totalPages").asInt());
        assertEquals(status, page.path("items").get(0));
        var detail = mapper.readTree(get(client, "/api/v1/environments/" + environmentId).body());
        assertEquals(id.toString(), detail.path("latestOperation").path("id").asText());
        assertEquals("FAILED", detail.path("latestOperation").path("status").asText());
        assertEquals(status.path("failureCode"), detail.path("latestOperation").path("failureCode"));
        assertEquals(status.path("failureMessage"), detail.path("latestOperation").path("failureMessage"));
        var list = mapper.readTree(get(client, "/api/v1/environments").body());
        assertEquals(detail, list.path("items").get(0));

        UUID newerId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO operations (id, environment_id, type, status, attempt_count,
                    next_attempt_at, created_at, updated_at, progress_step)
                VALUES (?, ?, 'DELETE_ENVIRONMENT', 'PENDING', 0, now(),
                    now() + interval '1 second', now(), 'ACCEPTED')
                """, newerId, UUID.fromString(environmentId));
        var newest = mapper.readTree(get(client, "/api/v1/environments/" + environmentId
                + "/operations?size=1").body());
        assertEquals(2, newest.path("totalElements").asInt());
        assertEquals(2, newest.path("totalPages").asInt());
        assertEquals(newerId.toString(), newest.path("items").get(0).path("id").asText());
        var older = mapper.readTree(get(client, "/api/v1/environments/" + environmentId
                + "/operations?size=1&page=1").body());
        assertEquals(id.toString(), older.path("items").get(0).path("id").asText());
        detail = mapper.readTree(get(client, "/api/v1/environments/" + environmentId).body());
        assertEquals(newerId.toString(), detail.path("latestOperation").path("id").asText());
        assertTrue(detail.path("latestOperation").path("failureCode").isNull());
        assertTrue(detail.path("latestOperation").path("failureMessage").isNull());
        var deletionClaim = worker.claim(new WorkerProperties(Duration.ofSeconds(30), 5, Duration.ofSeconds(2),
                Duration.ofSeconds(5), Duration.ofMinutes(5))).orElseThrow();
        assertEquals(newerId, deletionClaim.id());
        worker.fail(deletionClaim, "KUBERNETES_AUTHENTICATION_FAILED",
                "The Kubernetes API did not accept the worker credentials.");
        var deletion = mapper.readTree(get(client, "/api/v1/operations/" + newerId).body());
        detail = mapper.readTree(get(client, "/api/v1/environments/" + environmentId).body());
        assertEquals("DELETE_FAILED", detail.path("status").asText());
        assertEquals("FAILED", deletion.path("status").asText());
        assertEquals("KUBERNETES_AUTHENTICATION_FAILED", deletion.path("failureCode").asText());
        assertEquals(deletion.path("failureCode"), detail.path("latestOperation").path("failureCode"));
        assertEquals(deletion.path("failureMessage"), detail.path("latestOperation").path("failureMessage"));
        var deletionHistory = mapper.readTree(get(client, "/api/v1/environments/" + environmentId
                + "/operations").body());
        assertEquals(deletion, deletionHistory.path("items").get(0));
        assertEquals(0, mapper.readTree(get(client, "/api/v1/environments/" + environmentId
                + "/operations?page=2&size=1").body()).path("items").size());

        for (String query : new String[] {"page=-1", "page=1000001", "size=0", "size=101", "page=oops"}) {
            assertEquals(400, get(client, "/api/v1/environments/" + environmentId + "/operations?" + query)
                    .statusCode());
        }
        assertEquals(400, get(client, "/api/v1/environments/not-a-uuid/operations").statusCode());
        assertEquals(404, get(client, "/api/v1/environments/" + UUID.randomUUID() + "/operations").statusCode());

        var other = client.send(HttpRequest.newBuilder(url("/api/v1/environments"))
                .header("Content-Type", "application/json").header("Idempotency-Key", "other-history")
                .header("Authorization", TestUsers.basic(TestUsers.OPERATOR))
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"name":"Other environment","templateId":"sample-service","ttlHours":1}
                        """)).build(), HttpResponse.BodyHandlers.ofString());
        String otherId = mapper.readTree(other.body()).path("environmentId").asText();
        var otherHistory = mapper.readTree(get(client, "/api/v1/environments/" + otherId + "/operations").body());
        assertEquals(1, otherHistory.path("totalElements").asInt());
        assertEquals(otherId, otherHistory.path("items").get(0).path("environmentId").asText());
    }

    @Test
    void timelineAndMetricsExposeLifecycleMilestonesCountsAndDurations() throws Exception {
        var client = HttpClient.newHttpClient();
        var properties = new WorkerProperties(Duration.ofSeconds(30), 5, Duration.ofSeconds(2),
                Duration.ofSeconds(5), Duration.ofMinutes(5));
        UUID ready = accept(client, "metrics-ready");
        var claim = worker.claim(properties).orElseThrow();
        assertTrue(worker.progress(claim, Milestone.PROVISIONING_STARTED));
        assertTrue(worker.progress(claim, Milestone.NAMESPACE_CREATED));
        assertTrue(worker.progress(claim, Milestone.WORKLOAD_CREATED));
        assertTrue(worker.provisioned(claim));
        assertTrue(worker.succeed(claim));
        accept(client, "metrics-failed");
        var failed = worker.claim(properties).orElseThrow();
        assertTrue(worker.fail(failed, "KUBERNETES_FORBIDDEN", "Kubernetes access was denied."));
        accept(client, "metrics-pending");

        var operation = mapper.readTree(get(client, "/api/v1/operations/" + ready).body());
        var types = new java.util.ArrayList<String>();
        var codes = new java.util.ArrayList<String>();
        operation.path("events").forEach(event -> {
            types.add(event.path("type").asText());
            codes.add(event.path("code").asText());
        });
        assertEquals(java.util.List.of("CLAIMED", "PROGRESS", "PROGRESS", "PROGRESS", "PROVISIONED", "SUCCEEDED"),
                types);
        assertEquals(java.util.List.of("CLAIMED", "PROVISIONING_STARTED", "NAMESPACE_CREATED", "WORKLOAD_CREATED",
                "PROVISIONED", "ENVIRONMENT_READY"), codes);
        var history = mapper.readTree(get(client, "/api/v1/environments/"
                + operation.path("environmentId").asText() + "/operations").body());
        assertEquals(operation.path("events"), history.path("items").get(0).path("events"));

        var response = get(client, "/api/v1/operations/metrics");
        assertEquals(200, response.statusCode());
        var metrics = mapper.readTree(response.body());
        assertFalse(metrics.path("generatedAt").asText().isEmpty());
        assertFalse(metrics.path("workerEnabled").asBoolean());
        var create = metrics.path("operations").get(0);
        assertEquals("CREATE_ENVIRONMENT", create.path("type").asText());
        assertEquals(1, create.path("pending").asLong());
        assertEquals(0, create.path("running").asLong());
        assertEquals(1, create.path("succeeded").asLong());
        assertEquals(1, create.path("failed").asLong());
        assertTrue(create.path("averageQueueSeconds").asDouble() >= 0);
        assertEquals(1, create.path("succeededDuration").path("count").asLong());
        double average = create.path("succeededDuration").path("averageSeconds").asDouble();
        assertTrue(average >= 0);
        assertEquals(average, create.path("succeededDuration").path("p95Seconds").asDouble());
        assertEquals(average, create.path("succeededDuration").path("maxSeconds").asDouble());
        var delete = metrics.path("operations").get(1);
        assertEquals("DELETE_ENVIRONMENT", delete.path("type").asText());
        assertEquals(0, delete.path("pending").asLong() + delete.path("running").asLong()
                + delete.path("succeeded").asLong() + delete.path("failed").asLong());
        assertEquals(0, delete.path("succeededDuration").path("count").asLong());
        assertTrue(delete.path("succeededDuration").path("averageSeconds").isNull());
        assertTrue(delete.path("averageQueueSeconds").isNull());
        assertEquals(1, metrics.path("worker").path("dueOperations").asLong());
        assertEquals(0, metrics.path("worker").path("expiredLeases").asLong());
        assertFalse(metrics.path("worker").path("oldestDueAt").isNull());
        assertFalse(metrics.path("worker").path("lastEventAt").isNull());
        assertEquals(1, metrics.path("failureCodes").size());
        assertEquals("KUBERNETES_FORBIDDEN", metrics.path("failureCodes").get(0).path("code").asText());
        assertEquals(1, metrics.path("failureCodes").get(0).path("count").asLong());

        var running = worker.claim(properties).orElseThrow();
        jdbc.update("UPDATE operations SET lease_expires_at = clock_timestamp() - interval '1 second' WHERE id = ?",
                running.id());
        metrics = mapper.readTree(get(client, "/api/v1/operations/metrics").body());
        assertEquals(1, metrics.path("operations").get(0).path("running").asLong());
        assertEquals(0, metrics.path("worker").path("dueOperations").asLong());
        assertEquals(1, metrics.path("worker").path("expiredLeases").asLong());
        assertFalse(metrics.path("worker").path("oldestDueAt").isNull());
    }

    private UUID accept(HttpClient client, String key) throws Exception {
        var accepted = client.send(HttpRequest.newBuilder(url("/api/v1/environments"))
                .header("Content-Type", "application/json").header("Idempotency-Key", key)
                .header("Authorization", TestUsers.basic(TestUsers.OPERATOR))
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"name":"Metrics","templateId":"sample-service","ttlHours":1}
                        """)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(202, accepted.statusCode());
        return UUID.fromString(mapper.readTree(accepted.body()).path("operationId").asText());
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(url(path))
                .header("Authorization", TestUsers.basic(TestUsers.OPERATOR)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI url(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
