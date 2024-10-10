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
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.MaybeInaccessibleMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@WorkflowStepRegistration(WorkflowAction.DELETE_REVIEW_TEMPLATE)
public class DeleteReviewTemplateStep implements WorkflowStep {

    private final TemplateService templateService;

    public DeleteReviewTemplateStep(TemplateService templateService) {
        this.templateService = templateService;
    }

    @Override
    public WorkflowAction run(WorkflowDataBag bag) {
        Update update = bag.get(WorkflowDataKey.TELEGRAM_UPDATE, Update.class);
        RegataSimulatorBot bot = bag.get(WorkflowDataKey.REGATA_SIMULATOR_BOT, RegataSimulatorBot.class);

        UUID templateId = TelegramUtils.extractTemplateId(update.getCallbackQuery().getData());
        log.info("Deleting Review Template ID: {}", templateId);

        try {
            Optional<Template> templateOpt = templateService.getTemplate(templateId);
            if (templateOpt.isEmpty()) {
                log.error("Template not found: {}", templateId);
                return WorkflowAction.NONE;
            }
            templateService.deleteTemplate(templateOpt.get());

            MaybeInaccessibleMessage message = update.getCallbackQuery().getMessage();
            bot.execute(AnswerCallbackQuery.builder()
                .text("Template deletado.")
                .callbackQueryId(update.getCallbackQuery().getId())
                .build());
            bot.execute(DeleteMessage.builder()
                .chatId(message.getChatId().toString())
                .messageId(message.getMessageId())
                .build());
        } catch (TelegramApiException e) {
            log.error("Error while deleting template", e);
        }

        return WorkflowAction.NONE;
    }
}
