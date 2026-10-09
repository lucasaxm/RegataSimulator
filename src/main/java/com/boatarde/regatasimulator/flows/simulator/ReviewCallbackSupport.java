package com.boatarde.regatasimulator.flows.simulator;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowDataKey;
import com.boatarde.regatasimulator.models.CommonEntity;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.util.TelegramUtils;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.function.Consumer;
import java.util.function.Function;

/** Shared, fail-closed authorization for submitter preview callbacks, not administrator approval. */
@UtilityClass
@Slf4j
public class ReviewCallbackSupport {

    private static final String REJECTED =
        "Não foi possível processar este botão. Ele é inválido, expirou ou não pertence a você.";
    // Bounded process-local stripes prevent simultaneous confirm/cancel replays for the same item.
    private static final Object[] ITEM_LOCKS = IntStream.range(0, 64).mapToObj(index -> new Object()).toArray();

    public static boolean isType(String data, String type) {
        if (data == null) {
            return false;
        }
        String[] parts = data.split(":", -1);
        return parts.length > 1 && type.equals(parts[1]);
    }

    public static <T extends CommonEntity> void confirm(WorkflowDataBag bag, String type, String adminChat,
                                                       Function<UUID, Optional<T>> lookup, Consumer<T> complete) {
        handle(bag, type, "confirm", lookup, (bot, query, preview, item) -> {
            String label = label(type);
            bot.execute(SendPhoto.builder()
                .chatId(adminChat)
                .photo(new InputFile(preview.getPhoto().getLast().getFileId()))
                .caption("%s id <code>%s</code> aguardando aprovação.%nEnviado por %s".formatted(label,
                    item.getId(), TelegramUtils.usernameOrFullName(query.getFrom())))
                .parseMode("HTML")
                .build());
            complete.accept(item);
            bot.execute(EditMessageReplyMarkup.builder()
                .chatId(preview.getChatId())
                .messageId(preview.getMessageId())
                .replyMarkup(null)
                .build());
        }, label(type) + " enviado para aprovação.");
    }

    public static <T extends CommonEntity> void cancel(WorkflowDataBag bag, String type,
                                                      Function<UUID, Optional<T>> lookup, Consumer<T> delete) {
        handle(bag, type, "cancel", lookup, (bot, query, preview, item) -> {
            delete.accept(item);
            bot.execute(DeleteMessage.builder()
                .chatId(preview.getChatId())
                .messageId(preview.getMessageId())
                .build());
        }, label(type) + " deletado.");
    }

    private static <T extends CommonEntity> void handle(WorkflowDataBag bag, String type, String action,
                                                        Function<UUID, Optional<T>> lookup,
                                                        CallbackEffect<T> effect, String success) {
        Update update = bag.get(WorkflowDataKey.TELEGRAM_UPDATE, Update.class);
        RegataSimulatorBot bot = bag.get(WorkflowDataKey.REGATA_SIMULATOR_BOT, RegataSimulatorBot.class);
        if (update == null || !update.hasCallbackQuery() || bot == null) {
            return;
        }
        CallbackQuery query = update.getCallbackQuery();
        Optional<UUID> id = parseId(query.getData(), type, action);
        if (id.isEmpty() || !isAccessible(query)) {
            acknowledge(bot, query, REJECTED);
            return;
        }
        synchronized (ITEM_LOCKS[Math.floorMod(id.get().hashCode(), ITEM_LOCKS.length)]) {
            handleAuthorized(bot, new CallbackRequest(query, type, action, id.get()), lookup, effect, success);
        }
    }

    private static <T extends CommonEntity> void handleAuthorized(RegataSimulatorBot bot, CallbackRequest request,
                                                                 Function<UUID, Optional<T>> lookup,
                                                                 CallbackEffect<T> effect, String success) {
        CallbackQuery query = request.query();
        try {
            Optional<T> item = lookup.apply(request.itemId());
            Message preview = (Message) query.getMessage();
            if (item.isEmpty() || !isAuthorized(item.get(), query, preview)
                || ("confirm".equals(request.action()) && !hasPhoto(preview))) {
                acknowledge(bot, query, REJECTED);
                return;
            }
            effect.apply(bot, query, preview, item.get());
        } catch (TelegramApiException | RuntimeException e) {
            log.error("Unable to process {} review callback for {}", request.type(), request.itemId(), e);
            acknowledge(bot, query, REJECTED);
            return;
        }
        acknowledge(bot, query, success);
    }

    private static Optional<UUID> parseId(String data, String type, String action) {
        if (data == null) {
            return Optional.empty();
        }
        String[] parts = data.split(":", -1);
        if (parts.length != 3 || !type.equals(parts[1]) || !action.equals(parts[2])) {
            return Optional.empty();
        }
        try {
            UUID id = UUID.fromString(parts[0]);
            return id.toString().equalsIgnoreCase(parts[0]) ? Optional.of(id) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static boolean isAccessible(CallbackQuery query) {
        return query.getId() != null && !query.getId().isBlank()
            && query.getFrom() != null && query.getFrom().getId() != null
            && query.getMessage() instanceof Message preview
            && preview.getChat() != null && preview.getChatId() != null
            && preview.getMessageId() != null && preview.getMessageId() > 0;
    }

    private static boolean isAuthorized(CommonEntity item, CallbackQuery query, Message preview) {
        Message original = item.getMessage();
        return item.getStatus() == Status.REVIEW
            && original != null && original.getFrom() != null && original.getFrom().getId() != null
            && original.getFrom().getId().equals(query.getFrom().getId())
            && original.getChat() != null && preview.getChatId().equals(original.getChatId())
            && preview.getChatId().equals(item.getPreviewChatId())
            && preview.getMessageId().equals(item.getPreviewMessageId());
    }

    private static boolean hasPhoto(Message preview) {
        return preview.getPhoto() != null && !preview.getPhoto().isEmpty()
            && preview.getPhoto().getLast() != null && preview.getPhoto().getLast().getFileId() != null
            && !preview.getPhoto().getLast().getFileId().isBlank();
    }

    private static void acknowledge(RegataSimulatorBot bot, CallbackQuery query, String text) {
        if (query.getId() == null || query.getId().isBlank()) {
            return;
        }
        try {
            bot.execute(AnswerCallbackQuery.builder().callbackQueryId(query.getId()).text(text).build());
        } catch (TelegramApiException e) {
            log.warn("Unable to acknowledge review callback", e);
        }
    }

    private static String label(String type) {
        return "source".equals(type) ? "Source" : "Template";
    }

    private record CallbackRequest(CallbackQuery query, String type, String action, UUID itemId) {
    }

    @FunctionalInterface
    private interface CallbackEffect<T extends CommonEntity> {
        void apply(RegataSimulatorBot bot, CallbackQuery query, Message preview, T item) throws TelegramApiException;
    }
}