package io.github.dcfaight.platformlab;

import java.util.Map;
import org.flywaydb.core.Flyway;

/** Explicit migration process: never starts Spring, the API, or a worker. */
public final class DatabaseMigration {
    private DatabaseMigration() {}

    public static void main(String[] args) {
        try {
            migrate(System.getenv());
        } catch (Exception failure) {
            // Database errors can contain connection details or SQL values.
            System.err.println("Migration failed; keep runtime stopped and inspect the target through the administrator.");
            System.exit(1);
        }
    }

    public static void migrate(Map<String, String> environment) {
        Flyway.configure()
                .dataSource(required(environment, "MIGRATION_DB_URL"),
                        required(environment, "MIGRATION_DB_USERNAME"),
                        required(environment, "MIGRATION_DB_PASSWORD"))
                .loggers(new String[0])
                .connectRetries(10)
                .connectRetriesInterval(2)
                .cleanDisabled(true)
                .load()
                .migrate();
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing migration configuration");
        }
        return value;
    }
}
