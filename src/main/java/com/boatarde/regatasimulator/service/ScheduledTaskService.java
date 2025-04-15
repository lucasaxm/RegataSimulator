package com.boatarde.regatasimulator.service;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;

@Service
public class ScheduledTaskService {

    private final RegataSimulatorBot bot;
    private final RouterService routerService;
    private final Long backupChatId;

    public ScheduledTaskService(RegataSimulatorBot bot, RouterService routerService,
                                @Value("${telegram.bots.regata-simulator.backup-chat}") Long backupChatId) {
        this.bot = bot;
        this.routerService = routerService;
        this.backupChatId = backupChatId;
    }

    @Scheduled(cron = "0 0,30 * * * *")
    public void generateMeme() {
        routerService.startFlow(null, bot, WorkflowAction.GET_RANDOM_TEMPLATE);
    }

    @Scheduled(cron = "0 15 12 * * SUN")
    public void createBackup() {
        Update update = new Update();
        update.setMessage(new Message());
        update.getMessage().setChat(new Chat());
        update.getMessage().getChat().setId(backupChatId);
        routerService.startFlow(update, bot, WorkflowAction.BACKUP_JSON_DB_STEP);
    }
}
