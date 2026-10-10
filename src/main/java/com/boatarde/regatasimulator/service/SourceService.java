package com.boatarde.regatasimulator.service;

import com.boatarde.regatasimulator.dto.SearchCriteria;
import com.boatarde.regatasimulator.application.ApplicationFailure;
import com.boatarde.regatasimulator.models.GalleryResponse;
import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.util.JsonDBUtils;
import com.boatarde.regatasimulator.repository.SourceRepository;
import com.boatarde.regatasimulator.application.MediaStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@Slf4j
public class SourceService {

    private final SourceRepository repository;
    private final MediaStorage media;
    @Value("${regata-simulator.sources.initial-weight}")
    private int initialWeight;

    public SourceService(SourceRepository repository, MediaStorage media) {
        this.repository = repository;
        this.media = media;
    }

    public GalleryResponse<Source> getSources(int page, int perPage, Status status, Long userId) {
        return repository.page(new SourceRepository.Criteria(status, userId, List.of()),page,perPage);
    }

    public Resource loadSourceAsResource(Source source) {
        try {
            return new UrlResource(media.image(MediaStorage.Kind.SOURCE, source.getId()).toUri());
        } catch (ApplicationFailure e) {
            throw new ApplicationFailure(e.getKind(), "Source not found: " + source.getId(), e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load file.", e);
        }
    }

    public void deleteSource(Source source) {
        try {
            media.delete(MediaStorage.Kind.SOURCE, source.getId());
        } catch (ApplicationFailure e) {
            throw new ApplicationFailure(e.getKind(), "Failed to delete source: " + source.getId(), e.getCause());
        }
        repository.remove(source);
        log.info("Source {} deleted", source.getId());
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
        List<String> descriptions = criteria.getQuery() == null || criteria.getQuery().isBlank() ? List.of() : List.of(criteria.getQuery());
        return repository.page(new SourceRepository.Criteria(criteria.getStatus(),null,descriptions),criteria.getPage(),criteria.getPerPage());
    }

}
