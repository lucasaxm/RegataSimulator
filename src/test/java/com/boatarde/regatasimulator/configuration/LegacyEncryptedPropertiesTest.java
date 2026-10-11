package com.boatarde.regatasimulator.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.OriginLookup;
import org.springframework.boot.origin.OriginTrackedValue;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.boatarde.regatasimulator.configuration.LegacyEncryptionFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LegacyEncryptedPropertiesTest {
    @Test
    void decryptsNativeDefaultFixturesWithoutChangingCiphertextOrCachingPlaintext() {
        String first = encrypted("synthetic-text");
        String second = encrypted("synthetic-text");
        assertThat(first).isNotEqualTo(second);
        Map<String, Object> stored = new LinkedHashMap<>(Map.of("fixture.text", first));
        ConfigurableEnvironment environment = environment(Map.of());
        MapPropertySource original = new MapPropertySource("stored", stored);
        environment.getPropertySources().addFirst(original);
        LegacyEncryptedProperties.install(environment);

        MapPropertySource wrapped = (MapPropertySource) environment.getPropertySources().get("stored");
        assertThat(wrapped.getSource()).isSameAs(stored);
        assertThat(wrapped.getSource()).containsEntry("fixture.text", first);
        assertThat(environment.getProperty("fixture.text")).isEqualTo("synthetic-text");
        assertThat(original.getProperty("fixture.text")).isEqualTo(first);
        assertThat(stored).containsEntry("fixture.text", first);
        stored.put("fixture.text", encrypted("updated-synthetic-text"));
        assertThat(environment.getProperty("fixture.text")).isEqualTo("updated-synthetic-text");
        assertThat(nativeEncryptor(TEST_PASSWORD).decrypt(first.substring(4, first.length() - 1)))
            .isEqualTo("synthetic-text");
    }

    @Test
    void preservesEnumerationContainsOrderAndBootAttachedAdapterAndIsIdempotent() {
        ConfigurableEnvironment environment = environment(Map.of("fixture.text", encrypted("text"), "fixture.number", encrypted("42")));
        PropertySource<?> original = environment.getPropertySources().get("synthetic");
        String[] names = ((EnumerablePropertySource<?>) original).getPropertyNames();
        ConfigurationPropertySources.attach(environment);
        PropertySource<?> attached = environment.getPropertySources().get("configurationProperties");
        List<String> order = environment.getPropertySources().stream().map(PropertySource::getName).toList();

        LegacyEncryptedProperties.install(environment);
        PropertySource<?> wrapped = environment.getPropertySources().get("synthetic");
        assertThat(wrapped).isInstanceOf(EnumerablePropertySource.class);
        assertThat(wrapped).isInstanceOf(MapPropertySource.class);
        assertThat(wrapped.getSource()).isSameAs(original.getSource());
        assertThat(((EnumerablePropertySource<?>) wrapped).getPropertyNames()).containsExactly(names);
        assertThat(wrapped.containsProperty("fixture.text")).isTrue();
        assertThat(wrapped.containsProperty("fixture.absent")).isFalse();
        assertThat(environment.getPropertySources().get("configurationProperties")).isSameAs(attached);
        assertThat(environment.getPropertySources().stream().map(PropertySource::getName).toList()).isEqualTo(order);
        LegacyEncryptedProperties.install(environment);
        assertThat(environment.getPropertySources().get("synthetic")).isSameAs(wrapped);
        assertThat(Binder.get(environment).bind("fixture.number", Long.class).get()).isEqualTo(42L);
    }

    @Test
    void resolvesPlaceholderInsideMarkerAndInsideDecryptedValueAndEncryptedPropertyAliases() {
        String ciphertext = encrypted("${fixture.label}");
        ConfigurableEnvironment environment = environment(Map.of(
            "fixture.text", ciphertext,
            "fixture.label", "nested-synthetic-text",
            "fixture.alias", "${fixture.text}",
            "fixture.ciphertext", ciphertext.substring(4, ciphertext.length() - 1),
            "fixture.indirect", "ENC(${fixture.ciphertext})",
            "fixture.number", encrypted("${fixture.numeric}"),
            "fixture.numeric", "71"
        ));
        LegacyEncryptedProperties.install(environment);
        assertThat(environment.getProperty("fixture.text")).isEqualTo("nested-synthetic-text");
        assertThat(environment.getProperty("fixture.alias")).isEqualTo("nested-synthetic-text");
        assertThat(environment.getProperty("fixture.indirect")).isEqualTo("nested-synthetic-text");
        assertThat(environment.getProperty("fixture.number", Long.class)).isEqualTo(71L);
    }

    @Test
    void preservesHigherPrecedencePlaintextAndDoesNotReadUnusedEncryptedValuesOrPassword() {
        ConfigurableEnvironment environment = emptyEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource("low", Map.of("fixture.text", encrypted("low"))));
        environment.getPropertySources().addFirst(new MapPropertySource("high", Map.of("fixture.text", "plain-high")));
        LegacyEncryptedProperties.install(environment);
        assertThat(environment.getProperty("fixture.text")).isEqualTo("plain-high");
        assertThat(environment.getProperty("fixture.absent")).isNull();
    }

    @Test
    void leavesOrdinaryValuesTypesAndMissingPropertiesUntouched() {
        Object identity = new Object();
        ConfigurableEnvironment environment = emptyEnvironment();
        MapPropertySource original = new MapPropertySource("plain", Map.of(
            "fixture.text", "  ordinary value  ", "fixture.number", 12L, "fixture.object", identity,
            "fixture.embedded", "literal ENC(not-a-property-marker) inside text"));
        environment.getPropertySources().addFirst(original);
        LegacyEncryptedProperties.install(environment);
        PropertySource<?> wrapped = environment.getPropertySources().get("plain");
        assertThat(wrapped.getProperty("fixture.text")).isEqualTo("  ordinary value  ");
        assertThat(wrapped.getProperty("fixture.number")).isSameAs(original.getProperty("fixture.number"));
        assertThat(wrapped.getProperty("fixture.object")).isSameAs(identity);
        assertThat(wrapped.getProperty("fixture.embedded")).isEqualTo(original.getProperty("fixture.embedded"));
        assertThat(wrapped.getProperty("absent")).isNull();
    }

    @Test
    void missingKeyFailsOnlyWhenEncryptedPropertyIsRead() {
        ConfigurableEnvironment environment = emptyEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource("lazy", Map.of("fixture.text", encrypted("text"))));
        LegacyEncryptedProperties.install(environment);
        assertThat(environment.containsProperty("fixture.text")).isTrue();
        assertThat(((EnumerablePropertySource<?>) environment.getPropertySources().get("lazy")).getPropertyNames())
            .containsExactly("fixture.text");
        genericFailure(() -> environment.getProperty("fixture.text"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "${REGATA_SIMULATOR_ENC_PASSWORD}", "ENC(not-a-password)"})
    void rejectsMissingBlankUnresolvedOrEncryptedPassword(String password) {
        ConfigurableEnvironment environment = emptyEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource("lazy", Map.of(
            LegacyEncryptedProperties.PASSWORD, password, "fixture.text", encrypted("text"))));
        LegacyEncryptedProperties.install(environment);
        genericFailure(() -> environment.getProperty("fixture.text"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ENC()", "ENC(", "ENC(AAAA", "ENC(!!!!)", "ENC(AAAA))", "ENC(AAAA)trailing", "ENC(${missing})"})
    void malformedMarkersNeverFallBackToPlaintext(String malformed) {
        ConfigurableEnvironment environment = environment(Map.of("fixture.text", malformed));
        environment.getPropertySources().addLast(new MapPropertySource("fallback", Map.of("fixture.text", "must-not-return")));
        LegacyEncryptedProperties.install(environment);
        genericFailure(() -> environment.getProperty("fixture.text"));
        genericFailure(() -> Binder.get(environment).bind("fixture.text", String.class));
    }

    @Test
    void wrongPasswordHasGenericMessageNoCauseAndNoSensitiveStackTrace() {
        String ciphertext = encrypted("synthetic-private-value");
        ConfigurableEnvironment environment = environment(Map.of("fixture.text", ciphertext));
        environment.getPropertySources().addFirst(new MapPropertySource("wrong-key", Map.of(
            LegacyEncryptedProperties.PASSWORD, "synthetic-wrong-key")));
        LegacyEncryptedProperties.install(environment);
        EncryptedPropertyFailure failure = genericFailure(() -> environment.getProperty("fixture.text"));
        StringWriter output = new StringWriter();
        failure.printStackTrace(new PrintWriter(output));
        assertThat(output.toString()).doesNotContain(ciphertext, TEST_PASSWORD, "synthetic-wrong-key", "synthetic-private-value");
        assertThat(environment.getPropertySources().get("synthetic").toString()).doesNotContain(ciphertext, TEST_PASSWORD);
    }

    @Test
    void nonEnumerableSourceDecryptsAndBinderFailureCannotBeSuppressedByBootMapper() {
        ConfigurableEnvironment environment = environment(Map.of("fixture.text", "must-not-fall-back"));
        String ciphertext = encrypted("non-enumerable-text");
        PropertySource<Object> original = new PropertySource<>("opaque", new Object()) {
            @Override
            public Object getProperty(String name) {
                return name.equals("fixture.text") ? ciphertext : null;
            }
        };
        environment.getPropertySources().addFirst(original);
        ConfigurationPropertySources.attach(environment);
        LegacyEncryptedProperties.install(environment);
        assertThat(environment.getPropertySources().get("opaque")).isNotInstanceOf(EnumerablePropertySource.class);
        assertThat(environment.getPropertySources().get("opaque").containsProperty("fixture.text")).isTrue();
        assertThat(Binder.get(environment).bind("fixture.text", String.class).get()).isEqualTo("non-enumerable-text");

        ConfigurableEnvironment wrong = emptyEnvironment();
        wrong.getPropertySources().addFirst(original);
        wrong.getPropertySources().addLast(new MapPropertySource("fallback", Map.of("fixture.text", "must-not-fall-back")));
        ConfigurationPropertySources.attach(wrong);
        LegacyEncryptedProperties.install(wrong);
        genericFailure(() -> Binder.get(wrong).bind("fixture.text", String.class));
        genericFailure(() -> wrong.getProperty("fixture.text"));
    }

    @Test
    void syntheticEnvironmentSourceRetainsNativePasswordPlaceholderResolution() {
        ConfigurableEnvironment environment = emptyEnvironment();
        String ciphertext = encrypted("52");
        Map<String, Object> stored = new LinkedHashMap<>(Map.of(
            "REGATA_SIMULATOR_ENC_PASSWORD", TEST_PASSWORD, "FIXTURE_NUMBER", ciphertext));
        environment.getPropertySources().addLast(new SystemEnvironmentPropertySource("synthetic-system-environment", stored));
        environment.getPropertySources().addFirst(new MapPropertySource("config", Map.of(
            LegacyEncryptedProperties.PASSWORD, "${REGATA_SIMULATOR_ENC_PASSWORD}")));
        LegacyEncryptedProperties.install(environment);
        MapPropertySource wrapped = (MapPropertySource) environment.getPropertySources().get("synthetic-system-environment");
        assertThat(wrapped.getSource()).isSameAs(stored);
        assertThat(wrapped.containsProperty("fixture.number")).isTrue();
        assertThat(wrapped.containsProperty("fixture-number")).isTrue();
        assertThat(wrapped.getProperty("fixture-number")).isEqualTo("52");
        assertThat(environment.getProperty("fixture.number", Long.class)).isEqualTo(52L);
        assertThat(Binder.get(environment).bind("fixture.number", Long.class).get()).isEqualTo(52L);
        assertThat(stored).containsEntry("FIXTURE_NUMBER", ciphertext);
        assertThat(((EnumerablePropertySource<?>) environment.getPropertySources().get("synthetic-system-environment"))
            .getPropertyNames()).containsExactlyInAnyOrder("REGATA_SIMULATOR_ENC_PASSWORD", "FIXTURE_NUMBER");
    }

    @Test
    void delegatesOriginWithoutReadingOrChangingValue() {
        Origin origin = new Origin() {
            @Override
            public String toString() {
                return "synthetic-location:1";
            }
        };
        ConfigurableEnvironment environment = environment(Map.of());
        Object tracked = OriginTrackedValue.of(encrypted("text"), origin);
        OriginTrackedMapPropertySource original = new OriginTrackedMapPropertySource("origin", Map.of("fixture.text", tracked));
        environment.getPropertySources().addFirst(original);
        LegacyEncryptedProperties.install(environment);
        PropertySource<?> wrapped = environment.getPropertySources().get("origin");
        assertThat(wrapped).isInstanceOf(MapPropertySource.class);
        assertThat(wrapped.getSource()).isSameAs(original.getSource());
        assertThat(OriginLookup.getOrigin(wrapped, "fixture.text")).isSameAs(origin);
        assertThat(environment.getProperty("fixture.text")).isEqualTo("text");
        assertThat(((MapPropertySource) wrapped).getSource()).containsEntry("fixture.text", tracked);
    }

    @Test
    void customMapSubclassDelegatesLookupNamesAndContainsWithoutEagerDecryption() {
        ConfigurableEnvironment environment = emptyEnvironment();
        String ciphertext = encrypted("unread-without-key");
        Map<String, Object> stored = new LinkedHashMap<>(Map.of("native-key", ciphertext));
        MapPropertySource original = new MapPropertySource("custom-map", stored) {
            @Override
            public Object getProperty(String name) {
                return super.getProperty(name.equals("fixture.alias") ? "native-key" : name);
            }

            @Override
            public String[] getPropertyNames() {
                return new String[]{"fixture.alias"};
            }

            @Override
            public boolean containsProperty(String name) {
                return name.equals("fixture.alias");
            }
        };
        environment.getPropertySources().addFirst(original);
        LegacyEncryptedProperties.install(environment);
        MapPropertySource wrapped = (MapPropertySource) environment.getPropertySources().get("custom-map");
        assertThat(wrapped.getSource()).isSameAs(stored);
        assertThat(wrapped.getPropertyNames()).containsExactly("fixture.alias");
        assertThat(wrapped.containsProperty("fixture.alias")).isTrue();
        assertThat(wrapped.containsProperty("native-key")).isFalse();
        assertThat(wrapped.getProperty("absent")).isNull();
        genericFailure(() -> wrapped.getProperty("fixture.alias"));
        assertThat(stored).containsEntry("native-key", ciphertext);
    }

    @Test
    void nonMapEnumerableSourceStillDelegatesAndDecryptsLazily() {
        ConfigurableEnvironment environment = environment(Map.of());
        String ciphertext = encrypted("enumerable-text");
        EnumerablePropertySource<Object> original = new EnumerablePropertySource<>("non-map", new Object()) {
            @Override
            public String[] getPropertyNames() {
                return new String[]{"fixture.text"};
            }

            @Override
            public Object getProperty(String name) {
                return name.equals("fixture.text") ? ciphertext : null;
            }
        };
        environment.getPropertySources().addFirst(original);
        LegacyEncryptedProperties.install(environment);
        LegacyEncryptedProperties.install(environment);
        PropertySource<?> wrapped = environment.getPropertySources().get("non-map");
        assertThat(wrapped).isInstanceOf(EnumerablePropertySource.class).isNotInstanceOf(MapPropertySource.class);
        assertThat(wrapped.getSource()).isSameAs(original);
        assertThat(((EnumerablePropertySource<?>) wrapped).getPropertyNames()).containsExactly("fixture.text");
        assertThat(wrapped.containsProperty("fixture.text")).isTrue();
        assertThat(wrapped.containsProperty("absent")).isFalse();
        assertThat(Binder.get(environment).bind("fixture.text", String.class).get()).isEqualTo("enumerable-text");
        assertThat(original.getProperty("fixture.text")).isEqualTo(ciphertext);
    }

    @ParameterizedTest
    @ValueSource(strings = {"algorithm", "key-obtention-iterations", "provider-name", "pool-size",
        "salt-generator-classname", "iv-generator-classname", "string-output-type", "provider-class-name",
        "gcm-secret-key-password", "gcm-secret-key-string", "gcm-secret-key-location", "private-key-string",
        "bean", "property.prefix", "unknown-custom-option"})
    void rejectsGcmAsymmetricCustomAlgorithmsUnavailableProvidersAndAllNonLegacySettings(String setting) {
        ConfigurableEnvironment environment = environment(Map.of("jasypt.encryptor." + setting, "unsupported-synthetic-setting"));
        genericFailure(() -> LegacyEncryptedProperties.install(environment));
    }

    @Test
    void rejectsCustomizedParametersWithAlternateNamesInsteadOfSilentlyIgnoringThem() {
        ConfigurableEnvironment environment = environment(Map.of("JASYPT_ENCRYPTOR_KEY_OBTENTION_ITERATIONS", "2"));
        genericFailure(() -> LegacyEncryptedProperties.install(environment));
    }

    @Test
    void permitsExplicitLegacyDefaultParametersOnly() {
        ConfigurableEnvironment environment = environment(Map.of(
            "jasypt.encryptor.algorithm", "PBEWITHHMACSHA512ANDAES_256",
            "jasypt.encryptor.key-obtention-iterations", 1000,
            "jasypt.encryptor.provider-name", "SunJCE",
            "jasypt.encryptor.pool-size", 1,
            "jasypt.encryptor.salt-generator-classname", "org.jasypt.salt.RandomSaltGenerator",
            "jasypt.encryptor.iv-generator-classname", "org.jasypt.iv.RandomIvGenerator",
            "jasypt.encryptor.string-output-type", "base64",
            "fixture.text", encrypted("text")
        ));
        LegacyEncryptedProperties.install(environment);
        LegacyEncryptedProperties.install(environment);
        assertThat(environment.getProperty("fixture.text")).isEqualTo("text");
    }

    @Test
    void explicitlyRejectsGcmAlgorithmEvenWhenPbePasswordIsPresent() {
        ConfigurableEnvironment environment = environment(Map.of("jasypt.encryptor.algorithm", "AES/GCM/NoPadding"));
        genericFailure(() -> LegacyEncryptedProperties.install(environment));
    }

    @Test
    void knownGcmSettingsInOpaqueSourcesAreRejectedWithoutReadingAnyKeyFile() {
        ConfigurableEnvironment environment = emptyEnvironment();
        environment.getPropertySources().addFirst(new PropertySource<>("opaque", new Object()) {
            @Override
            public Object getProperty(String name) {
                return name.equals("jasypt.encryptor.gcm-secret-key-location") ? "never-open-this-synthetic-path" : null;
            }
        });
        genericFailure(() -> LegacyEncryptedProperties.install(environment));
    }

    @Test
    void cyclicEncryptedReferencesFailGenericallyAndDoNotPoisonLaterReads() {
        ConfigurableEnvironment environment = environment(Map.of(
            "fixture.text", encrypted("${fixture.text}"), "fixture.good", encrypted("good")));
        LegacyEncryptedProperties.install(environment);
        genericFailure(() -> environment.getProperty("fixture.text"));
        assertThat(environment.getProperty("fixture.good")).isEqualTo("good");
    }

    @Test
    void pooledNativeDecryptorInitializesAndDecryptsConcurrentlyWithoutPlaintextCache() throws Exception {
        ConfigurableEnvironment environment = environment(Map.of("fixture.text", encrypted("concurrent-text")));
        LegacyEncryptedProperties.install(environment);
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                tasks.add(() -> environment.getProperty("fixture.text"));
            }
            for (var result : executor.invokeAll(tasks, 30, TimeUnit.SECONDS)) {
                assertThat(result.get()).isEqualTo("concurrent-text");
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static EncryptedPropertyFailure genericFailure(Runnable action) {
        EncryptedPropertyFailure failure = assertThrows(EncryptedPropertyFailure.class, action::run);
        assertThat(failure.getMessage()).isEqualTo("Encrypted property configuration is invalid or unavailable");
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getSuppressed()).isEmpty();
        return failure;
    }
}
