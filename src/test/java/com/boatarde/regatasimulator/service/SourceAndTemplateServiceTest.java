package com.boatarde.regatasimulator.service;

import com.boatarde.regatasimulator.dto.SearchCriteria;
import com.boatarde.regatasimulator.models.GalleryResponse;
import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.models.Template;
import com.boatarde.regatasimulator.models.TemplateArea;
import io.jsondb.JsonDBTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.User;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Phase 0: real JsonDB metadata and isolated media, including current failure boundaries. */
class SourceAndTemplateServiceTest {

    @TempDir
    private Path tempDir;

    private JsonDBTemplate db;
    private Path sourceRoot;
    private Path templateRoot;
    private SourceService sources;
    private TemplateService templates;

    @BeforeEach
    void setUp() throws IOException {
        Path databaseRoot = Files.createDirectories(tempDir.resolve("db"));
        sourceRoot = Files.createDirectories(tempDir.resolve("sources"));
        templateRoot = Files.createDirectories(tempDir.resolve("templates"));
        db = new JsonDBTemplate(databaseRoot.toString(), "com.boatarde.regatasimulator.models");
        // JsonDB 1.0.115 has no public close API; without listeners it starts no watcher.
        assertFalse(db.hasCollectionFileChangeListener());
        db.createCollection(Source.class);
        db.createCollection(Template.class);
        sources = sourceService(db);
        templates = new TemplateService(templateRoot.toString(), db);
        ReflectionTestUtils.setField(templates, "initialWeight", 37);
    }

    @Test
    void sourceApprovalAndRejectionSaveRealMetadataAndPreserveNullOrigin() {
        Source approved = source("approved import", Status.REVIEW, null);
        Source rejected = source("imported", Status.REVIEW, null);
        db.insert(List.of(approved, rejected), Source.class);

        sources.approveSource(approved);
        assertEquals(Status.APPROVED, sources.getSource(approved.getId()).orElseThrow().getStatus());
        sources.rejectSource(rejected);

        Source stored = sources.getSource(rejected.getId()).orElseThrow();
        assertEquals(Status.REJECTED, stored.getStatus());
        assertEquals("imported", stored.getDescription());
        assertEquals(3, stored.getWeight());
        assertNull(stored.getMessage());
    }

    @Test
    void templateApprovalAndRejectionSaveRealMetadataAndPreserveAreas() {
        Template approved = template(Status.REVIEW, null);
        Template rejected = template(Status.REVIEW, null);
        TemplateArea area = TemplateArea.builder().index(1).source(2).build();
        approved.setAreas(List.of(area));
        rejected.setAreas(List.of(area));
        db.insert(List.of(approved, rejected), Template.class);

        templates.approveTemplate(approved);
        assertEquals(Status.APPROVED, templates.getTemplate(approved.getId()).orElseThrow().getStatus());
        templates.rejectTemplate(rejected);

        Template stored = templates.getTemplate(rejected.getId()).orElseThrow();
        assertEquals(Status.REJECTED, stored.getStatus());
        assertEquals(2, stored.getAreas().getFirst().getSource());
        assertEquals(3, stored.getWeight());
    }

    @Test
    void missingIdsReturnEmptyOptionals() {
        UUID missing = UUID.randomUUID();
        assertTrue(sources.getSource(missing).isEmpty());
        assertTrue(templates.getTemplate(missing).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"jpg", "jpeg", "png"})
    void imageLookupSupportsEachExistingExtension(String extension) throws Exception {
        Source source = source("image", Status.REVIEW, null);
        Template template = template(Status.REVIEW, null);
        Path sourceFile = media(sourceRoot, source.getId(), "source." + extension);
        Path templateFile = media(templateRoot, template.getId(), "template." + extension);

        assertEquals(sourceFile.toUri(), sources.loadSourceAsResource(source).getURI());
        assertEquals(templateFile.toUri(), templates.loadTemplateAsResource(template).getURI());
        assertTrue(sources.loadSourceAsResource(source).isReadable());
        assertTrue(templates.loadTemplateAsResource(template).isReadable());
    }

