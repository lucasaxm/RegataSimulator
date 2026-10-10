package com.boatarde.regatasimulator.repository;

/** Database-only work: callers must keep files and transport outside this boundary. */
@FunctionalInterface
public interface MetadataUnitOfWork {
    void execute(Runnable writes);
}