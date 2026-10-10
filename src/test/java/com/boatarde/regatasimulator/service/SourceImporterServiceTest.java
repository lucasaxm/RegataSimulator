package com.boatarde.regatasimulator.service;

import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.repository.jsondb.JsonDbSourceRepository;
import com.boatarde.regatasimulator.adapter.media.FileMediaStorage;
import com.boatarde.regatasimulator.factory.ImageTestFactory;
import com.boatarde.regatasimulator.util.MediaValidation;
import com.boatarde.regatasimulator.application.ApplicationFailure;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.util.TelegramFileDownloader;
import com.opencsv.exceptions.CsvMalformedLineException;
import io.jsondb.JsonDBTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Phase 0: actual CSV parser and JsonDB; the downloader never makes network requests. */
@ExtendWith(MockitoExtension.class)
class SourceImporterServiceTest {

    private static final String HEADER = "nome,texto,tipo,conteudo\n";

    @TempDir
    private Path tempDir;
    @Mock
    private TelegramFileDownloader downloader;

    private JsonDBTemplate db;
    private Path sourceRoot;
    private SourceImporterService importer;

    @BeforeEach
    void setUp() throws IOException {
        Path databaseRoot = Files.createDirectories(tempDir.resolve("db"));
        sourceRoot = Files.createDirectories(tempDir.resolve("sources"));
        db = new JsonDBTemplate(databaseRoot.toString(), "com.boatarde.regatasimulator.models");
        // JsonDB 1.0.115 has no public close API; without listeners it starts no watcher.
        assertFalse(db.hasCollectionFileChangeListener());
        db.createCollection(Source.class);
        importer = new SourceImporterService(new JsonDbSourceRepository(db), downloader, new FileMediaStorage(sourceRoot.toString(), tempDir.resolve("templates").toString()), 37);
    }

    @Test
    void csvParserNormalizesHeadersTrimsFieldsAndImportsQuotedNameWithNullOrigin() throws Exception {
        placeholderDownloads();
        String csv = """
             Conteudo , TIPO , TEXTO , NOME\s
             file-1 ,PHOTO,unused," name, with comma "
            """;

        List<Source> created = importer.importFromCsv(csv);

        assertEquals(1, created.size());
        Source source = created.getFirst();
        assertEquals("name, with comma", source.getDescription());
        assertEquals(Status.REVIEW, source.getStatus());
        assertEquals(37, source.getWeight());
        assertNull(source.getMessage());
        assertPersistedWithImage(source, "file-1");
        verify(downloader).downloadTelegramPhoto("file-1", sourceRoot.resolve(source.getId().toString()));
    }

    @Test
    void existingDescriptionsAndNonPhotoTypesAreFilteredBeforeDownload() throws Exception {
        Source existing = existing("known");
        db.insert(List.of(existing), Source.class);
        placeholderDownloads();
        String csv = HEADER + """
            known,,photo,known-file
            video,,video,video-file
            document,,document,document-file
            new,,pHoTo,new-file
            """;

        List<Source> created = importer.importFromCsv(csv);

        assertEquals(List.of("new"), created.stream().map(Source::getDescription).toList());
        assertEquals(2, db.findAll(Source.class).size());
        assertEquals(Status.APPROVED, db.<Source>findById(existing.getId(), Source.class).getStatus());
        assertPersistedWithImage(created.getFirst(), "new-file");
        verify(downloader).downloadTelegramPhoto(eq("new-file"), any(Path.class));
        assertEquals(1, sourceDirectories().size());
    }

    @Test
    void entirelyFilteredCsvDoesNotDownloadOrCreateDirectories() throws Exception {
        db.insert(List.of(existing("known")), Source.class);

        List<Source> created = importer.importFromCsv(HEADER
            + "known,,photo,known-file\nwrong,,audio,audio-file\n");

        assertTrue(created.isEmpty());
        assertEquals(1, db.findAll(Source.class).size());
        assertTrue(sourceDirectories().isEmpty());
        verifyNoInteractions(downloader);
    }

    @Test
    void rowDownloadFailureAllowsPartialSuccessAndRemovesFailedDirectory() throws Exception {
        when(downloader.downloadTelegramPhoto(anyString(), any(Path.class))).thenAnswer(invocation -> {
            String fileId = invocation.getArgument(0);
            Path destination = invocation.getArgument(1);
            Path image = writePlaceholder(destination, fileId);
            if ("bad-file".equals(fileId)) {
                throw new IOException("controlled row failure");
            }
            return image;
        });

        List<Source> created = importer.importFromCsv(HEADER
            + "first,,photo,first-file\nfailed,,photo,bad-file\nlast,,photo,last-file\n");

        assertEquals(List.of("first", "last"), created.stream().map(Source::getDescription).toList());
        assertEquals(2, db.findAll(Source.class).size());
        assertPersistedWithImage(created.get(0), "first-file");
        assertPersistedWithImage(created.get(1), "last-file");
        ArgumentCaptor<Path> directories = ArgumentCaptor.forClass(Path.class);
        InOrder order = inOrder(downloader);
        order.verify(downloader).downloadTelegramPhoto(eq("first-file"), directories.capture());
        order.verify(downloader).downloadTelegramPhoto(eq("bad-file"), directories.capture());
        order.verify(downloader).downloadTelegramPhoto(eq("last-file"), directories.capture());
        Path failedDirectory = directories.getAllValues().get(1);
        assertFalse(Files.exists(failedDirectory));
        assertFalse(created.stream().anyMatch(source ->
            source.getId().toString().equals(failedDirectory.getFileName().toString())));
        assertEquals(2, sourceDirectories().size());
    }

