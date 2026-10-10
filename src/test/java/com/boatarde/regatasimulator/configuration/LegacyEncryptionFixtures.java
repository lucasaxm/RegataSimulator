package com.boatarde.regatasimulator.configuration;

import org.jasypt.encryption.pbe.StandardPBEStringEncryptor;
import org.jasypt.iv.RandomIvGenerator;
import org.jasypt.salt.RandomSaltGenerator;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

import java.util.LinkedHashMap;
import java.util.Map;

/** Entirely synthetic fixtures; never read process environment, dotenv or application YAML. */
final class LegacyEncryptionFixtures {
    static final String TEST_PASSWORD = "synthetic-legacy-pbe-fixture-password-only";

    private LegacyEncryptionFixtures() {
    }

    static ConfigurableEnvironment emptyEnvironment() {
        return new StandardEnvironment() {
            @Override
            protected void customizePropertySources(MutablePropertySources sources) {
                // Do not install systemProperties or systemEnvironment.
            }
        };
    }

    static ConfigurableEnvironment environment(Map<String, Object> values) {
        ConfigurableEnvironment environment = emptyEnvironment();
        Map<String, Object> properties = new LinkedHashMap<>(values);
        properties.put(LegacyEncryptedProperties.PASSWORD, "${REGATA_SIMULATOR_ENC_PASSWORD}");
        properties.put("REGATA_SIMULATOR_ENC_PASSWORD", TEST_PASSWORD);
        environment.getPropertySources().addLast(new MapPropertySource("synthetic", properties));
        return environment;
    }

    static String encrypted(String plaintext) {
        return "ENC(" + nativeEncryptor(TEST_PASSWORD).encrypt(plaintext) + ")";
    }

    static StandardPBEStringEncryptor nativeEncryptor(String password) {
        StandardPBEStringEncryptor encryptor = new StandardPBEStringEncryptor();
        // Explicit independent fixture defaults, not values copied from the implementation under test.
        encryptor.setPassword(password);
        encryptor.setAlgorithm("PBEWITHHMACSHA512ANDAES_256");
        encryptor.setKeyObtentionIterations(1000);
        encryptor.setProviderName("SunJCE");
        encryptor.setSaltGenerator(new RandomSaltGenerator());
        encryptor.setIvGenerator(new RandomIvGenerator());
        encryptor.setStringOutputType("base64");
        return encryptor;
    }
}
