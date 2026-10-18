package io.github.dcfaight.platformlab;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.github.dcfaight.platformlab.security.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class PlatformLabApplicationTests {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        TestDatabase.migrate(POSTGRES);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        TestUsers.register(registry);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @BeforeEach
    void clearDatabase() {
        jdbc.update("DELETE FROM audit_events");
        jdbc.update("DELETE FROM idempotency_requests");
        jdbc.update("DELETE FROM operations");
        jdbc.update("DELETE FROM environments");
        jdbc.update("DELETE FROM account_team_memberships");
        jdbc.update("DELETE FROM platform_teams");
    }

    @Test
    void environmentWithoutOperationsHasNullSummaryAndEmptyHistory() throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO environments (id, name, namespace, template_id, status,
                    expires_at, created_at, updated_at)
                VALUES (?, 'No operation', ?, 'sample-service', 'REQUESTED',
                    now() + interval '1 hour', now(), now())
                """, id, "env-" + id.toString().replace("-", ""));
        var detail = get("/api/v1/environments/" + id);
        assertEquals(200, detail.status());
        assertTrue(detail.json().path("latestOperation").isNull());
        var history = get("/api/v1/environments/" + id + "/operations");
        assertEquals(200, history.status());
        assertFields(history.json(), "items", "page", "size", "totalElements", "totalPages");
        assertEquals(0, history.json().path("items").size());
        assertEquals(0, history.json().path("totalElements").asInt());
        assertEquals(0, history.json().path("totalPages").asInt());
    }

    @Test
    void creationPersistsCompleteEnvironmentOperationAndAudit() throws Exception {
        Instant before = Instant.now();
        ApiResponse created = request(port, "POST", "/api/v1/environments", """
                {"name":"  Preview 123_-  ","templateId":"sample-service","ttlHours":3}
                """, Map.of("Idempotency-Key", "create-complete", "X-Actor", "caller-selected-actor",
                "Authorization", TestUsers.basic(TestUsers.OPERATOR)));
        Instant after = Instant.now();

        assertEquals(202, created.status());
        assertFields(created.json(), "environmentId", "operationId", "environmentStatus", "operationStatus");
        String environmentId = uuid(created.json(), "environmentId");
        String operationId = uuid(created.json(), "operationId");
        assertEquals("REQUESTED", created.json().path("environmentStatus").asText());
        assertEquals("PENDING", created.json().path("operationStatus").asText());
        assertEquals("/api/v1/operations/" + operationId, created.header("Location"));

        ApiResponse environment = get("/api/v1/environments/" + environmentId);
        assertEquals(200, environment.status());
        assertFields(environment.json(), "id", "name", "namespace", "templateId", "status",
                "expiresAt", "createdAt", "updatedAt", "latestOperation", "teamId", "createdBy");
        assertFields(environment.json().path("latestOperation"), "id", "type", "status", "progressStep",
                "failureCode", "failureMessage");
        assertTrue(environment.json().path("latestOperation").path("failureCode").isNull());
        assertTrue(environment.json().path("latestOperation").path("failureMessage").isNull());
        assertEquals(operationId, environment.json().path("latestOperation").path("id").asText());
        assertEquals("PENDING", environment.json().path("latestOperation").path("status").asText());
        assertEquals(environmentId, environment.json().path("id").asText());
        assertEquals("Preview 123_-", environment.json().path("name").asText());
        assertEquals("env-" + environmentId.replace("-", ""), environment.json().path("namespace").asText());
        assertEquals("sample-service", environment.json().path("templateId").asText());
        assertEquals("REQUESTED", environment.json().path("status").asText());
        assertBetween(timestamp(environment.json(), "createdAt"), before, after);
        assertBetween(timestamp(environment.json(), "updatedAt"), before, after);
        assertBetween(timestamp(environment.json(), "expiresAt"),
                before.plus(Duration.ofHours(3)), after.plus(Duration.ofHours(3)));

        ApiResponse operation = get("/api/v1/operations/" + operationId);
        assertEquals(200, operation.status());
        assertFields(operation.json(), "id", "environmentId", "type", "status", "attemptCount",
                "nextAttemptAt", "leaseExpiresAt", "startedAt", "completedAt", "createdAt", "updatedAt",
                "failureCode", "failureMessage", "progressStep", "correlationId", "events");
        assertEquals(created.header("X-Request-Id"), operation.json().path("correlationId").asText());
        assertEquals("ACCEPTED", operation.json().path("progressStep").asText());
        assertTrue(operation.json().path("events").isEmpty());
        assertEquals(operationId, operation.json().path("id").asText());
        assertEquals(environmentId, operation.json().path("environmentId").asText());
        assertEquals("CREATE_ENVIRONMENT", operation.json().path("type").asText());
        assertEquals("PENDING", operation.json().path("status").asText());
        assertEquals(0, operation.json().path("attemptCount").asInt());
        for (String field : List.of("leaseExpiresAt", "startedAt", "completedAt")) {
            assertTrue(operation.json().path(field).isNull(), field);
        }
        for (String field : List.of("nextAttemptAt", "createdAt", "updatedAt")) {
            assertBetween(timestamp(operation.json(), field), before, after);
        }

        assertCounts(1);
        Map<String, Object> reservation = jdbc.queryForMap(
                "SELECT * FROM idempotency_requests WHERE key = ?", "create-complete");
        assertEquals("Preview 123_-", reservation.get("name"));
        assertEquals("sample-service", reservation.get("template_id"));
        assertEquals(3, ((Number) reservation.get("ttl_hours")).intValue());
        assertEquals(environmentId, reservation.get("environment_id").toString());
        assertEquals(operationId, reservation.get("operation_id").toString());
        Map<String, Object> audit = jdbc.queryForMap("SELECT * FROM audit_events");
        assertEquals(environmentId, audit.get("environment_id").toString());
        assertEquals(operationId, audit.get("operation_id").toString());
        assertEquals("ENVIRONMENT_REQUESTED", audit.get("action"));
        assertEquals(TestUsers.OPERATOR, audit.get("actor"));
        assertNotNull(audit.get("id"));
        assertNotNull(audit.get("created_at"));
    }

    @Test
    void equivalentNormalizedRequestsReplayWithoutNewWrites() throws Exception {
        ApiResponse original = post("same-normalized", """
                {"name":"  Preview  ","templateId":"sample-service"}
                """);
        assertEquals(202, original.status());
        for (String body : List.of(
                """
                {"name":"Preview","templateId":"sample-service","ttlHours":8}
                """,
                """
                {"name":" Preview ","templateId":"sample-service","ttlHours":null}
                """,
                """
                {"name":"Preview","templateId":"sample-service"}
                """)) {
            ApiResponse replay = post("same-normalized", body);
            assertEquals(202, replay.status());
            assertEquals(original.json(), replay.json());
            assertEquals(original.header("Location"), replay.header("Location"));
        }
        JsonNode environment = get("/api/v1/environments/" + original.json().path("environmentId").asText()).json();
        assertEquals(Duration.ofHours(8),
                Duration.between(timestamp(environment, "createdAt"), timestamp(environment, "expiresAt")));
        assertCounts(1);
    }

    @Test
    void differentPayloadForExistingKeyConflictsWithoutChangingOriginal() throws Exception {
        ApiResponse original = post("conflict", payload("Original", 8));
        assertEquals(202, original.status());
        assertError(post("conflict", payload("Different", 8)), 409);
        assertError(post("conflict", payload("Original", 9)), 409);
        assertEquals(original.json(), post("conflict", payload("Original", 8)).json());
        assertEquals("Original", jdbc.queryForObject("SELECT name FROM environments", String.class));
        assertCounts(1);
    }

    @Test
    void samePayloadWithDifferentKeysCreatesIndependentResources() throws Exception {
        ApiResponse first = post("first-key", payload("Same name", 1));
        ApiResponse second = post("second-key", payload("Same name", 1));
        assertEquals(202, first.status());
        assertEquals(202, second.status());
        assertNotEquals(first.json().path("environmentId"), second.json().path("environmentId"));
        assertNotEquals(first.json().path("operationId"), second.json().path("operationId"));
        assertCounts(2);
    }

    @Test
    void acceptsNameTtlAndIdempotencyKeyBoundaryValues() throws Exception {
        List<String> names = List.of("A", "0", "a".repeat(63), "Abc XYZ_9-");
        for (int index = 0; index < names.size(); index++) {
            String key = index == 0 ? "a" : index == 1 ? "Z" + "a._:-9".repeat(21) + "x" : "valid-" + index;
            int ttl = index % 2 == 0 ? 1 : 24;
            ApiResponse response = post(key, payload(names.get(index), ttl));
            assertEquals(202, response.status(), response.body());
            JsonNode environment = get("/api/v1/environments/" + response.json().path("environmentId").asText()).json();
            assertEquals(names.get(index), environment.path("name").asText());
            assertEquals(Duration.ofHours(ttl),
                    Duration.between(timestamp(environment, "createdAt"), timestamp(environment, "expiresAt")));
        }
        assertCounts(names.size());
    }

    @Test
    void rejectsInvalidNamesWithoutPersistingAnything() throws Exception {
        for (String name : List.of("", "   ", "a".repeat(64), "_abc", "-abc", "éclair",
                "abc/def", "abc.def", "abc\tdef", "abc\ndef", "abc😀")) {
            ApiResponse response = post("invalid-name", mapper.writeValueAsString(
                    Map.of("name", name, "templateId", "sample-service")));
            assertError(response, 400);
            assertFieldError(response, "name");
        }
        for (String body : List.of(
                "{\"templateId\":\"sample-service\"}",
                "{\"name\":null,\"templateId\":\"sample-service\"}")) {
            ApiResponse response = post("missing-name", body);
            assertError(response, 400);
            assertFieldError(response, "name");
        }
        assertCounts(0);
    }

    @Test
    void rejectsMissingOrUnsupportedTemplates() throws Exception {
        for (String body : List.of(
                "{\"name\":\"Preview\"}",
                "{\"name\":\"Preview\",\"templateId\":null}",
                "{\"name\":\"Preview\",\"templateId\":\"\"}",
                "{\"name\":\"Preview\",\"templateId\":\"other-service\"}",
                "{\"name\":\"Preview\",\"templateId\":\" sample-service \"}")) {
            ApiResponse response = post("invalid-template", body);
            assertError(response, 400);
            assertFieldError(response, "templateId");
        }
        assertCounts(0);
    }

    @Test
    void rejectsOutOfRangeNonIntegerAndMalformedJsonRequests() throws Exception {
        for (String ttl : List.of("0", "-1", "25", "2147483648", "1.5", "1.0", "\"8\"", "true", "[]", "{}")) {
            ApiResponse response = post("invalid-ttl",
                    "{\"name\":\"Preview\",\"templateId\":\"sample-service\",\"ttlHours\":" + ttl + "}");
            assertEquals(400, response.status(), "Invalid ttlHours " + ttl + ": " + response.body());
            assertError(response, 400);
        }
        for (String body : List.of(
                "{\"name\":\"Preview\",\"templateId\":\"sample-service\",\"unexpected\":true}",
                "{\"name\":123,\"templateId\":\"sample-service\"}",
                "{\"name\":true,\"templateId\":\"sample-service\"}",
                "{\"name\":\"Preview\",\"templateId\":123}",
                "{", "[]", "null", "")) {
            assertError(post("invalid-json", body), 400);
        }
        assertCounts(0);
    }

    @Test
    void rejectsMissingOrInvalidIdempotencyKeys() throws Exception {
        assertError(post(null, payload("Preview", 8)), 400);
        for (String key : List.of("", "a".repeat(129), "_bad", "-bad", ".bad", ":bad",
                "bad key", "bad/key", "bad@key")) {
            assertError(post(key, payload("Preview", 8)), 400);
        }
        assertCounts(0);
    }

    @Test
    void unknownResourcesAndMalformedIdentifiersHaveStructuredErrors() throws Exception {
        for (String resource : List.of("environments", "operations")) {
            assertError(get("/api/v1/" + resource + "/" + UUID.randomUUID()), 404);
            assertError(get("/api/v1/" + resource + "/not-a-uuid"), 400);
        }
    }

    @Test
    void paginationDefaultsAndBoundsAreEnforced() throws Exception {
        ApiResponse empty = get("/api/v1/environments");
        assertEquals(200, empty.status());
        assertFields(empty.json(), "items", "page", "size", "totalElements", "totalPages");
        assertTrue(empty.json().path("items").isArray());
        assertEquals(0, empty.json().path("items").size());
        assertEquals(0, empty.json().path("page").asInt());
        assertEquals(20, empty.json().path("size").asInt());
        assertEquals(0, empty.json().path("totalElements").asLong());
        assertEquals(0, empty.json().path("totalPages").asInt());
        for (String query : List.of("page=0&size=1", "page=1000000&size=100")) {
            assertEquals(200, get("/api/v1/environments?" + query).status());
        }
        for (String query : List.of("page=-1", "page=1000001", "page=2147483648", "page=abc",
                "page=1.5", "size=0", "size=-1", "size=101", "size=abc", "size=1.5")) {
            assertError(get("/api/v1/environments?" + query), 400);
        }
    }

    @Test
    void paginationUsesCreatedAtThenUuidDescendingWithConsistentTotals() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            ApiResponse created = post("pagination-" + index, payload("Page " + index, 8));
            assertEquals(202, created.status());
            ids.add(created.json().path("environmentId").asText());
        }
        jdbc.update("UPDATE environments SET created_at = TIMESTAMPTZ '2026-01-01 00:00:00+00'");
        String newest = ids.get(0);
        jdbc.update("UPDATE environments SET created_at = TIMESTAMPTZ '2026-01-02 00:00:00+00' WHERE id = ?::uuid", newest);
        List<String> expected = jdbc.queryForList(
                "SELECT id::text FROM environments ORDER BY created_at DESC, id DESC", String.class);
        assertEquals(newest, expected.get(0));
        List<String> actual = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            ApiResponse response = get("/api/v1/environments?page=" + page + "&size=2");
            assertEquals(200, response.status());
            assertEquals(page, response.json().path("page").asInt());
            assertEquals(2, response.json().path("size").asInt());
            assertEquals(5, response.json().path("totalElements").asLong());
            assertEquals(3, response.json().path("totalPages").asInt());
            assertEquals(page == 2 ? 1 : 2, response.json().path("items").size());
            for (JsonNode item : response.json().path("items")) {
                assertFields(item, "id", "name", "namespace", "templateId", "status",
                        "expiresAt", "createdAt", "updatedAt", "latestOperation", "teamId", "createdBy");
                actual.add(item.path("id").asText());
            }
        }
        assertEquals(expected, actual);
        assertEquals(0, get("/api/v1/environments?page=3&size=2").json().path("items").size());
    }

    @Test
    void concurrentIdenticalRequestsReserveExactlyOneCreation() throws Exception {
        List<ApiResponse> responses = concurrentPosts("parallel-same",
                java.util.Collections.nCopies(8, payload("Concurrent", 8)));
        JsonNode expected = responses.get(0).json();
        for (ApiResponse response : responses) {
            assertEquals(202, response.status(), response.body());
            assertEquals(expected, response.json());
        }
        assertCounts(1);
    }

    @Test
    void concurrentConflictingRequestsHaveOneWinnerAndOneConflict() throws Exception {
        List<ApiResponse> responses = concurrentPosts("parallel-conflict",
                List.of(payload("First", 8), payload("Second", 9)));
        assertEquals(List.of(202, 409), responses.stream().map(ApiResponse::status).sorted().toList());
        ApiResponse winner = responses.stream().filter(response -> response.status() == 202).findFirst().orElseThrow();
        ApiResponse loser = responses.stream().filter(response -> response.status() == 409).findFirst().orElseThrow();
        assertError(loser, 409);
        assertEquals(winner.json().path("environmentId").asText(),
                jdbc.queryForObject("SELECT id::text FROM environments", String.class));
        assertCounts(1);
    }

    @Test
    void failedOperationInsertRollsBackReservationAndEnvironment() throws Exception {
        assertAtomicRollback("operations");
    }

    @Test
    void failedAuditInsertRollsBackReservationEnvironmentAndOperation() throws Exception {
        assertAtomicRollback("audit_events");
    }

    @Test
    void requestIdsAreServerGeneratedReturnedAndLogged(CapturedOutput output) throws Exception {
        String supplied = "caller-controlled-request-id";
        ApiResponse invalid = request(port, "POST", "/api/v1/environments",
                payload("Preview", 8), Map.of("X-Request-Id", supplied,
                        "Authorization", TestUsers.basic(TestUsers.OPERATOR)));
        assertError(invalid, 400);
        String requestId = invalid.header("X-Request-Id");
        assertNotEquals(supplied, requestId);
        assertEquals(requestId, invalid.json().path("requestId").asText());
        assertTrue(output.getAll().contains(requestId), "The server-generated request ID must be logged");
        assertFalse(invalid.body().contains(supplied));
        ApiResponse valid = post("request-id", payload("Preview", 8));
        assertEquals(202, valid.status());
        assertDoesNotThrow(() -> UUID.fromString(valid.header("X-Request-Id")));
        assertNotEquals(requestId, valid.header("X-Request-Id"));
    }

    @Test
    void idempotencySurvivesStartingAFreshApplicationContext() throws Exception {
        ApiResponse original = post("restart-persistent", payload("Persistent", 8));
        assertEquals(202, original.status());

        // A separate server/context proves replay does not depend on the original application's memory.
        List<String> arguments = new ArrayList<>(List.of(
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource." + "password" + "=" + POSTGRES.getPassword()));
        arguments.addAll(TestUsers.commandLineArguments());
        try (var restarted = new SpringApplicationBuilder(PlatformLabApplication.class)
                .run(arguments.toArray(String[]::new))) {
            int restartedPort = ((WebServerApplicationContext) restarted).getWebServer().getPort();
            ApiResponse replay = request(restartedPort, "POST", "/api/v1/environments",
                    payload("Persistent", 8), Map.of("Idempotency-Key", "restart-persistent",
                            "Authorization", TestUsers.basic(TestUsers.OPERATOR)));
            assertEquals(202, replay.status(), replay.body());
            assertEquals(original.json(), replay.json());
            assertEquals(original.header("Location"), replay.header("Location"));
            assertEquals(200, request(restartedPort, "GET",
                    original.header("Location"), null, Map.of("Authorization", TestUsers.basic(TestUsers.OPERATOR))).status());
        }
        assertCounts(1);
    }

    @Test
    void deleteValidatesIdsAndRejectsCreateRacesAndDeletedEnvironments() throws Exception {
        assertError(delete("not-a-uuid"), 400);
        assertError(delete(UUID.randomUUID().toString()), 404);
        var created = post("delete-conflicts", payload("Delete conflicts", 8));
        UUID id = UUID.fromString(uuid(created.json(), "environmentId"));
        for (String status : List.of("REQUESTED", "PROVISIONING", "DELETED")) {
            jdbc.update("UPDATE environments SET status = ? WHERE id = ?", status, id);
            assertError(delete(id.toString()), 409);
        }
        assertCounts(1);
    }

    @Test
    void concurrentDeletesReuseOneDurableOperationAndReturnItsCurrentStatus() throws Exception {
        var created = post("delete-concurrent", payload("Concurrent delete", 8));
        UUID id = UUID.fromString(uuid(created.json(), "environmentId"));
        finishCreation(id, "READY");
        var executor = Executors.newFixedThreadPool(4);
        var barrier = new CyclicBarrier(4);
        try {
            var tasks = java.util.stream.IntStream.range(0, 4)
                    .<java.util.concurrent.Callable<ApiResponse>>mapToObj(index -> () -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        return delete(id.toString());
                    }).toList();
            String operationId = null;
            for (var future : executor.invokeAll(tasks, 30, TimeUnit.SECONDS)) {
                var accepted = future.get();
                assertEquals(202, accepted.status(), accepted.body());
                assertEquals("DELETING", accepted.json().path("environmentStatus").asText());
                assertEquals("PENDING", accepted.json().path("operationStatus").asText());
                String currentId = uuid(accepted.json(), "operationId");
                if (operationId != null) {
                    assertEquals(operationId, currentId);
                }
                operationId = currentId;
                assertEquals("/api/v1/operations/" + currentId, accepted.header("Location"));
            }
            UUID durableId = UUID.fromString(operationId);
            jdbc.update("UPDATE operations SET status = 'RUNNING' WHERE id = ?", durableId);
            var repeat = delete(id.toString());
            assertEquals(202, repeat.status());
            assertEquals(operationId, uuid(repeat.json(), "operationId"));
            assertEquals("RUNNING", repeat.json().path("operationStatus").asText());
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM operations", Integer.class));
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM audit_events", Integer.class));
            assertEquals("ENVIRONMENT_DELETE_REQUESTED", jdbc.queryForObject(
                    "SELECT action FROM audit_events WHERE operation_id = ?", String.class, durableId));
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                    jdbc.update("""
                            INSERT INTO operations (id, environment_id, type, status,
                                next_attempt_at, created_at, updated_at)
                            VALUES (?, ?, 'DELETE_ENVIRONMENT', 'PENDING', now(), now(), now())
                            """, UUID.randomUUID(), id));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failedDeleteCanBeRetriedWithoutErasingHistory() throws Exception {
        var created = post("delete-retry", payload("Retry delete", 8));
        UUID id = UUID.fromString(uuid(created.json(), "environmentId"));
        finishCreation(id, "FAILED");
        var first = delete(id.toString());
        UUID firstOperation = UUID.fromString(uuid(first.json(), "operationId"));
        jdbc.update("UPDATE operations SET status = 'FAILED', completed_at = now() WHERE id = ?", firstOperation);
        jdbc.update("UPDATE environments SET status = 'DELETE_FAILED' WHERE id = ?", id);
        var retry = delete(id.toString());
        assertEquals(202, retry.status());
        assertNotEquals(firstOperation.toString(), uuid(retry.json(), "operationId"));
        var history = get("/api/v1/environments/" + id + "/operations");
        assertEquals(3, history.json().path("totalElements").asInt());
        assertEquals("FAILED", get("/api/v1/operations/" + firstOperation).json().path("status").asText());
    }

    @Test
    void guardedDeleteIncludesExpiryBoundaryForReadyAndFailedEnvironments() throws Exception {
        Instant cutoff = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        for (String status : List.of("READY", "FAILED")) {
            for (Instant expiry : List.of(cutoff.minus(1, ChronoUnit.MICROS), cutoff)) {
                UUID id = seedEnvironment(status, expiry);
                var accepted = guardedDelete(id, cutoff.toString());
                assertEquals(202, accepted.status(), accepted.body());
                UUID operationId = UUID.fromString(uuid(accepted.json(), "operationId"));
                assertEquals("DELETING", get("/api/v1/environments/" + id).json().path("status").asText());
                assertEquals("DELETE_ENVIRONMENT", jdbc.queryForObject(
                        "SELECT type FROM operations WHERE id = ?", String.class, operationId));
                assertEquals(1, jdbc.queryForObject(
                        "SELECT count(*) FROM audit_events WHERE operation_id = ?", Integer.class, operationId));
            }
        }
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM operations", Integer.class));
    }

    @Test
    void guardedDeleteRejectsNewerExpiryAndIneligibleStatesWithoutWrites() throws Exception {
        Instant cutoff = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        for (Instant expiry : List.of(cutoff.plus(1, ChronoUnit.MICROS), Instant.now().plusSeconds(3600))) {
            for (String status : List.of("READY", "FAILED")) {
                UUID id = seedEnvironment(status, expiry);
                assertStateConflict(guardedDelete(id, cutoff.toString()));
                assertEquals(status, get("/api/v1/environments/" + id).json().path("status").asText());
            }
        }
        for (String status : List.of("REQUESTED", "PROVISIONING", "DELETE_FAILED", "DELETED", "DELETING")) {
            UUID id = seedEnvironment(status, cutoff.minusSeconds(1));
            assertStateConflict(guardedDelete(id, cutoff.toString()));
            assertEquals(status, get("/api/v1/environments/" + id).json().path("status").asText());
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM operations", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM audit_events", Integer.class));
    }

    @Test
    void guardedDeleteDoesNotRetryFailedDeletionButManualDeleteStillDoes() throws Exception {
        Instant cutoff = Instant.now().minusSeconds(60);
        UUID id = seedEnvironment("FAILED", cutoff.minusSeconds(1));
        var first = guardedDelete(id, cutoff.toString());
        assertEquals(202, first.status(), first.body());
        UUID failedOperation = UUID.fromString(uuid(first.json(), "operationId"));
        jdbc.update("UPDATE operations SET status = 'FAILED', completed_at = now() WHERE id = ?", failedOperation);
        jdbc.update("UPDATE environments SET status = 'DELETE_FAILED' WHERE id = ?", id);

        assertStateConflict(guardedDelete(id, cutoff.toString()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM operations", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_events", Integer.class));
        assertEquals("DELETE_FAILED", get("/api/v1/environments/" + id).json().path("status").asText());
        var manual = delete(id.toString());
        assertEquals(202, manual.status(), manual.body());
        assertNotEquals(failedOperation.toString(), uuid(manual.json(), "operationId"));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM operations", Integer.class));
        assertEquals("FAILED", get("/api/v1/operations/" + failedOperation).json().path("status").asText());
    }

    @Test
    void guardedDeleteRechecksExpiryEvenForActiveDeletion() throws Exception {
        UUID id = seedEnvironment("READY", Instant.now().plusSeconds(3600));
        var manual = delete(id.toString());
        assertEquals(202, manual.status(), manual.body());
        UUID operationId = UUID.fromString(uuid(manual.json(), "operationId"));
        for (String operationStatus : List.of("PENDING", "RUNNING")) {
            jdbc.update("UPDATE operations SET status = ? WHERE id = ?", operationStatus, operationId);
            var reused = guardedDelete(id, Instant.now().minusSeconds(60).toString());
            assertStateConflict(reused);
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM operations", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_events", Integer.class));
    }

    @Test
    void guardedDeleteRejectsInvalidOrFutureCutoffsWithoutWrites() throws Exception {
        UUID id = seedEnvironment("READY", Instant.now().minusSeconds(3600));
        for (String cutoff : List.of("", "not-an-instant", "2026-10-16", "2026-10-16T12:34:56",
                "2026-02-30T12:34:56Z", Instant.now().plusSeconds(3600).toString())) {
            var response = guardedDelete(id, cutoff);
            assertError(response, 400);
            assertEquals("INVALID_REQUEST", response.json().path("code").asText());
        }
        assertEquals("READY", get("/api/v1/environments/" + id).json().path("status").asText());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM operations", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM audit_events", Integer.class));
    }

    @Test
    void guardedDeleteChecksCurrentExpiryAndStatusAfterAcquiringRowLock() throws Exception {
        Instant cutoff = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        for (boolean changeExpiry : List.of(true, false)) {
            UUID id = seedEnvironment("READY", cutoff.minusSeconds(1));
            var executor = Executors.newSingleThreadExecutor();
            try (var connection = jdbc.getDataSource().getConnection()) {
                connection.setAutoCommit(false);
                try (var lock = connection.prepareStatement("SELECT id FROM environments WHERE id = ? FOR UPDATE")) {
                    lock.setObject(1, id);
                    try (var rows = lock.executeQuery()) {
                        assertTrue(rows.next());
                    }
                }
                var pending = executor.submit(() -> guardedDelete(id, cutoff.toString()));
                long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                boolean blocked;
                do {
                    blocked = jdbc.queryForObject("""
                            SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                                WHERE wait_event_type = 'Lock' AND query LIKE '%FROM environments WHERE id =%')
                            """, Boolean.class);
                    if (!blocked) {
                        Thread.sleep(20);
                    }
                } while (!blocked && System.nanoTime() < deadline);
                assertTrue(blocked, "DELETE must wait for the existing environment row lock");
                try (var update = connection.prepareStatement(
                        "UPDATE environments SET expires_at = ?, status = ? WHERE id = ?")) {
                    update.setObject(1, (changeExpiry ? Instant.now().plusSeconds(3600) : cutoff.minusSeconds(1))
                            .atOffset(ZoneOffset.UTC));
                    update.setString(2, changeExpiry ? "READY" : "DELETE_FAILED");
                    update.setObject(3, id);
                    update.executeUpdate();
                }
                connection.commit();
                assertStateConflict(pending.get(10, TimeUnit.SECONDS));
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM operations", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM audit_events", Integer.class));
    }

    @Test
    void pythonCleanupDryRunThenExecuteDeletesOnlyEligibleExpiredFixtures() throws Exception {
        Instant expired = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.MICROS);
        List<UUID> eligible = List.of(seedEnvironment("READY", expired), seedEnvironment("FAILED", expired));
        Map<UUID, String> untouched = Map.of(
                seedEnvironment("READY", Instant.now().plusSeconds(3600)), "READY",
                seedEnvironment("DELETE_FAILED", expired), "DELETE_FAILED",
                seedEnvironment("REQUESTED", expired), "REQUESTED",
                seedEnvironment("PROVISIONING", expired), "PROVISIONING",
                seedEnvironment("DELETING", expired), "DELETING",
                seedEnvironment("DELETED", expired), "DELETED");

        Set<String> eligibleIds = new HashSet<>(eligible.stream().map(UUID::toString).toList());
        JsonNode dryRun = runPythonCleanup(false);
        assertCleanupSummary(dryRun, "dry-run", 2, 0, 6);
        assertEquals(eligibleIds, jsonStrings(dryRun.path("selectedIds")));
        assertEquals(0, dryRun.path("acceptedOperations").size());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM operations", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM audit_events", Integer.class));
        assertEquals("READY", get("/api/v1/environments/" + eligible.get(0)).json().path("status").asText());
        assertEquals("FAILED", get("/api/v1/environments/" + eligible.get(1)).json().path("status").asText());

        JsonNode executed = runPythonCleanup(true);
        assertCleanupSummary(executed, "execute", 2, 2, 6);
        assertEquals(eligibleIds, jsonStrings(executed.path("selectedIds")));
        Set<String> requestedIds = new HashSet<>();
        Set<String> requestedOperations = new HashSet<>();
        assertEquals(2, executed.path("acceptedOperations").size());
        for (JsonNode accepted : executed.path("acceptedOperations")) {
            String environmentId = uuid(accepted, "environmentId");
            String operationId = uuid(accepted, "operationId");
            assertTrue(requestedIds.add(environmentId), "Each eligible environment is requested once");
            assertTrue(requestedOperations.add(operationId), "Each acceptance has its own durable operation");
            assertEquals("202", accepted.path("source").asText());
            assertEquals(environmentId, jdbc.queryForObject(
                    "SELECT environment_id::text FROM operations WHERE id = ?", String.class,
                    UUID.fromString(operationId)));
        }
        assertEquals(eligibleIds, requestedIds);
        for (UUID id : eligible) {
            assertEquals("DELETING", get("/api/v1/environments/" + id).json().path("status").asText());
            assertEquals(1, jdbc.queryForObject("""
                    SELECT count(*) FROM operations
                    WHERE environment_id = ? AND type = 'DELETE_ENVIRONMENT' AND status = 'PENDING'
                    """, Integer.class, id));
        }
        for (var fixture : untouched.entrySet()) {
            assertEquals(fixture.getValue(),
                    get("/api/v1/environments/" + fixture.getKey()).json().path("status").asText());
        }
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM operations", Integer.class));
        assertEquals(List.of(TestUsers.CLEANUP, TestUsers.CLEANUP), jdbc.queryForList(
                "SELECT actor FROM audit_events WHERE action = 'ENVIRONMENT_DELETE_REQUESTED'", String.class));

        JsonNode repeated = runPythonCleanup(true);
        assertCleanupSummary(repeated, "execute", 0, 0, 8);
        assertTrue(repeated.path("selectedIds").isEmpty());
        assertTrue(repeated.path("acceptedOperations").isEmpty());
        assertEquals(3, repeated.path("skipReasons").path("DELETING").asInt());
        assertEquals(requestedOperations,
                new HashSet<>(jdbc.queryForList("SELECT id::text FROM operations", String.class)));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM audit_events", Integer.class));
    }

    private void assertCleanupSummary(JsonNode summary, String mode, int eligible, int requested, int skipped) {
        assertEquals(mode, summary.path("mode").asText());
        assertTrue(summary.path("scanComplete").asBoolean());
        assertEquals(8, summary.path("scanned").asInt());
        assertEquals(eligible, summary.path("eligible").asInt());
        assertEquals(requested, summary.path("requested").asInt());
        assertEquals(skipped, summary.path("skipped").asInt());
        assertEquals(0, summary.path("exitStatus").asInt());
        for (String counter : List.of("duplicates", "failed", "unknown", "alreadyDeleting", "alreadyDeleted")) {
            assertEquals(0, summary.path(counter).asInt(), counter);
        }
        assertTrue(summary.path("errors").isEmpty());
    }

    private Set<String> jsonStrings(JsonNode values) {
        Set<String> result = new HashSet<>();
        for (JsonNode value : values) {
            assertTrue(result.add(value.asText()), "Duplicate JSON selection");
        }
        return result;
    }

    private JsonNode runPythonCleanup(boolean execute) throws Exception {
        Path script = Path.of("..", "automation", "cleanup_expired.py").toAbsolutePath().normalize();
        List<String> command = new ArrayList<>(List.of("python3", script.toString(), "--api-base-url",
                "http://127.0.0.1:" + port + "/api/v1", "--json"));
        if (execute) {
            command.add("--execute");
        }
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("PLATFORM_CLEANUP_USERNAME", TestUsers.CLEANUP);
        builder.environment().put("PLATFORM_CLEANUP_PASSWORD", TestUsers.password(TestUsers.CLEANUP));
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Python cleanup timed out");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            JsonNode summary = mapper.readTree(output);
            assertTrue(summary.isObject(), output);
            return summary;
        } finally {
            process.destroyForcibly();
        }
    }

    private UUID seedEnvironment(String status, Instant expiry) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO environments (id, name, namespace, template_id, status,
                    expires_at, created_at, updated_at)
                VALUES (?, 'Cleanup fixture', ?, 'sample-service', ?, ?, ?, now())
                """, id, "env-" + id.toString().replace("-", ""), status, expiry.atOffset(ZoneOffset.UTC),
                expiry.minusSeconds(3600).atOffset(ZoneOffset.UTC));
        return id;
    }

    private ApiResponse guardedDelete(UUID id, String cutoff) throws Exception {
        return request(port, "DELETE", "/api/v1/environments/" + id + "?expiredBefore="
                + URLEncoder.encode(cutoff, StandardCharsets.UTF_8), null,
                Map.of("Authorization", TestUsers.basic(TestUsers.OPERATOR)));
    }

    private void assertStateConflict(ApiResponse response) {
        assertError(response, 409);
        assertEquals("ENVIRONMENT_STATE_CONFLICT", response.json().path("code").asText());
    }

    @Test
    void deletionAuditFailureRollsBackOperationAndEnvironmentState() throws Exception {
        var created = post("delete-rollback", payload("Rollback delete", 8));
        UUID id = UUID.fromString(uuid(created.json(), "environmentId"));
        finishCreation(id, "READY");
        jdbc.execute("""
                ALTER TABLE audit_events ADD CONSTRAINT test_reject_delete
                CHECK (action <> 'ENVIRONMENT_DELETE_REQUESTED') NOT VALID
                """);
        try {
            assertError(delete(id.toString()), 500);
            assertEquals("READY", get("/api/v1/environments/" + id).json().path("status").asText());
            assertCounts(1);
        } finally {
            jdbc.execute("ALTER TABLE audit_events DROP CONSTRAINT test_reject_delete");
        }
        assertEquals(202, delete(id.toString()).status());
    }

    @Test
    void mutationsWithoutValidCredentialsAreRejectedWithoutWrites() throws Exception {
        UUID id = seedEnvironment("READY", Instant.now().minusSeconds(3600));
        String cutoff = URLEncoder.encode(Instant.now().minusSeconds(60).toString(), StandardCharsets.UTF_8);
        List<Map<String, String>> credentials = List.of(Map.of(),
                Map.of("Authorization", TestUsers.basic(TestUsers.OPERATOR, "wrong-password")),
                Map.of("Authorization", TestUsers.basic("unknown-user", "anything")),
                Map.of("Authorization", "Basic !!!not-base64"),
                Map.of("Authorization", "Bearer " + TestUsers.password(TestUsers.OPERATOR)),
                Map.of("X-Actor", TestUsers.OPERATOR, "X-Forwarded-User", TestUsers.OPERATOR));
        for (Map<String, String> headers : credentials) {
            Map<String, String> create = new java.util.HashMap<>(headers);
            create.put("Idempotency-Key", "anonymous-create");
            for (ApiResponse response : List.of(
                    request(port, "POST", "/api/v1/environments", payload("Anonymous", 8), create),
                    request(port, "DELETE", "/api/v1/environments/" + id, null, headers),
                    request(port, "DELETE", "/api/v1/environments/" + id + "?expiredBefore=" + cutoff, null, headers),
                    request(port, "PUT", "/api/v1/environments/" + id, payload("Anonymous", 8), headers),
                    request(port, "GET", "/api/v1/me", null, headers))) {
                assertUnauthenticated(response);
            }
        }
        assertEquals("READY", get("/api/v1/environments/" + id).json().path("status").asText());
        for (String table : List.of("operations", "idempotency_requests", "audit_events")) {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class), table);
        }
    }

    @Test
    void allReadsRequireCredentialsExceptHealthProbes() throws Exception {
        UUID id = seedEnvironment("READY", Instant.now().plusSeconds(3600));
        for (String path : List.of("/api/v1/environments", "/api/v1/environments/" + id,
                "/api/v1/environments/" + id + "/operations", "/api/v1/operations/" + UUID.randomUUID(),
                "/api/v1/operations/metrics", "/api/v1/me")) {
            assertUnauthenticated(request(port, "GET", path, null, Map.of()));
            assertEquals(401, request(port, "HEAD", path, null, Map.of()).status());
        }
        assertEquals(200, request(port, "GET", "/actuator/health/liveness", null, Map.of()).status());
        assertEquals(200, request(port, "GET", "/actuator/health/readiness", null, Map.of()).status());
    }

    @Test
    void identityEndpointReportsAuthenticatedUserAndRoles() throws Exception {
        for (var expected : Map.of(TestUsers.OPERATOR, "OPERATOR", TestUsers.CLEANUP, "CLEANUP").entrySet()) {
            var me = request(port, "GET", "/api/v1/me", null,
                    Map.of("Authorization", TestUsers.basic(expected.getKey())));
            assertEquals(200, me.status(), me.body());
            assertFields(me.json(), "username", "roles", "teams");
            assertEquals(expected.getKey(), me.json().path("username").asText());
            assertEquals(1, me.json().path("roles").size());
            assertEquals(expected.getValue(), me.json().path("roles").get(0).asText());
            assertTrue(me.header("Set-Cookie").isEmpty(), "Authentication must stay stateless");
        }
    }

    @Test
    void cleanupRoleMayOnlyRequestExpiryGuardedDeletion() throws Exception {
        Map<String, String> cleanup = Map.of("Authorization", TestUsers.basic(TestUsers.CLEANUP));
        Map<String, String> create = Map.of("Authorization", TestUsers.basic(TestUsers.CLEANUP),
                "Idempotency-Key", "cleanup-create");
        assertForbidden(request(port, "POST", "/api/v1/environments", payload("Cleanup", 8), create));
        UUID unexpired = seedEnvironment("READY", Instant.now().plusSeconds(3600));
        assertForbidden(request(port, "DELETE", "/api/v1/environments/" + unexpired, null, cleanup));
        UUID expired = seedEnvironment("READY", Instant.now().minusSeconds(3600));
        assertForbidden(request(port, "DELETE", "/api/v1/environments/" + expired, null, cleanup));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM operations", Integer.class));

        String cutoff = URLEncoder.encode(Instant.now().minusSeconds(60).toString(), StandardCharsets.UTF_8);
        assertStateConflict(request(port, "DELETE", "/api/v1/environments/" + unexpired
                + "?expiredBefore=" + cutoff, null, cleanup));
        assertError(request(port, "DELETE", "/api/v1/environments/" + expired + "?expiredBefore=", null, cleanup), 400);
        var accepted = request(port, "DELETE", "/api/v1/environments/" + expired + "?expiredBefore=" + cutoff,
                null, cleanup);
        assertEquals(202, accepted.status(), accepted.body());
        assertEquals(TestUsers.CLEANUP, jdbc.queryForObject("SELECT actor FROM audit_events WHERE operation_id = ?",
                String.class, UUID.fromString(uuid(accepted.json(), "operationId"))));
        assertEquals("READY", get("/api/v1/environments/" + unexpired).json().path("status").asText());
    }

    @Test
    void auditEventsRecordEachAuthenticatedRequester() throws Exception {
        var created = post("audit-identity", payload("Audit identity", 8));
        assertEquals(202, created.status(), created.body());
        UUID id = UUID.fromString(uuid(created.json(), "environmentId"));
        // Another account owns an independent reservation even with the same key and payload.
        var replay = request(port, "POST", "/api/v1/environments", payload("Audit identity", 8),
                Map.of("Idempotency-Key", "audit-identity", "Authorization", TestUsers.basic(TestUsers.SECOND_OPERATOR)));
        assertEquals(202, replay.status(), replay.body());
        assertNotEquals(created.json().path("environmentId"), replay.json().path("environmentId"));
        finishCreation(id, "READY");
        var deleted = request(port, "DELETE", "/api/v1/environments/" + id, null,
                Map.of("Authorization", TestUsers.basic(TestUsers.SECOND_OPERATOR)));
        assertEquals(202, deleted.status(), deleted.body());
        assertEquals(List.of("ENVIRONMENT_REQUESTED:" + TestUsers.OPERATOR,
                "ENVIRONMENT_REQUESTED:" + TestUsers.SECOND_OPERATOR,
                "ENVIRONMENT_DELETE_REQUESTED:" + TestUsers.SECOND_OPERATOR), jdbc.queryForList(
                "SELECT action || ':' || actor FROM audit_events ORDER BY created_at, action DESC", String.class));
    }

    @Test
    void developerCreationRequiresTrustedMembershipBeforeAnyReservation() throws Exception {
        seedTeams();
        for (String body : List.of(payload("Denied", 1),
                teamPayload("Denied", "team-b"), teamPayload("Denied", "unknown-team"))) {
            assertForbidden(postAs(TestUsers.DEVELOPER, "denied-key", body));
            assertCounts(0);
        }
        var spoofed = request(port, "POST", "/api/v1/environments", teamPayload("Denied", "team-b"),
                Map.of("Authorization", TestUsers.basic(TestUsers.DEVELOPER), "Idempotency-Key", "spoofed",
                       "X-Team-Id", "team-b", "X-Actor", TestUsers.OTHER_DEVELOPER));
        assertForbidden(spoofed);
        assertCounts(0);
        assertForbidden(postAs(TestUsers.OPERATOR, "unknown", teamPayload("Denied", "unknown-team")));
        assertCounts(0);
        var accepted = postAs(TestUsers.DEVELOPER, "denied-key", teamPayload("Allowed", "team-a"));
        assertEquals(202, accepted.status(), accepted.body());
        var row = jdbc.queryForMap("SELECT team_id, created_by FROM environments");
        assertEquals("team-a", row.get("team_id"));
        assertEquals(TestUsers.DEVELOPER, row.get("created_by"));
        assertEquals(TestUsers.DEVELOPER, jdbc.queryForObject("SELECT actor FROM audit_events", String.class));
        assertEquals(TestUsers.DEVELOPER + ":team-a",
                jdbc.queryForObject("SELECT scope FROM idempotency_requests", String.class));
        assertCounts(1);
    }

    @Test
    void operatorCanCreateForATeamWithoutMembership() throws Exception {
        seedTeams();
        assertTrue(getAs(TestUsers.OPERATOR, "/api/v1/me").json().path("teams").isEmpty());
        var created = postAs(TestUsers.OPERATOR, "operator-team", teamPayload("Managed", "team-b"));
        assertEquals(202, created.status(), created.body());
        var detail = get("/api/v1/environments/" + uuid(created.json(), "environmentId"));
        assertEquals("team-b", detail.json().path("teamId").asText());
        assertEquals(TestUsers.OPERATOR, detail.json().path("createdBy").asText());
        assertEquals(200, getAs(TestUsers.OTHER_DEVELOPER,
                "/api/v1/operations/" + uuid(created.json(), "operationId")).status());
        assertCounts(1);
    }

    @Test
    void identityTeamsComeOnlyFromCurrentDatabaseMemberships() throws Exception {
        seedTeams();
        jdbc.update("INSERT INTO account_team_memberships VALUES (?, 'team-b')", TestUsers.DEVELOPER);
        var identity = request(port, "GET", "/api/v1/me?teamId=untrusted", null,
                Map.of("Authorization", TestUsers.basic(TestUsers.DEVELOPER),
                       "X-Team-Id", "untrusted", "X-Forwarded-User", TestUsers.OPERATOR));
        assertEquals(200, identity.status(), identity.body());
        assertFields(identity.json(), "username", "roles", "teams");
        assertEquals(TestUsers.DEVELOPER, identity.json().path("username").asText());
        assertEquals(mapper.readTree("[\"DEVELOPER\"]"), identity.json().path("roles"));
        assertEquals(mapper.readTree("[\"team-a\",\"team-b\"]"), identity.json().path("teams"));
        jdbc.update("DELETE FROM account_team_memberships WHERE username = ?", TestUsers.DEVELOPER);
        assertTrue(getAs(TestUsers.DEVELOPER, "/api/v1/me").json().path("teams").isEmpty());
        assertTrue(getAs(TestUsers.OPERATOR, "/api/v1/me").json().path("teams").isEmpty());
        assertCounts(0);
    }

    @Test
    void teamReadsHistoryOperationsAndPaginationNeverLeakOtherTeamsOrLegacyRows() throws Exception {
        seedTeams();
        var first = postAs(TestUsers.DEVELOPER, "a-first", teamPayload("A first", "team-a"));
        var second = postAs(TestUsers.TEAMMATE, "a-second", teamPayload("A second", "team-a"));
        var other = postAs(TestUsers.OTHER_DEVELOPER, "b-first", teamPayload("B first", "team-b"));
        var legacy = post("unowned", payload("Operator managed", 1));
        for (var created : List.of(first, second, other, legacy)) {
            assertEquals(202, created.status(), created.body());
        }
        Set<String> expected = Set.of(uuid(first.json(), "environmentId"), uuid(second.json(), "environmentId"));
        Set<String> seen = new HashSet<>();
        for (int page = 0; page < 2; page++) {
            var listing = getAs(TestUsers.DEVELOPER, "/api/v1/environments?page=" + page + "&size=1");
            assertEquals(200, listing.status(), listing.body());
            assertEquals(2, listing.json().path("totalElements").asInt());
            assertEquals(2, listing.json().path("totalPages").asInt());
            assertEquals(1, listing.json().path("items").size());
            var item = listing.json().path("items").get(0);
            assertEquals("team-a", item.path("teamId").asText());
            seen.add(item.path("id").asText());
        }
        assertEquals(expected, seen);
        assertTrue(getAs(TestUsers.DEVELOPER, "/api/v1/environments?page=2&size=1")
                .json().path("items").isEmpty());
        for (String user : List.of(TestUsers.DEVELOPER, TestUsers.TEAMMATE)) {
            String id = uuid(first.json(), "environmentId");
            assertEquals(200, getAs(user, "/api/v1/environments/" + id).status());
            var history = getAs(user, "/api/v1/environments/" + id + "/operations");
            assertEquals(200, history.status(), history.body());
            assertEquals(1, history.json().path("totalElements").asInt());
            assertEquals(uuid(first.json(), "operationId"), history.json().path("items").get(0).path("id").asText());
            assertEquals(200, getAs(user, "/api/v1/operations/" + uuid(first.json(), "operationId")).status());
            for (var forbidden : List.of(other, legacy)) {
                String hiddenId = uuid(forbidden.json(), "environmentId");
                assertForbidden(getAs(user, "/api/v1/environments/" + hiddenId));
                assertForbidden(getAs(user, "/api/v1/environments/" + hiddenId + "/operations"));
                assertForbidden(getAs(user, "/api/v1/operations/" + uuid(forbidden.json(), "operationId")));
                assertForbidden(deleteAs(user, hiddenId, ""));
                assertForbidden(deleteAs(user, hiddenId, "?expiredBefore="
                       + URLEncoder.encode(Instant.now().minusSeconds(60).toString(), StandardCharsets.UTF_8)));
            }
            assertForbidden(getAs(user, "/api/v1/operations/metrics"));
        }
        assertCounts(4);
        for (String user : List.of(TestUsers.OPERATOR, TestUsers.CLEANUP)) {
            var listing = getAs(user, "/api/v1/environments?size=1");
            assertEquals(4, listing.json().path("totalElements").asInt());
            assertEquals(4, listing.json().path("totalPages").asInt());
        }
        for (var created : List.of(first, other, legacy)) {
            String id = uuid(created.json(), "environmentId");
            assertEquals(200, get("/api/v1/environments/" + id).status());
            assertEquals(200, get("/api/v1/environments/" + id + "/operations").status());
            assertEquals(200, get("/api/v1/operations/" + uuid(created.json(), "operationId")).status());
        }
        assertEquals(200, get("/api/v1/operations/metrics").status());
        jdbc.update("INSERT INTO account_team_memberships VALUES (?, 'team-b')", TestUsers.DEVELOPER);
        assertEquals(3, getAs(TestUsers.DEVELOPER, "/api/v1/environments").json().path("totalElements").asInt());
        jdbc.update("DELETE FROM account_team_memberships WHERE username = ?", TestUsers.DEVELOPER);
        var empty = getAs(TestUsers.DEVELOPER, "/api/v1/environments");
        assertEquals(0, empty.json().path("totalElements").asInt());
        assertEquals(0, empty.json().path("totalPages").asInt());
        assertTrue(empty.json().path("items").isEmpty());
    }

    @Test
    void teammateMayDeleteAndHistoryRemainsTeamReadable() throws Exception {
        seedTeams();
        var created = postAs(TestUsers.DEVELOPER, "shared-delete", teamPayload("Shared", "team-a"));
        UUID id = UUID.fromString(uuid(created.json(), "environmentId"));
        finishCreation(id, "READY");
        var deleted = deleteAs(TestUsers.TEAMMATE, id.toString(), "");
        assertEquals(202, deleted.status(), deleted.body());
        var replay = deleteAs(TestUsers.DEVELOPER, id.toString(), "");
        assertEquals(deleted.json(), replay.json());
        assertEquals(2, getAs(TestUsers.TEAMMATE, "/api/v1/environments/" + id + "/operations")
                .json().path("totalElements").asInt());
        assertEquals(200, getAs(TestUsers.TEAMMATE,
                "/api/v1/operations/" + uuid(deleted.json(), "operationId")).status());
        assertEquals(TestUsers.TEAMMATE, jdbc.queryForObject(
                "SELECT actor FROM audit_events WHERE action = 'ENVIRONMENT_DELETE_REQUESTED'", String.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM operations", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM audit_events", Integer.class));
    }

    @Test
    void scopedReplayIsIsolatedByAccountAndTeamAndRevocationDeniesBeforeWrites() throws Exception {
        seedTeams();
        jdbc.update("INSERT INTO account_team_memberships VALUES (?, 'team-b')", TestUsers.DEVELOPER);
        var first = postAs(TestUsers.DEVELOPER, "shared-key", teamPayload("Shared", "team-a"));
        var anotherTeam = postAs(TestUsers.DEVELOPER, "shared-key", teamPayload("Shared", "team-b"));
        var anotherAccount = postAs(TestUsers.TEAMMATE, "shared-key", teamPayload("Shared", "team-a"));
        Set<String> ids = new HashSet<>();
        for (var response : List.of(first, anotherTeam, anotherAccount)) {
            assertEquals(202, response.status(), response.body());
            ids.add(uuid(response.json(), "environmentId"));
        }
        assertEquals(3, ids.size());
        assertEquals(first.json(), postAs(TestUsers.DEVELOPER, "shared-key", teamPayload("Shared", "team-a")).json());
        assertEquals(anotherTeam.json(), postAs(TestUsers.DEVELOPER, "shared-key", teamPayload("Shared", "team-b")).json());
        assertError(postAs(TestUsers.DEVELOPER, "shared-key", teamPayload("Changed", "team-a")), 409);
        assertCounts(3);
        assertEquals(Set.of(TestUsers.DEVELOPER + ":team-a", TestUsers.DEVELOPER + ":team-b",
                TestUsers.TEAMMATE + ":team-a"), new HashSet<>(jdbc.queryForList(
                       "SELECT scope FROM idempotency_requests", String.class)));
        UUID id = UUID.fromString(uuid(first.json(), "environmentId"));
        finishCreation(id, "READY");
        jdbc.update("DELETE FROM account_team_memberships WHERE username = ? AND team_id = 'team-a'",
                TestUsers.DEVELOPER);
        assertForbidden(postAs(TestUsers.DEVELOPER, "shared-key", teamPayload("Shared", "team-a")));
        assertForbidden(postAs(TestUsers.DEVELOPER, "fresh-key", teamPayload("Fresh", "team-a")));
        assertForbidden(deleteAs(TestUsers.DEVELOPER, id.toString(), ""));
        assertForbidden(getAs(TestUsers.DEVELOPER, "/api/v1/environments/" + id));
        assertForbidden(getAs(TestUsers.DEVELOPER, "/api/v1/environments/" + id + "/operations"));
        assertForbidden(getAs(TestUsers.DEVELOPER, "/api/v1/operations/" + uuid(first.json(), "operationId")));
        assertCounts(3);
        jdbc.update("INSERT INTO account_team_memberships VALUES (?, 'team-a')", TestUsers.DEVELOPER);
        assertEquals(first.json(), postAs(TestUsers.DEVELOPER, "shared-key", teamPayload("Shared", "team-a")).json());
        assertCounts(3);
    }

    @Test
    void concurrentScopedReservationsDeduplicateOnlyWithinTheSameAccountAndTeam() throws Exception {
        seedTeams();
        jdbc.update("INSERT INTO account_team_memberships VALUES (?, 'team-b')", TestUsers.DEVELOPER);
        String teamA = teamPayload("Concurrent", "team-a");
        String teamB = teamPayload("Concurrent", "team-b");
        var responses = concurrentPosts("shared-concurrent-key",
                List.of(teamA, teamA, teamA, teamA, teamB),
                List.of(TestUsers.DEVELOPER, TestUsers.DEVELOPER, TestUsers.DEVELOPER,
                        TestUsers.TEAMMATE, TestUsers.DEVELOPER));
        for (var response : responses) {
            assertEquals(202, response.status(), response.body());
        }
        assertEquals(responses.get(0).json(), responses.get(1).json());
        assertEquals(responses.get(0).json(), responses.get(2).json());
        assertEquals(3, responses.stream().map(response -> uuid(response.json(), "environmentId")).distinct().count());
        assertEquals(3, responses.stream().map(response -> uuid(response.json(), "operationId")).distinct().count());
        assertEquals(responses.get(0).json(), postAs(TestUsers.DEVELOPER, "shared-concurrent-key", teamA).json());
        assertEquals(responses.get(3).json(), postAs(TestUsers.TEAMMATE, "shared-concurrent-key", teamA).json());
        assertEquals(responses.get(4).json(), postAs(TestUsers.DEVELOPER, "shared-concurrent-key", teamB).json());
        assertCounts(3);
        assertEquals(Set.of(TestUsers.DEVELOPER + ":team-a", TestUsers.DEVELOPER + ":team-b",
                TestUsers.TEAMMATE + ":team-a"), new HashSet<>(jdbc.queryForList(
                        "SELECT scope FROM idempotency_requests", String.class)));
    }

    @Test
    void legacyEmptyScopeIsPreservedAndOnlyOperatorsCanReplayIt() throws Exception {
        seedTeams();
        var original = post("legacy-key", payload("Legacy", 1));
        UUID id = UUID.fromString(uuid(original.json(), "environmentId"));
        jdbc.update("UPDATE idempotency_requests SET scope = '' WHERE key = 'legacy-key'");
        jdbc.update("UPDATE environments SET created_by = NULL WHERE id = ?", id);
        for (String operator : List.of(TestUsers.OPERATOR, TestUsers.SECOND_OPERATOR)) {
            assertEquals(original.json(), postAs(operator, "legacy-key", payload("Legacy", 1)).json());
            assertError(postAs(operator, "legacy-key", payload("Different", 1)), 409);
        }
        assertForbidden(postAs(TestUsers.DEVELOPER, "legacy-key", payload("Legacy", 1)));
        assertForbidden(postAs(TestUsers.CLEANUP, "legacy-key", payload("Legacy", 1)));
        assertCounts(1);
        assertEquals("", jdbc.queryForObject("SELECT scope FROM idempotency_requests", String.class));
        var preserved = get("/api/v1/environments/" + id);
        assertTrue(preserved.json().path("teamId").isNull());
        assertTrue(preserved.json().path("createdBy").isNull());
        var independent = postAs(TestUsers.DEVELOPER, "legacy-key", teamPayload("Legacy", "team-a"));
        assertEquals(202, independent.status(), independent.body());
        assertNotEquals(original.json().path("environmentId"), independent.json().path("environmentId"));
        assertCounts(2);
        finishCreation(id, "READY");
        assertEquals(202, deleteAs(TestUsers.SECOND_OPERATOR, id.toString(), "").status());
    }

    @Test
    void cleanupCanDiscoverEnvironmentsButCannotReadHistoryOperationsOrMetrics() throws Exception {
        seedTeams();
        var created = postAs(TestUsers.DEVELOPER, "cleanup-team", teamPayload("Expired", "team-a"));
        UUID id = UUID.fromString(uuid(created.json(), "environmentId"));
        finishCreation(id, "READY");
        assertEquals(200, getAs(TestUsers.CLEANUP, "/api/v1/environments/" + id).status());
        for (String path : List.of("/api/v1/environments/" + id + "/operations",
                "/api/v1/operations/" + uuid(created.json(), "operationId"), "/api/v1/operations/metrics")) {
            assertForbidden(getAs(TestUsers.CLEANUP, path));
        }
        assertForbidden(deleteAs(TestUsers.CLEANUP, id.toString(), ""));
        assertForbidden(postAs(TestUsers.CLEANUP, "cleanup-team", teamPayload("Expired", "team-a")));
        assertEquals(1, getAs(TestUsers.CLEANUP, "/api/v1/environments").json().path("totalElements").asInt());
        assertCounts(1);
    }

    @Test
    void cleanupGuardedDeleteReplayStillRequiresExpiryCutoffEligibility() throws Exception {
        seedTeams();
        var created = postAs(TestUsers.DEVELOPER, "cleanup-replay", teamPayload("Expired", "team-a"));
        UUID id = UUID.fromString(uuid(created.json(), "environmentId"));
        finishCreation(id, "READY");
        Instant expiry = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.MICROS);
        jdbc.update("UPDATE environments SET expires_at = ?, created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(expiry), java.sql.Timestamp.from(expiry.minusSeconds(3600)), id);
        String cutoff = "?expiredBefore=" + URLEncoder.encode(expiry.toString(), StandardCharsets.UTF_8);
        var deletion = deleteAs(TestUsers.CLEANUP, id.toString(), cutoff);
        assertEquals(202, deletion.status(), deletion.body());
        assertEquals(deletion.json(), deleteAs(TestUsers.CLEANUP, id.toString(), cutoff).json());
        assertError(deleteAs(TestUsers.CLEANUP, id.toString(), "?expiredBefore="
                + URLEncoder.encode(expiry.minusSeconds(1).toString(), StandardCharsets.UTF_8)), 409);
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM operations", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM audit_events", Integer.class));
        assertEquals("DELETING", jdbc.queryForObject("SELECT status FROM environments WHERE id = ?", String.class, id));
    }

    private void seedTeams() {
        jdbc.update("INSERT INTO platform_teams VALUES ('team-a'), ('team-b')");
        for (String user : List.of(TestUsers.DEVELOPER, TestUsers.TEAMMATE)) {
            jdbc.update("INSERT INTO account_team_memberships VALUES (?, 'team-a')", user);
        }
        jdbc.update("INSERT INTO account_team_memberships VALUES (?, 'team-b')", TestUsers.OTHER_DEVELOPER);
    }

    private String teamPayload(String name, String team) {
        return mapper.writeValueAsString(Map.of("name", name, "templateId", "sample-service",
                "ttlHours", 1, "teamId", team));
    }

    private ApiResponse postAs(String username, String key, String body) throws Exception {
        return request(port, "POST", "/api/v1/environments", body,
                Map.of("Authorization", TestUsers.basic(username), "Idempotency-Key", key));
    }

    private ApiResponse getAs(String username, String path) throws Exception {
        return request(port, "GET", path, null, Map.of("Authorization", TestUsers.basic(username)));
    }

    private ApiResponse deleteAs(String username, String id, String query) throws Exception {
        return request(port, "DELETE", "/api/v1/environments/" + id + query, null,
                Map.of("Authorization", TestUsers.basic(username)));
    }

    private void assertUnauthenticated(ApiResponse response) {
        assertError(response, 401);
        assertEquals("UNAUTHENTICATED", response.json().path("code").asText());
        assertTrue(response.header("WWW-Authenticate").isEmpty(), "Browsers must not be prompted to cache credentials");
        assertTrue(response.header("Set-Cookie").isEmpty());
    }

    private void assertForbidden(ApiResponse response) {
        assertError(response, 403);
        assertEquals("FORBIDDEN", response.json().path("code").asText());
    }

    private void finishCreation(UUID id, String status) {
        jdbc.update("UPDATE operations SET status = ?, completed_at = now() WHERE environment_id = ?",
                "READY".equals(status) ? "SUCCEEDED" : "FAILED", id);
        jdbc.update("UPDATE environments SET status = ? WHERE id = ?", status, id);
    }

    private ApiResponse delete(String id) throws Exception {
        return request(port, "DELETE", "/api/v1/environments/" + id, null,
                Map.of("Authorization", TestUsers.basic(TestUsers.OPERATOR)));
    }

    private void assertAtomicRollback(String table) throws Exception {
        String constraint = "test_reject_create";
        jdbc.execute("ALTER TABLE " + table + " ADD CONSTRAINT " + constraint + " CHECK (false) NOT VALID");
        try {
            ApiResponse failed = post("rollback-" + table, payload("Rollback", 8));
            assertError(failed, 500);
            String body = failed.body().toLowerCase(java.util.Locale.ROOT);
            for (String leaked : List.of(constraint, "postgres", "sqlstate", "jdbc", "psqlexception",
                    "insert into", "stacktrace", "local-development")) {
                assertFalse(body.contains(leaked), "Internal database details leaked: " + failed.body());
            }
            assertCounts(0);
        } finally {
            jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT " + constraint);
        }
        assertEquals(202, post("rollback-" + table, payload("Rollback", 8)).status(),
                "A rolled-back reservation must not prevent retry");
        assertCounts(1);
    }

    private List<ApiResponse> concurrentPosts(String key, List<String> bodies) throws Exception {
        return concurrentPosts(key, bodies, java.util.Collections.nCopies(bodies.size(), TestUsers.OPERATOR));
    }

    private List<ApiResponse> concurrentPosts(String key, List<String> bodies, List<String> usernames) throws Exception {
        var executor = Executors.newFixedThreadPool(bodies.size());
        CyclicBarrier barrier = new CyclicBarrier(bodies.size());
        try {
            var tasks = java.util.stream.IntStream.range(0, bodies.size())
                    .<java.util.concurrent.Callable<ApiResponse>>mapToObj(index -> () -> {
                barrier.await(20, TimeUnit.SECONDS);
                return postAs(usernames.get(index), key, bodies.get(index));
            }).toList();
            List<ApiResponse> responses = new ArrayList<>();
            for (var future : executor.invokeAll(tasks, 60, TimeUnit.SECONDS)) {
                responses.add(future.get());
            }
            return responses;
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private void assertCounts(int expected) {
        for (String table : List.of("environments", "operations", "idempotency_requests", "audit_events")) {
            assertEquals(expected, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class), table);
        }
    }

    private void assertError(ApiResponse response, int status) {
        assertEquals(status, response.status(), response.body());
        assertFields(response.json(), "code", "message", "requestId", "fieldErrors");
        assertFalse(response.json().path("code").asText().isBlank());
        assertFalse(response.json().path("message").asText().isBlank());
        assertDoesNotThrow(() -> UUID.fromString(response.header("X-Request-Id")));
        assertEquals(response.header("X-Request-Id"), response.json().path("requestId").asText());
        assertTrue(response.json().path("fieldErrors").isArray());
        for (JsonNode error : response.json().path("fieldErrors")) {
            assertFields(error, "field", "message");
            assertFalse(error.path("field").asText().isBlank());
            assertFalse(error.path("message").asText().isBlank());
        }
    }

    private void assertFieldError(ApiResponse response, String field) {
        for (JsonNode error : response.json().path("fieldErrors")) {
            if (field.equals(error.path("field").asText())) {
                return;
            }
        }
        fail("Expected field error for " + field + ": " + response.body());
    }

    private static void assertFields(JsonNode node, String... fields) {
        Set<String> actual = new HashSet<>();
        node.properties().forEach(entry -> actual.add(entry.getKey()));
        assertEquals(Set.of(fields), actual);
    }

    private static String uuid(JsonNode node, String field) {
        String value = node.path(field).asText();
        assertDoesNotThrow(() -> UUID.fromString(value));
        return value;
    }

    private static Instant timestamp(JsonNode node, String field) {
        return Instant.parse(node.path(field).asText());
    }

    private static void assertBetween(Instant actual, Instant lower, Instant upper) {
        assertFalse(actual.isBefore(lower), actual + " is before " + lower);
        assertFalse(actual.isAfter(upper), actual + " is after " + upper);
    }

    private String payload(String name, int ttl) {
        return mapper.writeValueAsString(Map.of("name", name, "templateId", "sample-service", "ttlHours", ttl));
    }

    private ApiResponse post(String key, String body) throws Exception {
        String authorization = TestUsers.basic(TestUsers.OPERATOR);
        return request(port, "POST", "/api/v1/environments", body, key == null
                ? Map.of("Authorization", authorization)
                : Map.of("Idempotency-Key", key, "Authorization", authorization));
    }

    private ApiResponse get(String path) throws Exception {
        return request(port, "GET", path, null, Map.of("Authorization", TestUsers.basic(TestUsers.OPERATOR)));
    }

    private ApiResponse request(int targetPort, String method, String path, String body, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + targetPort + path))
                .timeout(Duration.ofSeconds(30));
        headers.forEach(builder::header);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new ApiResponse(response.statusCode(), mapper.readTree(response.body()), response.body(), response.headers());
    }

    private record ApiResponse(int status, JsonNode json, String body, java.net.http.HttpHeaders headers) {
        String header(String name) {
            return headers.firstValue(name).orElse("");
        }
    }
}
