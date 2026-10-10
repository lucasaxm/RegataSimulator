package com.boatarde.regatasimulator.configuration;

import org.jasypt.encryption.pbe.PooledPBEStringEncryptor;
import org.jasypt.iv.RandomIvGenerator;
import org.jasypt.salt.RandomSaltGenerator;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.OriginLookup;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import java.util.Base64;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Narrow compatibility bridge, not a replacement implementation of the starter.
 * Only the legacy ENC(...), password-based default format is accepted. No GCM,
 * asymmetric encryption, custom encryptor beans, reflection or plaintext cache.
 * Sources remain untouched; only reads through the environment are decrypted.
 */
final class LegacyEncryptedProperties {
    static final String ALGORITHM = "PBEWITHHMACSHA512ANDAES_256";
    static final String PASSWORD = "jasypt.encryptor.password";
    private static final String PREFIX = "jasypt.encryptor.";
    private static final Map<String, String> DEFAULTS = Map.of(
        "algorithm", ALGORITHM,
        "key-obtention-iterations", "1000",
        "provider-name", "SunJCE",
        "pool-size", "1",
        "salt-generator-classname", "org.jasypt.salt.RandomSaltGenerator",
        "iv-generator-classname", "org.jasypt.iv.RandomIvGenerator",
        "string-output-type", "base64"
    );
    // Enumerable sources reject every unknown encryptor setting. Probe these
    // known starter alternatives as well for sources that cannot enumerate keys.
    private static final Set<String> UNSUPPORTED = Set.of(
        "bean", "provider-class-name", "proxy-property-sources", "skip-property-sources",
        "property.prefix", "property.suffix", "property.filter-bean", "property.resolver-bean",
        "property.detector-bean", "private-key-string", "private-key-location", "private-key-format",
        "public-key-string", "public-key-location", "public-key-format", "gcm-secret-key-string",
        "gcm-secret-key-location", "gcm-secret-key-password", "gcm-key-password",
        "gcm-secret-key-salt", "gcm-secret-key-algorithm"
    );

    private LegacyEncryptedProperties() {
    }

    static void install(ConfigurableEnvironment environment) {
        try {
            validateConfiguration(environment);
            Decryptor decryptor = null;
            for (PropertySource<?> source : environment.getPropertySources()) {
                if (source instanceof Wrapped wrapped) {
                    decryptor = wrapped.decryptor();
                    break;
                }
            }
            if (decryptor == null) {
                decryptor = new Decryptor(environment);
            }
            for (PropertySource<?> source : environment.getPropertySources()) {
                if (source instanceof Wrapped || ConfigurationPropertySources.isAttachedConfigurationPropertySource(source)) {
                    continue;
                }
                PropertySource<?> replacement = switch (source) {
                    case MapPropertySource map -> new MapWrapper(map, decryptor);
                    case EnumerablePropertySource<?> enumerable -> new EnumerableWrapper(enumerable, decryptor);
                    default -> new Wrapper(source, decryptor);
                };
                environment.getPropertySources().replace(source.getName(), replacement);
            }
        } catch (RuntimeException | LinkageError failure) {
            throw new EncryptedPropertyFailure();
        }
    }

