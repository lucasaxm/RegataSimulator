package com.boatarde.regatasimulator.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulingConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(SchedulingConfig.class);

    @Test
    void schedulingRemainsEnabledByDefault() {
        runner.run(context -> assertThat(context.containsBean(
            TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)).isTrue());
    }

    @Test
    void explicitlyDisabledSchedulingRegistersNoScheduledProcessor() {
        runner.withPropertyValues("regata-simulator.scheduling.enabled=false")
            .run(context -> assertThat(context.containsBean(
                TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)).isFalse());
    }
}