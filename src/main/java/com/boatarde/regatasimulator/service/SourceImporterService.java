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
        log.info("Starting import of sources from CSV content");

        List<SourceCsvRecord> records = parseCsv(csvContent);
        log.info("Parsed {} records from CSV", records.size());

        List<SourceCsvRecord> filtered = records.stream()
            .filter(r -> "photo".equalsIgnoreCase(r.getTipo()))
            .filter(r -> !sourceExists(r.getNome()))
            .toList();
        log.info("Filtered {} records of type 'photo' and not already existing", filtered.size());

        List<Source> createdSources = new ArrayList<>();
        for (int i = 0; i < filtered.size(); i++) {
            SourceCsvRecord record = filtered.get(i);
            try {
                log.info("Processing record: {}/{}", i + 1, filtered.size());
                Source source = createSourceFromRecord(record);
                createdSources.add(source);
                log.info("Successfully created source: {}", source);
            } catch (Exception e) {
                log.error("Failed to create source for record {}. Error: {}", record.getNome(), e.getMessage(), e);
            }
        }

        if (!createdSources.isEmpty()) {
            log.info("Saving {} created sources to the database", createdSources.size());
            jsonDBTemplate.insert(createdSources, Source.class);
            log.info("Successfully saved all created sources");
        } else {
            log.info("No new sources were created");
        }

        log.info("Import process completed with {} new sources created", createdSources.size());
        return createdSources;
    }

    private Source createSourceFromRecord(SourceCsvRecord record) throws Exception {
        UUID uuid = UUID.randomUUID();
        Path newDir = Path.of(sourcesPathString, uuid.toString());
        Files.createDirectories(newDir);
        log.info("Created directory for source {}: {}", record.getNome(), newDir);

        fileDownloader.downloadTelegramPhoto(record.getConteudo(), newDir);
        log.info("Downloaded Telegram photo for source {} to directory: {}", record.getNome(), newDir);

        Source source = new Source();
        source.setId(uuid);
        source.setDescription(record.getNome());
        source.setWeight(initialWeight);
        source.setMessage(null);
        source.setStatus(Status.REVIEW);

        log.info("Created Source object for {}: {}", record.getNome(), source);
        return source;
    }

    private boolean sourceExists(String description) {
        log.debug("Checking if source with description '{}' already exists", description);
        String jxQuery = "/.[description='" + description.replace("'", "\\'") + "']";
        boolean exists = !jsonDBTemplate.find(jxQuery, Source.class).isEmpty();
        log.debug("Source with description '{}' exists: {}", description, exists);
        return exists;
    }

    private List<SourceCsvRecord> parseCsv(String csvContent) throws Exception {
        log.info("Parsing CSV content");
        try (CSVReader csvReader = new CSVReader(new StringReader(csvContent))) {
            List<String[]> lines = csvReader.readAll();

            if (lines.isEmpty()) {
                log.warn("CSV content is empty");
                return Collections.emptyList();
            }

            log.info("Read {} lines from CSV", lines.size());
            String[] header = lines.remove(0);
            log.debug("CSV Header: {}", String.join(",", header));
            Map<String, Integer> headerMap = mapHeaders(header);

            List<SourceCsvRecord> records = new ArrayList<>();
            for (String[] row : lines) {
                if (row.length < 4) {
                    log.warn("Skipping malformed CSV row: {}", (Object) row);
                    continue;
                }
                String nome = getField(row, headerMap, "nome");
                String texto = getField(row, headerMap, "texto");
                String tipo = getField(row, headerMap, "tipo");
                String conteudo = getField(row, headerMap, "conteudo");
                records.add(new SourceCsvRecord(nome, texto, tipo, conteudo));
            }
            log.info("Parsed {} valid records from CSV", records.size());
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
        log.debug("Mapped CSV headers: {}", map);
        return map;
    }

    private String getField(String[] row, Map<String, Integer> headerMap, String fieldName) {
        Integer idx = headerMap.get(fieldName);
        String value = (idx != null && idx < row.length) ? row[idx].trim() : "";
        log.debug("Extracted field '{}' from row: {}", fieldName, value);
        return value;
    }
}
