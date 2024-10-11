package com.boatarde.regatasimulator.flows.simulator;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowDataKey;
import com.boatarde.regatasimulator.flows.WorkflowStep;
import com.boatarde.regatasimulator.flows.WorkflowStepRegistration;
import com.boatarde.regatasimulator.models.Meme;
import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.models.Template;
import com.boatarde.regatasimulator.util.TelegramUtils;
import io.jsondb.JsonDBTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@Slf4j
@WorkflowStepRegistration(WorkflowAction.SEND_MEME_STEP)
public class SendMemeStep implements WorkflowStep {

    private final Long channelId;
    private final JsonDBTemplate jsonDBTemplate;

    public SendMemeStep(@Value("${telegram.bots.regata-simulator.channel}") Long channelId,
                        JsonDBTemplate jsonDBTemplate) {
        this.channelId = channelId;
        this.jsonDBTemplate = jsonDBTemplate;
    }

    @Override
    public WorkflowAction run(WorkflowDataBag bag) {
        RegataSimulatorBot regataSimulatorBot = bag.get(WorkflowDataKey.REGATA_SIMULATOR_BOT, RegataSimulatorBot.class);
        Update update = bag.get(WorkflowDataKey.TELEGRAM_UPDATE, Update.class);
        Path memePath = bag.get(WorkflowDataKey.MEME_FILE, Path.class);
        Message creatingTemplateMessage = bag.get(WorkflowDataKey.CREATING_TEMPLATE_MESSAGE, Message.class);
        List<Meme> pastMemes = bag.getGeneric(WorkflowDataKey.MEMES_HISTORY, List.class, Meme.class);

        File file = memePath.toFile();

        try {
            SendPhoto sendPhoto = getSendPhoto(update, file);
            if (creatingTemplateMessage != null) {
                addConfirmKeyboard(bag, regataSimulatorBot, sendPhoto, creatingTemplateMessage);
            }
            Message response = TelegramUtils.executeSendMediaBotMethod(regataSimulatorBot, sendPhoto);

            log.info("Response: {}", TelegramUtils.toJson(response));

            if (creatingTemplateMessage == null) {
                Template template = bag.get(WorkflowDataKey.TEMPLATE, Template.class);
                List<Source> sources = bag.getGeneric(WorkflowDataKey.SOURCES, List.class, Source.class);
                updateWeights(template, sources);
                updateMemesDB(pastMemes, template, sources, response);
            }
        } catch (TelegramApiException e) {
            log.error(String.format("TelegramApiException when sending media: %s", e.getMessage()), e);
            return WorkflowAction.NONE;
        } finally {
            // Delete the temporary file when done
            if (file.exists() && file.delete()) {
                log.info("Temporary file deleted.");
            } else {
                log.error("Failed to delete the temporary file.");
            }
        }

        return WorkflowAction.NONE;
    }

    private void updateMemesDB(List<Meme> memes, Template template, List<Source> sources, Message response) {
        if (memes.size() >= 1000) {
            Meme removed = jsonDBTemplate.remove(memes.getLast(), Meme.class);
            if (removed == null) {
                log.error("Failed to delete meme: {}", memes.getLast());
            } else {
                log.info("Meme deleted from history: {}", removed);
            }
        }
        Meme meme = Meme.builder()
            .id(UUID.randomUUID())
            .templateId(template.getId())
            .sourceIds(sources.stream().map(Source::getId).toList())
            .message(response)
            .build();
        jsonDBTemplate.insert(meme);
        log.info("Meme {} saved.", meme.getId());
    }

    private void updateWeights(Template template, List<Source> sources) {
        sources.forEach(source -> {
            if (source.getWeight() == 1) {
                log.info("Source {} weight is already at minimum.", source.getId());
                return;
            }
            source.setWeight(source.getWeight() - 1);
            jsonDBTemplate.upsert(source);
            log.info("Source {} weight updated from {} to {}", source.getId(), source.getWeight() + 1,
                source.getWeight());
        });
        if (template.getWeight() == 1) {
            log.info("Template {} weight is already at minimum.", template.getId());
            return;
        }
        template.setWeight(template.getWeight() - 1);
        jsonDBTemplate.upsert(template);
        log.info("Template {} weight updated from {} to {}", template.getId(), template.getWeight() + 1,
            template.getWeight());
    }

    private SendPhoto getSendPhoto(Update update, File file) {
        if (update == null) {
            return SendPhoto.builder()
                .chatId(channelId)
                .photo(new InputFile(file))
                .build();
        }
        return SendPhoto.builder()
            .chatId(update.getMessage().getChatId())
            .photo(new InputFile(file))
            .allowSendingWithoutReply(true)
            .replyToMessageId(update.getMessage().getMessageId())
            .messageThreadId(update.getMessage().getMessageThreadId())
            .build();
    }

    private void addConfirmKeyboard(WorkflowDataBag bag, RegataSimulatorBot regataSimulatorBot,
                                    SendPhoto sendPhoto, Message creatingTemplateMessage)
        throws TelegramApiException {
        regataSimulatorBot.execute(DeleteMessage.builder()
            .chatId(creatingTemplateMessage.getChatId())
            .messageId(creatingTemplateMessage.getMessageId())
            .build());

        Path templatePath = bag.get(WorkflowDataKey.TEMPLATE_FILE, Path.class);

        String templateId = templatePath.getParent().getFileName().toString();
        sendPhoto.setReplyMarkup(InlineKeyboardMarkup.builder()
            .keyboard(List.of(List.of(InlineKeyboardButton.builder()
                    .text("✅ Confirmar")
                    .callbackData(templateId + ":template:confirm")
                    .build()),
                List.of(InlineKeyboardButton.builder()
                    .text("❌ Cancelar")
                    .callbackData(templateId + ":template:cancel")
                    .build())))
            .build());
    }
}
