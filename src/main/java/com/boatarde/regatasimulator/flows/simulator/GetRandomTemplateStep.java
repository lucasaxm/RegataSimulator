package com.boatarde.regatasimulator.flows.simulator;

import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowDataKey;
import com.boatarde.regatasimulator.flows.WorkflowStep;
import com.boatarde.regatasimulator.flows.WorkflowStepRegistration;
import com.boatarde.regatasimulator.models.Meme;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.models.Template;
import com.boatarde.regatasimulator.util.FileUtils;
import com.boatarde.regatasimulator.util.JsonDBUtils;
import io.jsondb.JsonDBTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@WorkflowStepRegistration(WorkflowAction.GET_RANDOM_TEMPLATE)
public class GetRandomTemplateStep implements WorkflowStep {

    private final String templatesPathString;
    private final JsonDBTemplate jsonDBTemplate;

    public GetRandomTemplateStep(@Value("${regata-simulator.templates.path}") String templatesPathString,
                                 JsonDBTemplate jsonDBTemplate) {
        this.templatesPathString = templatesPathString;
        this.jsonDBTemplate = jsonDBTemplate;
    }

    @Override
    public WorkflowAction run(WorkflowDataBag bag) {
        List<Template> approvedTemplates =
            jsonDBTemplate.find(JsonDBUtils.getJxQuery(Status.APPROVED, null), Template.class);
        if (approvedTemplates.isEmpty()) {
            log.error("No templates found.");
            return WorkflowAction.NONE;
        }
        List<Meme> memesHistory = bag.getGeneric(WorkflowDataKey.MEMES_HISTORY, List.class, Meme.class);
        if (memesHistory == null) {
            memesHistory = jsonDBTemplate.findAll(Meme.class).stream()
                .sorted(JsonDBUtils.getMemeComparator().reversed())
                .toList();
            bag.put(WorkflowDataKey.MEMES_HISTORY, memesHistory);
        }
        memesHistory.stream()
            .map(Meme::getTemplateId)
            .distinct()
            .limit(approvedTemplates.size() / 2)
            .forEach(templateId -> approvedTemplates.removeIf(template -> template.getId().equals(templateId)));

        Template template = JsonDBUtils.selectTemplatesWithWeight(approvedTemplates, 1).getFirst();
        Path selectedDirectory = Paths.get(templatesPathString).resolve(template.getId().toString());

        Optional<Path> fileOpt = FileUtils.getFirstExistingFile(selectedDirectory, "template.jpg", "template.png");
        if (fileOpt.isEmpty()) {
            log.error("Template file not found: {}", template.getId());
            return WorkflowAction.NONE;
        }
        Path templateFile = fileOpt.get();

        log.info("Template selecionado: {}", template.getId());

        bag.put(WorkflowDataKey.TEMPLATE_FILE, templateFile);
        bag.put(WorkflowDataKey.TEMPLATE, template);
        return WorkflowAction.GET_RANDOM_SOURCE;
    }
}
