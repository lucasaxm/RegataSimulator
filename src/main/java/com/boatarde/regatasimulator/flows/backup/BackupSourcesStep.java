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
@WorkflowStepRegistration(WorkflowAction.BACKUP_SOURCES_STEP)
public class BackupSourcesStep implements WorkflowStep {

    private final String sourcesPathString;
    private final BackupService backupService;

    public BackupSourcesStep(@Value("${regata-simulator.sources.path}") String sourcesPathString,
                             BackupService backupService) {
        this.sourcesPathString = sourcesPathString;
        this.backupService = backupService;
    }

    @Override
    public WorkflowAction run(WorkflowDataBag bag) {
        RegataSimulatorBot regataSimulatorBot = bag.get(WorkflowDataKey.REGATA_SIMULATOR_BOT, RegataSimulatorBot.class);
        try {
            backupService.zipToTelegram(regataSimulatorBot, sourcesPathString, "sources");
        } catch (IOException | TelegramApiException e) {
            log.error("Error during backup of sources: {}", e.getMessage(), e);
            return WorkflowAction.NONE;
        }
        return WorkflowAction.SEND_REPORT_STEP;
    }
}
