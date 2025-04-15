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
@WorkflowStepRegistration(WorkflowAction.BACKUP_JSON_DB_STEP)
public class BackupJsonDBStep implements WorkflowStep {

    private final String jsonDbPath;
    private final BackupService backupService;

    public BackupJsonDBStep(@Value("${regata-simulator.database.path}") String jsonDbPath,
                            BackupService backupService) {
        this.jsonDbPath = jsonDbPath;
        this.backupService = backupService;
    }

    @Override
    public WorkflowAction run(WorkflowDataBag bag) {
        RegataSimulatorBot regataSimulatorBot = bag.get(WorkflowDataKey.REGATA_SIMULATOR_BOT, RegataSimulatorBot.class);
        try {
            backupService.zipToTelegram(regataSimulatorBot, jsonDbPath, "jsondb");
        } catch (IOException | TelegramApiException e) {
            log.error("Error during backup of jsondb: {}", e.getMessage(), e);
            return WorkflowAction.NONE;
        }

        return WorkflowAction.BACKUP_TEMPLATES_STEP;
    }
}
