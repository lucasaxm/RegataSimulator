package com.boatarde.regatasimulator.flows.simulator;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowDataKey;
import com.boatarde.regatasimulator.flows.WorkflowStep;
import com.boatarde.regatasimulator.flows.WorkflowStepRegistration;
import com.boatarde.regatasimulator.models.Author;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.models.Template;
import com.boatarde.regatasimulator.util.FileUtils;
import com.boatarde.regatasimulator.util.JsonDBUtils;
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
import java.util.UUID;

@Slf4j
@WorkflowStepRegistration(WorkflowAction.CREATE_TEMPLATE)
public class CreateTemplateStep implements WorkflowStep {

    private final String templatesPathString;
    private final JsonDBTemplate jsonDBTemplate;

    public CreateTemplateStep(@Value("${regata-simulator.templates.path}") String templatesPathString,
                              JsonDBTemplate jsonDBTemplate) {
        this.templatesPathString = templatesPathString;
        this.jsonDBTemplate = jsonDBTemplate;
    }

    @Override
    public WorkflowAction run(WorkflowDataBag bag) {
        Update update = bag.get(WorkflowDataKey.TELEGRAM_UPDATE, Update.class);
        RegataSimulatorBot bot = bag.get(WorkflowDataKey.REGATA_SIMULATOR_BOT, RegataSimulatorBot.class);
        Path templatesDir = Paths.get(templatesPathString);

        String fileExtension = FileUtils.getFileExtension(update.getMessage().getDocument().getFileName());
        String fileName = "template" + fileExtension.toLowerCase();

        UUID uuid = UUID.randomUUID();
        Path newDir = templatesDir.resolve(uuid.toString());

        try {
            log.info("Creating new template dir: {}", uuid);
            Files.createDirectories(newDir);
            String fileId = update.getMessage().getDocument().getFileId();
            Path templateFile = TelegramUtils.downloadTelegramFile(bot, fileId, newDir, fileName);

            String csvContent = update.getMessage().getCaption();

            Template template = new Template();
            template.setId(uuid);
            template.setStatus(Status.REVIEW);
            template.setWeight(30);
            template.setMessage(update.getMessage());
            template.setAreas(JsonDBUtils.parseTemplateCsv(csvContent));

            Message response = bot.execute(SendMessage.builder()
                .chatId(update.getMessage().getChatId().toString())
                .replyToMessageId(update.getMessage().getMessageId())
                .allowSendingWithoutReply(true)
                .text("Criando template...")
                .build());

            saveTemplate(template);

            Author author = Author.builder()
                .id(update.getMessage().getFrom().getId())
                .userName(update.getMessage().getFrom().getUserName())
                .firstName(update.getMessage().getFrom().getFirstName())
                .lastName(update.getMessage().getFrom().getLastName())
                .build();

            saveAuthor(author);

            bag.put(WorkflowDataKey.TEMPLATE_FILE, templateFile);
            bag.put(WorkflowDataKey.TEMPLATE, template);
            bag.put(WorkflowDataKey.CREATING_TEMPLATE_MESSAGE, response);
        } catch (Exception e) {
            log.error(String.format("Exception when creating template: %s", e.getMessage()), e);
            try {
                Files.deleteIfExists(newDir);
            } catch (IOException ex) {
                log.error(String.format("Exception when deleting new template dir: %s", e.getMessage()), e);
            }
            return WorkflowAction.NONE;
        }
        return WorkflowAction.GET_RANDOM_SOURCE;
    }

    private void saveAuthor(Author author) {
        log.info("Inserting or updating author {} into collection.", author.getId());
        jsonDBTemplate.upsert(author);
    }

    private void saveTemplate(Template template) {
        log.info("Inserting template {} into collection.", template.getId());
        jsonDBTemplate.insert(template);
    }
}
