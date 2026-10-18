package io.github.dcfaight.platformlab.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
class TeamAuthorizationIntegrationTests {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        io.github.dcfaight.platformlab.TestDatabase.migrate(POSTGRES);
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
    void seedMemberships() {
        for (String table : List.of("audit_events", "idempotency_requests", "operations",
                "environments", "account_team_memberships", "platform_teams")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("INSERT INTO platform_teams VALUES ('team-a'), ('team-b')");
        jdbc.update("INSERT INTO account_team_memberships VALUES (?, 'team-a'), (?, 'team-a'), (?, 'team-b')",
                TestUsers.DEVELOPER, TestUsers.TEAMMATE, TestUsers.OTHER_DEVELOPER);
    }

    @Test
    void membershipUsesExactUsernameNotPrefixesCaseOrCallerSuppliedTeams() throws Exception {
        jdbc.update("INSERT INTO account_team_memberships VALUES (?, 'team-b'), (?, 'team-b')",
                TestUsers.DEVELOPER + "-shadow", TestUsers.DEVELOPER.toUpperCase(java.util.Locale.ROOT));
        var own = create(TestUsers.DEVELOPER, "own", "team-a");
        var other = create(TestUsers.OTHER_DEVELOPER, "other", "team-b");
        var unowned = create(TestUsers.OPERATOR, "unowned", null);
        assertEquals(202, own.status());
        assertEquals(202, other.status());
        assertEquals(202, unowned.status());

        var identity = call(TestUsers.DEVELOPER, "GET", "/api/v1/me?teamId=team-b", null,
                Map.of("X-Team-Id", "team-b", "X-Forwarded-User", TestUsers.OTHER_DEVELOPER));
        assertEquals(200, identity.status());
        assertEquals(mapper.readTree("[\"team-a\"]"), identity.json().path("teams"));
        var page = get(TestUsers.DEVELOPER, "/api/v1/environments?size=1");
        assertEquals(1, page.json().path("totalElements").asInt());
        assertEquals(1, page.json().path("totalPages").asInt());
        assertEquals(own.id(), page.json().path("items").get(0).path("id").asText());
        assertEquals(0, get(TestUsers.DEVELOPER, "/api/v1/environments?page=1&size=1")
                .json().path("items").size());
        assertEquals(3, get(TestUsers.OPERATOR, "/api/v1/environments").json().path("totalElements").asInt());
        assertEquals(3, get(TestUsers.CLEANUP, "/api/v1/environments").json().path("totalElements").asInt());

        for (var hidden : List.of(other, unowned)) {
            for (String path : List.of("/api/v1/environments/" + hidden.id(),
                    "/api/v1/environments/" + hidden.id() + "/operations",
                    "/api/v1/operations/" + hidden.operationId())) {
                assertEquals(403, get(TestUsers.DEVELOPER, path).status(), path);
            }
        }
        assertEquals(403, create(TestUsers.DEVELOPER, "not-a-member", "team-b").status());
        assertCounts(3);
    }

    @Test
    void loginCasingUsesConfiguredUsernameForMembershipAndReplayScope() throws Exception {
        String authorization = TestUsers.basic(TestUsers.DEVELOPER.toUpperCase(java.util.Locale.ROOT),
                TestUsers.password(TestUsers.DEVELOPER));
        var identity = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/me"))
                .timeout(Duration.ofSeconds(30)).header("Authorization", authorization).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, identity.statusCode(), identity.body());
        var me = mapper.readTree(identity.body());
        assertEquals(TestUsers.DEVELOPER, me.path("username").asText());
        assertEquals(mapper.readTree("[\"team-a\"]"), me.path("teams"));

        var original = create(TestUsers.DEVELOPER, "case-replay", "team-a");
        assertEquals(202, original.status());
        String body = mapper.writeValueAsString(Map.of("name", "Team preview",
                "templateId", "sample-service", "ttlHours", 1, "teamId", "team-a"));
        var replay = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/environments"))
                .timeout(Duration.ofSeconds(30)).header("Authorization", authorization)
                .header("Content-Type", "application/json").header("Idempotency-Key", "case-replay")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(202, replay.statusCode(), replay.body());
        assertEquals(original.json(), mapper.readTree(replay.body()));
        assertEquals(TestUsers.DEVELOPER + ":team-a",
                jdbc.queryForObject("SELECT scope FROM idempotency_requests", String.class));
        assertCounts(1);
    }

