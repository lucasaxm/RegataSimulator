package com.boatarde.regatasimulator.adapter.telegram;

import com.boatarde.regatasimulator.application.TelegramGateway;
import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.flows.ApplicationFailure;
import com.boatarde.regatasimulator.util.TelegramUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.nio.file.Path;
import java.util.List;

@Component
public class BotTelegramGateway implements TelegramGateway {
    private final ObjectProvider<RegataSimulatorBot> bots;
    // Resolve only during a request, avoiding the bot -> router -> service -> bot construction cycle.
    public BotTelegramGateway(ObjectProvider<RegataSimulatorBot> bots) { this.bots = bots; }

    @Override
    public Delivery sendText(Text request) {
        try {
            return delivery(bots.getObject().execute(SendMessage.builder()
                .chatId(request.destination().chatId()).replyToMessageId(request.destination().replyToMessageId())
                .messageThreadId(request.destination().threadId()).allowSendingWithoutReply(true)
                .text(request.text()).parseMode(request.html() ? "HTML" : null).build()));
        } catch (TelegramApiException e) { throw failed(e); }
    }

    @Override
    public Delivery sendPhoto(Photo request) {
        SendPhoto photo = SendPhoto.builder().chatId(request.destination().chatId())
            .replyToMessageId(request.destination().replyToMessageId()).messageThreadId(request.destination().threadId())
            .allowSendingWithoutReply(true).photo(new InputFile(request.file().toFile())).caption(request.caption()).build();
        if (request.buttons() != null) {
            PreviewButtons buttons = request.buttons();
            photo.setReplyMarkup(InlineKeyboardMarkup.builder().keyboard(List.of(
                List.of(button(buttons, "confirm", "✅ Confirmar")),
                List.of(button(buttons, "cancel", "❌ Cancelar")))).build());
        }
        try { return delivery(TelegramUtils.executeSendMediaBotMethod(bots.getObject(), photo)); }
        catch (TelegramApiException e) { throw failed(e); }
    }

    @Override
    public void sendDocument(Document request) {
        try {
            bots.getObject().execute(SendDocument.builder().chatId(request.destination().chatId())
                .caption(request.caption()).document(new InputFile(request.file().toFile(), request.fileName())).build());
        } catch (TelegramApiException e) { throw failed(e); }
    }

    @Override
    public Path download(String fileId, Path directory, String fileName) {
        try { return TelegramUtils.downloadTelegramFile(bots.getObject(), fileId, directory, fileName); }
        catch (Exception e) { throw failed(e); }
    }

    @Override
    public void deleteMessage(long chatId, int messageId) {
        try { bots.getObject().execute(DeleteMessage.builder().chatId(chatId).messageId(messageId).build()); }
        catch (TelegramApiException e) { throw failed(e); }
    }

    @Override
    public void acknowledge(String callbackId, String text) {
        try { bots.getObject().execute(AnswerCallbackQuery.builder().callbackQueryId(callbackId).text(text).build()); }
        catch (TelegramApiException e) { throw failed(e); }
    }

    private InlineKeyboardButton button(PreviewButtons buttons, String action, String text) {
        return InlineKeyboardButton.builder().text(text)
            .callbackData("%s:%s:%s".formatted(buttons.itemId(), buttons.type(), action)).build();
    }

    private Delivery delivery(Message message) {
        return new Delivery(message != null && message.getChat() != null && message.getChatId() != null
            ? message.getChatId() : 0, message == null ? null : message.getMessageId(), message);
    }

    private ApplicationFailure failed(Exception cause) {
        return new ApplicationFailure(ApplicationFailure.Kind.EXECUTION, "Telegram operation failed", cause);
    }
}