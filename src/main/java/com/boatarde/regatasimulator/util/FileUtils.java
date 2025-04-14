package com.boatarde.regatasimulator.util;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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

    public static String getFileExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return (dotIndex == -1) ? "" : fileName.substring(dotIndex);
    }


    /**
     * Creates multiple zip files from the templates directory by grouping complete UUID subdirectories.
     * No single UUID directory's contents are split between zip files.
     *
     * @param sourceDirPath path to the templates directory.
     * @param chunkSize
     * @return a list of Paths to the generated zip files.
     * @throws IOException if an I/O error occurs.
     */
    public static List<Path> zipInChunks(String sourceDirPath, long chunkSize) throws IOException {
        List<Path> zipFiles = new ArrayList<>();
        Path templatesPath = Paths.get(sourceDirPath);

        // List only the subdirectories (UUID directories) in the templates folder.
        List<Path> uuidDirs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(templatesPath)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    uuidDirs.add(entry);
                }
            }
        }

        long currentChunkSize = 0;
        ZipOutputStream zos = null;
        Path currentZipPath = null;

        // For each UUID directory
        for (Path uuidDir : uuidDirs) {
            long dirSize = calculateDirectorySize(uuidDir);

            // If current zip already exists and adding this directory would exceed the limit,
            // then close the current zip and start a new one.
            if (zos != null && (currentChunkSize + dirSize > chunkSize)) {
                zos.close();
                zos = null;
            }

            // If there's no current zip file open, create one.
            if (zos == null) {
                currentZipPath = Files.createTempFile(UUID.randomUUID().toString(), ".zip");
                zos = new ZipOutputStream(new FileOutputStream(currentZipPath.toFile()));
                zipFiles.add(currentZipPath);
                currentChunkSize = 0;
            }

            // Add the entire UUID directory (all its files, with its internal structure)
            addDirectoryToZip(uuidDir, templatesPath, zos);
            currentChunkSize += dirSize;
        }

        // Close any open zip stream
        if (zos != null) {
            zos.close();
        }
        return zipFiles;
    }

    /**
     * Recursively calculates the total size of all regular files in the given directory.
     *
     * @param dir the directory for which to calculate total size.
     * @return total size in bytes.
     * @throws IOException if an error occurs during traversal.
     */
    private long calculateDirectorySize(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile)
                .mapToLong(path -> {
                    try {
                        return Files.size(path);
                    } catch (IOException e) {
                        log.error("Error reading file size: {}", path, e);
                        return 0L;
                    }
                })
                .sum();
        }
    }

    /**
     * Adds a directory and all its contents recursively to the given ZipOutputStream.
     * The directory structure is preserved relative to the basePath.
     *
     * @param dir      the directory to add.
     * @param basePath the base path to relativize entry names.
     * @param zos      the ZipOutputStream to add entries to.
     * @throws IOException if an I/O error occurs.
     */
    private void addDirectoryToZip(Path dir, Path basePath, ZipOutputStream zos) throws IOException {
        // Walk the directory recursively.
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                // Determine the entry name relative to the base path. This preserves the UUID folder name.
                String entryName = basePath.relativize(path).toString();
                if (Files.isDirectory(path)) {
                    // For directories, add an entry with a trailing slash.
                    if (!entryName.endsWith("/")) {
                        entryName += "/";
                    }
                    ZipEntry dirEntry = new ZipEntry(entryName);
                    zos.putNextEntry(dirEntry);
                    zos.closeEntry();
                } else {
                    // For files, add the file contents.
                    ZipEntry zipEntry = new ZipEntry(entryName);
                    zos.putNextEntry(zipEntry);
                    Files.copy(path, zos);
                    zos.closeEntry();
                }
            }
        }
    }
}
