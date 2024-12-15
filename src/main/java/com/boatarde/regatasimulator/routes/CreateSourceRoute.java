package com.boatarde.regatasimulator.routes;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.generics.TelegramBot;

import java.util.Optional;

@Component
public class CreateSourceRoute implements Route {

    private static final String JPEG_MIME = "image/jpeg";
    private static final String PNG_MIME = "image/png";

    @Override
    public Optional<WorkflowAction> test(Update update, TelegramBot bot) {
        if (!isValidBot(bot)) {
            return Optional.empty();
        }

        // Check for confirm/cancel callback queries first
        if (update.hasCallbackQuery()) {
            String data = update.getCallbackQuery().getData();
            if (data.endsWith(":source:confirm")) {
                return Optional.of(WorkflowAction.CONFIRM_REVIEW_SOURCE);
            } else if (data.endsWith(":source:cancel")) {
                return Optional.of(WorkflowAction.DELETE_REVIEW_SOURCE);
            }
        }

        // Check if it's a new source creation message
        if (!update.hasMessage() || !update.getMessage().isUserMessage()) {
            return Optional.empty();
        }

        // Check if the message has a document of type JPEG or PNG
        if (!update.getMessage().hasDocument()) {
            return Optional.empty();
        }

        String mimeType = update.getMessage().getDocument().getMimeType();
        if (!JPEG_MIME.equals(mimeType) && !PNG_MIME.equals(mimeType)) {
            return Optional.empty();
        }

        String caption = update.getMessage().getCaption();
        if (caption == null || !caption.toLowerCase().startsWith("source:")) {
            return Optional.empty();
        }

        return Optional.of(WorkflowAction.CREATE_SOURCE);
    }

    private boolean isValidBot(TelegramBot bot) {
        return bot instanceof RegataSimulatorBot;
    }
}
