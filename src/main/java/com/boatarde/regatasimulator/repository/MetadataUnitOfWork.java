package com.boatarde.regatasimulator.repository;

/** Database-only work: callers must keep files and transport outside this boundary. */
@FunctionalInterface
public interface MetadataUnitOfWork {
    void execute(Runnable writes);

    @FunctionalInterface
    interface CheckedWrites { void run() throws java.io.IOException, java.sql.SQLException; }

    default void executeChecked(CheckedWrites writes) {
        execute(() -> {
            try { writes.run(); }
            catch(java.io.IOException | java.sql.SQLException e) {
                throw new com.boatarde.regatasimulator.application.ApplicationFailure(
                    com.boatarde.regatasimulator.application.ApplicationFailure.Kind.EXECUTION,"Metadata write failed",e);
            }
        });
    }
}