package com.boatarde.regatasimulator.factory;

import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.EntityType;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class TelegramTestFactory {

    private static final String PRIVATE = "private";
    private static final String TEST = "test";
    private static final Long CHAT_ID = 1234L;
    private static final Integer MESSAGE_ID = 5678;

    public static Update buildTextMessageUpdate(String text) {
        Message message = buildTextMessage(text);

        Update update = new Update();
        update.setMessage(message);

        return update;
    }

    public static Message buildTextMessage(String text) {
        Chat chat = buildChat();

        Message message = new Message();
        message.setChat(chat);
        message.setText(text);
        message.setMessageId(MESSAGE_ID);

        return message;
    }

    public static Update buildCommandTextMessageUpdate(String command) {
        Message message = buildCommandTextMessage(command);

        Update update = new Update();
        update.setMessage(message);

        return update;
    }

    public static Message buildCommandTextMessage(String command) {
        Chat chat = buildChat();

        Message message = new Message();
        message.setChat(chat);
        message.setText(command);
        message.setMessageId(MESSAGE_ID);
        message.setDate((int) (Instant.now().getEpochSecond()));
        message.setEntities(List.of(MessageEntity.builder()
            .type(EntityType.BOTCOMMAND)
            .offset(0)
            .length(command.length())
            .text(command)
            .build()));

        return message;
    }

    public static Update buildUrlTextMessageUpdate(List<String> urls) {
        Message message = buildUrlTextMessage(urls);

        Update update = new Update();
        update.setMessage(message);

        return update;
    }

    public static Message buildUrlTextMessage(List<String> urls) {
        Chat chat = buildChat();

        Message message = new Message();
        message.setChat(chat);
        message.setText(String.join("\n", urls));
        message.setMessageId(MESSAGE_ID);
        message.setDate((int) (Instant.now().getEpochSecond()));
        message.setEntities(new ArrayList<>());
        urls.forEach(currentUrl -> {
            int offset;
            List<MessageEntity> entities = message.getEntities();
            if (entities.isEmpty()) {
                offset = 0;
            } else {
                MessageEntity last = entities.getLast();
                offset = last.getOffset() + last.getText().length() + entities.size();
            }
            entities.add(MessageEntity.builder()
                .type(EntityType.URL)
                .offset(offset)
                .length(currentUrl.length())
                .build());
        });

        return message;
    }

    private static Chat buildChat() {
        Chat chat = new Chat();
        chat.setFirstName(TEST);
        chat.setType(PRIVATE);
        chat.setId(CHAT_ID);
        return chat;
    }
}
