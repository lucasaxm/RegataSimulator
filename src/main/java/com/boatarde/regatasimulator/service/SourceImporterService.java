package com.boatarde.regatasimulator.service;

import com.boatarde.regatasimulator.dto.SourceCsvRecord;
import com.boatarde.regatasimulator.flows.ApplicationFailure;
import com.boatarde.regatasimulator.application.MediaStorage;
import com.boatarde.regatasimulator.util.MediaValidation;
import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.util.TelegramFileDownloader;
import com.opencsv.CSVReaderBuilder;
import com.boatarde.regatasimulator.repository.SourceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.StringReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.Locale;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class SourceImporterService {

    private final SourceRepository repository;
    private final TelegramFileDownloader fileDownloader;
    private final MediaStorage media;
    private final int initialWeight;

    public SourceImporterService(SourceRepository repository,
                                 TelegramFileDownloader fileDownloader,
                                 MediaStorage media,
                                 @Value("${regata-simulator.sources.initial-weight}") int initialWeight) {
        this.repository = repository;
        this.fileDownloader = fileDownloader;
        this.media = media;
        this.initialWeight = initialWeight;
    }

    public enum Outcome { CREATED, SKIPPED, FAILED }
    public enum Reason { NONE, NON_PHOTO, DUPLICATE, INVALID_ROW, MEDIA_FAILURE, PERSISTENCE_FAILURE }
    public record RowResult(int row, String name, Outcome outcome, Reason reason) { }
    public record ImportReport(List<Source> created, List<RowResult> rows, boolean persistenceFailed) { }
    private record CsvRow(int row, SourceCsvRecord record) { }

    public List<Source> importFromCsv(String csvContent) throws Exception {
        ImportReport report = importReport(csvContent);
        if (report.persistenceFailed()) {
            throw new ApplicationFailure(ApplicationFailure.Kind.EXECUTION, "Import persistence failed");
        }
        return report.created();
    }

    public ImportReport importReport(String csvContent) throws Exception {
        List<CsvRow> records = parseCsv(csvContent);
        List<RowResult> results = new ArrayList<>();
        List<Source> created = new ArrayList<>();
        Set<String> names = new HashSet<>();
        repository.find(SourceRepository.Criteria.all()).stream().map(Source::getDescription)
            .filter(java.util.Objects::nonNull).map(SourceImporterService::normalize).forEach(names::add);
        for (CsvRow row : records) {
            results.add(prepareRow(row, names, created));
        }
        boolean persistenceFailed = !persistBatch(created);
        if (persistenceFailed) {
            results.replaceAll(result -> result.outcome() == Outcome.CREATED
                ? new RowResult(result.row(), result.name(), Outcome.FAILED, Reason.PERSISTENCE_FAILURE) : result);
            created.clear();
        }
        return new ImportReport(List.copyOf(created), List.copyOf(results), persistenceFailed);
    }

    private RowResult prepareRow(CsvRow row, Set<String> names, List<Source> created) {
        SourceCsvRecord record = row.record();
        String name = record.getNome();
        if (name.isBlank() || record.getTipo().isBlank() || record.getConteudo().isBlank()) {
            return new RowResult(row.row(), name, Outcome.FAILED, Reason.INVALID_ROW);
        }
        if (!"photo".equalsIgnoreCase(record.getTipo())) {
            return new RowResult(row.row(), name, Outcome.SKIPPED, Reason.NON_PHOTO);
        }
        if (names.contains(normalize(name))) {
            return new RowResult(row.row(), name, Outcome.SKIPPED, Reason.DUPLICATE);
        }
        try {
            created.add(createSourceFromRecord(record));
            names.add(normalize(name));
            return new RowResult(row.row(), name, Outcome.CREATED, Reason.NONE);
        } catch (Exception e) {
            log.warn("Import row {} failed during media preparation", row.row());
            return new RowResult(row.row(), name, Outcome.FAILED, Reason.MEDIA_FAILURE);
        }
    }

    private boolean persistBatch(List<Source> created) {
        if (created.isEmpty()) {
            return true;
        }
        try {
            repository.insertImported(created);
            return true;
        } catch (RuntimeException e) {
            // Compensate metadata before files; retain media if removal cannot be confirmed.
            for (Source source : created) {
                Source stored = repository.findById(source.getId()).orElse(null);
                if (stored != null && !repository.remove(stored)) {
                    throw new ApplicationFailure(ApplicationFailure.Kind.EXECUTION,
                        "Import compensation failed; retained media requires reconciliation", e);
                }
                media.discardUncommitted(MediaStorage.Kind.SOURCE, source.getId());
            }
            return false;
        }
    }

    private Source createSourceFromRecord(SourceCsvRecord record) throws Exception {
        UUID uuid = UUID.randomUUID();
        try {
            Path newDir = media.prepare(MediaStorage.Kind.SOURCE, uuid);
            Path image = fileDownloader.downloadTelegramPhoto(record.getConteudo(), newDir);
            if (image == null || !image.normalize().getParent().equals(newDir.normalize())) {
                throw new IOException("Downloader returned no owned image");
            }
            MediaValidation.image(image);

            Source source = new Source();
            source.setId(uuid);
            source.setDescription(record.getNome());
            source.setWeight(initialWeight);
            source.setMessage(null);
            source.setStatus(Status.REVIEW);
            return source;
        } catch (Exception e) {
            media.discardUncommitted(MediaStorage.Kind.SOURCE, uuid);
            throw e;
        }
    }

    private static String normalize(String name) {
        return Normalizer.normalize(name.strip(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private List<CsvRow> parseCsv(String csvContent) throws Exception {
        if (csvContent == null || csvContent.length() > 1024 * 1024) {
            throw new IOException("CSV content exceeds limits or is absent");
        }
        if (csvContent.isBlank()) {
            return List.of();
        }
        try (var csvReader = new CSVReaderBuilder(new StringReader(csvContent)).withMultilineLimit(20).build()) {
            Map<String, Integer> headerMap = mapHeaders(csvReader.readNext());
            List<CsvRow> records = new ArrayList<>();
            String[] row;
            while ((row = csvReader.readNext()) != null) {
                if (records.size() >= 1000) {
                    throw new IOException("CSV row limit exceeded");
                }
                String nome = getField(row, headerMap, "nome");
                String texto = getField(row, headerMap, "texto");
                String tipo = getField(row, headerMap, "tipo");
                String conteudo = getField(row, headerMap, "conteudo");
                records.add(new CsvRow(records.size() + 2, new SourceCsvRecord(nome, texto, tipo, conteudo)));
            }
            return records;
        }
    }

    private Map<String, Integer> mapHeaders(String[] header) throws IOException {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            if (map.put(header[i].trim().toLowerCase(Locale.ROOT), i) != null) {
                throw new IOException("Duplicate CSV header");
            }
        }
        if (!map.keySet().containsAll(Set.of("nome", "texto", "tipo", "conteudo"))) {
            throw new IOException("Missing required CSV headers");
        }
        return map;
    }

    private String getField(String[] row, Map<String, Integer> headerMap, String fieldName) {
        Integer idx = headerMap.get(fieldName);
        String value = (idx != null && idx < row.length) ? row[idx].trim() : "";
        return value;
    }
}
