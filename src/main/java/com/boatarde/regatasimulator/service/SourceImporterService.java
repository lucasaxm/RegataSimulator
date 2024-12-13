package com.boatarde.regatasimulator.service;

import com.boatarde.regatasimulator.dto.SourceCsvRecord;
import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.util.TelegramFileDownloader;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;
import io.jsondb.JsonDBTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class SourceImporterService {

    private final JsonDBTemplate jsonDBTemplate;
    private final TelegramFileDownloader fileDownloader;
    private final String sourcesPathString;
    private final int initialWeight;

    public SourceImporterService(JsonDBTemplate jsonDBTemplate,
                                 TelegramFileDownloader fileDownloader,
                                 @Value("${regata-simulator.sources.path}") String sourcesPathString,
                                 @Value("${regata-simulator.sources.initial-weight}") int initialWeight) {
        this.jsonDBTemplate = jsonDBTemplate;
        this.fileDownloader = fileDownloader;
        this.sourcesPathString = sourcesPathString;
        this.initialWeight = initialWeight;
    }

    public List<Source> importFromCsv(String csvContent) throws Exception {
        List<SourceCsvRecord> records = parseCsv(csvContent);

        // Filter only 'photo' entries and those which do not exist yet
        List<SourceCsvRecord> filtered = records.stream()
            .filter(r -> "photo".equalsIgnoreCase(r.getTipo()))
            .filter(r -> !sourceExists(r.getNome()))
            .toList();

        List<Source> createdSources = new ArrayList<>();
        for (SourceCsvRecord record : filtered) {
            try {
                Source source = createSourceFromRecord(record);
                createdSources.add(source);
            } catch (Exception e) {
                log.error("Failed to create source for record {}. Error: {}", record.getNome(), e.getMessage(), e);
            }
        }

        return createdSources;
    }

    private Source createSourceFromRecord(SourceCsvRecord record) throws Exception {
        UUID uuid = UUID.randomUUID();
        Path newDir = Path.of(sourcesPathString, uuid.toString());
        Files.createDirectories(newDir);

        // Download the telegram photo
        fileDownloader.downloadTelegramPhoto(record.getConteudo(), newDir);

        // Create Source
        Source source = new Source();
        source.setId(uuid);
        source.setDescription(record.getNome());
        source.setWeight(initialWeight);
        source.setMessage(null);
        source.setStatus(Status.APPROVED);

        jsonDBTemplate.insert(source);
        return source;
    }

    private boolean sourceExists(String description) {
        String jxQuery = "/.[description='" + description.replace("'", "\\'") + "']";
        return !jsonDBTemplate.find(jxQuery, Source.class).isEmpty();
    }

    private List<SourceCsvRecord> parseCsv(String csvContent) throws Exception {
        try (CSVReader csvReader = new CSVReader(new StringReader(csvContent))) {
            List<String[]> lines = csvReader.readAll();

            if (lines.isEmpty()) {
                return Collections.emptyList();
            }

            // Assuming the first line is header: nome,texto,tipo,conteudo
            // Validate or skip this step if we trust format
            String[] header = lines.removeFirst();
            Map<String, Integer> headerMap = mapHeaders(header);

            List<SourceCsvRecord> records = new ArrayList<>();
            for (String[] row : lines) {
                if (row.length < 4) {
                    // skip malformed line
                    continue;
                }
                String nome = getField(row, headerMap, "nome");
                String texto = getField(row, headerMap, "texto");
                String tipo = getField(row, headerMap, "tipo");
                String conteudo = getField(row, headerMap, "conteudo");
                records.add(new SourceCsvRecord(nome, texto, tipo, conteudo));
            }
            return records;
        } catch (CsvException e) {
            log.error("Error parsing CSV: {}", e.getMessage(), e);
            throw new Exception("CSV parsing error", e);
        }
    }

    private Map<String, Integer> mapHeaders(String[] header) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            map.put(header[i].trim().toLowerCase(), i);
        }
        return map;
    }

    private String getField(String[] row, Map<String, Integer> headerMap, String fieldName) {
        Integer idx = headerMap.get(fieldName);
        return (idx != null && idx < row.length) ? row[idx].trim() : "";
    }
}
