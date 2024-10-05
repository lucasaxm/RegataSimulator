package com.boatarde.regatasimulator.flows.simulator;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowDataKey;
import com.boatarde.regatasimulator.flows.WorkflowStep;
import com.boatarde.regatasimulator.flows.WorkflowStepRegistration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.objects.MaybeInaccessibleMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

@Slf4j
@WorkflowStepRegistration(WorkflowAction.CONFIRM_REVIEW_TEMPLATE)
public class ConfirmReviewTemplateStep implements WorkflowStep {

    private final String botAuthorId;

    public ConfirmReviewTemplateStep(@Value("${telegram.creator.id}") String botAuthorId) {
        this.botAuthorId = botAuthorId;
    }

    @Override
    public WorkflowAction run(WorkflowDataBag bag) {
        Update update = bag.get(WorkflowDataKey.TELEGRAM_UPDATE, Update.class);
        RegataSimulatorBot bot = bag.get(WorkflowDataKey.REGATA_SIMULATOR_BOT, RegataSimulatorBot.class);

        MaybeInaccessibleMessage message = update.getCallbackQuery().getMessage();
        String templateId = extractTemplateId(update.getCallbackQuery().getData());

        try {
            bot.execute(AnswerCallbackQuery.builder()
                .text("Template enviado para aprovação.")
                .callbackQueryId(update.getCallbackQuery().getId())
                .build());
            bot.execute(EditMessageReplyMarkup.builder()
                .chatId(message.getChatId().toString())
                .messageId(message.getMessageId())
                .replyMarkup(null)
                .build());
            bot.execute(SendMessage.builder()
                .chatId(botAuthorId)
                .text("Template id <code>%s</code> aguardando aprovação.\nEnviado por %s".formatted(templateId,
                    usernameOrFullName(update.getCallbackQuery().getFrom())))
                .parseMode("HTML")
                .build());
        } catch (TelegramApiException e) {
            log.error("Error while removing keyboard", e);
        }

        return WorkflowAction.NONE;
    }

    private String usernameOrFullName(User from) {
        if (from.getUserName() != null && !from.getUserName().isEmpty()) {
            return "@" + from.getUserName();
        }
        String fullName = from.getFirstName();
        if (from.getLastName() != null && !from.getLastName().isEmpty()) {
            fullName += " " + from.getLastName();
        }
        return "<a href=\"tg://user?id=%d\">%s</a>".formatted(from.getId(), fullName);
    }

    private String extractTemplateId(String data) {
        String[] parts = data.split(":");
        if (parts.length > 0) {
            return parts[0];
        }
        return null;
    }
}
