package com.boatarde.regatasimulator.configuration;

import io.jsondb.JsonDBTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JsonDBConfig {

    private final String dbPath;

    public JsonDBConfig(@Value("${regata-simulator.database.path}") String dbPath) {
        this.dbPath = dbPath;
    }

    @Bean
    public JsonDBTemplate jsonDBTemplate() {
        String baseScanPackage = "com.boatarde.regatasimulator.models";

        return new JsonDBTemplate(dbPath, baseScanPackage);

    }
}
