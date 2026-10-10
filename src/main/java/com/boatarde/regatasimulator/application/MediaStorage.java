package com.boatarde.regatasimulator.application;

import java.nio.file.Path;
import java.util.UUID;

public interface MediaStorage {
    enum Kind { SOURCE, TEMPLATE }
    Path image(Kind kind, UUID id);
    Path prepare(Kind kind, UUID id);
    void delete(Kind kind, UUID id);
    void discardUncommitted(Kind kind, UUID id);
}