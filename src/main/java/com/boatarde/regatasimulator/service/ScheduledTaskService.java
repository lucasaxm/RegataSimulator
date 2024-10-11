package com.boatarde.regatasimulator.service;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ScheduledTaskService {

    private final RegataSimulatorBot bot;
    private final RouterService routerService;

    public ScheduledTaskService(RegataSimulatorBot bot, RouterService routerService) {
        this.bot = bot;
        this.routerService = routerService;
    }

    @Scheduled(cron = "0 0,30 * * * *")
    public void generateMeme() {
        routerService.startFlow(null, bot, WorkflowAction.GET_RANDOM_TEMPLATE);
    }

    @Scheduled(cron = "0 15 12 * * SUN")
    public void backupJsonDB() {
        routerService.startFlow(null, bot, WorkflowAction.BACKUP_JSON_DB_STEP);
    }
}
