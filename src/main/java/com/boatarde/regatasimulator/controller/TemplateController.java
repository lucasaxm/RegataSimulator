package com.boatarde.regatasimulator.controller;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.models.GalleryResponse;
import com.boatarde.regatasimulator.models.ReviewTemplateBody;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.models.Template;
import com.boatarde.regatasimulator.service.RouterService;
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
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.UUID;

@RestController
@RequestMapping("/api/templates")
public class TemplateController {

    private final TemplateService templateService;
    private final RegataSimulatorBot bot;
    private final RouterService routerService;

    public TemplateController(TemplateService templateService, RegataSimulatorBot bot, RouterService routerService) {
        this.templateService = templateService;
        this.bot = bot;
        this.routerService = routerService;
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
        Template template = templateService.getTemplate(reviewTemplateBody.getTemplateId())
            .orElseThrow(() -> new RuntimeException("Template not found: " + reviewTemplateBody.getTemplateId()));
        if (template.getStatus() == Status.APPROVED) {
            throw new RuntimeException("Template already approved: " + reviewTemplateBody.getTemplateId());
        }

        Update update = new Update();
        update.setMessage(template.getMessage());

        if (reviewTemplateBody.isApproved()) {
            templateService.approveTemplate(template);
            if (template.getMessage() != null) {
                routerService.startFlow(update, bot, WorkflowAction.SEND_TEMPLATE_APPROVED_MESSAGE_STEP);
            }
        } else {
            templateService.rejectTemplate(template);

            if (template.getMessage() != null) {
                Message reasonMessage = new Message();
                reasonMessage.setText(reviewTemplateBody.getReason());
                update.setChannelPost(reasonMessage);
            }

            routerService.startFlow(update, bot, WorkflowAction.SEND_TEMPLATE_REJECTED_MESSAGE);
        }
        return ResponseEntity.noContent().build();
    }

    private Template getTemplate(UUID id) {
        return templateService.getTemplate(id)
            .orElseThrow(() -> new RuntimeException("Template not found: " + id));
    }
}