    @Test
    void imageLookupPrefersJpgThenJpegThenPngForBothServices() throws Exception {
        Source source = source("image", Status.REVIEW, null);
        Template template = template(Status.REVIEW, null);
        Path sourcePng = media(sourceRoot, source.getId(), "source.png");
        Path sourceJpeg = media(sourceRoot, source.getId(), "source.jpeg");
        Path sourceJpg = media(sourceRoot, source.getId(), "source.jpg");
        Path templatePng = media(templateRoot, template.getId(), "template.png");
        Path templateJpeg = media(templateRoot, template.getId(), "template.jpeg");
        Path templateJpg = media(templateRoot, template.getId(), "template.jpg");

        assertEquals(sourceJpg.toUri(), sources.loadSourceAsResource(source).getURI());
        assertEquals(templateJpg.toUri(), templates.loadTemplateAsResource(template).getURI());
        Files.delete(sourceJpg);
        Files.delete(templateJpg);
        assertEquals(sourceJpeg.toUri(), sources.loadSourceAsResource(source).getURI());
        assertEquals(templateJpeg.toUri(), templates.loadTemplateAsResource(template).getURI());
        Files.delete(sourceJpeg);
        Files.delete(templateJpeg);
        assertEquals(sourcePng.toUri(), sources.loadSourceAsResource(source).getURI());
        assertEquals(templatePng.toUri(), templates.loadTemplateAsResource(template).getURI());
    }

    @Test
    void missingImagesCurrentlyThrowRuntimeExceptions() {
        Source source = source("missing", Status.REVIEW, null);
        Template template = template(Status.REVIEW, null);

        assertEquals("Source not found: " + source.getId(),
            assertThrows(RuntimeException.class, () -> sources.loadSourceAsResource(source)).getMessage());
        assertEquals("Template not found: " + template.getId(),
            assertThrows(RuntimeException.class, () -> templates.loadTemplateAsResource(template)).getMessage());
    }

    @Test
    void sourceDeletionRemovesNestedMediaAndMetadataButPreservesSibling() throws Exception {
        Source source = source("delete", Status.REVIEW, null);
        Source sibling = source("keep", Status.REVIEW, null);
        db.insert(List.of(source, sibling), Source.class);
        media(sourceRoot, source.getId(), "nested/deeper/source.jpg");
        Path siblingFile = media(sourceRoot, sibling.getId(), "source.jpg");

        sources.deleteSource(source);

        assertFalse(Files.exists(sourceRoot.resolve(source.getId().toString())));
        assertTrue(sources.getSource(source.getId()).isEmpty());
        assertTrue(Files.exists(siblingFile));
        assertTrue(sources.getSource(sibling.getId()).isPresent());
    }

    @Test
    void templateDeletionRemovesNestedMediaAndMetadataButPreservesSibling() throws Exception {
        Template template = template(Status.REVIEW, null);
        Template sibling = template(Status.REVIEW, null);
        db.insert(List.of(template, sibling), Template.class);
        media(templateRoot, template.getId(), "nested/deeper/template.jpg");
        Path siblingFile = media(templateRoot, sibling.getId(), "template.jpg");

        templates.deleteTemplate(template);

        assertFalse(Files.exists(templateRoot.resolve(template.getId().toString())));
        assertTrue(templates.getTemplate(template.getId()).isEmpty());
        assertTrue(Files.exists(siblingFile));
        assertTrue(templates.getTemplate(sibling.getId()).isPresent());
    }

    @Test
    void missingMediaDirectoryCurrentlyPreventsSourceMetadataDeletionPhase1RecoveryGap() {
        Source source = source("missing files", Status.REVIEW, null);
        db.insert(List.of(source), Source.class);

        RuntimeException failure = assertThrows(RuntimeException.class, () -> sources.deleteSource(source));

        assertEquals("Failed to delete source: " + source.getId(), failure.getMessage());
        assertInstanceOf(IOException.class, failure.getCause());
        assertTrue(sources.getSource(source.getId()).isPresent());
    }

    @Test
    void missingMediaDirectoryCurrentlyPreventsTemplateMetadataDeletionPhase1RecoveryGap() {
        Template template = template(Status.REVIEW, null);
        db.insert(List.of(template), Template.class);

        RuntimeException failure = assertThrows(RuntimeException.class, () -> templates.deleteTemplate(template));

        assertEquals("Failed to delete template: " + template.getId(), failure.getMessage());
        assertInstanceOf(IOException.class, failure.getCause());
        assertTrue(templates.getTemplate(template.getId()).isPresent());
    }

