package com.boatarde.regatasimulator.flows.simulator;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowDataKey;
import com.boatarde.regatasimulator.flows.WorkflowStep;
import com.boatarde.regatasimulator.flows.WorkflowStepRegistration;
import com.boatarde.regatasimulator.models.Template;
import com.boatarde.regatasimulator.service.TemplateService;
import com.boatarde.regatasimulator.util.TelegramUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.MaybeInaccessibleMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.UUID;

@Slf4j
@WorkflowStepRegistration(WorkflowAction.CONFIRM_REVIEW_TEMPLATE)
public class ConfirmReviewTemplateStep implements WorkflowStep {

    private final String botAuthorId;
    private final TemplateService templateService;

    public ConfirmReviewTemplateStep(@Value("${telegram.creator.id}") String botAuthorId,
                                     TemplateService templateService) {
        this.botAuthorId = botAuthorId;
        this.templateService = templateService;
    }

    @Override
    public WorkflowAction run(WorkflowDataBag bag) {
        Update update = bag.get(WorkflowDataKey.TELEGRAM_UPDATE, Update.class);
        RegataSimulatorBot bot = bag.get(WorkflowDataKey.REGATA_SIMULATOR_BOT, RegataSimulatorBot.class);

        MaybeInaccessibleMessage message = update.getCallbackQuery().getMessage();
        UUID templateId = TelegramUtils.extractTemplateId(update.getCallbackQuery().getData());

        try {
            Template template = templateService.getTemplate(templateId)
                .orElseThrow(() -> new IllegalArgumentException("Template not found on jsondb."));

            bot.execute(AnswerCallbackQuery.builder()
                .text("Template enviado para aprovação.")
                .callbackQueryId(update.getCallbackQuery().getId())
                .build());
            bot.execute(EditMessageReplyMarkup.builder()
                .chatId(message.getChatId().toString())
                .messageId(message.getMessageId())
                .replyMarkup(null)
                .build());

            bot.execute(SendDocument.builder()
                .chatId(botAuthorId)
                .document(new InputFile(template.getMessage().getDocument().getFileId()))
                .caption("Template id <code>%s</code> aguardando aprovação.\nEnviado por %s".formatted(templateId,
                    TelegramUtils.usernameOrFullName(update.getCallbackQuery().getFrom())))
                .parseMode("HTML")
                .build());
        } catch (TelegramApiException e) {
            log.error("Error confirming template {}.", templateId, e);
        }

        return WorkflowAction.NONE;
    }
}
