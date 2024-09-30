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

    @Scheduled(cron = "0 0,15,30,45 * * * *")
    public void runScheduledTask() {
        routerService.startFlow(null, bot, WorkflowAction.GET_RANDOM_TEMPLATE);
    }
}
