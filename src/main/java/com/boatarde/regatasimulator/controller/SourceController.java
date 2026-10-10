package com.boatarde.regatasimulator.controller;

import com.boatarde.regatasimulator.dto.SearchCriteria;
import com.boatarde.regatasimulator.dto.GalleryDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import com.boatarde.regatasimulator.flows.ApplicationFailure;
import com.boatarde.regatasimulator.models.GalleryResponse;
import com.boatarde.regatasimulator.models.ReviewSourceBody;
import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.service.ModerationService;
import com.boatarde.regatasimulator.service.SourceImporterService;
import com.boatarde.regatasimulator.service.SourceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/sources")
public class SourceController {

    private final SourceService sourceService;
    private final SourceImporterService sourceImporterService;
    private final ModerationService moderation;

    public SourceController(SourceService sourceService, SourceImporterService sourceImporterService,
                            ModerationService moderation) {
        this.sourceService = sourceService;
        this.sourceImporterService = sourceImporterService;
        this.moderation = moderation;
    }

    @GetMapping
    public ResponseEntity<GalleryResponse<GalleryDtos.SourceItem>> getAllSources(@RequestParam(defaultValue = "1") @Min(1) int page,
                                                                 @RequestParam(defaultValue = "12") @Min(1) @Max(100) int perPage,
                                                                 @RequestParam(required = false) Status status,
                                                                 @RequestParam(required = false) Long userId) {
        GalleryResponse<Source> response = sourceService.getSources(page, perPage, status, userId);
        return ResponseEntity.ok(GalleryDtos.sources(response));
    }

    @GetMapping("/{id}.png")
    public ResponseEntity<Resource> getSourceImage(@PathVariable UUID id) {
        Source source = getSource(id);
        Resource file = sourceService.loadSourceAsResource(source);
        return ImageResponse.of(file);
    }

    @GetMapping("/{id}.json")
    public ResponseEntity<GalleryDtos.SourceItem> getSourceJson(@PathVariable UUID id) {
        Source source = getSource(id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(GalleryDtos.source(source));
    }


    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteImage(@PathVariable UUID id) {
        sourceService.deleteSource(getSource(id));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/review")
    public ResponseEntity<Void> reviewSource(@Valid @RequestBody ReviewSourceBody reviewSourceBody) {
        var result = moderation.decide(new ModerationService.Decision(ModerationService.ItemType.SOURCE,
            reviewSourceBody.getSourceId(), reviewSourceBody.isApproved() ? Status.APPROVED : Status.REJECTED,
            reviewSourceBody.getReason(), new ModerationService.Actor(
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName())));
        return result.notification() == ModerationService.Notification.FAILED
            ? ResponseEntity.noContent().header("X-Notification-Status", "failed").build()
            : ResponseEntity.noContent().build();
    }

    @PostMapping("/import")
    public ResponseEntity<List<GalleryDtos.SourceItem>> importSourcesFromCsv(@RequestBody String csv) throws Exception {
        List<Source> createdSources = sourceImporterService.importFromCsv(csv);
        return ResponseEntity.ok(createdSources.stream().map(GalleryDtos::source).toList());
    }

    @PostMapping("/import/report")
    public ResponseEntity<GalleryDtos.ImportReport> importReport(@RequestBody String csv) throws Exception {
        return ResponseEntity.ok(GalleryDtos.report(sourceImporterService.importReport(csv)));
    }

    @PostMapping("/reset_weights")
    public ResponseEntity<Void> resetWeights() {
        sourceService.resetAllWeights();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/search")
    public ResponseEntity<GalleryResponse<GalleryDtos.SourceItem>> searchSources(@Valid @RequestBody SearchCriteria criteria) {
        GalleryResponse<Source> response = sourceService.search(criteria);
        return ResponseEntity.ok(GalleryDtos.sources(response));
    }

    private Source getSource(UUID id) {
        return sourceService.getSource(id)
            .orElseThrow(() -> new ApplicationFailure(ApplicationFailure.Kind.NOT_FOUND, "Source not found: " + id));
    }
}
