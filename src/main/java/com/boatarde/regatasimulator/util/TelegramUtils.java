package com.boatarde.regatasimulator.util;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendAnimation;
import org.telegram.telegrambots.meta.api.methods.send.SendAudio;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaBotMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.media.InputMedia;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaAnimation;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaAudio;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaDocument;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaVideo;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@UtilityClass
@Slf4j
public class TelegramUtils {
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Parses a Telegram Message to extract the content following a command.
     *
     * @param message  The Telegram message object.
     * @param command  The command to look for (e.g., "/test").
     * @param username The username of the bot (e.g., "bilubot").
     * @return The content of the message following the command, or null if the command is not found.
     */
    public static String extractCommandContent(Message message, String command, String username) {
        if (message == null || !message.isCommand()) {
            return null;
        }

        String text = message.getText().trim();
        String fullCommand = command + "@" + username;
        String regex = "(^" + command + "(\\s|$))|(^" + fullCommand + "(\\s|$))";

        if (text.matches(regex)) {
            return ""; // No additional text beyond the command
        } else if (text.startsWith(command + " ") || text.startsWith(fullCommand + " ")) {
            // Extract text following the command
            return text.replaceFirst(regex, "").trim();
        }

        return null; // Command not matched
    }

    public static SendMediaBotMethod<Message> inputMediaToSendMedia(SendMediaGroup sendMediaGroup, int mediaIndex) {
        InputMedia inputMedia = sendMediaGroup.getMedias().get(mediaIndex);
        InputFile inputFile = new InputFile();
        inputFile.setMedia(inputMedia.getMedia());
        return switch (inputMedia) {
            case InputMediaPhoto inputMediaPhoto -> SendPhoto.builder()
                .chatId(sendMediaGroup.getChatId())
                .photo(inputFile)
                .allowSendingWithoutReply(sendMediaGroup.getAllowSendingWithoutReply())
                .replyToMessageId(sendMediaGroup.getReplyToMessageId())
                .messageThreadId(sendMediaGroup.getMessageThreadId())
                .caption(inputMediaPhoto.getCaption())
                .disableNotification(sendMediaGroup.getDisableNotification())
                .parseMode(inputMediaPhoto.getParseMode())
                .captionEntities(Optional.ofNullable(inputMediaPhoto.getCaptionEntities()).orElse(new ArrayList<>()))
                .protectContent(sendMediaGroup.getProtectContent())
                .hasSpoiler(inputMediaPhoto.getHasSpoiler())
                .replyParameters(sendMediaGroup.getReplyParameters())
                .build();
            case InputMediaVideo inputMediaVideo -> SendVideo.builder()
                .chatId(sendMediaGroup.getChatId())
                .messageThreadId(sendMediaGroup.getMessageThreadId())
                .video(inputFile)
                .duration(inputMediaVideo.getDuration())
                .caption(inputMediaVideo.getCaption())
                .width(inputMediaVideo.getWidth())
                .height(inputMediaVideo.getHeight())
                .supportsStreaming(inputMediaVideo.getSupportsStreaming())
                .disableNotification(sendMediaGroup.getDisableNotification())
                .replyToMessageId(sendMediaGroup.getReplyToMessageId())
                .parseMode(inputMediaVideo.getParseMode())
                .thumbnail(inputMediaVideo.getThumbnail())
                .captionEntities(Optional.ofNullable(inputMediaVideo.getCaptionEntities()).orElse(new ArrayList<>()))
                .allowSendingWithoutReply(sendMediaGroup.getAllowSendingWithoutReply())
                .protectContent(sendMediaGroup.getProtectContent())
                .hasSpoiler(inputMediaVideo.getHasSpoiler())
                .replyParameters(sendMediaGroup.getReplyParameters())
                .build();
            case InputMediaAudio inputMediaAudio -> SendAudio.builder()
                .chatId(sendMediaGroup.getChatId())
                .messageThreadId(sendMediaGroup.getMessageThreadId())
                .audio(inputFile)
                .replyToMessageId(sendMediaGroup.getReplyToMessageId())
                .disableNotification(sendMediaGroup.getDisableNotification())
                .performer(inputMediaAudio.getPerformer())
                .title(inputMediaAudio.getTitle())
                .caption(inputMediaAudio.getCaption())
                .parseMode(inputMediaAudio.getParseMode())
                .duration(inputMediaAudio.getDuration())
                .thumbnail(inputMediaAudio.getThumbnail())
                .captionEntities(Optional.ofNullable(inputMediaAudio.getCaptionEntities()).orElse(new ArrayList<>()))
                .allowSendingWithoutReply(sendMediaGroup.getAllowSendingWithoutReply())
                .protectContent(sendMediaGroup.getProtectContent())
                .replyParameters(sendMediaGroup.getReplyParameters())
                .build();
            case InputMediaAnimation inputMediaAnimation -> SendAnimation.builder()
                .chatId(sendMediaGroup.getChatId())
                .messageThreadId(sendMediaGroup.getMessageThreadId())
                .animation(inputFile)
                .duration(inputMediaAnimation.getDuration())
                .caption(inputMediaAnimation.getCaption())
                .width(inputMediaAnimation.getWidth())
                .height(inputMediaAnimation.getHeight())
                .disableNotification(sendMediaGroup.getDisableNotification())
                .replyToMessageId(sendMediaGroup.getReplyToMessageId())
                .parseMode(inputMediaAnimation.getParseMode())
                .thumbnail(inputMediaAnimation.getThumbnail())
                .captionEntities(
                    Optional.ofNullable(inputMediaAnimation.getCaptionEntities()).orElse(new ArrayList<>()))
                .allowSendingWithoutReply(sendMediaGroup.getAllowSendingWithoutReply())
                .protectContent(sendMediaGroup.getProtectContent())
                .hasSpoiler(inputMediaAnimation.getHasSpoiler())
                .replyParameters(sendMediaGroup.getReplyParameters())
                .build();
            case InputMediaDocument inputMediaDocument -> SendDocument.builder()
                .chatId(sendMediaGroup.getChatId())
                .messageThreadId(sendMediaGroup.getMessageThreadId())
                .document(inputFile)
                .caption(inputMediaDocument.getCaption())
                .disableNotification(sendMediaGroup.getDisableNotification())
                .replyToMessageId(sendMediaGroup.getReplyToMessageId())
                .parseMode(inputMediaDocument.getParseMode())
                .thumbnail(inputMediaDocument.getThumbnail())
                .captionEntities(Optional.ofNullable(inputMediaDocument.getCaptionEntities()).orElse(new ArrayList<>()))
                .allowSendingWithoutReply(sendMediaGroup.getAllowSendingWithoutReply())
                .disableContentTypeDetection(inputMediaDocument.getDisableContentTypeDetection())
                .protectContent(sendMediaGroup.getProtectContent())
                .replyParameters(sendMediaGroup.getReplyParameters())
                .build();
            default -> throw new IllegalStateException("Unexpected value: " + inputMedia);
        };
    }