    private static void validateConfiguration(ConfigurableEnvironment environment) {
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (ConfigurationPropertySources.isAttachedConfigurationPropertySource(source)) {
                continue;
            }
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                validateEnumerableSettings(environment, enumerable);
            }
        }
        for (Map.Entry<String, String> setting : DEFAULTS.entrySet()) {
            String configured = environment.getProperty(PREFIX + setting.getKey());
            if (configured != null && !setting.getValue().equals(configured)) {
                throw new EncryptedPropertyFailure();
            }
        }
        for (String key : UNSUPPORTED) {
            if (environment.containsProperty(PREFIX + key)) {
                throw new EncryptedPropertyFailure();
            }
        }
    }

    private static void validateEnumerableSettings(ConfigurableEnvironment environment, EnumerablePropertySource<?> source) {
        for (String name : source.getPropertyNames()) {
            String normalized = normalize(name);
            if (!normalized.startsWith("jasyptencryptor") || normalized.equals(normalize(PASSWORD))) {
                continue;
            }
            String expected = DEFAULTS.entrySet().stream()
                .filter(setting -> normalized.equals(normalize(PREFIX + setting.getKey())))
                .map(Map.Entry::getValue).findFirst().orElseThrow(EncryptedPropertyFailure::new);
            // Check the actual enumerated spelling as well: map sources do not
            // necessarily resolve camel-case/underscore aliases like the environment adapter does.
            Object configured = source instanceof Wrapped wrapped ? wrapped.delegate().getProperty(name)
                : source.getProperty(name);
            if (!(configured instanceof String) && !(configured instanceof Number)) {
                throw new EncryptedPropertyFailure();
            }
            if (!expected.equals(environment.resolveRequiredPlaceholders(configured.toString()))) {
                throw new EncryptedPropertyFailure();
            }
        }
    }

    private static String normalize(String name) {
        return name.replace(".", "").replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
    }

    private interface Wrapped {
        Decryptor decryptor();

        PropertySource<?> delegate();
    }

    private static final class Decryptor {
        private final ConfigurableEnvironment environment;
        private final ThreadLocal<Boolean> initializing = ThreadLocal.withInitial(() -> false);
        private final ThreadLocal<Set<String>> resolving = ThreadLocal.withInitial(HashSet::new);
        private volatile PooledPBEStringEncryptor encryptor;

        private Decryptor(ConfigurableEnvironment environment) {
            this.environment = environment;
        }

        private Object resolve(Object value) {
            if (!(value instanceof String text) || !text.trim().startsWith("ENC(")) {
                return value;
            }
            Set<String> active = resolving.get();
            if (!active.add(text)) {
                throw new EncryptedPropertyFailure();
            }
            try {
                String marked = text.trim();
                if (!marked.endsWith(")")) {
                    throw new EncryptedPropertyFailure();
                }
                String ciphertext = environment.resolveRequiredPlaceholders(marked.substring(4, marked.length() - 1));
                if (ciphertext.isEmpty() || !ciphertext.matches("[A-Za-z0-9+/]+={0,2}")
                        || ciphertext.length() % 4 != 0 || Base64.getDecoder().decode(ciphertext).length == 0) {
                    throw new EncryptedPropertyFailure();
                }
                return environment.resolveRequiredPlaceholders(encryptor().decrypt(ciphertext));
            } catch (RuntimeException | LinkageError failure) {
                // Neither Jasypt nor the placeholder resolver's messages/causes are safe to expose.
                throw new EncryptedPropertyFailure();
            } finally {
                active.remove(text);
                if (active.isEmpty()) {
                    resolving.remove();
                }
            }
        }

        private PooledPBEStringEncryptor encryptor() {
            if (Boolean.TRUE.equals(initializing.get())) {
                throw new EncryptedPropertyFailure();
            }
            PooledPBEStringEncryptor current = encryptor;
            if (current == null) {
                synchronized (this) {
                    current = encryptor;
                    if (current == null) {
                        initializing.set(true);
                        try {
                            // Resolve the key with Spring's normal placeholder-aware property resolver.
                            String password = environment.getProperty(PASSWORD);
                            if (password == null || password.isBlank() || password.trim().startsWith("ENC(")) {
                                throw new EncryptedPropertyFailure();
                            }
                            current = new PooledPBEStringEncryptor();
                            current.setPoolSize(1);
                            current.setPassword(password);
                            current.setAlgorithm(ALGORITHM);
                            current.setKeyObtentionIterations(1000);
                            current.setProviderName("SunJCE");
                            current.setSaltGenerator(new RandomSaltGenerator());
                            current.setIvGenerator(new RandomIvGenerator());
                            current.setStringOutputType("base64");
                            // Initialize the native pool before publishing it to concurrent readers.
                            current.initialize();
                            encryptor = current;
                        } finally {
                            initializing.remove();
                        }
                    }
                }
            }
            return current;
        }
    }

    private static final class MapWrapper extends MapPropertySource implements Wrapped, OriginLookup<String> {
        private final MapPropertySource delegate;
        private final Decryptor decryptor;

        private MapWrapper(MapPropertySource delegate, Decryptor decryptor) {
            // Boot test customizers require both the MapPropertySource type and its original map.
            // Delegate reads even for system-environment maps to retain native relaxed lookup.
            super(delegate.getName(), delegate.getSource());
            this.delegate = delegate;
            this.decryptor = decryptor;
        }

        @Override
        public String[] getPropertyNames() {
            return delegate.getPropertyNames();
        }

        @Override
        public boolean containsProperty(String name) {
            return delegate.containsProperty(name);
        }

        @Override
        public Object getProperty(String name) {
            return decryptor.resolve(delegate.getProperty(name));
        }

        @Override
        public Origin getOrigin(String name) {
            return OriginLookup.getOrigin(delegate, name);
        }

        @Override
        public Decryptor decryptor() {
            return decryptor;
        }

        @Override
        public PropertySource<?> delegate() {
            return delegate;
        }

        @Override
        public String toString() {
            return "Legacy encrypted map property source";
        }
    }

    private static final class EnumerableWrapper extends EnumerablePropertySource<PropertySource<?>>
            implements Wrapped, OriginLookup<String> {
        private final EnumerablePropertySource<?> delegate;
        private final Decryptor decryptor;

        private EnumerableWrapper(EnumerablePropertySource<?> delegate, Decryptor decryptor) {
            super(delegate.getName(), delegate);
            this.delegate = delegate;
            this.decryptor = decryptor;
        }

        @Override
        public String[] getPropertyNames() {
            return delegate.getPropertyNames();
        }

        @Override
        public boolean containsProperty(String name) {
            return delegate.containsProperty(name);
        }

        @Override
        public Object getProperty(String name) {
            return decryptor.resolve(delegate.getProperty(name));
        }

        @Override
        public Origin getOrigin(String name) {
            return OriginLookup.getOrigin(delegate, name);
        }

        @Override
        public Decryptor decryptor() {
            return decryptor;
        }

        @Override
        public PropertySource<?> delegate() {
            return delegate;
        }

        @Override
        public String toString() {
            return "Legacy encrypted enumerable property source";
        }
    }

    private static final class Wrapper extends PropertySource<PropertySource<?>> implements Wrapped, OriginLookup<String> {
        private final Decryptor decryptor;

        private Wrapper(PropertySource<?> delegate, Decryptor decryptor) {
            super(delegate.getName(), delegate);
            this.decryptor = decryptor;
        }

        @Override
        public boolean containsProperty(String name) {
            return source.containsProperty(name);
        }

        @Override
        public Object getProperty(String name) {
            return decryptor.resolve(source.getProperty(name));
        }

        @Override
        public Origin getOrigin(String name) {
            return OriginLookup.getOrigin(source, name);
        }

        @Override
        public Decryptor decryptor() {
            return decryptor;
        }

        @Override
        public PropertySource<?> delegate() {
            return source;
        }

        @Override
        public String toString() {
            return "Legacy encrypted property source";
        }
    }
}
