package com.boatarde.regatasimulator.configuration;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class DatabasePropertiesTest {
    @Test void jsonDbIsDefaultAndSqliteRequiresExplicitAbsoluteFileAndBoundedTimeout() {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator(); var properties=new DatabaseProperties();
            assertEquals(DatabaseProperties.Engine.JSONDB,properties.getEngine()); assertTrue(validator.validate(properties).isEmpty());
            properties.setEngine(DatabaseProperties.Engine.SQLITE); assertFalse(validator.validate(properties).isEmpty());
            properties.setSqliteFile(Path.of("relative.db")); assertFalse(validator.validate(properties).isEmpty());
            properties.setSqliteFile(Path.of("/isolated/candidate.db")); assertTrue(validator.validate(properties).isEmpty());
            properties.setBusyTimeoutMillis(10001); assertFalse(validator.validate(properties).isEmpty());
            properties.setBusyTimeoutMillis(0); assertFalse(validator.validate(properties).isEmpty());
        }
    }
}