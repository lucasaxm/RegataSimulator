package com.boatarde.regatasimulator.configuration;

import com.boatarde.regatasimulator.repository.MetadataUnitOfWork;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DatabaseProperties.class)
public class DatabaseConfig {
    @Bean
    @ConditionalOnProperty(name="regata-simulator.database.engine",havingValue="jsondb",matchIfMissing=true)
    MetadataUnitOfWork jsonMetadataUnitOfWork() { return Runnable::run; }
}