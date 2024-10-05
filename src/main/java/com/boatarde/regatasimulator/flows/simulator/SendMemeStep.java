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

@Slf4j
@WorkflowStepRegistration(WorkflowAction.SEND_MEME_STEP)
public class SendMemeStep implements WorkflowStep {

    private final Long channelId;

    public SendMemeStep(@Value("${telegram.bots.regata-simulator.channel}") Long channelId) {
        this.channelId = channelId;
    }

    @Override
    public WorkflowAction run(WorkflowDataBag bag) {
        RegataSimulatorBot regataSimulatorBot = bag.get(WorkflowDataKey.REGATA_SIMULATOR_BOT, RegataSimulatorBot.class);
        Update update = bag.get(WorkflowDataKey.TELEGRAM_UPDATE, Update.class);
        Path memePath = bag.get(WorkflowDataKey.MEME_FILE, Path.class);
        File file = memePath.toFile();

        try {
            SendPhoto sendPhoto = getSendPhoto(update, file);

            addConfirmKeyboard(bag, regataSimulatorBot, sendPhoto);

            Message response = TelegramUtils.executeSendMediaBotMethod(regataSimulatorBot, sendPhoto);

            log.info("Response: {}", TelegramUtils.toJson(response));
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
                                    SendPhoto sendPhoto)
        throws TelegramApiException {
        Message creatingTemplateMessage = bag.get(WorkflowDataKey.CREATING_TEMPLATE_MESSAGE, Message.class);
        if (creatingTemplateMessage != null) {
            regataSimulatorBot.execute(DeleteMessage.builder()
                .chatId(creatingTemplateMessage.getChatId())
                .messageId(creatingTemplateMessage.getMessageId())
                .build());

            Path templatePath = bag.get(WorkflowDataKey.TEMPLATE_FILE, Path.class);

            String templateId = templatePath.getParent().getFileName().toString();
            sendPhoto.setReplyMarkup(InlineKeyboardMarkup.builder()
                .keyboard(List.of(List.of(InlineKeyboardButton.builder()
                        .text("Confirmar")
                        .callbackData(templateId + ":template:confirm")
                        .build()),
                    List.of(InlineKeyboardButton.builder()
                        .text("Cancelar")
                        .callbackData(templateId + ":template:cancel")
                        .build())))
                .build());
        }
    }
}