    @Test
    void metadataRemovalFailureCurrentlyOccursAfterMediaDeletionPhase1AtomicityGap() throws Exception {
        // Controlled DB failures are mocked; all filesystem operations are still real and temporary.
        JsonDBTemplate failingDb = mock(JsonDBTemplate.class);
        Source source = source("delete", Status.REVIEW, null);
        Template template = template(Status.REVIEW, null);
        Path sourceFile = media(sourceRoot, source.getId(), "source.jpg");
        Path templateFile = media(templateRoot, template.getId(), "template.jpg");
        doAnswer(invocation -> {
            assertFalse(Files.exists(sourceFile.getParent()));
            throw new IllegalStateException("metadata removal failed");
        }).when(failingDb).remove(source, Source.class);
        doAnswer(invocation -> {
            assertFalse(Files.exists(templateFile.getParent()));
            throw new IllegalStateException("metadata removal failed");
        }).when(failingDb).remove(template, Template.class);

        SourceService failingSources = sourceService(failingDb);
        TemplateService failingTemplates = new TemplateService(templateRoot.toString(), failingDb);
        assertThrows(IllegalStateException.class, () -> failingSources.deleteSource(source));
        assertThrows(IllegalStateException.class,
            () -> failingTemplates.deleteTemplate(template));

        verify(failingDb).remove(source, Source.class);
        verify(failingDb).remove(template, Template.class);
        assertFalse(Files.exists(sourceFile));
        assertFalse(Files.exists(templateFile));
    }

    @Test
    void sourcePaginationOrdersByNewestOriginAndIncludesNullOriginLast() {
        Source imported = source("imported", Status.REVIEW, null);
        Source older = source("older", Status.REVIEW, message(100, 1L));
        Source newest = source("newest", Status.APPROVED, message(200, 2L));
        db.insert(List.of(imported, older, newest), Source.class);

        GalleryResponse<Source> first = sources.getSources(1, 2, null, null);
        assertEquals(3, first.getTotalItems());
        assertEquals(List.of(newest.getId(), older.getId()), first.getItems().stream().map(Source::getId).toList());
        assertEquals(List.of(imported.getId()),
            sources.getSources(2, 2, null, null).getItems().stream().map(Source::getId).toList());
        assertTrue(sources.getSources(3, 2, null, null).getItems().isEmpty());
        GalleryResponse<Source> filtered = sources.getSources(1, 12, Status.REVIEW, 1L);
        assertEquals(1, filtered.getTotalItems());
        assertEquals(older.getId(), filtered.getItems().getFirst().getId());
    }

    @Test
    void templatePaginationOrdersByNewestOriginAndFiltersStatusAndAuthor() {
        Template imported = template(Status.REVIEW, null);
        Template older = template(Status.REVIEW, message(100, 1L));
        Template newest = template(Status.APPROVED, message(200, 2L));
        db.insert(List.of(imported, older, newest), Template.class);

        GalleryResponse<Template> first = templates.getTemplates(1, 2, null, null);
        assertEquals(3, first.getTotalItems());
        assertEquals(List.of(newest.getId(), older.getId()), first.getItems().stream().map(Template::getId).toList());
        assertEquals(List.of(imported.getId()),
            templates.getTemplates(2, 2, null, null).getItems().stream().map(Template::getId).toList());
        assertTrue(templates.getTemplates(3, 2, null, null).getItems().isEmpty());
        GalleryResponse<Template> filtered = templates.getTemplates(1, 12, Status.REVIEW, 1L);
        assertEquals(1, filtered.getTotalItems());
        assertEquals(older.getId(), filtered.getItems().getFirst().getId());
    }

    @ParameterizedTest
    @CsvSource({"0,12", "-1,12", "1,-1"})
    void invalidPaginationCurrentlyThrowsInsteadOfValidationPhase1InputBug(int page, int perPage) {
        assertThrows(IllegalArgumentException.class, () -> sources.getSources(page, perPage, null, null));
        assertThrows(IllegalArgumentException.class, () -> templates.getTemplates(page, perPage, null, null));
        SearchCriteria criteria = criteria(page, perPage);
        assertThrows(IllegalArgumentException.class, () -> sources.search(criteria));
    }

