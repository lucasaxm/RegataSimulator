package com.boatarde.regatasimulator.controller;

import com.boatarde.regatasimulator.dto.GalleryDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import com.boatarde.regatasimulator.flows.ApplicationFailure;
import lombok.extern.slf4j.Slf4j;
import com.boatarde.regatasimulator.models.GalleryResponse;
import com.boatarde.regatasimulator.models.ReviewTemplateBody;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.models.Template;
import com.boatarde.regatasimulator.service.ModerationService;
import com.boatarde.regatasimulator.service.TemplateService;
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

import java.util.UUID;

@RestController
@Slf4j
@RequestMapping("/api/templates")
public class TemplateController {

    private final TemplateService templateService;
    private final ModerationService moderation;

    public TemplateController(TemplateService templateService, ModerationService moderation) {
        this.templateService = templateService;
        this.moderation = moderation;
    }

    @GetMapping
    public ResponseEntity<GalleryResponse<GalleryDtos.TemplateItem>> getTemplates(@RequestParam(defaultValue = "1") @Min(1) int page,
                                                                  @RequestParam(defaultValue = "12") @Min(1) @Max(100) int perPage,
                                                                  @RequestParam(required = false) Status status,
                                                                  @RequestParam(required = false) Long userId) {
        GalleryResponse<Template> response = templateService.getTemplates(page, perPage, status, userId);
        return ResponseEntity.ok(GalleryDtos.templates(response));
    }

    @GetMapping("/{id}.png")
    public ResponseEntity<Resource> getTemplateImage(@PathVariable UUID id) {
        Template template = getTemplate(id);
        Resource file = templateService.loadTemplateAsResource(template);
        return ImageResponse.of(file);
    }

    @GetMapping("/{id}.json")
    public ResponseEntity<GalleryDtos.TemplateItem> getTemplateJson(@PathVariable UUID id) {
        Template template = getTemplate(id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(GalleryDtos.template(template));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteImage(@PathVariable UUID id) {
        Template template = getTemplate(id);
        templateService.deleteTemplate(template);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/review")
    public ResponseEntity<Void> reviewTemplate(@Valid @RequestBody ReviewTemplateBody reviewTemplateBody) {
        var result = moderation.decide(new ModerationService.Decision(ModerationService.ItemType.TEMPLATE,
            reviewTemplateBody.getTemplateId(), reviewTemplateBody.isApproved() ? Status.APPROVED : Status.REJECTED,
            reviewTemplateBody.getReason(), new ModerationService.Actor(
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName())));
        return result.notification() == ModerationService.Notification.FAILED
            ? ResponseEntity.noContent().header("X-Notification-Status", "failed").build()
            : ResponseEntity.noContent().build();
    }

    @PostMapping("/reset_weights")
    public ResponseEntity<Void> resetWeights() {
        templateService.resetAllWeights();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/initialize_source_ids")
    public ResponseEntity<Void> initializeSourceIds() {
        templateService.initializeSourceIds();
        return ResponseEntity.noContent().build();
    }

    private Template getTemplate(UUID id) {
        return templateService.getTemplate(id)
            .orElseThrow(() -> new ApplicationFailure(ApplicationFailure.Kind.NOT_FOUND, "Template not found: " + id));
    }
}
