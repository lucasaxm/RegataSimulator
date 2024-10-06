package com.boatarde.regatasimulator.service;

import com.boatarde.regatasimulator.models.GalleryResponse;
import com.boatarde.regatasimulator.models.Source;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class SourceService {

    @Value("${regata-simulator.sources.path}")
    private String sourcesPathString;


    public GalleryResponse<Source> getSources(int page, int perPage) {
        Path dir = Paths.get(sourcesPathString);
        try (Stream<Path> dirStream = Files.list(dir)) {
            List<Source> allItems = dirStream
                .filter(Files::isDirectory)
                .flatMap(subdir -> {
                    try {
                        return Files.list(subdir)
                            .filter(file -> file.getFileName().toString().equalsIgnoreCase("source.jpg") ||
                                file.getFileName().toString().equalsIgnoreCase("source.png"))
                            .map(file -> Source.builder()
                                .id(UUID.fromString(subdir.getFileName().toString()))
                                .build());
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to read source subdirectory", e);
                    }
                })
                .collect(Collectors.toList());

            int totalItems = allItems.size();
            int startIndex = (page - 1) * perPage;
            int endIndex = Math.min(startIndex + perPage, totalItems);

            List<Source> pageItems = allItems.subList(startIndex, endIndex);

            return new GalleryResponse<>(pageItems, totalItems);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read source", e);
        }
    }

    public Resource loadImageAsResource(UUID id) {
        Path dir = Paths.get(sourcesPathString, id.toString());

        try {
            Path templateFile = dir.resolve("source.jpg");
            if (!templateFile.toFile().exists()) {
                templateFile = dir.resolve("source.png");
            }
            if (!templateFile.toFile().exists()) {
                throw new RuntimeException("Source not found: " + id);
            }
            return new UrlResource(templateFile.toUri());
        } catch (IOException e) {
            throw new RuntimeException("Failed to load file.", e);
        }
    }

    public void deleteSource(UUID id) {
        try {
            Path filePath = Paths.get(sourcesPathString).resolve(id.toString());
            try (Stream<Path> paths = Files.walk(filePath)) {
                paths.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete source: " + id, e);
        }
    }
}
