package com.boatarde.regatasimulator.util;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@UtilityClass
public class FileUtils {

    public static void deleteTree(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            log.warn("Could not clean temporary directory: {}", directory);
        }
    }

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
        return zipInChunks(sourceDirPath, chunkSize, null);
    }

    public static List<Path> zipInChunks(String sourceDirPath, long chunkSize, Path archiveDirectory) throws IOException {
        if (chunkSize <= 0) {
            throw new IOException("Archive chunk limit must be positive");
        }
        List<Path> zipFiles = new ArrayList<>();
        Path baseDir = Paths.get(sourceDirPath);

        // List the direct children (files and directories) in the source directory.
        List<Path> items = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(baseDir)) {
            for (Path entry : stream) {
                items.add(entry);
            }
        }

        items.sort(java.util.Comparator.naturalOrder());
        List<List<Path>> chunks = groupItems(items, chunkSize);
        try {
            for (List<Path> chunk : chunks) {
                var permissions = java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                Path zip = archiveDirectory == null ? Files.createTempFile("regata-backup-", ".zip", permissions)
                    : Files.createTempFile(archiveDirectory, "regata-backup-", ".zip", permissions);
                zipFiles.add(zip);
                writeChunk(zip, chunk, baseDir, chunkSize);
            }
        } catch (IOException | RuntimeException e) {
            for (Path zip : zipFiles) {
                try {
                    Files.deleteIfExists(zip);
                } catch (IOException cleanup) {
                    e.addSuppressed(cleanup);
                }
            }
            throw e;
        }
        return zipFiles;
    }

    private static List<List<Path>> groupItems(List<Path> items, long chunkSize) throws IOException {
        List<List<Path>> chunks = new ArrayList<>();
        List<Path> current = new ArrayList<>();
        long currentSize = 0;
        for (Path item : items) {
            if (Files.isSymbolicLink(item)) {
                throw new IOException("Backup does not follow symbolic links");
            }
            long size = Files.isDirectory(item) ? calculateDirectorySize(item) : Files.size(item);
            if (size > chunkSize) {
                throw new IOException("Backup item exceeds archive limit; items are not split");
            }
            if (!current.isEmpty() && size > chunkSize - currentSize) {
                chunks.add(current);
                current = new ArrayList<>();
                currentSize = 0;
            }
            current.add(item);
            currentSize += size;
        }
        if (!current.isEmpty()) chunks.add(current);
        return chunks;
    }

    private static void writeChunk(Path zip, List<Path> chunk, Path baseDir, long chunkSize) throws IOException {
        try (var output = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Path item : chunk) {
                if (Files.isDirectory(item)) addDirectoryToZip(item, baseDir, output);
                else addFileToZip(item, baseDir, output);
            }
        }
        if (Files.size(zip) > chunkSize) {
            throw new IOException("Encoded archive exceeds limit");
        }
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
            long size = 0;
            for (Path path : files.toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("Backup does not follow symbolic links");
                if (Files.isRegularFile(path)) size = Math.addExact(size, Files.size(path));
            }
            return size;
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
                if (Files.isSymbolicLink(path)) throw new IOException("Backup does not follow symbolic links");
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
