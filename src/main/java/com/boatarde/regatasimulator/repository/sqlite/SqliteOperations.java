package com.boatarde.regatasimulator.repository.sqlite;

import com.boatarde.regatasimulator.application.ApplicationFailure;
import com.boatarde.regatasimulator.models.Status;
import org.springframework.dao.DataAccessException;
import java.sql.SQLException;
import java.util.function.Supplier;

final class SqliteOperations {
    private SqliteOperations() { }
    static <T> T call(Supplier<T> operation) {
        try { return operation.get(); }
        catch (DataAccessException e) {
            Throwable cause = e;
            while (cause.getCause() != null && !(cause instanceof SQLException)) cause = cause.getCause();
            int code = cause instanceof org.sqlite.SQLiteException sqlite ? sqlite.getResultCode().code
                : cause instanceof SQLException sql ? sql.getErrorCode() : 0;
            ApplicationFailure.Kind kind = switch (code & 255) {
                case 5, 6 -> ApplicationFailure.Kind.UNAVAILABLE;
                case 19 -> code == 2067 || code == 1555 ? ApplicationFailure.Kind.CONFLICT : ApplicationFailure.Kind.INVALID_INPUT;
                default -> ApplicationFailure.Kind.EXECUTION;
            };
            throw new ApplicationFailure(kind, "Database operation failed", e);
        }
    }
    static void decision(Status status) {
        if (status != Status.APPROVED && status != Status.REJECTED) throw new IllegalArgumentException("Invalid review decision");
    }
    static void binding(int messageId) {
        if (messageId <= 0) throw new IllegalArgumentException("Preview message must be positive");
    }
}