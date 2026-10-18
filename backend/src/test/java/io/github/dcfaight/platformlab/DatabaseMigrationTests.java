package io.github.dcfaight.platformlab;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DatabaseMigrationTests {
    @Test
    void runtimeVariablesCannotBeUsedAsMigrationFallback() {
        assertThrows(IllegalArgumentException.class, () -> DatabaseMigration.migrate(Map.of(
                "DB_URL", "jdbc:postgresql://localhost/unused",
                "DB_USERNAME", "platform_runtime", "DB_PASSWORD", UUID.randomUUID().toString())));
    }

    @Test
    void standaloneFailureIsNonzeroAndDoesNotExposeConfiguration() throws Exception {
        var process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), DatabaseMigration.class.getName())
                .redirectErrorStream(true);
        process.environment().clear();
        String secret = UUID.randomUUID().toString();
        process.environment().putAll(Map.of("MIGRATION_DB_URL", "jdbc:invalid:" + secret,
                "MIGRATION_DB_USERNAME", "migration-" + secret, "MIGRATION_DB_PASSWORD", secret));
        var running = process.start();
        String output = new String(running.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(1, running.waitFor());
        assertEquals("Migration failed; keep runtime stopped and inspect the target through the administrator."
                + System.lineSeparator(), output);
        assertFalse(output.contains(secret));
    }
}
