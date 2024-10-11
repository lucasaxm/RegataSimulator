package com.boatarde.regatasimulator.flows;

import com.boatarde.regatasimulator.models.Author;
import com.boatarde.regatasimulator.models.Meme;
import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.models.Template;
import com.boatarde.regatasimulator.util.JsonDBUtils;
import io.jsondb.JsonDBTemplate;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.List;
import java.util.Map;

@Slf4j
@WorkflowStepRegistration(WorkflowAction.SEND_REPORT_STEP)
public class SendReportStep implements WorkflowStep {

    private final JsonDBTemplate jsonDBTemplate;

    public SendReportStep(JsonDBTemplate jsonDBTemplate) {
        this.jsonDBTemplate = jsonDBTemplate;
    }

    @Override
    public WorkflowAction run(WorkflowDataBag bag) {
        Message replyToMessage = bag.get(WorkflowDataKey.MESSAGE_TO_REPLY, Message.class);
        if (replyToMessage == null) {
            log.info("Message to reply is null, using update message instead");
            Update update = bag.get(WorkflowDataKey.TELEGRAM_UPDATE, Update.class);
            if (update == null) {
                log.error("Update is null, cannot send report");
                return WorkflowAction.NONE;
            }
            replyToMessage = update.getMessage();
        }

        SendMessage sendMessage = SendMessage.builder()
            .chatId(replyToMessage.getChatId().toString())
            .replyToMessageId(replyToMessage.getMessageId())
            .parseMode("HTML")
            .allowSendingWithoutReply(true)
            .text(getReportText())
            .build();

        bag.put(WorkflowDataKey.SEND_MESSAGE, sendMessage);

        return WorkflowAction.SEND_MESSAGE_STEP;
    }

    private String getReportText() {
        StringBuilder builder = new StringBuilder();
        builder.append("Relatório:\n");

        List<Template> templates = jsonDBTemplate.findAll(Template.class);
        builder.append("%d templates.\n".formatted(templates.size()));

        List<Source> sources = jsonDBTemplate.findAll(Source.class);
        builder.append("%d sources.\n".formatted(sources.size()));

        List<Meme> memes = jsonDBTemplate.findAll(Meme.class);
        builder.append("%d memes.\n".formatted(memes.size()));

        List<Author> authors = jsonDBTemplate.findAll(Author.class);
        builder.append("%d autores.\n".formatted(authors.size()));
        builder.append("--------------------\n");
        // Create a list of authors with their template counts
        List<Map.Entry<Author, Long>> authorCounts = authors.stream()
            .map(author -> Map.entry(author, templates.stream()
                .filter(template -> template.getMessage() != null &&
                    template.getMessage().getFrom().getId().equals(author.getId()))
                .count()))
            .sorted(Map.Entry.<Author, Long>comparingByValue().reversed())
            .toList();

        // Append the sorted authors to the builder
        authorCounts.forEach(entry -> {
            Author author = entry.getKey();
            Long count = entry.getValue();
            builder.append("%s fez %d templates.\n".formatted(JsonDBUtils.usernameOrFullName(author), count));
        });
        return builder.toString();
    }
}
