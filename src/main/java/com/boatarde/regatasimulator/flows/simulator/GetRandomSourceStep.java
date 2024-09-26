package com.boatarde.regatasimulator.flows.simulator;

import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowDataKey;
import com.boatarde.regatasimulator.flows.WorkflowStep;
import com.boatarde.regatasimulator.flows.WorkflowStepRegistration;
import com.boatarde.regatasimulator.models.TemplateArea;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

@Slf4j
@WorkflowStepRegistration(WorkflowAction.GET_RANDOM_SOURCE)
public class GetRandomSourceStep implements WorkflowStep {

    private final String sourcesPathString;

    public GetRandomSourceStep(@Value("${regata-simulator.sources.path}") String sourcesPathString) {
        this.sourcesPathString = sourcesPathString;
    }

    @Override
    public WorkflowAction run(WorkflowDataBag bag) {
        List<TemplateArea> templateAreaList =
            bag.getGeneric(WorkflowDataKey.TEMPLATE_AREAS, List.class, TemplateArea.class);
        try {
            Path sourcesDir = Paths.get(sourcesPathString);
            // Listar todos os arquivos .jpg e .png dentro de "resources/templates" e seus subdiretórios
            try (Stream<Path> filesStream = Files.walk(sourcesDir)) {
                List<Path> imageFiles = new ArrayList<>(filesStream
                    .filter(Files::isRegularFile)  // Filtra apenas arquivos (não diretórios)
                    .filter(path -> {
                        String fileName = path.getFileName().toString().toLowerCase();
                        return fileName.endsWith(".jpg") || fileName.endsWith(".png");
                    })
                    .toList());

                if (imageFiles.isEmpty()) {
                    log.error("Nenhum arquivo de imagem encontrado.");
                    return WorkflowAction.NONE;
                }

                List<Path> sourceFiles = new ArrayList<>();

                Random random = new Random();
                for (TemplateArea templateArea : templateAreaList) {
                    if (imageFiles.isEmpty()) {
                        log.error("Nem todos os arquivos de imagem necessários foram encontrados.");
                        return WorkflowAction.NONE;
                    }
                    Path selectedFile = imageFiles.remove(random.nextInt(imageFiles.size()));

                    log.info("Area  {} - Arquivo selecionado: {}", templateArea.getIndex(), selectedFile.getFileName());

                    sourceFiles.add(selectedFile);
                }

                bag.put(WorkflowDataKey.SOURCE_FILES, sourceFiles);
                return WorkflowAction.BUILD_MEME_STEP;
            }
        } catch (IOException e) {
            log.error(e.getLocalizedMessage(), e);
        }
        return WorkflowAction.NONE;
    }
}
