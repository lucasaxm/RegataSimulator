package com.boatarde.regatasimulator.application.creatorauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** Restart-local, absolute-expiry credentials. Only SHA-256 keys are retained. */
public final class CreatorSessions {
    public record Issued(String accessToken, String tokenType, String expiresAt) {
        @Override public String toString() { return "Issued[redacted]"; }
    }
    private record Stored(CreatorActor actor, Instant expiresAt) { }
    private static final class TokenHash {
        private final byte[] digest;
        private TokenHash(byte[] digest) { this.digest = digest; }
        @Override public int hashCode() { return Arrays.hashCode(digest); }
        @Override public boolean equals(Object other) {
            return other instanceof TokenHash key && MessageDigest.isEqual(digest, key.digest);
        }
        @Override public String toString() { return "TokenHash[redacted]"; }
    }
    public static final class CapacityFailure extends RuntimeException {
        public CapacityFailure() { super("Creator authentication unavailable"); }
    }

    private final Map<TokenHash, Stored> sessions = new HashMap<>();
    private final Clock clock;
    private final SecureRandom random;
    private final int capacity;

    public CreatorSessions(Clock clock, SecureRandom random, int capacity) {
        if (capacity < 1 || capacity > 100000) throw new IllegalArgumentException("Invalid creator session capacity");
        this.clock = clock;
        this.random = random;
        this.capacity = capacity;
    }

    public synchronized Issued issue(CreatorActor actor) {
        Instant now = clock.instant();
        sessions.values().removeIf(s -> !s.expiresAt().isAfter(now));
        if (sessions.size() >= capacity) throw new CapacityFailure();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        TokenHash key = hash(token);
        if (sessions.containsKey(key)) throw new CapacityFailure();
        Instant expires = now.plusSeconds(1800);
        sessions.put(key, new Stored(actor, expires));
        return new Issued(token, "Bearer", expires.toString());
    }

    public synchronized CreatorActor authenticate(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw new CreatorAuthenticationFailure();
        TokenHash key = hash(token);
        Stored stored = sessions.get(key);
        if (stored == null) throw new CreatorAuthenticationFailure();
        if (!stored.expiresAt().isAfter(clock.instant())) {
            sessions.remove(key);
            throw new CreatorAuthenticationFailure();
        }
        return stored.actor();
    }

    private static TokenHash hash(String token) {
        try {
            return new TokenHash(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException ignored) {
            throw new IllegalStateException("Creator authentication unavailable");
        }
    }

    @Override public String toString() { return "CreatorSessions[redacted]"; }
}
