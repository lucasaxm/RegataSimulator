package com.boatarde.regatasimulator.application.creatorauth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Independent test signer, synthetic token only; no production verifier helpers. */
public final class SyntheticInitData {
    public static final String BOT = "phase0-test-token-never-used";
    public static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    private SyntheticInitData() { }

    public static String valid() { return sign(Map.of("auth_date", Long.toString(NOW.getEpochSecond()), "user", "{\"id\":42}")); }

    public static String sign(Map<String, String> values) { return sign(values, BOT); }

    public static String sign(Map<String, String> values, String bot) {
        try {
            var sorted = new TreeMap<>(values);
            String canonical = sorted.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("\n"));
            Mac derive = Mac.getInstance("HmacSHA256");
            derive.init(new SecretKeySpec("WebAppData".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] key = derive.doFinal(bot.getBytes(StandardCharsets.UTF_8));
            Mac sign = Mac.getInstance("HmacSHA256");
            sign.init(new SecretKeySpec(key, "HmacSHA256"));
            sorted.put("hash", HexFormat.of().formatHex(sign.doFinal(canonical.getBytes(StandardCharsets.UTF_8))));
            return sorted.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue())).collect(Collectors.joining("&"));
        } catch (Exception failure) {
            throw new AssertionError("Synthetic signer failed", failure);
        }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
