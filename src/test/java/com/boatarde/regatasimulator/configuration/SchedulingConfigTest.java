package com.boatarde.regatasimulator.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulingConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(SchedulingConfig.class);

    @Test
    void schedulingRemainsEnabledByDefault() {
        runner.run(context -> assertThat(context.containsBean(
            "org.springframework.context.annotation.internalScheduledAnnotationProcessor")).isTrue());
    }

    @Test
    void explicitlyDisabledSchedulingRegistersNoScheduledProcessor() {
        runner.withPropertyValues("regata-simulator.scheduling.enabled=false")
            .run(context -> assertThat(context.containsBean(
                "org.springframework.context.annotation.internalScheduledAnnotationProcessor")).isFalse());
    }
}