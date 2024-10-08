package com.boatarde.regatasimulator.controller;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.models.Template;
import com.boatarde.regatasimulator.service.RouterService;
import com.boatarde.regatasimulator.service.SourceService;
import com.boatarde.regatasimulator.service.TemplateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final RegataSimulatorBot bot;
    private final RouterService routerService;
    private final TemplateService templateService;
    private final SourceService sourceService;

    public AdminController(RegataSimulatorBot bot, RouterService routerService, TemplateService templateService,
                           SourceService sourceService) {
        this.bot = bot;
        this.routerService = routerService;
        this.templateService = templateService;
        this.sourceService = sourceService;
    }

    @GetMapping("/post_meme")
    public ResponseEntity<Void> postMeme() {
        routerService.startFlow(null, bot, WorkflowAction.GET_RANDOM_TEMPLATE);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/save_templates")
    public List<Template> saveTemplates() {
        return templateService.saveTemplates();
    }

    @GetMapping("/save_sources")
    public List<Source> saveSources() {
        return sourceService.saveSources();
    }
}
