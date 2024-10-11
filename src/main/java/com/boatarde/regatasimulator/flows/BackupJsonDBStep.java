package com.boatarde.regatasimulator.flows;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.util.TelegramUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@WorkflowStepRegistration(WorkflowAction.BACKUP_JSON_DB_STEP)
public class BackupJsonDBStep implements WorkflowStep {

    private final Long backupChatId;
    private final String jsonDbPath;

    public BackupJsonDBStep(@Value("${telegram.bots.regata-simulator.backup-chat}") Long backupChatId,
                            @Value("${regata-simulator.database.path}") String jsonDbPath) {
        this.backupChatId = backupChatId;
        this.jsonDbPath = jsonDbPath;
    }

    @Override
    public WorkflowAction run(WorkflowDataBag bag) {
        RegataSimulatorBot regataSimulatorBot = bag.get(WorkflowDataKey.REGATA_SIMULATOR_BOT, RegataSimulatorBot.class);

        try {
            log.info("Running backup of JsonDB");
            // Zip the directory
            Path zipFilePath = zipDirectory(jsonDbPath);
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));

            // Create SendDocument request
            SendDocument sendDocument = new SendDocument();
            sendDocument.setChatId(backupChatId.toString());
            sendDocument.setCaption(timestamp);
            sendDocument.setDocument(new InputFile(zipFilePath.toFile(), "backup-" + timestamp + ".zip"));

            // Send the document
            Message response = regataSimulatorBot.execute(sendDocument);
            log.info("JsonDB backup sent to Telegram: {}", TelegramUtils.toJson(response, false));
            // Clean up the zip file
            Files.delete(zipFilePath);
            bag.put(WorkflowDataKey.MESSAGE_TO_REPLY, response);
        } catch (IOException | TelegramApiException e) {
            log.error(e.getLocalizedMessage(), e);
            return WorkflowAction.NONE;
        }

        return WorkflowAction.SEND_REPORT_STEP;
    }

    private Path zipDirectory(String sourceDirPath) throws IOException {
        Path zipFilePath = Files.createTempFile("backup", ".zip");
        try (ZipOutputStream zs = new ZipOutputStream(new FileOutputStream(zipFilePath.toFile()))) {
            Path pp = Paths.get(sourceDirPath);
            try (Stream<Path> paths = Files.walk(pp)) {
                paths.filter(path -> !Files.isDirectory(path))
                    .forEach(path -> {
                        ZipEntry zipEntry = new ZipEntry(pp.relativize(path).toString());
                        try {
                            zs.putNextEntry(zipEntry);
                            Files.copy(path, zs);
                            zs.closeEntry();
                        } catch (IOException e) {
                            log.error("Error zipping file: {}", path, e);
                        }
                    });
            }
        }
        return zipFilePath;
    }
}