package com.boatarde.regatasimulator.configuration;

/**
 * Fatal bootstrap failure with no property name, value, password or nested cause.
 * This deliberately extends Error: Boot 4.1.1's SpringConfigurationPropertySource
 * catches Exception and tries lower-precedence sources. Encryption failure must
 * not be interpreted as an absent property or permit that fallback.
 * A RuntimeException is therefore unsafe even for an opaque source; the synthetic
 * Binder regression test covers this escape hatch. No JVM termination is requested.
 */
public final class EncryptedPropertyFailure extends Error {
    private static final long serialVersionUID = 1L;

    EncryptedPropertyFailure() {
        super("Encrypted property configuration is invalid or unavailable", null);
    }
}
