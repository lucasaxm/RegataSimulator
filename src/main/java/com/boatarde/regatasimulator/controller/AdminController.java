package com.boatarde.regatasimulator.controller;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.service.RouterService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final RegataSimulatorBot bot;
    private final RouterService routerService;

    public AdminController(RegataSimulatorBot bot, RouterService routerService) {
        this.bot = bot;
        this.routerService = routerService;
    }

    @GetMapping("/post_meme")
    public ResponseEntity<Void> postMeme() {
        routerService.startFlow(null, bot, WorkflowAction.GET_RANDOM_TEMPLATE);
        return ResponseEntity.ok().build();
    }

}
