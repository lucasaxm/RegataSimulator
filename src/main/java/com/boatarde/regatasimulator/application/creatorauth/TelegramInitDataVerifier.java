package com.boatarde.regatasimulator.application.creatorauth;

import com.fasterxml.jackson.databind.JsonNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Telegram bot-token HMAC verification, without network or application data access. */
public final class TelegramInitDataVerifier {
    private final byte[] secret;
    private final Clock clock;

    public TelegramInitDataVerifier(String botToken, Clock clock) {
        if (botToken == null || botToken.isBlank() || botToken.length() > 1024) {
            throw new IllegalStateException("Creator authentication configuration invalid");
        }
        try {
            strictText(botToken);
            this.secret = hmac("WebAppData".getBytes(StandardCharsets.UTF_8), botToken.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            throw new IllegalStateException("Creator authentication configuration invalid");
        }
        this.clock = clock;
    }

    public CreatorActor verify(String raw) {
        try {
            if (raw == null || raw.length() > 16384) throw new CreatorAuthenticationFailure();
            strictText(raw);
            if (raw.getBytes(StandardCharsets.UTF_8).length > 16384) throw new CreatorAuthenticationFailure();
            String[] parts = raw.split("&", -1);
            if (parts.length > 32) throw new CreatorAuthenticationFailure();
            Map<String, String> fields = new TreeMap<>();
            for (String part : parts) {
                int equals = part.indexOf('=');
                if (equals < 1) throw new CreatorAuthenticationFailure();
                String key = decode(part.substring(0, equals));
                String value = decode(part.substring(equals + 1));
                if (!key.matches("[A-Za-z0-9_]{1,64}") || fields.putIfAbsent(key, value) != null) {
                    throw new CreatorAuthenticationFailure();
                }
            }
            String hash = fields.remove("hash");
            if (hash == null || !hash.matches("[0-9a-fA-F]{64}")) throw new CreatorAuthenticationFailure();
            String check = fields.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("\n"));
            if (!MessageDigest.isEqual(HexFormat.of().parseHex(hash), hmac(secret, check.getBytes(StandardCharsets.UTF_8)))) {
                throw new CreatorAuthenticationFailure();
            }
            String date = fields.get("auth_date");
            if (date == null || !date.matches("[0-9]{1,19}")) throw new CreatorAuthenticationFailure();
            long seconds = Long.parseLong(date);
            if (seconds <= 0) throw new CreatorAuthenticationFailure();
            Instant signed = Instant.ofEpochSecond(seconds);
            Instant now = clock.instant();
            if (signed.isBefore(now.minusSeconds(300)) || signed.isAfter(now.plusSeconds(60))) {
                throw new CreatorAuthenticationFailure();
            }
            String user = fields.get("user");
            if (user == null || user.length() > 8192) throw new CreatorAuthenticationFailure();
            JsonNode node = CreatorJson.read(user);
            if (node == null || !node.isObject()) throw new CreatorAuthenticationFailure();
            validateJsonText(node);
            JsonNode id = node.get("id");
            if (id == null || !id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0) {
                throw new CreatorAuthenticationFailure();
            }
            return new CreatorActor(id.longValue(), name(node, "first_name"), name(node, "last_name"), name(node, "username"));
        } catch (Exception ignored) {
            throw new CreatorAuthenticationFailure();
        }
    }

    private static void validateJsonText(JsonNode node) {
        if (node.isTextual()) strictText(node.textValue());
        if (node.isObject()) node.fieldNames().forEachRemaining(TelegramInitDataVerifier::strictText);
        if (node.isContainerNode()) node.forEach(TelegramInitDataVerifier::validateJsonText);
    }

    private static String name(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) return null;
        if (!value.isTextual() || value.textValue().length() > 256) throw new CreatorAuthenticationFailure();
        strictText(value.textValue());
        return value.textValue();
    }

    private static String decode(String encoded) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(encoded.length());
        for (int i = 0; i < encoded.length();) {
            char c = encoded.charAt(i);
            if (c == '%') {
                if (i + 2 >= encoded.length()) throw new CreatorAuthenticationFailure();
                int high = Character.digit(encoded.charAt(i + 1), 16);
                int low = Character.digit(encoded.charAt(i + 2), 16);
                if (high < 0 || low < 0 || encoded.charAt(i + 1) > 127 || encoded.charAt(i + 2) > 127) {
                    throw new CreatorAuthenticationFailure();
                }
                bytes.write(high * 16 + low);
                i += 3;
            } else {
                int cp = encoded.codePointAt(i);
                bytes.writeBytes((c == '+' ? " " : new String(Character.toChars(cp))).getBytes(StandardCharsets.UTF_8));
                i += Character.charCount(cp);
            }
        }
        String decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        strictText(decoded);
        return decoded;
    }

    private static void strictText(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c)) throw new CreatorAuthenticationFailure();
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) throw new CreatorAuthenticationFailure();
            } else if (Character.isLowSurrogate(c)) throw new CreatorAuthenticationFailure();
        }
    }

    private static byte[] hmac(byte[] key, byte[] message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(message);
    }

    @Override public String toString() { return "TelegramInitDataVerifier[redacted]"; }
}
