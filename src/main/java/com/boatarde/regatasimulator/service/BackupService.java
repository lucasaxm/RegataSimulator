package com.boatarde.regatasimulator.service;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.util.FileUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
public class BackupService {
    private static final long MAX_CHUNK_SIZE = 40L * 1024 * 1024;

    private final Long backupChatId;

    public BackupService(@Value("${telegram.bots.regata-simulator.backup-chat}") Long backupChatId) {
        this.backupChatId = backupChatId;
    }

    public void zipToTelegram(RegataSimulatorBot bot, String backupDirPath, String filePrefix)
        throws IOException, TelegramApiException {
        log.info("Running backup of {}.", filePrefix);
        List<Path> zipFiles = FileUtils.zipInChunks(backupDirPath, MAX_CHUNK_SIZE);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));

        for (int i = 0; i < zipFiles.size(); i++) {
            Path zipFilePath = zipFiles.get(i);
            String fileName = filePrefix + "-" + timestamp + "-" + i + ".zip";
            String caption = (zipFiles.size() > 1)
                ? filePrefix + " backup (" + (i + 1) + "/" + zipFiles.size() + ")"
                : filePrefix + " backup";
            SendDocument sendDocument = SendDocument.builder()
                .chatId(backupChatId.toString())
                .caption(caption)
                .document(new InputFile(zipFilePath.toFile(), fileName))
                .build();
            bot.execute(sendDocument);
            Files.delete(zipFilePath);
            log.info("Sent backup file: {}", fileName);
        }
    }
}
