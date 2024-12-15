package com.boatarde.regatasimulator.flows.simulator;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowDataKey;
import com.boatarde.regatasimulator.flows.WorkflowStep;
import com.boatarde.regatasimulator.flows.WorkflowStepRegistration;
import com.boatarde.regatasimulator.service.SourceService;
import com.boatarde.regatasimulator.util.TelegramUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.MaybeInaccessibleMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.UUID;

@Slf4j
@WorkflowStepRegistration(WorkflowAction.CONFIRM_REVIEW_SOURCE)
public class ConfirmReviewSourceStep implements WorkflowStep {

    private final String botAuthorId;
    private final SourceService sourceService;

    public ConfirmReviewSourceStep(@Value("${telegram.creator.id}") String botAuthorId,
                                   SourceService sourceService) {
        this.botAuthorId = botAuthorId;
        this.sourceService = sourceService;
    }

    @Override
    public WorkflowAction run(WorkflowDataBag bag) {
        Update update = bag.get(WorkflowDataKey.TELEGRAM_UPDATE, Update.class);
        RegataSimulatorBot bot = bag.get(WorkflowDataKey.REGATA_SIMULATOR_BOT, RegataSimulatorBot.class);

        MaybeInaccessibleMessage message = update.getCallbackQuery().getMessage();
        UUID sourceId = TelegramUtils.extractItemId(update.getCallbackQuery().getData());

        try {
            sourceService.getSource(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("Source not found on jsondb."));

            bot.execute(AnswerCallbackQuery.builder()
                .text("Source enviado para aprovação.")
                .callbackQueryId(update.getCallbackQuery().getId())
                .build());
            bot.execute(EditMessageReplyMarkup.builder()
                .chatId(message.getChatId().toString())
                .messageId(message.getMessageId())
                .replyMarkup(null)
                .build());

            bot.execute(SendPhoto.builder()
                .chatId(botAuthorId)
                .photo(new InputFile(((Message) message).getPhoto().getLast().getFileId()))
                .caption("Source id <code>%s</code> aguardando aprovação.\nEnviado por %s".formatted(sourceId,
                    TelegramUtils.usernameOrFullName(update.getCallbackQuery().getFrom())))
                .parseMode("HTML")
                .build());
        } catch (TelegramApiException e) {
            log.error("Error confirming source {}.", sourceId, e);
        }

        return WorkflowAction.NONE;
    }
}
