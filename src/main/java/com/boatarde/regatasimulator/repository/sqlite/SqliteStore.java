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
        if (!file.isAbsolute() || Files.isDirectory(file) || file.toString().contains("?")
            || file.toString().contains("#") || !Files.isDirectory(file.getParent())
            || busyTimeoutMillis < 1 || busyTimeoutMillis > 10_000) {
            throw new IllegalArgumentException("An absolute local database file and bounded timeout are required");
        }
        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.enforceForeignKeys(true);
        sqlite.setBusyTimeout(busyTimeoutMillis);
        sqlite.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
        sqlite.setJournalMode(SQLiteConfig.JournalMode.WAL);
        sqlite.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        SQLiteDataSource nativeSource = new SQLiteDataSource(sqlite);
        nativeSource.setUrl("jdbc:sqlite:" + file);
        HikariConfig pool = new HikariConfig();
        pool.setDataSource(nativeSource);
        pool.setMaximumPoolSize(2);
        pool.setMinimumIdle(0);
        pool.setConnectionTimeout(5_000);
        pool.setPoolName("regata-sqlite");
        dataSource = new HikariDataSource(pool);
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        try {
            requirePatchedEngine(jdbc.queryForObject("SELECT sqlite_version()", String.class));
            if (!"wal".equalsIgnoreCase(jdbc.queryForObject("PRAGMA journal_mode", String.class))) {
                throw new IllegalStateException("WAL unavailable");
            }
            migrate();
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
    public JdbcTemplate jdbc() { return jdbc; }
    public TransactionTemplate transactions() { return transactions; }
    @Override public void close() { dataSource.close(); }
}