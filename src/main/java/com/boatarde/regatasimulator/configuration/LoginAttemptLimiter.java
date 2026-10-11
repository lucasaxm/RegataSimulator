package com.boatarde.regatasimulator.configuration;

import java.time.Clock;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Bounded process-local limiter. At capacity, new identities fail closed until TTL expiry. */
public final class LoginAttemptLimiter {
    private record Bucket(int attempts,long expires) { }
    private final Map<String,Bucket> buckets=new HashMap<>();
    private final Clock clock;
    private final int attempts;
    private final long windowMillis;
    private final int maximumEntries;

    public LoginAttemptLimiter(Clock clock,int attempts,long windowMillis,int maximumEntries) {
        if (attempts<1 || attempts>100 || windowMillis<1000 || windowMillis>3_600_000 || maximumEntries<10 || maximumEntries>100_000) throw new IllegalArgumentException("Login limiter bounds invalid");
        this.clock=clock; this.attempts=attempts; this.windowMillis=windowMillis; this.maximumEntries=maximumEntries;
    }
    public synchronized boolean reserve(String remoteAddress,String username) {
        if (username==null || username.length()>200 || remoteAddress==null || remoteAddress.length()>256) return false;
        long now=clock.millis(); buckets.values().removeIf(b -> b.expires()<=now);
        List<String> keys=keys(remoteAddress,username);
        if (keys.stream().anyMatch(k -> buckets.containsKey(k) && buckets.get(k).attempts()>=attempts)
            || buckets.size()+keys.stream().filter(k -> !buckets.containsKey(k)).count()>maximumEntries) return false;
        for(String key:keys) {
            Bucket old=buckets.get(key);
            buckets.put(key,new Bucket(old==null ? 1 : old.attempts()+1,old==null ? now+windowMillis : old.expires()));
        }
        return true;
    }
    public synchronized void succeeded(String address,String username) { keys(address,username).forEach(buckets::remove); }
    public synchronized int size() { return buckets.size(); }
    private static List<String> keys(String address,String username) { return List.of(hash("ip:"+address),hash("user:"+username)); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}