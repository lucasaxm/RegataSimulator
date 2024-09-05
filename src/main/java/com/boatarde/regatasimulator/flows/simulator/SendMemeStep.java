package com.boatarde.regatasimulator.flows.simulator;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowDataKey;
import com.boatarde.regatasimulator.flows.WorkflowStep;
import com.boatarde.regatasimulator.flows.WorkflowStepRegistration;
import com.boatarde.regatasimulator.models.Picture;
import com.boatarde.regatasimulator.util.TelegramUtils;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.UUID;

@Slf4j
@WorkflowStepRegistration(WorkflowAction.SEND_MEME_STEP)
public class SendMemeStep implements WorkflowStep {

    @Override
    public WorkflowAction run(WorkflowDataBag bag) {
        RegataSimulatorBot regataSimulatorBot = bag.get(WorkflowDataKey.REGATA_SIMULATOR_BOT, RegataSimulatorBot.class);
        Update update = bag.get(WorkflowDataKey.TELEGRAM_UPDATE, Update.class);
        Picture memePicture = bag.get(WorkflowDataKey.MEME_FILE, Picture.class);

        File memeFile = null;
        try {
            memeFile = saveOutput(memePicture);
            SendPhoto sendPhoto = SendPhoto.builder()
                .chatId(update.getMessage().getChatId())
                .photo(new InputFile(memeFile))
                .allowSendingWithoutReply(true)
                .replyToMessageId(update.getMessage().getMessageId())
                .messageThreadId(update.getMessage().getMessageThreadId())
                .build();
            Message response = TelegramUtils.executeSendMediaBotMethod(regataSimulatorBot, sendPhoto);

            log.info("Response: {}", TelegramUtils.toJson(response));
        } catch (TelegramApiException e) {
            log.error(String.format("TelegramApiException when sending media: %s", e.getMessage()), e);
            return WorkflowAction.NONE;
        } catch (IOException e) {
            log.error(String.format("IOException when sending media: %s", e.getMessage()), e);
            throw new RuntimeException(e);
        } finally {
            // Delete the temporary file when done
            if (memeFile != null && memeFile.exists()) {
                if (memeFile.delete()) {
                    log.info("Temporary file deleted.");
                } else {
                    log.error("Failed to delete the temporary file.");
                }
            }
        }

        return WorkflowAction.NONE;
    }

    private File saveOutput(Picture shitpost) throws IOException {
        File tempFile;
        // Create a temporary file
        tempFile = File.createTempFile(UUID.randomUUID().toString(), ".png");
        System.out.println("Temporary file created at: " + tempFile.getAbsolutePath());

        // Write data to the temporary file
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write("This is a temporary file.");
        }

        ImageIO.write(shitpost.getImage(), "png", tempFile);
        return tempFile;
    }
}
