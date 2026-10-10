package com.boatarde.regatasimulator.controller;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.dto.SearchCriteria;
import com.boatarde.regatasimulator.dto.GalleryDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.ApplicationFailure;
import com.boatarde.regatasimulator.models.GalleryResponse;
import com.boatarde.regatasimulator.models.ReviewSourceBody;
import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.service.RouterService;
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
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/sources")
public class SourceController {

    private final SourceService sourceService;
    private final SourceImporterService sourceImporterService;
    private final RegataSimulatorBot bot;
    private final RouterService routerService;

    public SourceController(SourceService sourceService, SourceImporterService sourceImporterService,
                            RegataSimulatorBot bot, RouterService routerService) {
        this.sourceService = sourceService;
        this.sourceImporterService = sourceImporterService;
        this.bot = bot;
        this.routerService = routerService;
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
        Source source = getSource(reviewSourceBody.getSourceId());

        if (source.getStatus() != Status.REVIEW) {
            throw new ApplicationFailure(ApplicationFailure.Kind.CONFLICT, "Source no longer in review");
        }

        // Build an Update object from the source's message for workflow steps
        Update update = new Update();
        update.setMessage(source.getMessage());
        boolean notificationFailed = false;

        if (reviewSourceBody.isApproved()) {
            sourceService.approveSource(source);
            if (source.getMessage() != null) {
                notificationFailed = !notifyDecision(update, WorkflowAction.SEND_SOURCE_APPROVED_MESSAGE_STEP);
            }
        } else {
            sourceService.rejectSource(source);

            if (source.getMessage() != null) {
                Message reasonMessage = new Message();
                reasonMessage.setText(reviewSourceBody.getReason());
                update.setChannelPost(reasonMessage);
                notificationFailed = !notifyDecision(update, WorkflowAction.SEND_SOURCE_REJECTED_MESSAGE);
            }

        }
        return notificationFailed ? ResponseEntity.noContent().header("X-Notification-Status", "failed").build()
            : ResponseEntity.noContent().build();
    }

    private boolean notifyDecision(Update update, WorkflowAction action) {
        try {
            routerService.startFlow(update, bot, action);
            return true;
        } catch (RuntimeException e) {
            log.warn("Source decision applied, but notification failed");
            return false;
        }
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
