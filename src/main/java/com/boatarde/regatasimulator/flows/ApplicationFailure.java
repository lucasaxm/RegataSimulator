package com.boatarde.regatasimulator.flows;

/** Temporary compatibility for obsolete workflow regressions, not production orchestration. */
public class ApplicationFailure extends com.boatarde.regatasimulator.application.ApplicationFailure {
    public ApplicationFailure(Kind kind, String message) {
        super(kind, message);
    }

    public ApplicationFailure(Kind kind, String message, Throwable cause) {
        super(kind, message, cause);
    }
}