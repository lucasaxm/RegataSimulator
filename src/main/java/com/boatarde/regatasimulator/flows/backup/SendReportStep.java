package com.boatarde.regatasimulator.flows.backup;

import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowDataKey;
import com.boatarde.regatasimulator.flows.WorkflowStep;
import com.boatarde.regatasimulator.flows.WorkflowStepRegistration;
import com.boatarde.regatasimulator.models.Author;
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
        // Retrieve the data from the database
        List<Template> templates = jsonDBTemplate.findAll(Template.class);
        List<Source> sources = jsonDBTemplate.findAll(Source.class);
        List<Author> authors = jsonDBTemplate.findAll(Author.class);

        StringBuilder builder = new StringBuilder();

        // Header
        builder.append("<b>📊 Relatório Geral</b>\n");

        // Overall summary section
        builder.append("<b>Resumo:</b>\n");
        builder.append("• Templates: ").append(templates.size()).append("\n");
        builder.append("• Sources: ").append(sources.size()).append("\n");
        builder.append("• Autores: ").append(authors.size()).append("\n\n");

        // Detailed section for templates per user
        builder.append("<b>📝 Templates</b>\n");
        List<Map.Entry<Author, Long>> authorTemplateCounts = authors.stream()
            .map(author -> Map.entry(author, templates.stream()
                .filter(template -> template.getMessage() != null &&
                    template.getMessage().getFrom().getId().equals(author.getId()))
                .count()))
            .sorted(Map.Entry.<Author, Long>comparingByValue().reversed())
            .toList();

        int rank = 1;
        for (Map.Entry<Author, Long> entry : authorTemplateCounts) {
            builder.append(String.format("%d. <b>%s</b>: %d templates.%n",
                rank++, JsonDBUtils.usernameOrFullName(entry.getKey()), entry.getValue()));
        }

        // Detailed section for sources per user
        builder.append("\n<b>🖼 Sources</b>\n");
        List<Map.Entry<Author, Long>> authorSourceCounts = authors.stream()
            .map(author -> Map.entry(author, sources.stream()
                .filter(source -> source.getMessage() != null &&
                    source.getMessage().getFrom().getId().equals(author.getId()))
                .count()))
            .filter(authorSourceEntry -> authorSourceEntry.getValue() > 0)
            .sorted(Map.Entry.<Author, Long>comparingByValue().reversed())
            .toList();

        rank = 1;
        for (Map.Entry<Author, Long> entry : authorSourceCounts) {
            builder.append(String.format("%d. <b>%s</b>: %d sources.%n",
                rank++, JsonDBUtils.usernameOrFullName(entry.getKey()), entry.getValue()));
        }

        return builder.toString();
    }

}
