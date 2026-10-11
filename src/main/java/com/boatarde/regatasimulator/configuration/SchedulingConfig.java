package com.boatarde.regatasimulator.configuration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "regata-simulator.scheduling", name = "enabled", havingValue = "true",
    matchIfMissing = true)
public class SchedulingConfig {
    // This class enables scheduling
}
