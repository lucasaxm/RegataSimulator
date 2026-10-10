package com.boatarde.regatasimulator.flows.simulator;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowDataKey;
import com.boatarde.regatasimulator.flows.WorkflowStep;
import com.boatarde.regatasimulator.flows.WorkflowStepRegistration;
import com.boatarde.regatasimulator.models.Template;
import com.boatarde.regatasimulator.adapter.media.ImageMagickRenderer;
import com.boatarde.regatasimulator.application.ImageRenderer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

@Slf4j
@WorkflowStepRegistration(WorkflowAction.BUILD_MEME_STEP)
public class BuildMemeStep implements WorkflowStep {

    private final String magickPath;

    public BuildMemeStep(@Value("${magick.path}") String magickPath) {
        this.magickPath = magickPath;
    }

    @Override
    public WorkflowAction run(WorkflowDataBag bag) {
        List<Path> sourceFiles = bag.getGeneric(WorkflowDataKey.SOURCE_FILES, List.class, Path.class);
        Path templateFile = bag.get(WorkflowDataKey.TEMPLATE_FILE, Path.class);
        Template template = bag.get(WorkflowDataKey.TEMPLATE, Template.class);
        ImageRenderer renderer = new ImageMagickRenderer(magickPath) {
            @Override protected Process startProcess(ProcessBuilder builder) throws IOException {
                return BuildMemeStep.this.startProcess(builder);
            }
        };
        var image = renderer.render(new ImageRenderer.Request(templateFile, template.getAreas(), sourceFiles,
            progress -> editCreatingTemplateMessage(bag, progress)));
        bag.put(WorkflowDataKey.MEME_FILE, image.file());
        bag.put(WorkflowDataKey.RENDER_JOB_DIRECTORY, image.jobDirectory());
        return WorkflowAction.SEND_MEME_STEP;
    }

    private void editCreatingTemplateMessage(WorkflowDataBag bag, int progress) {
        Message creatingTemplateMessage = bag.get(WorkflowDataKey.CREATING_TEMPLATE_MESSAGE, Message.class);
        if (creatingTemplateMessage != null) {
            try {
                bag.get(WorkflowDataKey.REGATA_SIMULATOR_BOT, RegataSimulatorBot.class)
                    .execute(EditMessageText.builder()
                        .chatId(creatingTemplateMessage.getChatId())
                        .messageId(creatingTemplateMessage.getMessageId())
                        .text("Gerando meme de teste...%n<code>%s</code>".formatted(generateProgressBar(progress)))
                        .parseMode("HTML")
                        .build());
            } catch (TelegramApiException e) {
                log.error(e.getLocalizedMessage(), e);
            }
        }
    }

    private String generateProgressBar(int progress) {
        int totalBars = 20;
        int filledBars = (progress * totalBars) / 100;
        int emptyBars = totalBars - filledBars;
        return "[" + "=".repeat(filledBars) + " ".repeat(emptyBars) + "] " + progress + "%";
    }

    protected Process startProcess(ProcessBuilder builder) throws IOException {
        return builder.start();
    }
}
