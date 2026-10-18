package io.github.dcfaight.platformlab;

import java.util.Map;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Existing regression fixtures retain administrator access for seeding and cleanup only. */
public final class TestDatabase {
    private TestDatabase() {}

    public static void migrate(PostgreSQLContainer postgres) {
        DatabaseMigration.migrate(Map.of(
                "MIGRATION_DB_URL", postgres.getJdbcUrl(),
                "MIGRATION_DB_USERNAME", postgres.getUsername(),
                "MIGRATION_DB_PASSWORD", postgres.getPassword()));
    }
}