    @Test
    void replayReauthorizesCurrentPersistedOwnershipAndRejectsChangedTeamWithoutWrites() throws Exception {
        var original = create(TestUsers.DEVELOPER, "ownership-change", "team-a");
        assertEquals(202, original.status());
        assertEquals(original.json(), create(TestUsers.DEVELOPER, "ownership-change", "team-a").json());
        jdbc.update("UPDATE environments SET team_id = 'team-b' WHERE id = ?", UUID.fromString(original.id()));

        var deniedReplay = create(TestUsers.DEVELOPER, "ownership-change", "team-a");
        assertEquals(403, deniedReplay.status());
        assertEquals("FORBIDDEN", deniedReplay.json().path("code").asText());
        assertEquals(403, get(TestUsers.DEVELOPER, "/api/v1/environments/" + original.id()).status());
        assertEquals(403, get(TestUsers.DEVELOPER, "/api/v1/operations/" + original.operationId()).status());
        assertEquals(0, get(TestUsers.DEVELOPER, "/api/v1/environments").json().path("totalElements").asInt());
        assertEquals(200, get(TestUsers.OTHER_DEVELOPER, "/api/v1/environments/" + original.id()).status());
        assertCounts(1);

        jdbc.update("INSERT INTO account_team_memberships VALUES (?, 'team-b')", TestUsers.DEVELOPER);
        var conflict = create(TestUsers.DEVELOPER, "ownership-change", "team-a");
        assertEquals(409, conflict.status());
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.json().path("code").asText());
        assertCounts(1);
        var independent = create(TestUsers.DEVELOPER, "ownership-change", "team-b");
        assertEquals(202, independent.status());
        assertNotEquals(original.id(), independent.id());
        assertCounts(2);
        assertEquals(Set.of(TestUsers.DEVELOPER + ":team-a", TestUsers.DEVELOPER + ":team-b"),
                new HashSet<>(jdbc.queryForList("SELECT scope FROM idempotency_requests", String.class)));
    }

    @Test
    void revokedMembershipHidesHistoryAndPreventsBothFreshAndExistingKeyWrites() throws Exception {
        var created = create(TestUsers.DEVELOPER, "revoked", "team-a");
        assertEquals(202, created.status());
        jdbc.update("UPDATE operations SET status = 'SUCCEEDED', completed_at = now() WHERE id = ?",
                UUID.fromString(created.operationId()));
        jdbc.update("UPDATE environments SET status = 'READY' WHERE id = ?", UUID.fromString(created.id()));
        jdbc.update("DELETE FROM account_team_memberships WHERE username = ?", TestUsers.DEVELOPER);

        for (String key : List.of("revoked", "new-after-revocation")) {
            assertEquals(403, create(TestUsers.DEVELOPER, key, "team-a").status());
        }
        for (String path : List.of("/api/v1/environments/" + created.id(),
                "/api/v1/environments/" + created.id() + "/operations",
                "/api/v1/operations/" + created.operationId(), "/api/v1/operations/metrics")) {
            assertEquals(403, get(TestUsers.DEVELOPER, path).status(), path);
        }
        assertEquals(403, call(TestUsers.DEVELOPER, "DELETE",
                "/api/v1/environments/" + created.id(), null, Map.of()).status());
        var identity = get(TestUsers.DEVELOPER, "/api/v1/me");
        assertTrue(identity.json().path("teams").isEmpty());
        var page = get(TestUsers.DEVELOPER, "/api/v1/environments");
        assertTrue(page.json().path("items").isEmpty());
        assertEquals(0, page.json().path("totalElements").asInt());
        assertEquals(0, page.json().path("totalPages").asInt());
        assertEquals("READY", jdbc.queryForObject("SELECT status FROM environments", String.class));
        assertCounts(1);
        assertEquals(200, get(TestUsers.TEAMMATE, "/api/v1/environments/" + created.id()).status());
        jdbc.update("INSERT INTO account_team_memberships VALUES (?, 'team-a')", TestUsers.DEVELOPER);
        assertEquals(created.json(), create(TestUsers.DEVELOPER, "revoked", "team-a").json());
        assertCounts(1);
    }

    private void assertCounts(int count) {
        for (String table : List.of("environments", "operations", "audit_events", "idempotency_requests")) {
            assertEquals(count, jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class), table);
        }
    }

    private Response create(String username, String key, String team) throws Exception {
        var body = new java.util.HashMap<String, Object>(Map.of(
                "name", "Team preview", "templateId", "sample-service", "ttlHours", 1));
        if (team != null) {
            body.put("teamId", team);
        }
        return call(username, "POST", "/api/v1/environments", mapper.writeValueAsString(body),
                Map.of("Idempotency-Key", key));
    }

    private Response get(String username, String path) throws Exception {
        return call(username, "GET", path, null, Map.of());
    }

    private Response call(String username, String method, String path, String body, Map<String, String> headers)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30)).header("Authorization", TestUsers.basic(username));
        headers.forEach(request::header);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        var response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), mapper.readTree(response.body()));
    }

    private record Response(int status, JsonNode json) {
        String id() {
            return json.path("environmentId").asText();
        }

        String operationId() {
            return json.path("operationId").asText();
        }
    }
}
