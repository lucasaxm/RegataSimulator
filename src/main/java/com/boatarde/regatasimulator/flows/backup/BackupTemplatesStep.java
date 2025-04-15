package com.boatarde.regatasimulator.flows.backup;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowDataKey;
import com.boatarde.regatasimulator.flows.WorkflowStep;
import com.boatarde.regatasimulator.flows.WorkflowStepRegistration;
import com.boatarde.regatasimulator.service.BackupService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.IOException;

@Slf4j
@WorkflowStepRegistration(WorkflowAction.BACKUP_TEMPLATES_STEP)
public class BackupTemplatesStep implements WorkflowStep {

    private final String templatesPathString;
    private final BackupService backupService;

    public BackupTemplatesStep(@Value("${regata-simulator.templates.path}") String templatesPathString,
                               BackupService backupService) {
        this.templatesPathString = templatesPathString;
        this.backupService = backupService;
    }

    @Override
    public WorkflowAction run(WorkflowDataBag bag) {
        RegataSimulatorBot regataSimulatorBot = bag.get(WorkflowDataKey.REGATA_SIMULATOR_BOT, RegataSimulatorBot.class);
        try {
            backupService.zipToTelegram(regataSimulatorBot, templatesPathString, "templates");
        } catch (IOException | TelegramApiException e) {
            log.error("Error during backup of templates: {}", e.getMessage(), e);
            return WorkflowAction.NONE;
        }
        return WorkflowAction.BACKUP_SOURCES_STEP;
    }
}
