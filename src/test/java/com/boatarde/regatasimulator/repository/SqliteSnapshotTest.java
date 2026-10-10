package com.boatarde.regatasimulator.repository;

import com.boatarde.regatasimulator.repository.sqlite.SqliteStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class SqliteSnapshotTest {
    @TempDir Path temp;

    @Test void vacuumIncludesCommittedWalAndIsIndependentOfLaterWrites() throws Exception {
        temp = temp.toRealPath();
        Path live = temp.resolve("live.db"), snapshot = temp.resolve("snapshot.db");
        try (var store = new SqliteStore(live, 100)) {
            store.jdbc().execute("PRAGMA wal_autocheckpoint=0");
            store.jdbc().update("INSERT INTO users(id,first_name) VALUES(?,?)", 9000000000L, "synthetic");
            assertTrue(Files.size(Path.of(live + "-wal")) > 0);
            store.snapshot(snapshot);
            store.jdbc().update("INSERT INTO users(id) VALUES(2)");
            try (var copy = new SqliteStore(snapshot, 100, true)) {
                assertEquals(1, copy.jdbc().queryForObject("SELECT count(*) FROM users", Integer.class));
                assertEquals(9000000000L, copy.jdbc().queryForObject("SELECT id FROM users", Long.class));
            }
            assertThrows(IllegalArgumentException.class, () -> store.snapshot(snapshot));
            assertThrows(IllegalArgumentException.class, () -> store.transactions().execute(tx -> { store.snapshot(temp.resolve("tx.db")); return null; }));
        }
        assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(snapshot));
    }

    @Test void rejectsFutureIdentityEvenWhenChangesetCountMatches() {
        Path file = temp.resolve("future.db");
        try (var store = new SqliteStore(file, 100)) { store.jdbc().update("UPDATE DATABASECHANGELOG SET ID='future' WHERE ID='2'"); }
        assertThrows(IllegalStateException.class, () -> new SqliteStore(file, 100, true));
    }

    @Test void rejectsTamperedChecksum() {
        Path file = temp.resolve("tampered.db");
        try (var store = new SqliteStore(file, 100)) { store.jdbc().update("UPDATE DATABASECHANGELOG SET MD5SUM='9:00000000000000000000000000000000' WHERE ID='1'"); }
        assertThrows(IllegalStateException.class, () -> new SqliteStore(file, 100, true));
    }
}