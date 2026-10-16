package com.boatarde.regatasimulator.application.creatorauth;

/** Identity derived exclusively from verified Telegram data, never from posted actor IDs. */
public record CreatorActor(long id, String firstName, String lastName, String username) {
    public CreatorActor {
        if (id <= 0) throw new IllegalArgumentException("Invalid creator identity");
    }

    @Override public String toString() { return "CreatorActor[redacted]"; }
}
