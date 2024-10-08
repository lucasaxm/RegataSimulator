package com.boatarde.regatasimulator.util;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.Optional;

@Slf4j
@UtilityClass
public class FileUtils {

    public static Optional<Path> getFirstExistingFile(Path directory, String... filenames) {
        for (String filename : filenames) {
            Path filePath = directory.resolve(filename);
            if (filePath.toFile().exists()) {
                return Optional.of(filePath);
            }
        }
        return Optional.empty();
    }
}
