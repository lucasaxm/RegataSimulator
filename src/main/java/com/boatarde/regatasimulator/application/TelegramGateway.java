package com.boatarde.regatasimulator.application;

import java.nio.file.Path;
import java.util.UUID;
import org.telegram.telegrambots.meta.api.objects.Message;

public interface TelegramGateway {
    record Destination(long chatId, Integer replyToMessageId, Integer threadId) {
        public static Destination chat(long chatId) { return new Destination(chatId, null, null); }
    }
    record Text(Destination destination, String text, boolean html) { }
    record PreviewButtons(UUID itemId, String type) { }
    record Photo(Destination destination, Path file, String caption, PreviewButtons buttons) { }
    record Document(Destination destination, Path file, String fileName, String caption) { }
    /** Legacy message is retained only for persisted JsonDB history compatibility. */
    record Delivery(long chatId, Integer messageId, Message legacyMessage) { }

    Delivery sendText(Text request);
    Delivery sendPhoto(Photo request);
    void sendDocument(Document request);
    Path download(String fileId, Path directory, String fileName);
    void deleteMessage(long chatId, int messageId);
    void acknowledge(String callbackId, String text);
}