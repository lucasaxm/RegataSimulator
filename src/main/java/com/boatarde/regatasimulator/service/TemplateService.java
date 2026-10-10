package com.boatarde.regatasimulator.service;

import com.boatarde.regatasimulator.models.GalleryResponse;
import com.boatarde.regatasimulator.flows.ApplicationFailure;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.models.Template;
import com.boatarde.regatasimulator.util.JsonDBUtils;
import com.boatarde.regatasimulator.repository.TemplateRepository;
import com.boatarde.regatasimulator.application.MediaStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class TemplateService {

    private final MediaStorage media;
    private final TemplateRepository repository;

    @Value("${regata-simulator.templates.initial-weight}")
    private int initialWeight;

    public TemplateService(TemplateRepository repository, MediaStorage media) {
        this.media = media;
        this.repository = repository;
    }

    public GalleryResponse<Template> getTemplates(int page, int perPage, Status status, Long userId) {
        List<Template> allMatchingTemplates = repository.find(new TemplateRepository.Criteria(status, userId, false));
        int totalItems = allMatchingTemplates.size();
        List<Template> result = allMatchingTemplates.stream()
            .sorted(JsonDBUtils.getComparator().reversed())
            .skip((long) (page - 1) * perPage)
            .limit(perPage)
            .toList();

        return new GalleryResponse<>(result, totalItems);
    }

    public Resource loadTemplateAsResource(Template template) {
        try {
            return new UrlResource(media.image(MediaStorage.Kind.TEMPLATE, template.getId()).toUri());
        } catch (ApplicationFailure e) {
            throw new ApplicationFailure(e.getKind(), "Template not found: " + template.getId(), e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load file.", e);
        }
    }

    public void deleteTemplate(Template template) {
        try {
            media.delete(MediaStorage.Kind.TEMPLATE, template.getId());
        } catch (ApplicationFailure e) {
            throw new ApplicationFailure(e.getKind(), "Failed to delete template: " + template.getId(), e.getCause());
        }
        repository.remove(template);
        log.info("Template {} deleted", template.getId());
    }

    public Optional<Template> getTemplate(UUID id) {
        return repository.findById(id);
    }

    public void completePreviewReview(Template template) {
        if (!repository.clearPreview(template.getId())) {
            throw new IllegalStateException("Template no longer exists: " + template.getId());
        }
        template.setPreviewChatId(null);
        template.setPreviewMessageId(null);
    }

    public void approveTemplate(Template template) {
        reviewTemplate(template, Status.APPROVED);
        log.info("Template {} approved", template.getId());
    }

    public void rejectTemplate(Template template) {
        reviewTemplate(template, Status.REJECTED);
        log.info("Template {} rejected", template.getId());
    }

    private void reviewTemplate(Template template, Status decision) {
        if (!repository.decideReview(template.getId(), decision)) {
            throw new ApplicationFailure(ApplicationFailure.Kind.CONFLICT, "Template no longer in review");
        }
        template.setStatus(decision);
        template.setPreviewChatId(null);
        template.setPreviewMessageId(null);
    }

    public void resetAllWeights() {
        repository.resetWeights(initialWeight);
        log.info("All templates weights have been reset to {}", initialWeight);
    }

    public void initializeSourceIds() {
        repository.initializeSourceIds();
        log.info("All templates source ids have been reset");
    }
}
