package com.boatarde.regatasimulator.controller;

import com.boatarde.regatasimulator.models.GalleryResponse;
import com.boatarde.regatasimulator.models.ReviewTemplateBody;
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
                                                                  @RequestParam(defaultValue = "12")
                                                                          int perPage) {
        GalleryResponse<Template> response = templateService.getTemplates(page, perPage, false);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Resource> getTemplate(@PathVariable UUID id) {
        Resource file = templateService.loadTemplateAsResource(id, false);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(file);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteImage(@PathVariable UUID id) {
        templateService.deleteTemplate(id, false);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/review")
    public ResponseEntity<GalleryResponse<Template>> getTemplatesToReview(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "12")
        int perPage) {
        GalleryResponse<Template> response = templateService.getTemplates(page, perPage, true);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/review/{id}")
    public ResponseEntity<Resource> getImage(@PathVariable UUID id) {
        Resource file = templateService.loadTemplateAsResource(id, true);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(file);
    }

    @PostMapping("/review/{id}")
    public ResponseEntity<Void> reviewTemplate(@PathVariable UUID id,
                                               @RequestBody ReviewTemplateBody reviewTemplateBody) {
        templateService.reviewTemplate(id, reviewTemplateBody);
        return ResponseEntity.noContent().build();
    }
}
