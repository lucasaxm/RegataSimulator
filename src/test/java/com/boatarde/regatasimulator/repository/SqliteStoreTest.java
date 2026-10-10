package com.boatarde.regatasimulator.repository;

import com.boatarde.regatasimulator.repository.sqlite.SqliteStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SqliteStoreTest {
    @TempDir Path temp;

    @Test void migratesReopensAndConfiguresEveryConnection() throws Exception {
        Path file = temp.resolve("candidate.db");
        try (var store = new SqliteStore(file, 150)) {
            assertEquals("3.53.4", store.jdbc().queryForObject("SELECT sqlite_version()", String.class));
            assertEquals(2, store.jdbc().queryForObject("SELECT count(*) FROM DATABASECHANGELOG", Integer.class));
            try (var first = store.dataSource().getConnection(); var second = store.dataSource().getConnection()) {
            for (var connection : java.util.List.of(first, second)) {
                try (var statement = connection.createStatement()) {
                    for (var setting : java.util.Map.of("foreign_keys", 1, "busy_timeout", 150, "synchronous", 2).entrySet()) {
                        try (var rows = statement.executeQuery("PRAGMA " + setting.getKey())) {
                            assertTrue(rows.next());
                            assertEquals(setting.getValue(), rows.getInt(1));
                        }
                    }
                }
            }
            }
        }
        try (var store = new SqliteStore(file, 150)) {
            assertEquals(2, store.jdbc().queryForObject("SELECT count(*) FROM DATABASECHANGELOG", Integer.class));
            assertEquals("ok", store.jdbc().queryForObject("PRAGMA integrity_check", String.class));
        }
    }

    @Test void rejectsUnpatchedEnginesAndDirectoryOrRelativeTargets() {
        for (String version : java.util.List.of("3.50.4", "3.51.2", "3.52.0")) {
            assertThrows(IllegalStateException.class, () -> SqliteStore.requirePatchedEngine(version));
        }
        assertThrows(IllegalArgumentException.class, () -> new SqliteStore(temp, 100));
        assertThrows(IllegalArgumentException.class, () -> new SqliteStore(Path.of("candidate.db"), 100));
    }
}