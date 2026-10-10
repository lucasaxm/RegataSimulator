package com.boatarde.regatasimulator.service;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.application.TelegramGateway;
import com.boatarde.regatasimulator.flows.ApplicationFailure;
import org.springframework.beans.factory.annotation.Autowired;
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
    private TelegramGateway telegram;
    private ReportService reports;
    private String databasePath;
    private String templatesPath;
    private String sourcesPath;

    public BackupService(@Value("${telegram.bots.regata-simulator.backup-chat}") Long backupChatId) {
        this.backupChatId = backupChatId;
    }

    @Autowired
    public BackupService(@Value("${telegram.bots.regata-simulator.backup-chat}") Long backupChatId,
                         TelegramGateway telegram, ReportService reports,
                         @Value("${regata-simulator.database.path}") String databasePath,
                         @Value("${regata-simulator.templates.path}") String templatesPath,
                         @Value("${regata-simulator.sources.path}") String sourcesPath) {
        this(backupChatId);
        this.telegram = telegram; this.reports = reports; this.databasePath = databasePath;
        this.templatesPath = templatesPath; this.sourcesPath = sourcesPath;
    }

    /** Explicit backup orchestration; no Update or reply-origin fabrication. */
    public void create() {
        create(TelegramGateway.Destination.chat(backupChatId));
    }

    public void create(TelegramGateway.Destination reportDestination) {
        archive(databasePath, "jsondb");
        archive(templatesPath, "templates");
        archive(sourcesPath, "sources");
        reports.send(reportDestination);
    }

    public void archive(String directory, String prefix) {
        try (var archives = new Archives(FileUtils.zipInChunks(directory, MAX_CHUNK_SIZE))) {
            String timestamp = LocalDateTime.now(java.time.ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            for (int i = 0; i < archives.paths().size(); i++) {
                Path path = archives.paths().get(i);
                if (Files.size(path) > MAX_CHUNK_SIZE) throw new IOException("Backup archive exceeds delivery limit");
                String caption = archives.paths().size() > 1
                    ? prefix + " backup (" + (i + 1) + "/" + archives.paths().size() + ")" : prefix + " backup";
                telegram.sendDocument(new TelegramGateway.Document(TelegramGateway.Destination.chat(backupChatId),
                    path, prefix + "-" + timestamp + "-" + i + ".zip", caption));
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new ApplicationFailure(ApplicationFailure.Kind.EXECUTION, "Backup failed", e);
        }
    }

    public void zipToTelegram(RegataSimulatorBot bot, String backupDirPath, String filePrefix)
        throws IOException, TelegramApiException {
        log.info("Running backup of {}.", filePrefix);
        try (var archives = new Archives(FileUtils.zipInChunks(backupDirPath, MAX_CHUNK_SIZE))) {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            for (int i = 0; i < archives.paths().size(); i++) {
                Path zipFilePath = archives.paths().get(i);
                if (Files.size(zipFilePath) > MAX_CHUNK_SIZE) {
                    throw new IOException("Backup archive exceeds delivery limit");
                }
                String fileName = filePrefix + "-" + timestamp + "-" + i + ".zip";
                String caption = (archives.paths().size() > 1)
                    ? filePrefix + " backup (" + (i + 1) + "/" + archives.paths().size() + ")"
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

    private record Archives(List<Path> paths) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            IOException cleanupFailure = null;
            for (Path zip : paths) {
                try {
                    Files.deleteIfExists(zip);
                } catch (IOException e) {
                    if (cleanupFailure == null) cleanupFailure = e;
                    else cleanupFailure.addSuppressed(e);
                }
            }
            if (cleanupFailure != null) {
                throw cleanupFailure;
            }
        }
    }
}
