package com.boatarde.regatasimulator.flows.simulator;

import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowDataKey;
import com.boatarde.regatasimulator.flows.WorkflowStep;
import com.boatarde.regatasimulator.flows.WorkflowStepRegistration;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.stream.Stream;

@Slf4j
@WorkflowStepRegistration(WorkflowAction.GET_RANDOM_TEMPLATE)
public class GetRandomTemplateStep implements WorkflowStep {

    @Override
    public WorkflowAction run(WorkflowDataBag bag) {
        Path templatesDir;
        try {
            ClassLoader classLoader = GetRandomTemplateStep.class.getClassLoader();
            templatesDir = Paths.get(Objects.requireNonNull(classLoader.getResource("templates")).toURI());
        } catch (URISyntaxException e) {
            log.error(e.getLocalizedMessage(), e);
            return WorkflowAction.NONE;
        }
        try (Stream<Path> directoriesStream = Files.list(templatesDir)) {
            List<Path> directories = directoriesStream
                .filter(Files::isDirectory)
                .toList();
            if (directories.isEmpty()) {
                System.out.println("No template directory.");
                return WorkflowAction.NONE;
            }

            Random random = new Random();
            Path selectedDirectory = directories.get(random.nextInt(directories.size()));

            log.info("Diretório selecionado: {}", selectedDirectory.getFileName());

            bag.put(WorkflowDataKey.TEMPLATE_DIRECTORY, selectedDirectory);
            return WorkflowAction.GET_RANDOM_SOURCE;
        } catch (IOException e) {
            log.error(e.getLocalizedMessage(), e);
            return WorkflowAction.NONE;
        }

    }
}
