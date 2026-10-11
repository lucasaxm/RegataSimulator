package com.boatarde.regatasimulator.configuration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import java.nio.file.Path;

@Getter
@Setter
@Validated
@ConfigurationProperties("regata-simulator.database")
public class DatabaseProperties {
    public enum Engine { JSONDB, SQLITE }
    @NotNull private Engine engine = Engine.JSONDB;
    private Path sqliteFile;
    @Min(1) @Max(10_000) private int busyTimeoutMillis = 2_000;
    @AssertTrue(message="SQLite requires an explicit absolute local file")
    public boolean isSqliteFileValid() { return engine != Engine.SQLITE || sqliteFile != null && sqliteFile.isAbsolute(); }
}