    public static Message executeSendMediaBotMethod(TelegramLongPollingBot bot,
                                                    SendMediaBotMethod<Message> mediaBotMethod)
        throws TelegramApiException {
        return switch (mediaBotMethod) {
            case SendPhoto sendPhoto -> bot.execute(sendPhoto);
            case SendVideo sendVideo -> bot.execute(sendVideo);
            case SendAudio sendAudio -> bot.execute(sendAudio);
            case SendAnimation sendAnimation -> bot.execute(sendAnimation);
            case SendDocument sendDocument -> bot.execute(sendDocument);
            default -> throw new IllegalStateException("Unexpected value: " + mediaBotMethod);
        };
    }

    /**
     * Downloads a file from Telegram and saves it to the specified directory.
     *
     * @param bot       The Telegram bot instance.
     * @param fileId    The ID of the file to download.
     * @param directory The directory to save the downloaded file.
     * @param fileName  The name to give the downloaded file.
     * @return The path to the downloaded file.
     * @throws TelegramApiException If there is an error with the Telegram API.
     * @throws IOException          If there is an error reading or writing the file.
     */
    public static Path downloadTelegramFile(RegataSimulatorBot bot, String fileId, Path directory,
                                            String fileName) throws TelegramApiException, IOException {
        Path tmpFilePath = null;
        try {
            GetFile getFile = GetFile.builder().fileId(fileId).build();
            String filePath = bot.execute(getFile).getFilePath();
            File file = bot.downloadFile(filePath);
            if (!FileUtils.isImage(file)) {
                throw new RuntimeException("File is not an image");
            }

            Path newFile = directory.resolve(fileName);
            tmpFilePath = file.toPath();
            return Files.copy(tmpFilePath, newFile);
        } catch (TelegramApiException | IOException e) {
            log.error("Failed to download file {} from Telegram", fileId, e);
            throw e;
        } finally {
            if (tmpFilePath != null) {
                Files.deleteIfExists(tmpFilePath);
            }
        }
    }

    public static String toJson(Object o, boolean prettyPrint) {
        if (o == null) {
            return null;
        }
        try {
            if (prettyPrint) {
                return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(o);
            }
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            log.error(e.getLocalizedMessage(), e);
        }
        // fallback to string
        return o.toString();
    }

    public static String toJson(Object o) {
        return toJson(o, false);
    }

    public static <T> T fromJson(String str, Class<T> clazz) throws JsonProcessingException {
        return mapper.readValue(str, clazz);
    }

    public static void normalizeMediaGroupCaption(SendMediaGroup sendMediaGroup) {
        if (sendMediaGroup.getMedias().stream()
            .allMatch(inputMedia -> Objects.equals(inputMedia.getCaption(),
                sendMediaGroup.getMedias().getFirst().getCaption()))) {
            sendMediaGroup.getMedias().subList(1, sendMediaGroup.getMedias().size())
                .forEach(inputMedia -> inputMedia.setCaption(null));
        }
    }

    public static String usernameOrFullName(User from) {
        if (from.getUserName() != null && !from.getUserName().isEmpty()) {
            return "@" + from.getUserName();
        }
        String fullName = from.getFirstName();
        if (from.getLastName() != null && !from.getLastName().isEmpty()) {
            fullName += " " + from.getLastName();
        }
        return "<a href=\"tg://user?id=%d\">%s</a>".formatted(from.getId(), fullName);
    }

    public static UUID extractTemplateId(String data) {
        String[] parts = data.split(":");
        if (parts.length > 0) {
            return UUID.fromString(parts[0]);
        }
        return null;
    }
}
