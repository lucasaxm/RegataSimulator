package com.boatarde.regatasimulator.application;

import java.nio.file.Path;
import java.util.UUID;

public interface MediaStorage {
    enum Kind { SOURCE, TEMPLATE }
    Path image(Kind kind, UUID id);
    Path prepare(Kind kind, UUID id);
    void delete(Kind kind, UUID id);
    void deleteAfterMetadata(Kind kind, UUID id, Runnable removeMetadata, java.util.function.BooleanSupplier metadataExists);
    void discardUncommitted(Kind kind, UUID id);
}