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
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.MaybeInaccessibleMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

@Slf4j
@WorkflowStepRegistration(WorkflowAction.DELETE_REVIEW_TEMPLATE)
public class DeleteReviewTemplateStep implements WorkflowStep {

    private final String reviewTemplatesPathString;

    public DeleteReviewTemplateStep(
        @Value("${regata-simulator.templates.review-path}") String reviewTemplatesPathString) {
        this.reviewTemplatesPathString = reviewTemplatesPathString;
    }

    @Override
    public WorkflowAction run(WorkflowDataBag bag) {
        Update update = bag.get(WorkflowDataKey.TELEGRAM_UPDATE, Update.class);
        RegataSimulatorBot bot = bag.get(WorkflowDataKey.REGATA_SIMULATOR_BOT, RegataSimulatorBot.class);

        String templateId = update.getCallbackQuery().getData().split(":")[0];
        log.info("Deleting Review Template ID: {}", templateId);

        try {
            deleteTemplate(UUID.fromString(templateId));

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

    private void deleteTemplate(UUID id) {
        try {
            Path filePath = Paths.get(reviewTemplatesPathString).resolve(id.toString());
            try (Stream<Path> paths = Files.walk(filePath)) {
                paths.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete template: " + id, e);
        }
    }
}
