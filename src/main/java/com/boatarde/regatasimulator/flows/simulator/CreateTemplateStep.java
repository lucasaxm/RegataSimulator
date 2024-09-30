package com.boatarde.regatasimulator.flows.simulator;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowDataKey;
import com.boatarde.regatasimulator.flows.WorkflowStep;
import com.boatarde.regatasimulator.flows.WorkflowStepRegistration;
import com.boatarde.regatasimulator.util.TelegramUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Slf4j
@WorkflowStepRegistration(WorkflowAction.CREATE_TEMPLATE)
public class CreateTemplateStep implements WorkflowStep {

    private final String templatesPathString;

    public CreateTemplateStep(@Value("${regata-simulator.templates.path}") String templatesPathString) {
        this.templatesPathString = templatesPathString;
    }

    private static Path downloadTelegramFile(RegataSimulatorBot bot, String fileId, Path directory,
                                             String fileName) throws TelegramApiException, IOException {
        GetFile getFile = GetFile.builder().fileId(fileId).build();
        String filePath = bot.execute(getFile).getFilePath();
        File file = bot.downloadFile(filePath);
        if (!TelegramUtils.isImage(file)) {
            throw new RuntimeException("File is not an image");
        }

        UUID uuid = UUID.randomUUID();
        Path newDir = directory.resolve(uuid.toString());
        Files.createDirectories(newDir);

        Path newFile = newDir.resolve(fileName);
        return Files.copy(file.toPath(), newFile);
    }

    @Override
    public WorkflowAction run(WorkflowDataBag bag) {
        Update update = bag.get(WorkflowDataKey.TELEGRAM_UPDATE, Update.class);
        RegataSimulatorBot bot = bag.get(WorkflowDataKey.REGATA_SIMULATOR_BOT, RegataSimulatorBot.class);
        Path templatesDir = Paths.get(templatesPathString);

        String fileExtension = getFileExtension(update.getMessage().getDocument().getFileName());
        String fileName = "template" + fileExtension.toLowerCase();

        try {
            Path templateFile =
                downloadTelegramFile(bot, update.getMessage().getDocument().getFileId(), templatesDir, fileName);
            Path templateDir = templateFile.getParent();
            Path csvFile = templateDir.resolve("template.csv");

            String csvContent = update.getMessage().getCaption();
            Files.writeString(csvFile, csvContent);

            bag.put(WorkflowDataKey.TEMPLATE_FILE, templateFile);
            bag.put(WorkflowDataKey.TEMPLATE_AREAS, TelegramUtils.parseTemplateCsv(csvContent));
        } catch (Exception e) {
            log.error(String.format("Exception when downloading file: %s", e.getMessage()), e);
            return WorkflowAction.NONE;
        }
        return WorkflowAction.GET_RANDOM_SOURCE;
    }

    private String getFileExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return (dotIndex == -1) ? "" : fileName.substring(dotIndex);
    }
}
