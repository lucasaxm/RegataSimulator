package com.boatarde.regatasimulator.flows;

/** Safe classification for adapters; exception details are never sent to users. */
public class ApplicationFailure extends RuntimeException {
    public enum Kind { UNAVAILABLE, INVALID_INPUT, EXECUTION }

    private final Kind kind;

    public ApplicationFailure(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public ApplicationFailure(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}