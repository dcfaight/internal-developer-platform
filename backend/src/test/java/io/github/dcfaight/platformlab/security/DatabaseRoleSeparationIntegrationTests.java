package io.github.dcfaight.platformlab.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import io.github.dcfaight.platformlab.DatabaseMigration;
import io.github.dcfaight.platformlab.environment.EnvironmentRepository;
import io.github.dcfaight.platformlab.kubernetes.EnvironmentProvisioner;
import io.github.dcfaight.platformlab.kubernetes.OperationProgress.Milestone;
import io.github.dcfaight.platformlab.worker.ProvisioningWorker;
import io.github.dcfaight.platformlab.worker.WorkerProperties;
import io.github.dcfaight.platformlab.worker.WorkerRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DatabaseRoleSeparationIntegrationTests {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");
    static final String RUNTIME_SECRET = UUID.randomUUID().toString();
    static final String MIGRATION_SECRET = UUID.randomUUID().toString();
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        try (var admin = administrator(); var sql = admin.createStatement()) {
            sql.execute(Files.readString(Path.of("database/bootstrap.sql")));
            // Values are generated test credentials, never supplied through process arguments.
            sql.execute("ALTER ROLE platform_migration LOGIN PASSWORD '" + MIGRATION_SECRET + "'");
            sql.execute("ALTER ROLE platform_runtime LOGIN PASSWORD '" + RUNTIME_SECRET + "'");
        }
        DatabaseMigration.migrate(migrationEnvironment());
        DatabaseMigration.migrate(migrationEnvironment());
        try (var admin = administrator(); var sql = admin.createStatement()) {
            sql.execute(Files.readString(Path.of("database/runtime-grants.sql")));
            sql.execute("ALTER ROLE platform_migration NOLOGIN");
            sql.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                    + "WHERE usename = 'platform_migration'");
            sql.execute("INSERT INTO platform_teams VALUES ('runtime-test')");
            sql.execute("INSERT INTO account_team_memberships VALUES ('dev-a', 'runtime-test')");
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "platform_runtime");
        registry.add("spring.datasource.password", () -> RUNTIME_SECRET);
        TestUsers.register(registry);
    }

    @LocalServerPort
    int port;
    @Autowired
    ApplicationContext context;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    WorkerRepository worker;

    @Test
    void runtimeStartsWithoutFlywayOrMigrationCredentials() throws Exception {
        assertFalse(context.getEnvironment().getProperty("spring.flyway.enabled", Boolean.class));
        assertTrue(context.getBeansOfType(Flyway.class).isEmpty());
        assertNull(context.getEnvironment().getProperty("MIGRATION_DB_PASSWORD"));
        assertEquals("platform_runtime", jdbc.queryForObject("SELECT current_user", String.class));
        assertEquals(200, request("GET", "/actuator/health/readiness", null, TestUsers.OPERATOR).statusCode());
        try (var admin = administrator(); var sql = admin.createStatement();
                var result = sql.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE success")) {
            assertTrue(result.next());
            assertEquals(7, result.getInt(1));
        }
        assertThrows(SQLException.class, () -> DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), "platform_migration", MIGRATION_SECRET));
    }

    @Test
    void workerStartsWithOnlyTheRestrictedDatasource() {
        new ApplicationContextRunner()
                .withUserConfiguration(ProvisioningWorker.class)
                .withBean(WorkerRepository.class, () -> worker)
                .withBean(EnvironmentRepository.class, () -> context.getBean(EnvironmentRepository.class))
                .withBean(EnvironmentProvisioner.class, () -> mock(EnvironmentProvisioner.class))
                .withPropertyValues("platform.worker.enabled=true",
                        "platform.worker.poll-delay-ms=3600000", "platform.worker.lease-duration=30s",
                        "platform.worker.max-attempts=5", "platform.worker.retry-backoff=2s",
                        "platform.worker.readiness-poll-interval=5s", "platform.worker.readiness-timeout=5m")
                .run(started -> {
                    assertNull(started.getStartupFailure());
                    assertTrue(started.getBeansOfType(Flyway.class).isEmpty());
                    assertNull(started.getEnvironment().getProperty("MIGRATION_DB_PASSWORD"));
                    started.getBean(ProvisioningWorker.class).poll();
                });
    }

    @Test
    void runtimeSupportsAuthorizationIdempotencyWorkerFencingAndDeletion() throws Exception {
        String body = """
                {"name":"Restricted runtime","templateId":"sample-service","ttlHours":1,"teamId":"runtime-test"}
                """;
        var created = request("POST", "/api/v1/environments", body, TestUsers.DEVELOPER);
        assertEquals(202, created.statusCode(), created.body());
        var replay = request("POST", "/api/v1/environments", body, TestUsers.DEVELOPER);
        assertEquals(202, replay.statusCode(), replay.body());
        assertEquals(mapper.readTree(created.body()), mapper.readTree(replay.body()));
        String id = mapper.readTree(created.body()).path("environmentId").asText();
        String path = "/api/v1/environments/" + id;
        assertEquals(200, request("GET", path, null, TestUsers.DEVELOPER).statusCode());
        assertEquals(403, request("GET", path, null, TestUsers.OTHER_DEVELOPER).statusCode());
        assertEquals(200, request("GET", "/api/v1/environments", null, TestUsers.DEVELOPER).statusCode());

        var properties = new WorkerProperties(Duration.ofSeconds(30), 5, Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofMinutes(5));
        var claim = worker.claim(properties).orElseThrow();
        assertTrue(worker.owns(claim));
        assertTrue(worker.progress(claim, Milestone.NAMESPACE_CREATED));
        assertFalse(worker.progress(claim, Milestone.NAMESPACE_CREATED));
        assertTrue(worker.provisioned(claim));
        assertTrue(worker.waiting(claim, Duration.ZERO));
        var reclaimed = worker.claim(properties).orElseThrow();
        assertFalse(worker.succeed(claim));
        assertTrue(worker.succeed(reclaimed));
        assertEquals(200, request("GET", path + "/operations", null, TestUsers.DEVELOPER).statusCode());
        assertEquals(409, request("DELETE", path + "?expiredBefore=2020-01-01T00:00:00Z",
                null, TestUsers.CLEANUP).statusCode());
        var deleted = request("DELETE", path, null, TestUsers.DEVELOPER);
        assertEquals(202, deleted.statusCode(), deleted.body());
        var deletion = worker.claim(properties).orElseThrow();
        assertTrue(worker.deletionWaiting(deletion, Duration.ZERO));
        assertTrue(worker.succeed(worker.claim(properties).orElseThrow()));
        assertEquals("DELETED", jdbc.queryForObject(
                "SELECT status FROM environments WHERE id = ?::uuid", String.class, id));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM operation_events WHERE type = 'SUCCEEDED'",
                Integer.class));
        try (var admin = administrator(); var sql = admin.createStatement();
                var result = sql.executeQuery("SELECT count(*) FROM audit_events")) {
            assertTrue(result.next());
            assertEquals(2, result.getInt(1));
        }
    }

    @Test
    void runtimeCannotMutateSchemaRolesMembershipsOrProtectedApplicationData() throws Exception {
        for (String statement : List.of(
                "CREATE TABLE public.escalation (id int)",
                "CREATE SCHEMA escalation",
                "CREATE TEMP TABLE escalation (id int)",
                "CREATE DATABASE escalation",
                "ALTER SCHEMA public OWNER TO platform_runtime",
                "ALTER TABLE environments ADD COLUMN escalation int",
                "ALTER TABLE environments OWNER TO platform_runtime",
                "DROP TABLE environments",
                "TRUNCATE environments CASCADE",
                "CREATE ROLE escalation",
                "ALTER ROLE platform_runtime SUPERUSER",
                "GRANT platform_migration TO platform_runtime",
                "ALTER DEFAULT PRIVILEGES FOR ROLE platform_migration GRANT SELECT ON TABLES TO platform_runtime",
                "SET ROLE platform_migration",
                "SET ROLE pg_read_all_data",
                "SET ROLE pg_write_all_data",
                "SET ROLE " + POSTGRES.getUsername(),
                "SET ROLE pg_database_owner",
                "SET SESSION AUTHORIZATION " + POSTGRES.getUsername(),
                "INSERT INTO platform_teams VALUES ('escalation')",
                "UPDATE platform_teams SET id = 'escalation'",
                "DELETE FROM platform_teams",
                "INSERT INTO account_team_memberships VALUES ('dev-b', 'runtime-test')",
                "UPDATE account_team_memberships SET username = 'dev-b'",
                "DELETE FROM account_team_memberships",
                "UPDATE environments SET team_id = NULL",
                "UPDATE environments SET created_by = 'escalation'",
                "UPDATE environments SET namespace = 'escalation'",
                "UPDATE operations SET environment_id = gen_random_uuid()",
                "DELETE FROM environments",
                "DELETE FROM operations",
                "UPDATE idempotency_requests SET name = 'escalation'",
                "DELETE FROM idempotency_requests",
                "SELECT * FROM audit_events",
                "UPDATE audit_events SET actor = 'escalation'",
                "DELETE FROM audit_events",
                "UPDATE operation_events SET message = 'escalation'",
                "DELETE FROM operation_events",
                "SELECT * FROM flyway_schema_history",
                "DELETE FROM flyway_schema_history",
                "SELECT setval('operation_events_id_seq', 999)",
                "CREATE FUNCTION public.escalation() RETURNS int LANGUAGE sql AS 'SELECT 1'")) {
            denied(statement);
        }
    }

    @Test
    void ownershipPublicDefaultsAndRoleMembershipDoNotConferAuthority() throws Exception {
        assertFalse(jdbc.queryForObject("""
                SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls OR rolinherit
                FROM pg_roles WHERE rolname = current_user
                """, Boolean.class));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM pg_auth_members WHERE member = (SELECT oid FROM pg_roles WHERE rolname = current_user)
                """, Integer.class));
        assertFalse(jdbc.queryForObject("SELECT has_database_privilege(current_database(), 'CREATE') "
                + "OR has_database_privilege(current_database(), 'TEMP')", Boolean.class));
        assertFalse(jdbc.queryForObject("SELECT has_schema_privilege('public', 'CREATE')", Boolean.class));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM pg_class WHERE relowner = (SELECT oid FROM pg_roles WHERE rolname = current_user)
                """, Integer.class));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.column_privileges
                WHERE grantee = current_user AND is_grantable = 'YES'
                """, Integer.class));
        try (var admin = administrator(); var sql = admin.createStatement()) {
            sql.execute("SET ROLE platform_migration");
            sql.execute("CREATE TABLE public.future_table (id int)");
            sql.execute("CREATE SEQUENCE public.future_sequence");
            sql.execute("CREATE FUNCTION public.future_function() RETURNS int LANGUAGE sql SECURITY DEFINER AS 'SELECT 1'");
        }
        denied("SELECT * FROM future_table");
        denied("INSERT INTO future_table VALUES (1)");
        denied("SELECT nextval('future_sequence')");
        denied("SELECT future_function()");
        // PostgreSQL may warn rather than throw for an ineffective object GRANT.
        try (var runtime = runtime(); var sql = runtime.createStatement()) {
            sql.execute("GRANT SELECT ON environments TO PUBLIC");
        }
        assertFalse(jdbc.queryForObject("""
                SELECT has_table_privilege('public', 'environments', 'SELECT')
                """, Boolean.class));
    }

    @Test
    void bootstrapRefusesAnExistingTarget() throws Exception {
        try (var admin = administrator(); var sql = admin.createStatement()) {
            assertThrows(SQLException.class, () -> sql.execute(Files.readString(Path.of("database/bootstrap.sql"))));
        }
        assertEquals("platform_migration", jdbc.queryForObject(
                "SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = 'public'", String.class));
    }

    @Test
    void reviewedExistingDisposableTargetPreservesRowsAndHistory() throws Exception {
        try (var existing = new PostgreSQLContainer("postgres:18.6")) {
            existing.start();
            io.github.dcfaight.platformlab.TestDatabase.migrate(existing);
            try (var admin = DriverManager.getConnection(existing.getJdbcUrl(),
                    existing.getUsername(), existing.getPassword()); var sql = admin.createStatement()) {
                sql.execute("INSERT INTO platform_teams VALUES ('retained')");
                sql.execute("INSERT INTO account_team_memberships VALUES ('retained-user', 'retained')");
                sql.execute("""
                            INSERT INTO environments (id, name, namespace, template_id, status,
                                expires_at, created_at, updated_at, team_id)
                            VALUES ('00000000-0000-0000-0000-000000000001', 'Retained',
                                'env-00000000000000000000000000000001', 'sample-service', 'REQUESTED',
                                now() + interval '1 hour', now(), now(), 'retained')
                            """);
                String history;
                try (var result = sql.executeQuery(
                        "SELECT md5(string_agg(t::text, ',' ORDER BY installed_rank)) FROM flyway_schema_history t")) {
                    result.next();
                    history = result.getString(1);
                }
                sql.execute("SET platform.role_split_transition = 'on'");
                sql.execute(Files.readString(Path.of("database/bootstrap.sql")));
                sql.execute("ALTER ROLE platform_migration LOGIN PASSWORD '" + MIGRATION_SECRET + "'");
                sql.execute("ALTER ROLE platform_runtime LOGIN PASSWORD '" + RUNTIME_SECRET + "'");
                DatabaseMigration.migrate(Map.of("MIGRATION_DB_URL", existing.getJdbcUrl(),
                        "MIGRATION_DB_USERNAME", "platform_migration", "MIGRATION_DB_PASSWORD", MIGRATION_SECRET));
                sql.execute(Files.readString(Path.of("database/runtime-grants.sql")));
                sql.execute("ALTER ROLE platform_migration NOLOGIN");
                try (var result = sql.executeQuery(
                        "SELECT md5(string_agg(t::text, ',' ORDER BY installed_rank)) FROM flyway_schema_history t")) {
                    result.next();
                    assertEquals(history, result.getString(1));
                }
                try (var result = sql.executeQuery("""
                            SELECT count(*) FROM pg_class WHERE relnamespace = 'public'::regnamespace
                            AND relowner <> (SELECT oid FROM pg_roles WHERE rolname = 'platform_migration')
                            """)) {
                    result.next();
                    assertEquals(0, result.getInt(1));
                }
            }
            try (var runtime = DriverManager.getConnection(existing.getJdbcUrl(), "platform_runtime", RUNTIME_SECRET);
                    var sql = runtime.createStatement();
                    var result = sql.executeQuery("""
                                SELECT e.name FROM environments e JOIN account_team_memberships m
                                ON m.team_id = e.team_id WHERE m.username = 'retained-user'
                                """)) {
                assertTrue(result.next());
                assertEquals("Retained", result.getString(1));
                assertFalse(result.next());
            }
        }
    }

    private void denied(String statement) throws Exception {
        try (var runtime = runtime(); var sql = runtime.createStatement()) {
            var failure = assertThrows(SQLException.class, () -> sql.execute(statement), statement);
            assertEquals("42501", failure.getSQLState(), statement);
        }
    }

    private HttpResponse<String> request(String method, String path, String body, String user) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Authorization", TestUsers.basic(user));
        if (body != null) {
            request.header("Content-Type", "application/json").header("Idempotency-Key", "runtime-separation");
        }
        return HTTP.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Connection administrator() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Connection runtime() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "platform_runtime", RUNTIME_SECRET);
    }

    private static Map<String, String> migrationEnvironment() {
        return Map.of("MIGRATION_DB_URL", POSTGRES.getJdbcUrl(),
                "MIGRATION_DB_USERNAME", "platform_migration", "MIGRATION_DB_PASSWORD", MIGRATION_SECRET);
    }
}
