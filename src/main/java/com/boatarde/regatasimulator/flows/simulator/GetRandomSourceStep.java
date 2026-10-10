package com.boatarde.regatasimulator.flows.simulator;

import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.application.ApplicationFailure;
import static com.boatarde.regatasimulator.application.ApplicationFailure.Kind.UNAVAILABLE;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowDataKey;
import com.boatarde.regatasimulator.flows.WorkflowStep;
import com.boatarde.regatasimulator.flows.WorkflowStepRegistration;
import com.boatarde.regatasimulator.models.Meme;
import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.models.Template;
import com.boatarde.regatasimulator.models.TemplateArea;
import com.boatarde.regatasimulator.util.FileUtils;
import com.boatarde.regatasimulator.util.JsonDBUtils;
import com.boatarde.regatasimulator.util.JxQueryBuilder;
import io.jsondb.JsonDBTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@WorkflowStepRegistration(WorkflowAction.GET_RANDOM_SOURCE)
public class GetRandomSourceStep implements WorkflowStep {

    private final String sourcesPathString;
    private final JsonDBTemplate jsonDBTemplate;
    private final Clock clock;

    public GetRandomSourceStep(String sourcesPathString, JsonDBTemplate jsonDBTemplate) {
        this(sourcesPathString, jsonDBTemplate, Clock.system(ZoneId.of("America/Sao_Paulo")));
    }

    @Autowired
    public GetRandomSourceStep(@Value("${regata-simulator.sources.path}") String sourcesPathString,
                               JsonDBTemplate jsonDBTemplate, Clock clock) {
        this.sourcesPathString = sourcesPathString;
        this.jsonDBTemplate = jsonDBTemplate;
        this.clock = clock;
    }

    @Override
    public WorkflowAction run(WorkflowDataBag bag) {
        Template template = bag.getGeneric(WorkflowDataKey.TEMPLATE, Template.class);
        int required = (int) template.getAreas().stream().map(TemplateArea::getSource).distinct().count();
        if (required == 0) {
            throw new ApplicationFailure(UNAVAILABLE, "Template has no source slots");
        }
        Path sourcesDirectory = Paths.get(sourcesPathString);

        JxQueryBuilder jxQueryBuilder = JsonDBUtils.jxQuery()
            .withStatus(Status.APPROVED);

        addBirthdayFilter(jxQueryBuilder);

        String query = jxQueryBuilder.build();
        String allApproved = JsonDBUtils.jxQuery().withStatus(Status.APPROVED).build();
        List<Source> approvedSources = new ArrayList<>(jsonDBTemplate.find(query, Source.class));
        if (approvedSources.size() < required && !query.equals(allApproved)) {
            approvedSources = new ArrayList<>(jsonDBTemplate.find(allApproved, Source.class));
        }
        if (approvedSources.size() < required) {
            throw new ApplicationFailure(UNAVAILABLE, "Insufficient approved sources for template slots");
        }

        // remove sources that have already been used from pool
        List<Meme> memesHistory = bag.getGeneric(WorkflowDataKey.MEMES_HISTORY, List.class, Meme.class);
        if (memesHistory == null) {
            memesHistory = jsonDBTemplate.findAll(Meme.class).stream()
                .sorted(JsonDBUtils.getMemeComparator().reversed())
                .toList();
            bag.put(WorkflowDataKey.MEMES_HISTORY, memesHistory);
        }
        JsonDBUtils.excludeRecent(approvedSources, memesHistory.stream()
            .filter(meme -> meme.getSourceIds() != null).flatMap(meme -> meme.getSourceIds().stream()), required);

        List<Source> sources = JsonDBUtils.selectSourcesWithWeight(approvedSources, required);
        List<Path> sourceFiles = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            Source source = sources.get(i);
            Path selectedDirectory = sourcesDirectory.resolve(source.getId().toString());
            Optional<Path> fileOpt = FileUtils.getFirstExistingFile(selectedDirectory, "source.jpg",
                "source.jpeg", "source.png");
            if (fileOpt.isEmpty()) {
                throw new ApplicationFailure(UNAVAILABLE, "Source media unavailable");
            }
            Path sourceFile = fileOpt.get();
            sourceFiles.add(i, sourceFile);
            log.info("Source {} - {}", i + 1, source.getId());
        }

        bag.put(WorkflowDataKey.SOURCES, sources);
        bag.put(WorkflowDataKey.SOURCE_FILES, sourceFiles);
        return WorkflowAction.BUILD_MEME_STEP;
    }

    private void addBirthdayFilter(JxQueryBuilder jxQueryBuilder) {
        ZonedDateTime today = ZonedDateTime.now(clock.withZone(ZoneId.of("America/Sao_Paulo")));
        if (today.getMonth().equals(Month.JANUARY) && today.getDayOfMonth() == 22) {
            jxQueryBuilder.withDescription("brenda");
        } else if (today.getMonth().equals(Month.APRIL)) {
            if (today.getDayOfMonth() == 3) {
                jxQueryBuilder.withDescription("ander");
            } else if (today.getDayOfMonth() == 14) {
                jxQueryBuilder.withDescription("gab");
            } else if (today.getDayOfMonth() == 30) {
                jxQueryBuilder.withDescription("gui");
            }
        } else if (today.getMonth().equals(Month.MAY) && today.getDayOfMonth() == 27) {
            jxQueryBuilder.withDescription("xxk", "gayzito");
        } else if (today.getMonth().equals(Month.JUNE) && today.getDayOfMonth() == 10) {
            jxQueryBuilder.withDescription("lucas", "c4", "celta");
        } else if (today.getMonth().equals(Month.AUGUST) && today.getDayOfMonth() == 12) {
            jxQueryBuilder.withDescription("valb", "punhet");
        } else if (today.getMonth().equals(Month.OCTOBER) && today.getDayOfMonth() == 25) {
            jxQueryBuilder.withDescription("dedey");
        }
    }
}
