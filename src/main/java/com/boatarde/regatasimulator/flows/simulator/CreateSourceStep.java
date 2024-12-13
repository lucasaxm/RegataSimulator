package com.boatarde.regatasimulator.flows.simulator;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowDataKey;
import com.boatarde.regatasimulator.flows.WorkflowStep;
import com.boatarde.regatasimulator.flows.WorkflowStepRegistration;
import com.boatarde.regatasimulator.models.Author;
import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.util.TelegramUtils;
import io.jsondb.JsonDBTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Slf4j
@WorkflowStepRegistration(WorkflowAction.CREATE_SOURCE)
public class CreateSourceStep implements WorkflowStep {

    private final JsonDBTemplate jsonDBTemplate;
    private final String sourcesPathString;

    public CreateSourceStep(
        JsonDBTemplate jsonDBTemplate,
        @Value("${regata-simulator.sources.path}") String sourcesPathString
    ) {
        this.jsonDBTemplate = jsonDBTemplate;
        this.sourcesPathString = sourcesPathString;
    }

    @Override
    public WorkflowAction run(WorkflowDataBag bag) {
        Update update = bag.get(WorkflowDataKey.TELEGRAM_UPDATE, Update.class);
        RegataSimulatorBot bot = bag.get(WorkflowDataKey.REGATA_SIMULATOR_BOT, RegataSimulatorBot.class);

        if (update == null || update.getMessage() == null || !update.getMessage().hasDocument()) {
            log.error("Update or message invalid for creating a source.");
            return WorkflowAction.NONE;
        }

        // Prepare directories
        UUID sourceId = UUID.randomUUID();
        Path sourceDir = Paths.get(sourcesPathString, sourceId.toString());
        try {
            Files.createDirectories(sourceDir);
        } catch (IOException e) {
            log.error("Failed to create source directory", e);
            return WorkflowAction.NONE;
        }

        // Download the file
        String fileId = update.getMessage().getDocument().getFileId();
        String fileName = "source" + getFileExtension(update.getMessage().getDocument().getFileName());
        try {
            TelegramUtils.downloadTelegramFile(bot, fileId, sourceDir, fileName);
        } catch (Exception e) {
            log.error("Failed to download source file", e);
            return WorkflowAction.NONE;
        }

        Source source = new Source();
        source.setId(sourceId);
        source.setWeight(10);
        source.setStatus(Status.REVIEW);
        source.setMessage(update.getMessage());
        source.setDescription(extractDescription(update.getMessage().getCaption()));

        // Save source in DB
        jsonDBTemplate.insert(source);

        // Save author if needed
        if (update.getMessage().getFrom() != null) {
            Author author = new Author(update.getMessage().getFrom());
            jsonDBTemplate.upsert(author);
        }

        // Send a confirmation message to the user
        try {
            bot.execute(SendMessage.builder()
                .chatId(update.getMessage().getChatId().toString())
                .replyToMessageId(update.getMessage().getMessageId())
                .text("✅ Template enviado para aprovação.\nID: " + sourceId)
                .allowSendingWithoutReply(true)
                .build());
        } catch (Exception e) {
            log.error("Failed to send confirmation message", e);
        }

        return WorkflowAction.NONE;
    }

    private String getFileExtension(String fileName) {
        int idx = fileName.lastIndexOf('.');
        if (idx == -1) {
            return ".jpg"; // default extension if none found
        }
        return fileName.substring(idx);
    }

    private String extractDescription(String caption) {
        if (caption == null || caption.trim().isEmpty()) {
            return "";
        }
        int idx = caption.indexOf(':');
        if (idx == -1 || idx == caption.length() - 1) {
            return "";
        }
        return caption.substring(idx + 1).trim();
    }
}
