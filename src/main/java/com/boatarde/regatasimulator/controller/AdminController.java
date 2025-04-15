package com.boatarde.regatasimulator.controller;

import com.boatarde.regatasimulator.service.ScheduledTaskService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final ScheduledTaskService scheduledTaskService;

    public AdminController(ScheduledTaskService scheduledTaskService) {
        this.scheduledTaskService = scheduledTaskService;
    }

    @GetMapping("/post_meme")
    public ResponseEntity<Void> postMeme() {
        scheduledTaskService.generateMeme();
        return ResponseEntity.ok().build();
    }

    @GetMapping("/create_backup")
    public ResponseEntity<Void> backup() {
        scheduledTaskService.createBackup();
        return ResponseEntity.ok().build();
    }

}
