package com.boatarde.regatasimulator.flows.backup;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowDataKey;
import com.boatarde.regatasimulator.flows.WorkflowStep;
import com.boatarde.regatasimulator.flows.WorkflowStepRegistration;
import com.boatarde.regatasimulator.util.FileUtils;
import com.boatarde.regatasimulator.util.TelegramUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaBotMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaDocument;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@WorkflowStepRegistration(WorkflowAction.BACKUP_TEMPLATES_STEP)
public class BackupTemplatesStep implements WorkflowStep {

    // Maximum allowed chunk size (e.g., 50 MB) – adjust as needed.
    // For testing here, you might use a lower number.
    private static final long MAX_CHUNK_SIZE = 40L * 1024 * 1024;

    private final Long backupChatId;
    private final String templatesPathString;

    public BackupTemplatesStep(@Value("${telegram.bots.regata-simulator.backup-chat}") Long backupChatId,
                               @Value("${regata-simulator.templates.path}") String templatesPathString) {
        this.backupChatId = backupChatId;
        this.templatesPathString = templatesPathString;
    }

    @Override
    public WorkflowAction run(WorkflowDataBag bag) {
        RegataSimulatorBot regataSimulatorBot = bag.get(WorkflowDataKey.REGATA_SIMULATOR_BOT, RegataSimulatorBot.class);
        try {
            log.info("Running backup of Templates directory.");
            // Create zip files for the templates folder and chunk by complete UUID directories
            List<Path> zipFiles = FileUtils.zipInChunks(templatesPathString, MAX_CHUNK_SIZE);
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));

            SendMediaGroup sendMediaGroup = SendMediaGroup.builder()
                .chatId(backupChatId)
                .medias(new ArrayList<>())
                .build();

            for (int i = 0; i < zipFiles.size(); i++) {
                Path zipFilePath = zipFiles.get(i);
                String fileName = "templates-" + timestamp + "-" + i + ".zip";
                InputMediaDocument document = new InputMediaDocument();
                document.setMedia(zipFilePath.toFile(), fileName);
                document.setCaption(i == zipFiles.size() - 1 ? "Templates backup" : null);
                sendMediaGroup.getMedias().add(document);
            }
            if (sendMediaGroup.getMedias().size() == 1) {
                SendMediaBotMethod<Message> media = TelegramUtils.inputMediaToSendMedia(sendMediaGroup, 0);
                TelegramUtils.executeSendMediaBotMethod(regataSimulatorBot, media);
            } else {
                TelegramUtils.normalizeMediaGroupCaption(sendMediaGroup);
                regataSimulatorBot.execute(sendMediaGroup);
            }
            zipFiles.forEach(zipFile -> {
                try {
                    Files.delete(zipFile);
                } catch (IOException e) {
                    log.error("Failed to delete file: {}", zipFile, e);
                }
            });
            log.info("Templates backup sent as media group: {}", TelegramUtils.toJson(sendMediaGroup, false));
        } catch (IOException | TelegramApiException e) {
            log.error("Error during backup of templates: {}", e.getMessage(), e);
            return WorkflowAction.NONE;
        }
        return WorkflowAction.BACKUP_SOURCES_STEP;
    }
}
