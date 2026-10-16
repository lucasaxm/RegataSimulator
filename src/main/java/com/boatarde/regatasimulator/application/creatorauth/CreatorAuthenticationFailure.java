package com.boatarde.regatasimulator.application.creatorauth;

import org.springframework.security.core.AuthenticationException;

/** Deliberately cause-free: parser/crypto diagnostics must not expose signed data. */
public final class CreatorAuthenticationFailure extends AuthenticationException {
    public CreatorAuthenticationFailure() { super("Invalid creator credentials"); }
}