    @Test
    void duplicateDescriptionsWithinOneBatchCreateOnlyOneSource() throws Exception {
        placeholderDownloads();

        List<Source> created = importer.importFromCsv(HEADER
            + "duplicate,,photo,file-1\nduplicate,,photo,file-2\n");

        assertEquals(1, created.size());
        assertEquals(List.of("duplicate"), created.stream().map(Source::getDescription).toList());
        assertEquals(1, db.findAll(Source.class).size());
        assertPersistedWithImage(created.get(0), "file-1");
        assertEquals(1, sourceDirectories().size());
        verify(downloader).downloadTelegramPhoto(anyString(), any(Path.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "nome,texto,tipo,conteudo\n", "\n"})
    void emptyOrHeaderOnlyCsvReturnsNoSources(String csv) throws Exception {
        assertTrue(importer.importFromCsv(csv).isEmpty());
        assertTrue(db.findAll(Source.class).isEmpty());
        assertTrue(sourceDirectories().isEmpty());
        verifyNoInteractions(downloader);
    }

    @Test
    void shortMalformedRowsAreSkippedWhileValidRowsStillImport() throws Exception {
        placeholderDownloads();

        List<Source> created = importer.importFromCsv(HEADER
            + "short,only,two\nvalid,,photo,file-1\n");

        assertEquals(List.of("valid"), created.stream().map(Source::getDescription).toList());
        assertPersistedWithImage(created.getFirst(), "file-1");
        assertEquals(1, db.findAll(Source.class).size());
        verify(downloader).downloadTelegramPhoto(eq("file-1"), any(Path.class));
    }

    @Test
    void unmatchedQuotePropagatesMalformedLineIOExceptionWithoutDownloads() throws Exception {
        // OpenCSV reports malformed lines as IOException; HTTP advice maps them to a safe 400.
        assertThrows(CsvMalformedLineException.class,
            () -> importer.importFromCsv(HEADER + "\"unterminated,,photo,file-1"));

        assertTrue(db.findAll(Source.class).isEmpty());
        assertTrue(sourceDirectories().isEmpty());
        verifyNoInteractions(downloader);
    }

    @Test
    void missingRequiredHeadersRejectCsvBeforeDownloads() throws Exception {
        assertThrows(IOException.class, () -> importer.importFromCsv("name,text,kind,file\nname,,photo,file-1\n"));
        assertTrue(db.findAll(Source.class).isEmpty());
        assertTrue(sourceDirectories().isEmpty());
        verifyNoInteractions(downloader);
    }

    @Test
    void batchInsertFailurePropagatesAndRemovesUncommittedDownloadedImages() throws Exception {
        // Only this failure-control case substitutes the DB; normal imports above use actual JsonDB.
        JsonDBTemplate failingDb = mock(JsonDBTemplate.class);
        when(failingDb.find(anyString(), eq(Source.class))).thenReturn(List.of());
        IllegalStateException failure = new IllegalStateException("controlled insert failure");
        doThrow(failure).when(failingDb).insert(anyList(), eq(Source.class));
        placeholderDownloads();
        SourceImporterService failingImporter = new SourceImporterService(
            new JsonDbSourceRepository(failingDb), downloader, new FileMediaStorage(sourceRoot.toString(), tempDir.resolve("templates").toString()), 37);

        assertThrows(ApplicationFailure.class, () -> failingImporter.importFromCsv(HEADER + "new,,photo,file-1\n"));

        verify(failingDb).insert(anyList(), eq(Source.class));
        List<Path> directories = sourceDirectories();
        assertTrue(directories.isEmpty());
    }

    private void placeholderDownloads() throws IOException {
        when(downloader.downloadTelegramPhoto(anyString(), any(Path.class))).thenAnswer(invocation ->
            writePlaceholder(invocation.getArgument(1), invocation.getArgument(0)));
    }

    private Path writePlaceholder(Path destination, String fileId) throws IOException {
        assertEquals(sourceRoot, destination.getParent());
        assertTrue(destination.startsWith(tempDir));
        assertTrue(Files.isDirectory(destination));
        return ImageTestFactory.image(destination.resolve("source.jpg"));
    }

    private void assertPersistedWithImage(Source source, String fileId) throws IOException {
        Source stored = db.findById(source.getId(), Source.class);
        assertEquals(source.getId(), stored.getId());
        assertEquals(source.getDescription(), stored.getDescription());
        assertEquals(Status.REVIEW, stored.getStatus());
        assertEquals(37, stored.getWeight());
        assertNull(stored.getMessage());
        assertEquals(new MediaValidation.Dimensions(400, 300),
            MediaValidation.image(sourceRoot.resolve(source.getId().toString()).resolve("source.jpg")));
    }

    @Test
    void typedReportIncludesInvalidSkippedFailedAndCreatedRows() throws Exception {
        when(downloader.downloadTelegramPhoto(anyString(), any(Path.class))).thenAnswer(invocation -> {
            Path target = invocation.getArgument(1);
            if (invocation.getArgument(0).equals("bad")) return Files.writeString(target.resolve("source.jpg"), "not image");
            return writePlaceholder(target, invocation.getArgument(0));
        });
        SourceImporterService.ImportReport report = importer.importReport(HEADER
            + "valid,,photo,good\nVALID,,photo,duplicate\nshort,only,two\nvideo,,video,file\nbad,,photo,bad\n");
        assertEquals(1, report.created().size());
        assertEquals(List.of(SourceImporterService.Outcome.CREATED, SourceImporterService.Outcome.SKIPPED,
            SourceImporterService.Outcome.FAILED, SourceImporterService.Outcome.SKIPPED,
            SourceImporterService.Outcome.FAILED), report.rows().stream().map(SourceImporterService.RowResult::outcome).toList());
        assertEquals(List.of(2, 3, 4, 5, 6), report.rows().stream().map(SourceImporterService.RowResult::row).toList());
        assertEquals(SourceImporterService.Reason.MEDIA_FAILURE, report.rows().getLast().reason());
        assertEquals(1, sourceDirectories().size());
    }

    @Test
    void normalizationIsLocaleIndependentAndToleratesHistoricalNullDescriptions() throws Exception {
        db.insert(List.of(existing("TITLE"), existing(null)), Source.class);
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            assertTrue(importer.importFromCsv(HEADER + "title,,photo,file\n").isEmpty());
        } finally {
            java.util.Locale.setDefault(previous);
        }
        verifyNoInteractions(downloader);
    }

