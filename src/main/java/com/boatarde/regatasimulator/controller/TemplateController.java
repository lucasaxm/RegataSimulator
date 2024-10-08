package com.boatarde.regatasimulator.controller;

import com.boatarde.regatasimulator.models.GalleryResponse;
import com.boatarde.regatasimulator.models.ReviewTemplateBody;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.models.Template;
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
@RequestMapping("/api/templates")
public class TemplateController {

    private final TemplateService templateService;

    public TemplateController(TemplateService templateService) {
        this.templateService = templateService;
    }

    @GetMapping
    public ResponseEntity<GalleryResponse<Template>> getTemplates(@RequestParam(defaultValue = "1") int page,
                                                                  @RequestParam(defaultValue = "12") int perPage,
                                                                  @RequestParam(required = false) Status status,
                                                                  @RequestParam(required = false) Long userId) {
        GalleryResponse<Template> response = templateService.getTemplates(page, perPage, status, userId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}.png")
    public ResponseEntity<Resource> getTemplateImage(@PathVariable UUID id) {
        Template template = getTemplate(id);
        Resource file = templateService.loadTemplateAsResource(template);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(file);
    }

    @GetMapping("/{id}.json")
    public ResponseEntity<Template> getTemplateJson(@PathVariable UUID id) {
        Template template = getTemplate(id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(template);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteImage(@PathVariable UUID id) {
        Template template = getTemplate(id);
        templateService.deleteTemplate(template);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/review")
    public ResponseEntity<Void> reviewTemplate(@RequestBody ReviewTemplateBody reviewTemplateBody) {
        templateService.reviewTemplate(reviewTemplateBody);
        return ResponseEntity.noContent().build();
    }

    private Template getTemplate(UUID id) {
        return templateService.getTemplate(id)
            .orElseThrow(() -> new RuntimeException("Template not found: " + id));
    }
}
