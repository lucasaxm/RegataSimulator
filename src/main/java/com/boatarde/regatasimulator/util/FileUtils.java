package com.boatarde.regatasimulator.util;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
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

    public static boolean isImage(File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            return false;
        }

        try {
            return ImageIO.read(file) != null;
        } catch (IOException e) {
            return false;
        }
    }

    public static String getFileExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return (dotIndex == -1) ? "" : fileName.substring(dotIndex);
    }
}
