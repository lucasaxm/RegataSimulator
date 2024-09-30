package com.boatarde.regatasimulator.controller;

import com.boatarde.regatasimulator.models.GalleryResponse;
import com.boatarde.regatasimulator.service.SourceService;
import com.boatarde.regatasimulator.service.TemplateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class GalleryController {

    @Autowired
    private SourceService sourceService;

    @Autowired
    private TemplateService templateService;

    @GetMapping("/gallery")
    public ResponseEntity<GalleryResponse> getGalleryItems(
        @RequestParam String type,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "12") int perPage) {
        GalleryResponse response = type.equals("sources")
            ? sourceService.getGalleryItems(page, perPage)
            : templateService.getGalleryItems(page, perPage);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/template-details")
    public ResponseEntity<String> getTemplateDetails(@RequestParam String id) {
        String csvContent = templateService.getTemplateDetails(id);
        return ResponseEntity.ok(csvContent);
    }

    @GetMapping("/image/{type}/{filename:.+}")
    public ResponseEntity<Resource> getImage(@PathVariable String type, @PathVariable String filename) {
        Resource file = type.equals("sources")
            ? sourceService.loadImageAsResource(filename)
            : templateService.loadImageAsResource(filename);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(file);
    }

    @DeleteMapping("/image/{type}/{filename:.+}")
    public ResponseEntity<Void> deleteImage(@PathVariable String type, @PathVariable String filename) {
        if (type.equals("sources")) {
            sourceService.deleteImage(filename);
        } else if (type.equals("templates")) {
            String templateId = filename.split("_")[0];
            templateService.deleteTemplate(templateId);
        } else {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.noContent().build();
    }
}
