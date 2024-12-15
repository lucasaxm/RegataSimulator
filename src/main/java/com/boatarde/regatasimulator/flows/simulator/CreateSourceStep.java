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
import com.boatarde.regatasimulator.util.FileUtils;
import com.boatarde.regatasimulator.util.TelegramUtils;
import io.jsondb.JsonDBTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

@Slf4j
@WorkflowStepRegistration(WorkflowAction.CREATE_SOURCE)
public class CreateSourceStep implements WorkflowStep {

    private final String sourcesPathString;
    private final JsonDBTemplate jsonDBTemplate;
    private final int initialWeight;

    public CreateSourceStep(@Value("${regata-simulator.sources.path}") String sourcesPathString,
                            JsonDBTemplate jsonDBTemplate,
                            @Value("${regata-simulator.sources.initial-weight}") int initialWeight) {
        this.sourcesPathString = sourcesPathString;
        this.jsonDBTemplate = jsonDBTemplate;
        this.initialWeight = initialWeight;
    }

    @Override
    public WorkflowAction run(WorkflowDataBag bag) {
        Update update = bag.get(WorkflowDataKey.TELEGRAM_UPDATE, Update.class);
        RegataSimulatorBot bot = bag.get(WorkflowDataKey.REGATA_SIMULATOR_BOT, RegataSimulatorBot.class);
        Path sourcesDir = Paths.get(sourcesPathString);

        String fileExtension = FileUtils.getFileExtension(update.getMessage().getDocument().getFileName());
        String fileName = "source" + fileExtension.toLowerCase();

        UUID uuid = UUID.randomUUID();
        Path newDir = sourcesDir.resolve(uuid.toString());

        try {
            log.info("Creating new source dir: {}", uuid);
            Files.createDirectories(newDir);
            String fileId = update.getMessage().getDocument().getFileId();
            Path sourceFile = TelegramUtils.downloadTelegramFile(bot, fileId, newDir, fileName);

            String csvContent = update.getMessage().getCaption();

            Source source = new Source();
            source.setId(uuid);
            source.setStatus(Status.REVIEW);
            source.setWeight(initialWeight);
            source.setMessage(update.getMessage());
            source.setDescription(extractDescription(update.getMessage().getCaption()));


            Message response = bot.execute(SendMessage.builder()
                .chatId(update.getMessage().getChatId().toString())
                .replyToMessageId(update.getMessage().getMessageId())
                .allowSendingWithoutReply(true)
                .text("Criando source...")
                .build());

            saveSource(source);

            Author author = Author.builder()
                .id(update.getMessage().getFrom().getId())
                .userName(update.getMessage().getFrom().getUserName())
                .firstName(update.getMessage().getFrom().getFirstName())
                .lastName(update.getMessage().getFrom().getLastName())
                .build();

            saveAuthor(author);

            bag.put(WorkflowDataKey.SOURCE_FILES, List.of(sourceFile));
            bag.put(WorkflowDataKey.SOURCES, List.of(source));
            bag.put(WorkflowDataKey.CREATING_SOURCE_MESSAGE, response);
        } catch (Exception e) {
            log.error(String.format("Exception when creating source: %s", e.getMessage()), e);
            try {
                Files.deleteIfExists(newDir);
            } catch (IOException ex) {
                log.error(String.format("Exception when deleting new source dir: %s", e.getMessage()), e);
            }
            return WorkflowAction.NONE;
        }
        return WorkflowAction.GET_RANDOM_TEMPLATE;
    }

    private void saveAuthor(Author author) {
        log.info("Inserting or updating author {} into collection.", author.getId());
        jsonDBTemplate.upsert(author);
    }

    private void saveSource(Source source) {
        log.info("Inserting source {} into collection.", source.getId());
        jsonDBTemplate.insert(source);
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
