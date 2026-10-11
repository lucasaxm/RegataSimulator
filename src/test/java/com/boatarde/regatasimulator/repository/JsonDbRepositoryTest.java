package com.boatarde.regatasimulator.repository;

import com.boatarde.regatasimulator.models.*;
import com.boatarde.regatasimulator.repository.jsondb.*;
import io.jsondb.JsonDBTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.telegram.telegrambots.meta.api.objects.Message;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class JsonDbRepositoryTest {
    @TempDir Path root;
    private JsonDBTemplate db;
    private SourceRepository sources;
    private TemplateRepository templates;
    private MemeHistoryRepository history;

    @BeforeEach
    void setUp() throws Exception {
        db = new JsonDBTemplate(Files.createDirectories(root.resolve("db")).toString(),
            "com.boatarde.regatasimulator.models");
        db.createCollection(Source.class);
        db.createCollection(Template.class);
        db.createCollection(Author.class);
        db.createCollection(Meme.class);
        sources = new JsonDbSourceRepository(db);
        templates = new JsonDbTemplateRepository(db);
        history = new JsonDbMemeHistoryRepository(db);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void conditionalReviewAndBindingPreserveCurrentFields(boolean sourceType) {
        CommonEntity item = sourceType ? source() : template();
        db.insert(item);
        UUID id = item.getId();
        assertTrue(sourceType ? sources.bindReviewPreview(id, 23L, 45) : templates.bindReviewPreview(id, 23L, 45));
        assertTrue(sourceType ? sources.decideReview(id, Status.APPROVED) : templates.decideReview(id, Status.APPROVED));
        assertFalse(sourceType ? sources.bindReviewPreview(id, 23L, 46) : templates.bindReviewPreview(id, 23L, 46));
        assertFalse(sourceType ? sources.decideReview(id, Status.REJECTED) : templates.decideReview(id, Status.REJECTED));
        CommonEntity stored = sourceType ? sources.findById(id).orElseThrow() : templates.findById(id).orElseThrow();
        assertEquals(Status.APPROVED, stored.getStatus());
        assertEquals(17, stored.getWeight());
        assertNull(stored.getPreviewChatId());
        assertNull(stored.getMessage());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void reviewRequiresAnActualDecisionAndPositiveBinding(boolean sourceType) {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> {
            if (sourceType) sources.decideReview(id, Status.REVIEW);
            else templates.decideReview(id, Status.REVIEW);
        });
        assertThrows(IllegalArgumentException.class, () -> {
            if (sourceType) sources.bindReviewPreview(id, 1, 0);
            else templates.bindReviewPreview(id, 1, 0);
        });
        assertFalse(sourceType ? sources.clearPreview(id) : templates.clearPreview(id));
    }

    @Test
    void descriptionsAreLiteralCaseInsensitiveTextNotJxpath() {
        Source source = source();
        source.setDescription("O'Brien ALPHA");
        sources.insertSubmission(source);
        assertEquals(List.of(source.getId()), sources.find(new SourceRepository.Criteria(Status.REVIEW,
            null, List.of("o'BRIEN"))).stream().map(Source::getId).toList());
        assertTrue(sources.find(new SourceRepository.Criteria(null, null, List.of("' or true() or '"))).isEmpty());
    }

    @Test
    void singleAreaCriteriaIsExplicitAndToleratesLegacyNullAreas() {
        Template single = template();
        single.setAreas(List.of(TemplateArea.builder().index(1).source(1).build()));
        Template legacy = template();
        legacy.setAreas(null);
        templates.insertSubmission(single);
        templates.insertSubmission(legacy);
        assertEquals(List.of(single.getId()), templates.find(new TemplateRepository.Criteria(Status.REVIEW,
            null, true)).stream().map(Template::getId).toList());
    }

    @Test
    void weightChangesPreserveStatusBindingAndFloor() {
        Source source = source();
        Template template = template();
        sources.insertSubmission(source);
        templates.insertSubmission(template);
        sources.bindReviewPreview(source.getId(), 2, 3);
        templates.bindReviewPreview(template.getId(), 2, 3);
        sources.resetWeights(1);
        templates.resetWeights(1);
        sources.decreaseWeight(source.getId());
        templates.decreaseWeight(template.getId());
        assertEquals(1, sources.findById(source.getId()).orElseThrow().getWeight());
        assertEquals(1, templates.findById(template.getId()).orElseThrow().getWeight());
        assertEquals(3, sources.findById(source.getId()).orElseThrow().getPreviewMessageId());
        assertEquals(Status.REVIEW, templates.findById(template.getId()).orElseThrow().getStatus());
    }

    @Test
    void concurrentReviewHasOnlyOneWinnerOnSharedJsonDb() throws Exception {
        Source source = source();
        sources.insertSubmission(source);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var approve = executor.submit(() -> sources.decideReview(source.getId(), Status.APPROVED));
            var reject = executor.submit(() -> sources.decideReview(source.getId(), Status.REJECTED));
            assertNotEquals(approve.get(), reject.get());
        }
    }

    @Test
    void historyPreservesSourceOrderAndTrimsFromCurrentStore() {
        List<Meme> existing = IntStream.range(0, 1000).mapToObj(this::meme).toList();
        db.insert(existing, Meme.class);
        Meme delivered = meme(1001);
        history.recordDelivered(delivered);
        assertEquals(1000, history.newestFirst().size());
        assertEquals(delivered.getId(), history.newestFirst().getFirst().getId());
        assertEquals(delivered.getSourceIds(), history.newestFirst().getFirst().getSourceIds());
        assertNull(db.findById(existing.getFirst().getId(), Meme.class));
        assertEquals(1000, new JsonDbMemeHistoryRepository(new JsonDBTemplate(root.resolve("db").toString(),
            "com.boatarde.regatasimulator.models")).newestFirst().size());
    }

    @Test
    void authorRepositoryRecordsAndUpdatesSubmitterWithoutNewIdentity() {
        AuthorRepository authors = new JsonDbAuthorRepository(db);
        authors.recordSubmitter(Author.builder().id(42L).firstName("before").build());
        authors.recordSubmitter(Author.builder().id(42L).firstName("after").build());
        assertEquals(1, authors.findAll().size());
        assertEquals("after", authors.findAll().getFirst().getFirstName());
    }

    private Source source() {
        Source source = new Source();
        source.setId(UUID.randomUUID()); source.setStatus(Status.REVIEW); source.setWeight(17);
        return source;
    }

    private Template template() {
        Template template = new Template();
        template.setId(UUID.randomUUID()); template.setStatus(Status.REVIEW); template.setWeight(17);
        return template;
    }

    private Meme meme(int date) {
        Message message = new Message(); message.setDate(date);
        return Meme.builder().id(UUID.randomUUID()).templateId(UUID.randomUUID())
            .sourceIds(List.of(UUID.randomUUID(), UUID.randomUUID())).message(message).build();
    }
}