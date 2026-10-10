package com.boatarde.regatasimulator.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.boatarde.regatasimulator.configuration.LegacyEncryptionFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Real Boot lifecycle, but no application main, auto-configuration, database, bot or process environment. */
class LegacyEncryptedBootstrapTest {
    @TempDir Path temporary;

    @Test
    void factoriesDiscoveryDecryptsConfigDataBeforeConfigurationPropertiesBindingAndHonorsProfiles() throws Exception {
        String base = """
            jasypt:
              encryptor:
                password: ${REGATA_SIMULATOR_ENC_PASSWORD}
            fixture:
              text: %s
              number: %s
              alias: ${fixture.text}
            """.formatted(encrypted("base-text"), encrypted("31"));
        String profile = """
            fixture:
              text: %s
              number: %s
            """.formatted(encrypted("profile-text"), encrypted("64"));
        Path baseFile = Files.writeString(temporary.resolve("application.yml"), base);
        Path profileFile = Files.writeString(temporary.resolve("application-synthetic.yml"), profile);
        SpringApplication application = isolatedApplication(temporary.toUri().toString(), Map.of(
            "REGATA_SIMULATOR_ENC_PASSWORD", TEST_PASSWORD));
        application.setAdditionalProfiles("synthetic");

        try (ConfigurableApplicationContext context = application.run()) {
            FixtureProperties properties = context.getBean(FixtureProperties.class);
            assertThat(properties.getText()).isEqualTo("profile-text");
            assertThat(properties.getNumber()).isEqualTo(64L);
            assertThat(properties.getAlias()).isEqualTo("profile-text");
            assertThat(context.getEnvironment().getPropertySources().stream()
                .filter(source -> source.getName().contains("application-synthetic.yml")).toList())
                .isNotEmpty().allMatch(source -> source instanceof MapPropertySource
                    && source.getSource() instanceof Map<?, ?>);
            assertThat(context.getEnvironment().getProperty("fixture.text")).isEqualTo("profile-text");
            assertThat(context.getBeanDefinitionNames()).noneMatch(name -> name.contains("DataSource") || name.contains("Telegram"));
        }
        assertThat(Files.readString(baseFile)).isEqualTo(base);
        assertThat(Files.readString(profileFile)).isEqualTo(profile);
        assertThat(new LegacyEncryptedEnvironmentPostProcessor().getOrder()).isGreaterThan(ConfigDataEnvironmentPostProcessor.ORDER);
    }

    @Test
    void wrapsSourcesAddedAfterEnvironmentProcessorsAndDuringInitializersAndConfigurationParsing() {
        SpringApplication application = isolatedApplication("optional:" + temporary.toUri(), Map.of(
            LegacyEncryptedProperties.PASSWORD, "${REGATA_SIMULATOR_ENC_PASSWORD}",
            "REGATA_SIMULATOR_ENC_PASSWORD", TEST_PASSWORD,
            "fixture.text", encrypted("bootstrap-text")), LateSourceConfiguration.class);
        application.addListeners(new LateEnvironmentListener());
        application.addInitializers(new EarlyInitializer());

        try (ConfigurableApplicationContext context = application.run()) {
            FixtureProperties properties = context.getBean(FixtureProperties.class);
            assertThat(properties.getText()).isEqualTo("bootstrap-text");
            assertThat(properties.getNumber()).isEqualTo(93L);
            assertThat(properties.getLateNumber()).isEqualTo(92L);
            assertThat(properties.getRegistryValue()).isEqualTo("registry-text");
            for (String source : new String[]{"late-environment", "early-initializer", "registry-source"}) {
                PropertySource<?> wrapped = context.getEnvironment().getPropertySources().get(source);
                assertThat(wrapped).isInstanceOf(MapPropertySource.class);
                assertThat(wrapped.getSource()).isInstanceOf(Map.class);
            }
        }
    }

    @Test
    void higherPrecedencePlainPropertyWinsOverProfileCiphertext() throws Exception {
        Files.writeString(temporary.resolve("application.yml"), "fixture:\n  text: " + encrypted("lower-text") + "\n");
        SpringApplication application = isolatedApplication(temporary.toUri().toString(), Map.of("fixture.text", "plain-override"));
        // No key is supplied: reading the lower-priority encrypted value would fail.
        try (ConfigurableApplicationContext context = application.run()) {
            assertThat(context.getBean(FixtureProperties.class).getText()).isEqualTo("plain-override");
        }
    }

