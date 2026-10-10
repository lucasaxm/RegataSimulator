package com.boatarde.regatasimulator.repository.sqlite;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.core.SQLiteDatabase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;

/** Explicit local-disk store. Never interprets the legacy JsonDB directory as a file. */
public final class SqliteStore implements AutoCloseable {
    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public SqliteStore(Path file, int busyTimeoutMillis) {
        this(file,busyTimeoutMillis,false);
    }

    public SqliteStore(Path file, int busyTimeoutMillis, boolean readOnly) {
        if (!file.isAbsolute() || Files.isDirectory(file) || file.toString().contains("?")
            || file.toString().contains("#") || !Files.isDirectory(file.getParent())
            || busyTimeoutMillis < 1 || busyTimeoutMillis > 10_000) {
            throw new IllegalArgumentException("An absolute local database file and bounded timeout are required");
        }
        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.enforceForeignKeys(true);
        sqlite.setBusyTimeout(busyTimeoutMillis);
        if (readOnly) {
            if (!Files.isRegularFile(file)) throw new IllegalArgumentException("Existing SQLite file required");
            sqlite.setReadOnly(true);
            sqlite.setTransactionMode(SQLiteConfig.TransactionMode.DEFERRED);
        } else {
            sqlite.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
            sqlite.setJournalMode(SQLiteConfig.JournalMode.WAL);
            sqlite.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        }
        SQLiteDataSource nativeSource = new SQLiteDataSource(sqlite);
        nativeSource.setUrl("jdbc:sqlite:" + file);
        HikariConfig pool = new HikariConfig();
        pool.setDataSource(nativeSource);
        pool.setReadOnly(readOnly);
        pool.setMaximumPoolSize(2);
        pool.setMinimumIdle(0);
        pool.setConnectionTimeout(5_000);
        pool.setPoolName("regata-sqlite");
        dataSource = new HikariDataSource(pool);
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        try {
            requirePatchedEngine(jdbc.queryForObject("SELECT sqlite_version()", String.class));
            String journal = jdbc.queryForObject("PRAGMA journal_mode", String.class);
            if (!"wal".equalsIgnoreCase(journal) && !(readOnly && "delete".equalsIgnoreCase(journal))) {
                throw new IllegalStateException("WAL unavailable");
            }
            if (readOnly) {
                validateSchema();
                verifyIntegrity();
            } else migrate();
        } catch (RuntimeException e) {
            dataSource.close();
            throw e;
        }
    }

    public static void requirePatchedEngine(String version) {
        String[] parts = version.split("\\.");
        int major = Integer.parseInt(parts[0]);
        int minor = Integer.parseInt(parts[1]);
        int patch = Integer.parseInt(parts[2]);
        if (major < 3 || major == 3 && (minor < 51 || minor == 51 && patch < 3 || minor == 52)) {
            throw new IllegalStateException("SQLite engine lacks required WAL-reset patch");
        }
    }

    private void migrate() {
        try (var connection = dataSource.getConnection(); var resources = new ClassLoaderResourceAccessor()) {
            SQLiteDatabase database = new SQLiteDatabase();
            database.setConnection(new JdbcConnection(connection));
            try (var migrations = new Liquibase("db/changelog/sqlite.sql", resources, database)) {
                migrations.update(new Contexts(), new LabelExpression());
            }
        } catch (Exception e) {
            throw new IllegalStateException("SQLite schema migration failed", e);
        }
    }

    public HikariDataSource dataSource() { return dataSource; }
    public void verifyIntegrity() {
        if (!java.util.List.of("ok").equals(jdbc.queryForList("PRAGMA integrity_check", String.class))
            || !jdbc.queryForList("PRAGMA foreign_key_check").isEmpty()) {
            throw new IllegalStateException("SQLite integrity validation failed");
        }
    }

    private void validateSchema() {
        var ids = jdbc.queryForList("SELECT ID FROM DATABASECHANGELOG ORDER BY ORDEREXECUTED", String.class);
        if (!ids.equals(java.util.List.of("1", "2"))
            || jdbc.queryForObject("SELECT count(*) FROM DATABASECHANGELOG WHERE AUTHOR='regata' AND FILENAME='db/changelog/sqlite.sql'", Integer.class) != ids.size()) {
            throw new IllegalStateException("Unsupported schema for offline export");
        }
        try (var connection = dataSource.getConnection(); var resources = new ClassLoaderResourceAccessor()) {
            SQLiteDatabase database = new SQLiteDatabase();
            database.setConnection(new JdbcConnection(connection));
            try (var migrations = new Liquibase("db/changelog/sqlite.sql", resources, database)) { migrations.validate(); }
        } catch (Exception e) { throw new IllegalStateException("SQLite changelog validation failed", e); }
    }

    /** Autocommit-only snapshot, including committed WAL writes. Output must be new. */
    public void snapshot(Path target) {
        if (!target.isAbsolute() || target.toString().contains("?") || target.toString().contains("#")
            || Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            || !Files.isDirectory(target.getParent())
            || org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalArgumentException("New snapshot file outside a transaction required");
        }
        for (Path part = target; part != null; part = part.getParent()) {
            if (Files.isSymbolicLink(part)) throw new IllegalArgumentException("Snapshot symlinks forbidden");
        }
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("VACUUM INTO ?")) {
            if (!connection.getAutoCommit()) throw new IllegalStateException("Autocommit snapshot required");
            statement.setString(1, target.toString());
            statement.execute();
            Files.setPosixFilePermissions(target, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            try (var copy = new SqliteStore(target, 2000, true)) { copy.verifyIntegrity(); }
        } catch (Exception e) { throw new IllegalStateException("SQLite snapshot failed; candidate must not be used", e); }
    }
    public JdbcTemplate jdbc() { return jdbc; }
    public TransactionTemplate transactions() { return transactions; }
    @Override public void close() { dataSource.close(); }
}