package com.boatarde.regatasimulator.controller;

import com.boatarde.regatasimulator.models.GalleryResponse;
import com.boatarde.regatasimulator.models.TemplateResponse;
import com.boatarde.regatasimulator.service.TemplateService;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    public ResponseEntity<GalleryResponse<TemplateResponse>> getGalleryItems(@RequestParam(defaultValue = "1") int page,
                                                                             @RequestParam(defaultValue = "12")
                                                                             int perPage) {
        GalleryResponse<TemplateResponse> response = templateService.getTemplates(page, perPage);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Resource> getImage(@PathVariable UUID id) {
        Resource file = templateService.loadImageAsResource(id);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(file);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteImage(@PathVariable UUID id) {
        templateService.deleteTemplate(id);
        return ResponseEntity.noContent().build();
    }
}
