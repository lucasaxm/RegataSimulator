package com.boatarde.regatasimulator.flows.simulator;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowDataKey;
import com.boatarde.regatasimulator.flows.WorkflowStep;
import com.boatarde.regatasimulator.flows.WorkflowStepRegistration;
import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.service.SourceService;
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
@WorkflowStepRegistration(WorkflowAction.DELETE_REVIEW_SOURCE)
public class DeleteReviewSourceStep implements WorkflowStep {

    private final SourceService sourceService;

    public DeleteReviewSourceStep(SourceService sourceService) {
        this.sourceService = sourceService;
    }

    @Override
    public WorkflowAction run(WorkflowDataBag bag) {
        Update update = bag.get(WorkflowDataKey.TELEGRAM_UPDATE, Update.class);
        RegataSimulatorBot bot = bag.get(WorkflowDataKey.REGATA_SIMULATOR_BOT, RegataSimulatorBot.class);

        UUID sourceId = TelegramUtils.extractItemId(update.getCallbackQuery().getData());
        log.info("Deleting Review Source ID: {}", sourceId);

        try {
            Optional<Source> sourceOpt = sourceService.getSource(sourceId);
            if (sourceOpt.isEmpty()) {
                log.error("Source not found: {}", sourceId);
                return WorkflowAction.NONE;
            }
            sourceService.deleteSource(sourceOpt.get());

            MaybeInaccessibleMessage message = update.getCallbackQuery().getMessage();
            bot.execute(AnswerCallbackQuery.builder()
                .text("Source deletado.")
                .callbackQueryId(update.getCallbackQuery().getId())
                .build());
            bot.execute(DeleteMessage.builder()
                .chatId(message.getChatId().toString())
                .messageId(message.getMessageId())
                .build());
        } catch (TelegramApiException e) {
            log.error("Error while deleting source", e);
        }

        return WorkflowAction.NONE;
    }
}
