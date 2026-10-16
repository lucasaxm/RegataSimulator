package com.boatarde.regatasimulator.application.creatorauth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static com.boatarde.regatasimulator.application.creatorauth.SyntheticInitData.*;
import static org.junit.jupiter.api.Assertions.*;

class TelegramInitDataVerifierTest {
    private final TelegramInitDataVerifier verifier = new TelegramInitDataVerifier(BOT, Clock.fixed(NOW, ZoneOffset.UTC));

    private String signedUser(String user) {
        return sign(Map.of("user", user, "auth_date", Long.toString(NOW.getEpochSecond())));
    }

    private void rejected(String input) {
        var failure = assertThrows(CreatorAuthenticationFailure.class, () -> verifier.verify(input));
        assertEquals("Invalid creator credentials", failure.getMessage());
        assertNull(failure.getCause());
    }

    @Test void verifiesOriginalWhitespaceUnicodePlusAndUnknownSignedFieldsIncludingSignature() {
        String user = "{ \"id\" : 42, \"first_name\" : \"João + Ação\", \"last_name\":\"é\", \"future\":true }";
        var fields = Map.of("user", user, "auth_date", Long.toString(NOW.getEpochSecond()),
            "signature", "future-signature+value", "unknown", "ação");
        String raw = sign(fields);
        var actor = verifier.verify(raw);
        assertEquals(42, actor.id());
        assertEquals("João + Ação", actor.firstName());
        assertEquals("é", actor.lastName());
        rejected(raw.replace("future-signature", "changed-signature"));
        rejected(raw.replace("unknown=a%C3%A7", "unknown=changed"));
        rejected(raw.replace("%7B+", "%7B"));
        rejected(sign(fields, "wrong-synthetic-bot"));
        assertEquals(42, verifier.verify(raw.replace("user=", "%75ser=")).id());
    }

    @Test void rejectsTamperingAndMalformedHashes() {
        rejected(valid().replace("42", "43"));
        rejected(valid().replaceFirst("hash=.", "hash=z"));
        rejected(valid().replaceFirst("hash=", "hash=00"));
        rejected(valid().replaceFirst("hash=[^&]+&", ""));
        rejected(null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "x", "=x", "user=%", "user=%0", "user=%GG", "user=%C3%28", "user=%ED%A0%80",
        "user=%00", "user=%0A", "user=%0D", "bad-key=x", "ü=x", "user=x&%75ser=y", "hash=a&%68ash=b",
        "user=x&user=y", "user=\uD800", "user=\uDC00", "user=x\n"})
    void rejectsMalformedQueryWithoutLeakingInput(String raw) { rejected(raw); }

    @Test void enforcesByteFieldAndKeyBoundsBeforeVerification() {
        rejected("x=" + "a".repeat(16383));
        rejected("x=" + "é".repeat(8192));
        rejected("a".repeat(65) + "=x");
        rejected("x=x&".repeat(33));
        var fields = new HashMap<String, String>();
        fields.put("user", "{\"id\":42}");
        fields.put("auth_date", Long.toString(NOW.getEpochSecond()));
        for (int i = 0; i < 29; i++) fields.put("k" + i, "v");
        assertEquals(42, verifier.verify(sign(fields)).id()); // 32 including hash
        fields.put("over", "v");
        rejected(sign(fields));
        fields = new HashMap<>();
        fields.put("user", "{\"id\":42}");
        fields.put("auth_date", Long.toString(NOW.getEpochSecond()));
        fields.put("long", "é".repeat(8192));
        rejected(sign(fields));
    }

    @Test void exactFreshnessAndFutureBoundariesUseInjectedClock() {
        for (long offset : new long[]{-300, 0, 60}) {
            assertEquals(42, verifier.verify(sign(Map.of("user", "{\"id\":42}",
                "auth_date", Long.toString(NOW.getEpochSecond() + offset)))).id());
        }
        for (long offset : new long[]{-301, 61}) {
            rejected(sign(Map.of("user", "{\"id\":42}", "auth_date", Long.toString(NOW.getEpochSecond() + offset))));
        }
        for (String invalid : new String[]{"0", "-1", "+1", "1.0", "1e9", " 1", "9223372036854775807", "9223372036854775808", "9999999999999999999999"}) {
            rejected(sign(Map.of("user", "{\"id\":42}", "auth_date", invalid)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "null", "{}", "{\"id\":\"42\"}", "{\"id\":42.0}", "{\"id\":4.2e1}",
        "{\"id\":true}", "{\"id\":0}", "{\"id\":-1}", "{\"id\":9223372036854775808}",
        "{\"id\":42,\"id\":43}", "{\"id\":42,\"x\":{\"a\":1,\"a\":2}}", "{\"id\":42} {}",
        "{\"id\":42,\"first_name\":null}", "{\"id\":42,\"username\":true}",
        "{\"id\":42,\"first_name\":\"\\n\"}", "{\"id\":42,\"first_name\":\"\\ud800\"}",
        "{\"id\":42,\"future\":\"\\ud800\"}"})
    void rejectsInvalidUserJson(String user) { rejected(signedUser(user)); }

    @Test void boundsOptionalNamesAndAcceptsLongIdentity() {
        rejected(signedUser("{\"id\":42,\"first_name\":\"" + "x".repeat(257) + "\"}"));
        assertEquals(Long.MAX_VALUE, verifier.verify(signedUser("{\"id\":9223372036854775807}")).id());
    }

    @Test void redactionAndConfigurationFailureHaveNoSecretOrCause() {
        assertFalse(verifier.toString().contains(BOT));
        assertEquals("CreatorActor[redacted]", verifier.verify(valid()).toString());
        for (String invalid : new String[]{"", " ", "synthetic\nsecret", "x".repeat(1025)}) {
            var failure = assertThrows(IllegalStateException.class, () -> new TelegramInitDataVerifier(invalid, Clock.systemUTC()));
            assertEquals("Creator authentication configuration invalid", failure.getMessage());
            assertNull(failure.getCause());
        }
    }
}
