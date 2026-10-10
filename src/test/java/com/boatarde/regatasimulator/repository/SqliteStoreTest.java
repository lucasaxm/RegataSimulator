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
            assertEquals(3, store.jdbc().queryForObject("SELECT count(*) FROM DATABASECHANGELOG", Integer.class));
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
            assertEquals(3, store.jdbc().queryForObject("SELECT count(*) FROM DATABASECHANGELOG", Integer.class));
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

    @Test void upgradesFirstSchemaVersionWithoutLosingExistingRows() throws Exception {
        Path file=temp.resolve("earlier.db");
        try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+file);
            var resources=new liquibase.resource.ClassLoaderResourceAccessor()) {
            var database=new liquibase.database.core.SQLiteDatabase();
            database.setConnection(new liquibase.database.jvm.JdbcConnection(connection));
            try(var migration=new liquibase.Liquibase("db/changelog/sqlite.sql",resources,database)) {
                migration.update(1,new liquibase.Contexts(),new liquibase.LabelExpression());
            }
        }
        try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+file);var statement=connection.prepareStatement("INSERT INTO sources(id,weight,status) VALUES(?,7,'REVIEW')")) {
            statement.setString(1,"00000000-0000-0000-0000-000000000001"); statement.executeUpdate();
        }
        try(var store=new SqliteStore(file,100)) {
            assertEquals(3,store.jdbc().queryForObject("SELECT count(*) FROM DATABASECHANGELOG",Integer.class));
            assertEquals(7,store.jdbc().queryForObject("SELECT weight FROM sources",Integer.class));
            assertEquals(1,store.jdbc().queryForObject("SELECT count(*) FROM sqlite_master WHERE name='sources_gallery'",Integer.class));
        }
    }

    @Test void readsHistoricalSchemaTwoWithoutMigratingOrInventingAuditsThenUpgradesExplicitly() throws Exception {
        Path file=temp.resolve("phase-four.db");
        try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+file);var resources=new liquibase.resource.ClassLoaderResourceAccessor()) {
            var database=new liquibase.database.core.SQLiteDatabase(); database.setConnection(new liquibase.database.jvm.JdbcConnection(connection));
            try(var migrations=new liquibase.Liquibase("db/changelog/sqlite.sql",resources,database)) { migrations.update(2,new liquibase.Contexts(),new liquibase.LabelExpression()); }
        }
        try(var store=new SqliteStore(file,100,true)) {
            assertEquals(2,store.jdbc().queryForObject("SELECT count(*) FROM DATABASECHANGELOG",Integer.class));
            assertTrue(new com.boatarde.regatasimulator.repository.sqlite.SqliteAuditRepository(store).findAll().isEmpty());
        }
        try(var store=new SqliteStore(file,100)) {
            assertEquals(3,store.jdbc().queryForObject("SELECT count(*) FROM DATABASECHANGELOG",Integer.class));
            assertTrue(new com.boatarde.regatasimulator.repository.sqlite.SqliteAuditRepository(store).findAll().isEmpty());
        }
    }
}