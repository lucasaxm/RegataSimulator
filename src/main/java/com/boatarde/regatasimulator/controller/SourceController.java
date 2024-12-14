package com.boatarde.regatasimulator.controller;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.dto.SearchCriteria;
import com.boatarde.regatasimulator.flows.WorkflowAction;
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
    public ResponseEntity<GalleryResponse<Source>> getAllSources(@RequestParam(defaultValue = "1") int page,
                                                                 @RequestParam(defaultValue = "12") int perPage,
                                                                 @RequestParam(required = false) Status status,
                                                                 @RequestParam(required = false) Long userId) {
        GalleryResponse<Source> response = sourceService.getSources(page, perPage, status, userId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}.png")
    public ResponseEntity<Resource> getSourceImage(@PathVariable UUID id) {
        Source source = getSource(id);
        Resource file = sourceService.loadSourceAsResource(source);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(file);
    }

    @GetMapping("/{id}.json")
    public ResponseEntity<Source> getSourceJson(@PathVariable UUID id) {
        Source source = getSource(id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(source);
    }


    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteImage(@PathVariable UUID id) {
        sourceService.deleteSource(getSource(id));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/review")
    public ResponseEntity<Void> reviewSource(@RequestBody ReviewSourceBody reviewSourceBody) {
        Source source = sourceService.getSource(reviewSourceBody.getSourceId())
            .orElseThrow(() -> new RuntimeException("Source not found: " + reviewSourceBody.getSourceId()));

        if (source.getStatus() == Status.APPROVED) {
            throw new RuntimeException("Source already approved: " + reviewSourceBody.getSourceId());
        }

        // Build an Update object from the source's message for workflow steps
        Update update = new Update();
        update.setMessage(source.getMessage());

        if (reviewSourceBody.isApproved()) {
            sourceService.approveSource(source);
            if (source.getMessage() != null) {
                routerService.startFlow(update, bot, WorkflowAction.SEND_SOURCE_APPROVED_MESSAGE_STEP);
            }
        } else {
            sourceService.rejectSource(source);

            if (source.getMessage() != null) {
                Message reasonMessage = new Message();
                reasonMessage.setText(reviewSourceBody.getReason());
                update.setChannelPost(reasonMessage);
            }

            routerService.startFlow(update, bot, WorkflowAction.SEND_SOURCE_REJECTED_MESSAGE);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/import")
    public ResponseEntity<List<Source>> importSourcesFromCsv(@RequestBody String csv) {
        try {
            List<Source> createdSources = sourceImporterService.importFromCsv(csv);
            return ResponseEntity.ok(createdSources);
        } catch (Exception e) {
            log.error("Import failed: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/reset_weights")
    public ResponseEntity<Void> resetWeights() {
        sourceService.resetAllWeights();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/search")
    public ResponseEntity<GalleryResponse<Source>> searchSources(@RequestBody SearchCriteria criteria) {
        GalleryResponse<Source> response = sourceService.search(criteria);
        return ResponseEntity.ok(response);
    }

    private Source getSource(UUID id) {
        return sourceService.getSource(id)
            .orElseThrow(() -> new RuntimeException("Source not found: " + id));
    }
}
