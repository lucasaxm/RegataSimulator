package com.boatarde.regatasimulator.flows.simulator;

import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowDataKey;
import com.boatarde.regatasimulator.flows.WorkflowStep;
import com.boatarde.regatasimulator.flows.WorkflowStepRegistration;
import com.boatarde.regatasimulator.models.Picture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
        try {
            Path sourcesDir = Paths.get(sourcesPathString);
            // Listar todos os arquivos .jpg e .png dentro de "resources/templates" e seus subdiretórios
            try (Stream<Path> filesStream = Files.walk(sourcesDir)) {
                List<File> imageFiles = filesStream
                    .filter(Files::isRegularFile)  // Filtra apenas arquivos (não diretórios)
                    .filter(path -> {
                        String fileName = path.getFileName().toString().toLowerCase();
                        return fileName.endsWith(".jpg") || fileName.endsWith(".png");
                    })
                    .map(Path::toFile)  // Converte Path para File
                    .toList();

                if (imageFiles.isEmpty()) {
                    log.error("Nenhum arquivo de imagem encontrado.");
                    return WorkflowAction.NONE;
                }

                // Selecionar um arquivo de imagem aleatoriamente
                Random random = new Random();
                File selectedFile = imageFiles.get(random.nextInt(imageFiles.size()));

                log.info("Arquivo selecionado: {}", selectedFile.getName());

                bag.put(WorkflowDataKey.SOURCE_FILE, new Picture(selectedFile));
                return WorkflowAction.BUILD_MEME_STEP;
            }
        } catch (IOException e) {
            log.error(e.getLocalizedMessage(), e);
        }
        return WorkflowAction.NONE;
    }
}