    @Test
    void zeroPageSizeCurrentlyReturnsEmptyItemsWithUnchangedTotals() {
        db.insert(List.of(source("one", Status.REVIEW, null)), Source.class);
        db.insert(List.of(template(Status.REVIEW, null)), Template.class);

        assertEquals(1, sources.getSources(1, 0, null, null).getTotalItems());
        assertTrue(sources.getSources(1, 0, null, null).getItems().isEmpty());
        assertEquals(1, templates.getTemplates(1, 0, null, null).getTotalItems());
        assertTrue(templates.getTemplates(1, 0, null, null).getItems().isEmpty());
        assertEquals(1, sources.search(criteria(1, 0)).getTotalItems());
        assertTrue(sources.search(criteria(1, 0)).getItems().isEmpty());
    }

    @Test
    void sourceSearchFiltersCaseInsensitivelyAndUsesDateOrderBeforePagination() {
        Source older = source("ALPHA apple", Status.REVIEW, message(100, 1L));
        Source newer = source("alpha zebra", Status.REVIEW, message(200, 1L));
        Source approved = source("Alpha approved", Status.APPROVED, message(300, 1L));
        Source unnamed = source(null, Status.REVIEW, null);
        db.insert(List.of(older, newer, approved, unnamed), Source.class);
        SearchCriteria criteria = criteria(1, 1);
        criteria.setQuery("AlPhA");
        criteria.setStatus(Status.REVIEW);

        GalleryResponse<Source> first = sources.search(criteria);
        assertEquals(2, first.getTotalItems());
        assertEquals(newer.getId(), first.getItems().getFirst().getId());
        criteria.setPage(2);
        assertEquals(older.getId(), sources.search(criteria).getItems().getFirst().getId());
        criteria.setPage(1);
        criteria.setPerPage(12);
        criteria.setQuery("   ");
        assertEquals(3, sources.search(criteria).getTotalItems());
    }

    @Test
    void weightResetUsesInjectedValuesAndPreservesStatuses() {
        Source source = source("reset", Status.REJECTED, null);
        Template template = template(Status.APPROVED, null);
        db.insert(List.of(source), Source.class);
        db.insert(List.of(template), Template.class);

        sources.resetAllWeights();
        templates.resetAllWeights();

        assertEquals(37, sources.getSource(source.getId()).orElseThrow().getWeight());
        assertEquals(37, templates.getTemplate(template.getId()).orElseThrow().getWeight());
        assertEquals(Status.REJECTED, sources.getSource(source.getId()).orElseThrow().getStatus());
        assertEquals(Status.APPROVED, templates.getTemplate(template.getId()).orElseThrow().getStatus());
    }

    @Test
    void initializeSourceIdsPersistsAreaIndexesAsSourceNumbers() {
        Template template = template(Status.REVIEW, null);
        template.setAreas(List.of(TemplateArea.builder().index(1).source(7).build(),
            TemplateArea.builder().index(2).source(7).build()));
        db.insert(List.of(template), Template.class);

        templates.initializeSourceIds();

        assertEquals(List.of(1, 2), templates.getTemplate(template.getId()).orElseThrow()
            .getAreas().stream().map(TemplateArea::getSource).toList());
    }

    private SourceService sourceService(JsonDBTemplate database) {
        SourceService service = new SourceService(database);
        ReflectionTestUtils.setField(service, "sourcesPathString", sourceRoot.toString());
        ReflectionTestUtils.setField(service, "initialWeight", 37);
        return service;
    }

    private Path media(Path root, UUID id, String fileName) throws IOException {
        Path file = root.resolve(id.toString()).resolve(fileName);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, "placeholder media");
    }

    private Source source(String description, Status status, Message message) {
        Source source = new Source();
        source.setId(UUID.randomUUID());
        source.setDescription(description);
        source.setStatus(status);
        source.setMessage(message);
        source.setWeight(3);
        return source;
    }

    private Template template(Status status, Message message) {
        Template template = new Template();
        template.setId(UUID.randomUUID());
        template.setStatus(status);
        template.setMessage(message);
        template.setWeight(3);
        template.setAreas(List.of());
        return template;
    }

    private Message message(int date, Long authorId) {
        User author = new User();
        author.setId(authorId);
        author.setFirstName("Test author");
        author.setIsBot(false);
        Chat chat = new Chat();
        chat.setId(123L);
        chat.setType("private");
        Message message = new Message();
        message.setMessageId(date);
        message.setDate(date);
        message.setFrom(author);
        message.setChat(chat);
        return message;
    }

    private SearchCriteria criteria(int page, int perPage) {
        SearchCriteria criteria = new SearchCriteria();
        criteria.setPage(page);
        criteria.setPerPage(perPage);
        return criteria;
    }
}