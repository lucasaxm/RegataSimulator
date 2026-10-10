package com.boatarde.regatasimulator.application;

/** Safe use-case failure classification; adapters never disclose exception details. */
public class ApplicationFailure extends RuntimeException {
    public enum Kind { UNAVAILABLE, INVALID_INPUT, NOT_FOUND, CONFLICT, EXECUTION }
    private final Kind kind;
    public ApplicationFailure(Kind kind, String message) { super(message); this.kind = kind; }
    public ApplicationFailure(Kind kind, String message, Throwable cause) { super(message, cause); this.kind = kind; }
    public Kind getKind() { return kind; }
}