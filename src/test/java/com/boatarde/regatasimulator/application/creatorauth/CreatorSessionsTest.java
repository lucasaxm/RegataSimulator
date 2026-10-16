package com.boatarde.regatasimulator.application.creatorauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class CreatorSessionsTest {
    private final MutableClock clock = new MutableClock();
    private final CreatorActor actor = new CreatorActor(42, "Synthetic", null, null);

    @Test void opaqueTokensRedactionAbsoluteExpiryAndRestart() throws Exception {
        var registry = new CreatorSessions(clock, new SecureRandom(), 2);
        var issued = registry.issue(actor);
        assertTrue(issued.accessToken().matches("[A-Za-z0-9_-]{43}"));
        assertEquals("Bearer", issued.tokenType());
        assertEquals(clock.instant().plusSeconds(1800).toString(), issued.expiresAt());
        assertFalse(issued.toString().contains(issued.accessToken()));
        assertFalse(registry.toString().contains(issued.accessToken()));
        assertTrue(new ObjectMapper().writeValueAsString(issued).contains("\"accessToken\""));
        assertThrows(CreatorAuthenticationFailure.class,
            () -> new CreatorSessions(clock, new SecureRandom(), 2).authenticate(issued.accessToken()));
        clock.now = clock.now.plusSeconds(1799);
        assertSame(actor, registry.authenticate(issued.accessToken()));
        clock.now = clock.now.plusSeconds(1);
        assertThrows(CreatorAuthenticationFailure.class, () -> registry.authenticate(issued.accessToken()));
    }

    @Test void capacityDoesNotEvictActiveSessionsAndExpiredEntriesArePruned() {
        var registry = new CreatorSessions(clock, new SecureRandom(), 1);
        var first = registry.issue(actor);
        assertThrows(CreatorSessions.CapacityFailure.class, () -> registry.issue(actor));
        assertSame(actor, registry.authenticate(first.accessToken()));
        clock.now = clock.now.plusSeconds(1800);
        var next = registry.issue(actor);
        assertNotEquals(first.accessToken(), next.accessToken());
        assertThrows(CreatorAuthenticationFailure.class, () -> registry.authenticate(first.accessToken()));
        assertSame(actor, registry.authenticate(next.accessToken()));
        for (String invalid : new String[]{"", "x".repeat(42), "x".repeat(44), "x".repeat(43), "!".repeat(43)}) {
            assertThrows(CreatorAuthenticationFailure.class, () -> registry.authenticate(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> new CreatorSessions(clock, new SecureRandom(), 0));
    }

    @Test void concurrentCapacityReservationsStayBounded() throws Exception {
        var registry = new CreatorSessions(clock, new SecureRandom(), 4);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 40; i++) tasks.add(pool.submit(() -> {
                try { registry.issue(actor); return true; }
                catch (CreatorSessions.CapacityFailure ignored) { return false; }
            }));
            int issued = 0;
            for (var task : tasks) if (task.get()) issued++;
            assertEquals(4, issued);
        }
    }

    static final class MutableClock extends Clock {
        Instant now = SyntheticInitData.NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