    @Test
    void noEncryptedPropertiesNeedsNoKeyAndDoesNotChangeSecretFreeContext() {
        SpringApplication application = isolatedApplication("optional:" + temporary.toUri(), Map.of(
            "fixture.text", "plain-test-text", "fixture.number", "17"));
        try (ConfigurableApplicationContext context = application.run()) {
            assertThat(context.getBean(FixtureProperties.class).getText()).isEqualTo("plain-test-text");
            assertThat(context.getBean(FixtureProperties.class).getNumber()).isEqualTo(17L);
            assertThat(context.getEnvironment().getProperty(LegacyEncryptedProperties.PASSWORD)).isNull();
        }
    }

    @Test
    void existingSyntheticTestProfileRemainsUnmodifiedAndWorksWithoutMainApplication() {
        SpringApplication application = isolatedApplication("classpath:/application-test.yml", Map.of());
        try (ConfigurableApplicationContext context = application.run()) {
            assertThat(context.getEnvironment().getProperty("telegram.bots.regata-simulator.registration-enabled", Boolean.class))
                .isFalse();
            assertThat(context.getEnvironment().getProperty("regata-simulator.scheduling.enabled", Boolean.class)).isFalse();
            assertThat(context.getEnvironment().getProperty("web-admin.username")).isEqualTo("phase0-admin");
            assertThat(context.getBeanDefinitionNames()).noneMatch(name -> name.contains("Telegram") || name.contains("DataSource"));
        }
    }

    private static SpringApplication isolatedApplication(String location, Map<String, Object> values, Class<?>... additional) {
        ConfigurableEnvironment environment = emptyEnvironment();
        Map<String, Object> properties = new LinkedHashMap<>(values);
        properties.put("spring.config.location", location);
        environment.getPropertySources().addFirst(new MapPropertySource("synthetic-bootstrap", properties));
        Class<?>[] sources = new Class<?>[additional.length + 1];
        sources[0] = MinimalConfiguration.class;
        System.arraycopy(additional, 0, sources, 1, additional.length);
        SpringApplication application = new SpringApplication(sources);
        application.setEnvironment(environment);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.setRegisterShutdownHook(false);
        return application;
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FixtureProperties.class)
    static class MinimalConfiguration {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LateSourceConfiguration {
        private LateSourceConfiguration() {
        }

        @Bean
        static BeanDefinitionRegistryPostProcessor syntheticLatePropertySource(ConfigurableEnvironment environment) {
            return new BeanDefinitionRegistryPostProcessor() {
                @Override
                public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
                    environment.getPropertySources().addFirst(new MapPropertySource("registry-source", Map.of(
                        "fixture.registry-value", encrypted("registry-text"))));
                }

                @Override
                public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
                    // The bootstrap initializer's processor wraps this source before binding.
                }
            };
        }
    }

    private static final class LateEnvironmentListener
            implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {
        @Override
        public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
            event.getEnvironment().getPropertySources().addFirst(new MapPropertySource("late-environment", Map.of(
                "fixture.late-number", encrypted("92"))));
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }

    private static final class EarlyInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext>, Ordered {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            // Verifies the environment processor was discovered automatically, not called by the test.
            PropertySource<?> wrapped = context.getEnvironment().getPropertySources().get("synthetic-bootstrap");
            assertThat(wrapped).isInstanceOf(MapPropertySource.class);
            assertThat(wrapped.getSource()).isInstanceOf(Map.class);
            assertThat(wrapped.getProperty("fixture.text")).isEqualTo("bootstrap-text");
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("early-initializer", Map.of(
                "fixture.number", encrypted("93"))));
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }

    @ConfigurationProperties("fixture")
    public static class FixtureProperties {
        private String text;
        private Long number;
        private String alias;
        private String registryValue;
        private Long lateNumber;

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public Long getNumber() { return number; }
        public void setNumber(Long number) { this.number = number; }
        public String getAlias() { return alias; }
        public void setAlias(String alias) { this.alias = alias; }
        public String getRegistryValue() { return registryValue; }
        public void setRegistryValue(String registryValue) { this.registryValue = registryValue; }
        public Long getLateNumber() { return lateNumber; }
        public void setLateNumber(Long lateNumber) { this.lateNumber = lateNumber; }
    }
}
