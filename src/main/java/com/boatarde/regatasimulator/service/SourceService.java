package com.boatarde.regatasimulator.service;

import com.boatarde.regatasimulator.dto.SearchCriteria;
import com.boatarde.regatasimulator.flows.ApplicationFailure;
import com.boatarde.regatasimulator.models.GalleryResponse;
import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.util.JsonDBUtils;
import com.boatarde.regatasimulator.repository.SourceRepository;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@Slf4j
public class SourceService {

    private final SourceRepository repository;
    @Value("${regata-simulator.sources.path}")
    private String sourcesPathString;
    @Value("${regata-simulator.sources.initial-weight}")
    private int initialWeight;

    public SourceService(SourceRepository repository) {
        this.repository = repository;
    }

    public GalleryResponse<Source> getSources(int page, int perPage, Status status, Long userId) {
        List<Source> allMatchingSources = repository.find(new SourceRepository.Criteria(status, userId, List.of()));
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
        List<String> possibleExtensions = Arrays.asList("jpg", "jpeg", "png");

        try {
            // Find the first source file that exists
            Path sourceFile = possibleExtensions.stream()
                .map(ext -> dir.resolve("source." + ext))
                .filter(Files::exists)
                .findFirst()
                .orElseThrow(() -> new ApplicationFailure(ApplicationFailure.Kind.NOT_FOUND, "Source not found: " + source.getId()));

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
            repository.remove(source);
            log.info("Source {} deleted", source.getId());
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete source: " + source.getId(), e);
        }
    }

    public Optional<Source> getSource(UUID id) {
        return repository.findById(id);
    }

    public void completePreviewReview(Source source) {
        if (!repository.clearPreview(source.getId())) {
            throw new IllegalStateException("Source no longer exists: " + source.getId());
        }
        source.setPreviewChatId(null);
        source.setPreviewMessageId(null);
    }

    public void approveSource(Source source) {
        reviewSource(source, Status.APPROVED);
        log.info("Source {} approved", source.getId());
    }

    public void rejectSource(Source source) {
        reviewSource(source, Status.REJECTED);
        log.info("Source {} rejected", source.getId());
    }

    private void reviewSource(Source source, Status decision) {
        if (!repository.decideReview(source.getId(), decision)) {
            throw new ApplicationFailure(ApplicationFailure.Kind.CONFLICT, "Source no longer in review");
        }
        source.setStatus(decision);
        source.setPreviewChatId(null);
        source.setPreviewMessageId(null);
    }

    public void resetAllWeights() {
        repository.resetWeights(initialWeight);
        log.info("All sources weights have been reset to {}", initialWeight);
    }

    public GalleryResponse<Source> search(SearchCriteria criteria) {
        List<Source> allSources = repository.find(SourceRepository.Criteria.all());

        Stream<Source> stream = allSources.stream();

        // Filter by query if present
        if (criteria.getQuery() != null && !criteria.getQuery().isBlank()) {
            String lowerQuery = criteria.getQuery().toLowerCase();
            stream =
                stream.filter(s -> s.getDescription() != null && s.getDescription().toLowerCase().contains(lowerQuery));
        }

        // Filter by status if present
        if (criteria.getStatus() != null) {
            stream = stream.filter(s -> s.getStatus() == criteria.getStatus());
        }

        List<Source> filtered = stream
            .sorted(Comparator.comparing(s -> Optional.ofNullable(s.getDescription()).orElse("").toLowerCase()))
            .toList();

        int totalItems = filtered.size();
        List<Source> result = filtered.stream()
            .sorted(JsonDBUtils.getComparator().reversed())
            .skip((long) (criteria.getPage() - 1) * criteria.getPerPage())
            .limit(criteria.getPerPage())
            .toList();

        return new GalleryResponse<>(result, totalItems);
    }

}
