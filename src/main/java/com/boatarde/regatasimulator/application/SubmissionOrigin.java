package com.boatarde.regatasimulator.application;

import org.telegram.telegrambots.meta.api.objects.Message;

/** Explicit transport projection plus unchanged legacy persistence envelope. */
public record SubmissionOrigin(TelegramGateway.Destination destination, Message legacyMessage) {
    public static SubmissionOrigin from(Message message) {
        return new SubmissionOrigin(new TelegramGateway.Destination(message.getChatId(), message.getMessageId(),
            message.getMessageThreadId()), message);
    }
}