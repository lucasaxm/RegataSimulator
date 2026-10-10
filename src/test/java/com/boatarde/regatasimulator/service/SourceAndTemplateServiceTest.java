package com.boatarde.regatasimulator.service;

import com.boatarde.regatasimulator.dto.SearchCriteria;
import com.boatarde.regatasimulator.repository.jsondb.JsonDbSourceRepository;
import com.boatarde.regatasimulator.repository.jsondb.JsonDbTemplateRepository;
import com.boatarde.regatasimulator.adapter.media.FileMediaStorage;
import com.boatarde.regatasimulator.models.AreaCorner;
import com.boatarde.regatasimulator.models.CommonEntity;
import com.boatarde.regatasimulator.application.ApplicationFailure;
import com.boatarde.regatasimulator.models.GalleryResponse;
import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.models.Template;
import com.boatarde.regatasimulator.models.TemplateArea;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        templates = new TemplateService(new JsonDbTemplateRepository(db), new FileMediaStorage(sourceRoot.toString(), templateRoot.toString()));
        ReflectionTestUtils.setField(templates, "initialWeight", 37);
    }

    @Test
    void sourcePreviewBindingRoundTripsOnDiskWithoutChangingOriginOrMetadata() {
        Source original = sourceWithPreview();
        db.insert(List.of(original), Source.class);

        Source stored = reopenDatabase().findById(original.getId(), Source.class);

        assertEquals(original.getPreviewChatId(), stored.getPreviewChatId());
        assertEquals(original.getPreviewMessageId(), stored.getPreviewMessageId());
        assertSourceMetadata(original, stored);
    }

    @Test
    void templatePreviewBindingRoundTripsOnDiskWithoutChangingOriginOrMetadata() {
        Template original = templateWithPreview();
        db.insert(List.of(original), Template.class);

        Template stored = reopenDatabase().findById(original.getId(), Template.class);

        assertEquals(original.getPreviewChatId(), stored.getPreviewChatId());
        assertEquals(original.getPreviewMessageId(), stored.getPreviewMessageId());
        assertTemplateMetadata(original, stored);
    }

    @Test
    void completeSourcePreviewReviewPersistsNullBindingAndPreservesReviewOrigin() {
        Source original = sourceWithPreview();
        db.insert(List.of(original), Source.class);
        Source pending = reopenDatabase().findById(original.getId(), Source.class);
        assertEquals(original.getPreviewChatId(), pending.getPreviewChatId());
        assertEquals(original.getPreviewMessageId(), pending.getPreviewMessageId());

        sources.completePreviewReview(pending);

        assertNull(pending.getPreviewChatId());
        assertNull(pending.getPreviewMessageId());
        Source stored = reopenDatabase().findById(original.getId(), Source.class);
        assertNull(stored.getPreviewChatId());
        assertNull(stored.getPreviewMessageId());
        assertSourceMetadata(original, stored);
    }

    @Test
    void completeTemplatePreviewReviewPersistsNullBindingAndPreservesReviewOrigin() {
        Template original = templateWithPreview();
        db.insert(List.of(original), Template.class);
        Template pending = reopenDatabase().findById(original.getId(), Template.class);
        assertEquals(original.getPreviewChatId(), pending.getPreviewChatId());
        assertEquals(original.getPreviewMessageId(), pending.getPreviewMessageId());

        templates.completePreviewReview(pending);

        assertNull(pending.getPreviewChatId());
        assertNull(pending.getPreviewMessageId());
        Template stored = reopenDatabase().findById(original.getId(), Template.class);
        assertNull(stored.getPreviewChatId());
        assertNull(stored.getPreviewMessageId());
        assertTemplateMetadata(original, stored);
    }

    @Test
    void completeSourcePreviewReviewWithStaleSnapshotPreservesCurrentApprovedMetadata() {
        Source original = sourceWithPreview();
        db.insert(List.of(original), Source.class);
        Source staleReview = reopenDatabase().findById(original.getId(), Source.class);
        Source current = db.findById(original.getId(), Source.class);
        current.setWeight(41);
        current.setDescription("updated approved source description");
        db.save(current, Source.class);
        sources.approveSource(current);
        assertEquals(Status.REVIEW, staleReview.getStatus());
        assertEquals(original.getPreviewChatId(), staleReview.getPreviewChatId());
        assertEquals(original.getPreviewMessageId(), staleReview.getPreviewMessageId());
        assertEquals(Status.APPROVED, reopenDatabase().findById(original.getId(), Source.class).getStatus());

        sources.completePreviewReview(staleReview);

        assertNull(staleReview.getPreviewChatId());
        assertNull(staleReview.getPreviewMessageId());
        assertSourceMetadata(original, staleReview);
        Source stored = reopenDatabase().findById(original.getId(), Source.class);
        assertNotNull(stored);
        assertEquals(original.getId(), stored.getId());
        assertNull(stored.getPreviewChatId());
        assertNull(stored.getPreviewMessageId());
        assertEquals(Status.APPROVED, stored.getStatus());
        assertEquals(current.getWeight(), stored.getWeight());
        assertEquals(current.getDescription(), stored.getDescription());
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(mapper.valueToTree(original.getMessage()), mapper.valueToTree(stored.getMessage()));
    }

    @Test
    void completeTemplatePreviewReviewWithStaleSnapshotPreservesCurrentApprovedMetadata() {
        Template original = templateWithPreview();
        db.insert(List.of(original), Template.class);
        Template staleReview = reopenDatabase().findById(original.getId(), Template.class);
        Template current = db.findById(original.getId(), Template.class);
        current.setWeight(43);
        current.setAreas(List.of(TemplateArea.builder().index(3).source(9)
            .topLeft(new AreaCorner(30, 40)).topRight(new AreaCorner(130, 45))
            .bottomRight(new AreaCorner(135, 240)).bottomLeft(new AreaCorner(35, 235))
            .background(false).build()));
        db.save(current, Template.class);
        templates.approveTemplate(current);
        assertEquals(Status.REVIEW, staleReview.getStatus());
        assertEquals(original.getPreviewChatId(), staleReview.getPreviewChatId());
        assertEquals(original.getPreviewMessageId(), staleReview.getPreviewMessageId());
        assertEquals(Status.APPROVED, reopenDatabase().findById(original.getId(), Template.class).getStatus());

        templates.completePreviewReview(staleReview);

        assertNull(staleReview.getPreviewChatId());
        assertNull(staleReview.getPreviewMessageId());
        assertTemplateMetadata(original, staleReview);
        Template stored = reopenDatabase().findById(original.getId(), Template.class);
        assertNotNull(stored);
        assertEquals(original.getId(), stored.getId());
        assertNull(stored.getPreviewChatId());
        assertNull(stored.getPreviewMessageId());
        assertEquals(Status.APPROVED, stored.getStatus());
        assertEquals(current.getWeight(), stored.getWeight());
        assertEquals(current.getAreas(), stored.getAreas());
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(mapper.valueToTree(original.getMessage()), mapper.valueToTree(stored.getMessage()));
    }

    @Test
    void completeMissingSourcePreviewReviewFailsWithoutReinsertingOrClearingSnapshotBinding() {
        Source original = sourceWithPreview();
        db.insert(List.of(original), Source.class);
        Source staleReview = reopenDatabase().findById(original.getId(), Source.class);
        db.remove(original, Source.class);
        assertNull(db.findById(original.getId(), Source.class));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> sources.completePreviewReview(staleReview));

        assertEquals("Source no longer exists: " + original.getId(), failure.getMessage());
        assertEquals(original.getPreviewChatId(), staleReview.getPreviewChatId());
        assertEquals(original.getPreviewMessageId(), staleReview.getPreviewMessageId());
        assertSourceMetadata(original, staleReview);
        assertNull(db.findById(original.getId(), Source.class));
        JsonDBTemplate reopened = reopenDatabase();
        assertNull(reopened.findById(original.getId(), Source.class));
        assertTrue(reopened.findAll(Source.class).isEmpty());
    }

    @Test
    void completeMissingTemplatePreviewReviewFailsWithoutReinsertingOrClearingSnapshotBinding() {
        Template original = templateWithPreview();
        db.insert(List.of(original), Template.class);
        Template staleReview = reopenDatabase().findById(original.getId(), Template.class);
        db.remove(original, Template.class);
        assertNull(db.findById(original.getId(), Template.class));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> templates.completePreviewReview(staleReview));

        assertEquals("Template no longer exists: " + original.getId(), failure.getMessage());
        assertEquals(original.getPreviewChatId(), staleReview.getPreviewChatId());
        assertEquals(original.getPreviewMessageId(), staleReview.getPreviewMessageId());
        assertTemplateMetadata(original, staleReview);
        assertNull(db.findById(original.getId(), Template.class));
        JsonDBTemplate reopened = reopenDatabase();
        assertNull(reopened.findById(original.getId(), Template.class));
        assertTrue(reopened.findAll(Template.class).isEmpty());
    }

    @Test
    void jacksonDeserializesLegacySourceWithoutPreviewFieldsAsNull() throws IOException {
        Source original = sourceWithPreview();
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode legacy = mapper.valueToTree(original);
        legacy.remove(List.of("previewChatId", "previewMessageId"));

        Source stored = mapper.readValue(legacy.toString(), Source.class);

        assertNull(stored.getPreviewChatId());
        assertNull(stored.getPreviewMessageId());
        assertSourceMetadata(original, stored);
    }

    @Test
    void jacksonDeserializesLegacyTemplateWithoutPreviewFieldsAsNull() throws IOException {
        Template original = templateWithPreview();
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode legacy = mapper.valueToTree(original);
        legacy.remove(List.of("previewChatId", "previewMessageId"));

        Template stored = mapper.readValue(legacy.toString(), Template.class);

        assertNull(stored.getPreviewChatId());
        assertNull(stored.getPreviewMessageId());
        assertTemplateMetadata(original, stored);
    }

    @Test
    void freshJsonDbLoadsLegacySourcesWithoutPreviewFieldsAndPreservesMetadata() throws IOException {
        Source original = sourceWithPreview();
        Source imported = sourceWithPreview();
        imported.setDescription("legacy imported source");
        imported.setMessage(null);
        db.insert(List.of(original, imported), Source.class);
        removePreviewFieldsFromTemporaryCollection("sources", 2);

        JsonDBTemplate reopened = reopenDatabase();

        assertEquals(2, reopened.findAll(Source.class).size());
        for (Source expected : List.of(original, imported)) {
            Source stored = reopened.findById(expected.getId(), Source.class);
            assertNotNull(stored);
            assertNull(stored.getPreviewChatId());
            assertNull(stored.getPreviewMessageId());
            assertSourceMetadata(expected, stored);
        }
    }

    @Test
    void freshJsonDbLoadsLegacyTemplatesWithoutPreviewFieldsAndPreservesMetadata() throws IOException {
        Template original = templateWithPreview();
        Template imported = templateWithPreview();
        imported.setMessage(null);
        db.insert(List.of(original, imported), Template.class);
        removePreviewFieldsFromTemporaryCollection("templates", 2);

        JsonDBTemplate reopened = reopenDatabase();

        assertEquals(2, reopened.findAll(Template.class).size());
        for (Template expected : List.of(original, imported)) {
            Template stored = reopened.findById(expected.getId(), Template.class);
            assertNotNull(stored);
            assertNull(stored.getPreviewChatId());
            assertNull(stored.getPreviewMessageId());
            assertTemplateMetadata(expected, stored);
        }
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
        TemplateService failingTemplates = new TemplateService(new JsonDbTemplateRepository(failingDb), new FileMediaStorage(sourceRoot.toString(), templateRoot.toString()));
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
    void zeroPageSizeIsRejectedByRepositoryPaginationBoundary() {
        db.insert(List.of(source("one", Status.REVIEW, null)), Source.class);
        db.insert(List.of(template(Status.REVIEW, null)), Template.class);

        assertThrows(IllegalArgumentException.class, () -> sources.getSources(1, 0, null, null));
        assertThrows(IllegalArgumentException.class, () -> templates.getTemplates(1, 0, null, null));
        assertThrows(IllegalArgumentException.class, () -> sources.search(criteria(1, 0)));
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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void moderationUsesStoredReviewStateAndPreservesUnrelatedCurrentFields(boolean approved) {
        Source originalSource = sourceWithPreview();
        Template originalTemplate = templateWithPreview();
        db.insert(List.of(originalSource), Source.class);
        db.insert(List.of(originalTemplate), Template.class);
        Source staleSource = reopenDatabase().findById(originalSource.getId(), Source.class);
        Template staleTemplate = reopenDatabase().findById(originalTemplate.getId(), Template.class);
        Source currentSource = db.findById(originalSource.getId(), Source.class);
        Template currentTemplate = db.findById(originalTemplate.getId(), Template.class);
        currentSource.setDescription("current description");
        currentSource.setWeight(71);
        currentTemplate.setWeight(72);
        db.save(currentSource, Source.class);
        db.save(currentTemplate, Template.class);
        if (approved) {
            sources.approveSource(staleSource);
            templates.approveTemplate(staleTemplate);
        } else {
            sources.rejectSource(staleSource);
            templates.rejectTemplate(staleTemplate);
        }
        Source storedSource = reopenDatabase().findById(originalSource.getId(), Source.class);
        Template storedTemplate = reopenDatabase().findById(originalTemplate.getId(), Template.class);
        assertEquals(approved ? Status.APPROVED : Status.REJECTED, storedSource.getStatus());
        assertEquals(storedSource.getStatus(), storedTemplate.getStatus());
        assertEquals("current description", storedSource.getDescription());
        assertEquals(71, storedSource.getWeight());
        assertEquals(72, storedTemplate.getWeight());
        assertNull(storedSource.getPreviewChatId());
        assertNull(storedTemplate.getPreviewMessageId());
        assertThrows(ApplicationFailure.class, () -> sources.approveSource(staleSource));
        assertThrows(ApplicationFailure.class, () -> templates.rejectTemplate(staleTemplate));
        assertEquals(storedSource.getStatus(), reopenDatabase().findById(originalSource.getId(), Source.class).getStatus());
    }

    private JsonDBTemplate reopenDatabase() {
        JsonDBTemplate reopened = new JsonDBTemplate(tempDir.resolve("db").toString(),
            "com.boatarde.regatasimulator.models");
        assertFalse(reopened.hasCollectionFileChangeListener());
        return reopened;
    }

    private Source sourceWithPreview() {
        Source source = source("original source description", Status.REVIEW, message(1700000000, 456L));
        source.setWeight(19);
        source.setPreviewChatId(-1001234567890L);
        source.setPreviewMessageId(9876);
        return source;
    }

    private Template templateWithPreview() {
        Template template = template(Status.REVIEW, message(1700000001, 789L));
        template.setWeight(23);
        template.setPreviewChatId(-1009876543210L);
        template.setPreviewMessageId(5432);
        template.setAreas(List.of(TemplateArea.builder().index(1).source(7)
            .topLeft(new AreaCorner(10, 20)).topRight(new AreaCorner(110, 25))
            .bottomRight(new AreaCorner(115, 220)).bottomLeft(new AreaCorner(15, 215))
            .background(true).build(), TemplateArea.builder().index(2).source(8).build()));
        return template;
    }

    private void assertSourceMetadata(Source expected, Source actual) {
        assertCommonMetadata(expected, actual);
        assertEquals(expected.getDescription(), actual.getDescription());
    }

    private void assertTemplateMetadata(Template expected, Template actual) {
        assertCommonMetadata(expected, actual);
        assertEquals(expected.getAreas(), actual.getAreas());
    }

    private void assertCommonMetadata(CommonEntity expected, CommonEntity actual) {
        assertNotNull(actual);
        assertEquals(expected.getId(), actual.getId());
        assertEquals(Status.REVIEW, actual.getStatus());
        assertEquals(expected.getWeight(), actual.getWeight());
        if (expected.getMessage() == null) {
            assertNull(actual.getMessage());
            return;
        }
        Message origin = expected.getMessage();
        Message storedOrigin = actual.getMessage();
        assertNotNull(storedOrigin);
        assertEquals(origin.getMessageId(), storedOrigin.getMessageId());
        assertEquals(origin.getDate(), storedOrigin.getDate());
        assertNotNull(storedOrigin.getFrom());
        assertEquals(origin.getFrom().getId(), storedOrigin.getFrom().getId());
        assertNotNull(storedOrigin.getChat());
        assertEquals(origin.getChat().getId(), storedOrigin.getChat().getId());
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(mapper.valueToTree(origin), mapper.valueToTree(storedOrigin));
    }

    private void removePreviewFieldsFromTemporaryCollection(String collection, int recordCount) throws IOException {
        Path file = tempDir.resolve("db").resolve(collection + ".json");
        List<String> lines = new ArrayList<>(Files.readAllLines(file));
        assertEquals(recordCount + 1, lines.size());
        String header = lines.getFirst();
        ObjectMapper mapper = new ObjectMapper();
        // JsonDB stores a schema header followed by one JSON object per data record.
        for (int index = 1; index < lines.size(); index++) {
            ObjectNode dataRecord = (ObjectNode) mapper.readTree(lines.get(index));
            assertTrue(dataRecord.hasNonNull("previewChatId"));
            assertTrue(dataRecord.hasNonNull("previewMessageId"));
            dataRecord.remove(List.of("previewChatId", "previewMessageId"));
            assertFalse(dataRecord.has("previewChatId"));
            assertFalse(dataRecord.has("previewMessageId"));
            lines.set(index, mapper.writeValueAsString(dataRecord));
        }
        Files.write(file, lines);
        assertEquals(header, Files.readAllLines(file).getFirst());
    }

    private SourceService sourceService(JsonDBTemplate database) {
        SourceService service = new SourceService(new JsonDbSourceRepository(database), new FileMediaStorage(sourceRoot.toString(), templateRoot.toString()));
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