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
     * Creates multiple zip files from the given directory by grouping items (files and subdirectories).
     * - Files are added individually.
     * - A subdirectory is added as a whole (all its internal files and structure) and will not be split.
     * If adding an item would exceed the specified chunkSize, the current zip is closed and a new one is started.
     *
     * @param sourceDirPath path to the directory.
     * @param chunkSize     maximum allowed size for each zip file.
     * @return a list of Paths to the generated zip files.
     * @throws IOException if an I/O error occurs.
     */
    public static List<Path> zipInChunks(String sourceDirPath, long chunkSize) throws IOException {
        List<Path> zipFiles = new ArrayList<>();
        Path baseDir = Paths.get(sourceDirPath);

        // List the direct children (files and directories) in the source directory.
        List<Path> items = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(baseDir)) {
            for (Path entry : stream) {
                items.add(entry);
            }
        }

        long currentChunkSize = 0;
        ZipOutputStream zos = null;

        for (Path item : items) {
            long itemSize;
            if (Files.isDirectory(item)) {
                // Calculate the total size for all files in the directory.
                itemSize = calculateDirectorySize(item);
            } else {
                itemSize = Files.size(item);
            }

            // If adding this item would exceed the chunkSize and we already have something in the current zip,
            // then close the current zip and start a new one.
            if (zos != null && (currentChunkSize + itemSize > chunkSize)) {
                zos.close();
                zos = null;
            }

            // If there is no current zip, create one.
            if (zos == null) {
                Path currentZipPath = Files.createTempFile(UUID.randomUUID().toString(), ".zip");
                zos = new ZipOutputStream(new FileOutputStream(currentZipPath.toFile()));
                zipFiles.add(currentZipPath);
                currentChunkSize = 0;
            }

            // Add the item to the zip; preserve the relative path (so for subdirectories, the entire directory
            // structure is maintained).
            if (Files.isDirectory(item)) {
                addDirectoryToZip(item, baseDir, zos);
            } else {
                addFileToZip(item, baseDir, zos);
            }
            currentChunkSize += itemSize;
        }

        // Close any open zip stream at the end.
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
    private static long calculateDirectorySize(Path dir) throws IOException {
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
    private static void addDirectoryToZip(Path dir, Path basePath, ZipOutputStream zos) throws IOException {
        // Walk the directory recursively.
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                String entryName = basePath.relativize(path).toString();
                if (Files.isDirectory(path)) {
                    // Ensure directory entry ends with a slash.
                    if (!entryName.endsWith("/")) {
                        entryName += "/";
                    }
                    ZipEntry dirEntry = new ZipEntry(entryName);
                    zos.putNextEntry(dirEntry);
                    zos.closeEntry();
                } else {
                    ZipEntry zipEntry = new ZipEntry(entryName);
                    zos.putNextEntry(zipEntry);
                    Files.copy(path, zos);
                    zos.closeEntry();
                }
            }
        }
    }

    /**
     * Adds a single file to the given ZipOutputStream.
     * The file is added with its relative path (determined from basePath).
     *
     * @param file     the file to add.
     * @param basePath the base path to relativize entry name.
     * @param zos      the ZipOutputStream to add the file to.
     * @throws IOException if an I/O error occurs.
     */
    private static void addFileToZip(Path file, Path basePath, ZipOutputStream zos) throws IOException {
        String entryName = basePath.relativize(file).toString();
        ZipEntry zipEntry = new ZipEntry(entryName);
        zos.putNextEntry(zipEntry);
        Files.copy(file, zos);
        zos.closeEntry();
    }
}