    @Test
    void rowLimitAndDuplicateHeadersFailBeforeDownloads() {
        assertThrows(IOException.class, () -> importer.importFromCsv(HEADER + "name,,photo,file\n".repeat(1001)));
        assertThrows(IOException.class, () -> importer.importFromCsv("nome,texto,tipo,conteudo,NOME\n"));
        verifyNoInteractions(downloader);
    }

    @Test
    void partialBatchInsertIsCompensatedBeforeMediaCleanup() throws Exception {
        JsonDBTemplate partial = mock(JsonDBTemplate.class);
        when(partial.find(anyString(), eq(Source.class))).thenReturn(List.of());
        java.util.Map<UUID, Source> stored = new java.util.HashMap<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            List<Source> batch = invocation.getArgument(0);
            stored.put(batch.getFirst().getId(), batch.getFirst());
            throw new IllegalStateException("partial persistence");
        }).when(partial).insert(anyList(), eq(Source.class));
        when(partial.findById(any(UUID.class), eq(Source.class))).thenAnswer(invocation -> stored.get(invocation.getArgument(0)));
        when(partial.remove(any(Source.class), eq(Source.class))).thenAnswer(invocation -> {
            Source source = invocation.getArgument(0);
            assertTrue(Files.exists(sourceRoot.resolve(source.getId().toString()).resolve("source.jpg")));
            return stored.remove(source.getId());
        });
        placeholderDownloads();
        var report = new SourceImporterService(new JsonDbSourceRepository(partial), downloader, new FileMediaStorage(sourceRoot.toString(), tempDir.resolve("templates").toString()), 37)
            .importReport(HEADER + "first,,photo,a\nsecond,,photo,b\n");
        assertTrue(report.persistenceFailed());
        assertTrue(report.created().isEmpty());
        assertTrue(report.rows().stream().allMatch(row -> row.reason() == SourceImporterService.Reason.PERSISTENCE_FAILURE));
        assertTrue(stored.isEmpty());
        assertTrue(sourceDirectories().isEmpty());
    }

    private Source existing(String description) {
        Source source = new Source();
        source.setId(UUID.randomUUID());
        source.setDescription(description);
        source.setStatus(Status.APPROVED);
        source.setWeight(10);
        return source;
    }

    private List<Path> sourceDirectories() throws IOException {
        try (Stream<Path> children = Files.list(sourceRoot)) {
            return children.toList();
        }
    }
}