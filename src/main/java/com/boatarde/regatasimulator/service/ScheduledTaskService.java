package com.boatarde.regatasimulator.service;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class ScheduledTaskService {

    private final RegataSimulatorBot bot;
    private final RouterService routerService;
    private final Long backupChatId;
    private BackupService backups;

    public ScheduledTaskService(RegataSimulatorBot bot, RouterService routerService,
                                @Value("${telegram.bots.regata-simulator.backup-chat}") Long backupChatId) {
        this.bot = bot;
        this.routerService = routerService;
        this.backupChatId = backupChatId;
    }

    @Autowired
    public ScheduledTaskService(RegataSimulatorBot bot, RouterService routerService, BackupService backups,
                                @Value("${telegram.bots.regata-simulator.backup-chat}") Long backupChatId) {
        this(bot, routerService, backupChatId);
        this.backups = backups;
    }

    @Scheduled(cron = "0 0,30 * * * *")
    public void generateMeme() {
        routerService.startFlow(null, bot, WorkflowAction.GET_RANDOM_TEMPLATE);
    }

    @Scheduled(cron = "0 15 12 * * SUN")
    public void createBackup() {
        backups.create();
    }
}
