package com.boatarde.regatasimulator.service;

import com.boatarde.regatasimulator.application.TelegramGateway;
import com.boatarde.regatasimulator.models.Author;
import com.boatarde.regatasimulator.models.CommonEntity;
import com.boatarde.regatasimulator.repository.AuthorRepository;
import com.boatarde.regatasimulator.repository.SourceRepository;
import com.boatarde.regatasimulator.repository.TemplateRepository;
import com.boatarde.regatasimulator.util.JsonDBUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class ReportService {
    private final SourceRepository sources;
    private final TemplateRepository templates;
    private final AuthorRepository authors;
    private final TelegramGateway telegram;

    public ReportService(SourceRepository sources, TemplateRepository templates, AuthorRepository authors,
                         TelegramGateway telegram) {
        this.sources = sources; this.templates = templates; this.authors = authors; this.telegram = telegram;
    }

    public void send(TelegramGateway.Destination destination) {
        telegram.sendText(new TelegramGateway.Text(destination, text(), true));
    }

    public String text() {
        var allTemplates = templates.find(TemplateRepository.Criteria.all());
        var allSources = sources.find(SourceRepository.Criteria.all());
        var allAuthors = authors.findAll();
        StringBuilder report = new StringBuilder("<b>📊 Relatório Geral</b>\n");
        report.append("• Templates: ").append(allTemplates.size()).append("\n");
        report.append("• Sources: ").append(allSources.size()).append("\n\n");
        report.append("<b>📝 Templates</b>\n");
        appendRanks(report, allAuthors, allTemplates, "templates", false);
        report.append("\n<b>🖼 Sources</b>\n");
        appendRanks(report, allAuthors, allSources, "sources", true);
        return report.toString();
    }

    private void appendRanks(StringBuilder report, List<Author> allAuthors, List<? extends CommonEntity> items,
                             String label, boolean excludeEmpty) {
        List<Map.Entry<Author, Long>> counts = allAuthors.stream().map(author -> Map.entry(author,
            items.stream().filter(item -> item.getMessage() != null && item.getMessage().getFrom() != null
                && author.getId().equals(item.getMessage().getFrom().getId())).count()))
            .filter(entry -> !excludeEmpty || entry.getValue() > 0)
            .sorted(Map.Entry.<Author, Long>comparingByValue().reversed()).toList();
        int rank = 1;
        for (var entry : counts) {
            report.append("%d. <b>%s</b>: %d %s.%n".formatted(rank++,
                JsonDBUtils.usernameOrFullName(entry.getKey()), entry.getValue(), label));
        }
    }
}