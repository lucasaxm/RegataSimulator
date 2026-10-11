package com.boatarde.regatasimulator.configuration;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;

/** Covers sources added after environment processing, including configuration-class property sources. */
public final class LegacyEncryptedPropertyInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext>, Ordered {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        LegacyEncryptedProperties.install(context.getEnvironment());
        // Programmatic factory processors run after configuration-class parsing,
        // but before regular beans and @ConfigurationProperties binding.
        context.addBeanFactoryPostProcessor(beanFactory -> LegacyEncryptedProperties.install(context.getEnvironment()));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
