package com.boatarde.regatasimulator.service;

import com.boatarde.regatasimulator.models.GalleryResponse;
import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.util.JsonDBUtils;
import io.jsondb.JsonDBTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@Slf4j
public class SourceService {

    private final JsonDBTemplate jsonDBTemplate;
    @Value("${regata-simulator.sources.path}")
    private String sourcesPathString;

    public SourceService(JsonDBTemplate jsonDBTemplate) {
        this.jsonDBTemplate = jsonDBTemplate;
    }

    public GalleryResponse<Source> getSources(int page, int perPage, Status status, Long userId) {
        String jxQuery = JsonDBUtils.getJxQuery(status, userId);

        // Query to get the total number of items and the paginated result
        List<Source> allMatchingSources = jsonDBTemplate.find(jxQuery, Source.class);
        int totalItems = allMatchingSources.size();
        List<Source> result = allMatchingSources.stream()
            .sorted(JsonDBUtils.getComparator().reversed())
            .skip((long) (page - 1) * perPage)
            .limit(perPage)
            .toList();

        return new GalleryResponse<>(result, totalItems);
    }

    public Resource loadSourceAsResource(Source source) {
        Path dir = Paths.get(sourcesPathString, source.getId().toString());

        try {
            Path sourceFile = dir.resolve("source.jpg");
            if (!sourceFile.toFile().exists()) {
                sourceFile = dir.resolve("source.png");
            }
            if (!sourceFile.toFile().exists()) {
                throw new RuntimeException("Source not found: " + source.getId());
            }
            return new UrlResource(sourceFile.toUri());
        } catch (IOException e) {
            throw new RuntimeException("Failed to load file.", e);
        }
    }

    public void deleteSource(Source source) {
        try {
            Path filePath = Paths.get(sourcesPathString, source.getId().toString());
            try (Stream<Path> paths = Files.walk(filePath)) {
                if (!paths.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .allMatch(File::delete)) {
                    throw new RuntimeException("Failed to delete source: " + source.getId());
                }
            }
            jsonDBTemplate.remove(source, Source.class);
            log.info("Template {} deleted", source.getId());
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete source: " + source.getId(), e);
        }
    }

    public Optional<Source> getSource(UUID id) {
        return Optional.ofNullable(jsonDBTemplate.findById(id, Source.class));
    }

    // temporary method to save templates from file system to jsondb
    public List<Source> saveSources() {
        Path sourcesPath = Paths.get(sourcesPathString);
        List<Source> sources = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(sourcesPath)) {
            paths.filter(Files::isDirectory)
                .filter(path -> {
                    try {
                        UUID.fromString(path.getFileName().toString());
                        return true;
                    } catch (IllegalArgumentException e) {
                        log.error("Invalid source id: %s".formatted(path));
                        return false;
                    }
                })
                .forEach(path -> {
                    Source source = new Source();
                    source.setId(UUID.fromString(path.getFileName().toString()));
                    source.setWeight(30);
                    source.setStatus(Status.APPROVED);
                    sources.add(source);
                });
        } catch (IOException e) {
            log.error("Failed to read sources", e);
        }
        jsonDBTemplate.upsert(sources, Source.class);

        return sources;
    }
}
