package com.boatarde.regatasimulator.flows.simulator;

import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowDataKey;
import com.boatarde.regatasimulator.models.Meme;
import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.models.Template;
import com.boatarde.regatasimulator.models.TemplateArea;
import io.jsondb.JsonDBTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.Message;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetRandomTemplateStepTest {
    private static final String APPROVED_QUERY = "/.[(status='APPROVED')]";

    @TempDir
    private Path templatesDirectory;

    @Mock
    private JsonDBTemplate database;

    private WorkflowDataBag bag;
    private GetRandomTemplateStep step;

    @BeforeEach
    void setUp() {
        bag = new WorkflowDataBag();
        step = new GetRandomTemplateStep(templatesDirectory.toString(), database);
    }

    @ParameterizedTest
    @ValueSource(strings = {"template.jpg", "template.jpeg", "template.png"})
    void selectsApprovedTemplateStoresFileAndProceedsToSourceSelection(String filename) throws IOException {
        Template template = template(1, 2);
        Path file = media(template, filename);
        when(database.find(APPROVED_QUERY, Template.class)).thenReturn(new ArrayList<>(List.of(template)));
        when(database.findAll(Meme.class)).thenReturn(List.of());

        assertEquals(WorkflowAction.GET_RANDOM_SOURCE, step.run(bag));

        assertSame(template, bag.get(WorkflowDataKey.TEMPLATE, Template.class));
        assertEquals(file, bag.get(WorkflowDataKey.TEMPLATE_FILE, Path.class));
        assertEquals(List.of(), history());
        verify(database).find(APPROVED_QUERY, Template.class);
        verify(database).findAll(Meme.class);
        verifyNoMoreInteractions(database);
    }

    @Test
    void prefersJpgWhenAllSupportedMediaFilesExist() throws IOException {
        Template template = template(1, 1);
        Path jpg = media(template, "template.jpg");
        media(template, "template.jpeg");
        media(template, "template.png");
        bag.put(WorkflowDataKey.MEMES_HISTORY, List.of());
        when(database.find(APPROVED_QUERY, Template.class)).thenReturn(new ArrayList<>(List.of(template)));

        assertEquals(WorkflowAction.GET_RANDOM_SOURCE, step.run(bag));
        assertEquals(jpg, bag.get(WorkflowDataKey.TEMPLATE_FILE, Path.class));
        verify(database, never()).findAll(Meme.class);
    }

    @Test
    void sortsHistoryNewestFirstOnceAndReusesItForAnotherSelection() throws IOException {
        Template oldestTemplate = template(1, 1);
        Template middleTemplate = template(2, 1);
        Template newestTemplate = template(3, 1);
        Template unusedTemplate = template(4, 1);
        Path file = media(unusedTemplate, "template.png");
        Meme imported = meme(1, oldestTemplate, null);
        Meme older = meme(2, middleTemplate, 100);
        Meme newer = meme(3, newestTemplate, 200);
        when(database.find(APPROVED_QUERY, Template.class)).thenAnswer(invocation ->
            new ArrayList<>(List.of(oldestTemplate, middleTemplate, newestTemplate, unusedTemplate)));
        when(database.findAll(Meme.class)).thenReturn(List.of(older, imported, newer));

        assertEquals(WorkflowAction.GET_RANDOM_SOURCE, step.run(bag));
        List<Meme> cached = history();
        assertEquals(List.of(newer, older, imported), cached);
        assertSame(unusedTemplate, bag.get(WorkflowDataKey.TEMPLATE, Template.class));
        assertEquals(file, bag.get(WorkflowDataKey.TEMPLATE_FILE, Path.class));

        assertEquals(WorkflowAction.GET_RANDOM_SOURCE, step.run(bag));
        assertSame(cached, history());
        assertSame(unusedTemplate, bag.get(WorkflowDataKey.TEMPLATE, Template.class));
        verify(database, times(2)).find(APPROVED_QUERY, Template.class);
        verify(database, times(1)).findAll(Meme.class);
    }

    @Test
    void historyExclusionDeduplicatesAndCapsDistinctTemplateIds() throws IOException {
        Template first = template(1, 1);
        Template second = template(2, 1);
        Template third = template(3, 1);
        Template fourth = template(4, 1);
        media(fourth, "template.jpg");
        List<Meme> suppliedHistory = List.of(meme(1, first, 100), meme(2, first, 200),
            meme(3, second, 300), meme(4, third, 400), meme(5, fourth, 500));
        bag.put(WorkflowDataKey.MEMES_HISTORY, suppliedHistory);
        when(database.find(APPROVED_QUERY, Template.class))
            .thenReturn(new ArrayList<>(List.of(first, second, third, fourth)));

        assertEquals(WorkflowAction.GET_RANDOM_SOURCE, step.run(bag));
        assertSame(suppliedHistory, history());
        assertSame(fourth, bag.get(WorkflowDataKey.TEMPLATE, Template.class));
        verify(database, never()).findAll(Meme.class);
    }

    @Test
    void returnsNoneWithoutLoadingHistoryWhenNoApprovedTemplatesExist() {
        when(database.find(APPROVED_QUERY, Template.class)).thenReturn(new ArrayList<>());

        assertEquals(WorkflowAction.NONE, step.run(bag));
        assertNull(history());
        assertNull(bag.get(WorkflowDataKey.TEMPLATE, Template.class));
        assertNull(bag.get(WorkflowDataKey.TEMPLATE_FILE, Path.class));
        verify(database).find(APPROVED_QUERY, Template.class);
        verifyNoMoreInteractions(database);
    }

    @Test
    void returnsNoneWithoutPublishingSelectionWhenTemplateMediaIsMissing() {
        Template template = template(1, 1);
        bag.put(WorkflowDataKey.MEMES_HISTORY, List.of());
        when(database.find(APPROVED_QUERY, Template.class)).thenReturn(new ArrayList<>(List.of(template)));

        assertEquals(WorkflowAction.NONE, step.run(bag));
        assertNull(bag.get(WorkflowDataKey.TEMPLATE, Template.class));
        assertNull(bag.get(WorkflowDataKey.TEMPLATE_FILE, Path.class));
    }

    @Test
    void oneItemPoolUsedInHistoryCurrentlyThrowsInsteadOfReturningNone() throws IOException {
        Template template = template(1, 1);
        media(template, "template.jpg");
        bag.put(WorkflowDataKey.MEMES_HISTORY, List.of(meme(1, template, 100)));
        when(database.find(APPROVED_QUERY, Template.class)).thenReturn(new ArrayList<>(List.of(template)));

        // Known Phase 0 bug: history removes the only template; not the intended fallback behavior.
        RuntimeException failure = assertThrows(RuntimeException.class, () -> step.run(bag));
        assertEquals("Not enough entities to select from. Amount requested: 1, entities available: 0",
            failure.getMessage());
        assertNull(bag.get(WorkflowDataKey.TEMPLATE, Template.class));
        assertNull(bag.get(WorkflowDataKey.TEMPLATE_FILE, Path.class));
    }

    @Test
    void sourcePreviewPreservesSubmittedSourceAndBypassesRandomSourceSelection() throws IOException {
        List<Source> submittedSources = submittedSourcePreview();
        List<Path> submittedFiles = bag.getGeneric(WorkflowDataKey.SOURCE_FILES, List.class, Path.class);
        Template multiArea = template(1, 2);
        Template singleArea = template(2, 1);
        Path file = media(singleArea, "template.png");
        // Even if history contains the candidate, previews bypass history exclusion.
        List<Meme> suppliedHistory = List.of(meme(1, singleArea, 100));
        bag.put(WorkflowDataKey.MEMES_HISTORY, suppliedHistory);
        when(database.find(APPROVED_QUERY, Template.class))
            .thenReturn(new ArrayList<>(List.of(multiArea, singleArea)));

        assertEquals(WorkflowAction.BUILD_MEME_STEP, step.run(bag));
        assertSame(singleArea, bag.get(WorkflowDataKey.TEMPLATE, Template.class));
        assertEquals(1, singleArea.getAreas().size());
        assertEquals(Status.APPROVED, singleArea.getStatus());
        assertEquals(file, bag.get(WorkflowDataKey.TEMPLATE_FILE, Path.class));
        assertSame(submittedSources, bag.getGeneric(WorkflowDataKey.SOURCES, List.class, Source.class));
        assertSame(submittedFiles, bag.getGeneric(WorkflowDataKey.SOURCE_FILES, List.class, Path.class));
        assertEquals(Status.REVIEW, submittedSources.getFirst().getStatus());
        assertSame(suppliedHistory, history());
        verify(database).find(APPROVED_QUERY, Template.class);
        verifyNoMoreInteractions(database);
    }

    @Test
    void sourcePreviewDoesNotLoadHistoryWhenBagHasNone() throws IOException {
        submittedSourcePreview();
        Template singleArea = template(1, 1);
        media(singleArea, "template.jpeg");
        when(database.find(APPROVED_QUERY, Template.class)).thenReturn(new ArrayList<>(List.of(singleArea)));

        assertEquals(WorkflowAction.BUILD_MEME_STEP, step.run(bag));
        assertNull(history());
        verify(database).find(APPROVED_QUERY, Template.class);
        verifyNoMoreInteractions(database);
    }

    @Test
    void sourcePreviewWithoutSingleAreaTemplateCurrentlyThrowsRatherThanReturningNone() throws IOException {
        List<Source> submittedSources = submittedSourcePreview();
        Template multiArea = template(1, 2);
        when(database.find(APPROVED_QUERY, Template.class)).thenReturn(new ArrayList<>(List.of(multiArea)));

        // Known Phase 0 bug: no eligible preview template leaks a RuntimeException, not NONE.
        RuntimeException failure = assertThrows(RuntimeException.class, () -> step.run(bag));
        assertEquals("No single area templates found", failure.getMessage());
        assertSame(submittedSources, bag.getGeneric(WorkflowDataKey.SOURCES, List.class, Source.class));
        assertNull(bag.get(WorkflowDataKey.TEMPLATE, Template.class));
        assertNull(bag.get(WorkflowDataKey.TEMPLATE_FILE, Path.class));
        verify(database).find(APPROVED_QUERY, Template.class);
        verifyNoMoreInteractions(database);
    }

    @Test
    void sourcePreviewWithMissingTemplateMediaReturnsNoneAndPreservesSubmission() throws IOException {
        List<Source> submittedSources = submittedSourcePreview();
        List<Path> submittedFiles = bag.getGeneric(WorkflowDataKey.SOURCE_FILES, List.class, Path.class);
        Template singleArea = template(1, 1);
        when(database.find(APPROVED_QUERY, Template.class)).thenReturn(new ArrayList<>(List.of(singleArea)));

        assertEquals(WorkflowAction.NONE, step.run(bag));
        assertSame(submittedSources, bag.getGeneric(WorkflowDataKey.SOURCES, List.class, Source.class));
        assertSame(submittedFiles, bag.getGeneric(WorkflowDataKey.SOURCE_FILES, List.class, Path.class));
        assertNull(bag.get(WorkflowDataKey.TEMPLATE, Template.class));
        assertNull(bag.get(WorkflowDataKey.TEMPLATE_FILE, Path.class));
        verify(database).find(APPROVED_QUERY, Template.class);
        verifyNoMoreInteractions(database);
    }

    private Template template(int id, int areaCount) {
        List<TemplateArea> areas = new ArrayList<>();
        for (int i = 1; i <= areaCount; i++) {
            areas.add(TemplateArea.builder().index(i).source(i).build());
        }
        Template template = Template.builder().areas(areas).build();
        template.setId(new UUID(0, id));
        template.setStatus(Status.APPROVED);
        template.setWeight(10);
        return template;
    }

    private Meme meme(int id, Template template, Integer date) {
        Message message = null;
        if (date != null) {
            message = new Message();
            message.setDate(date);
        }
        return Meme.builder().id(new UUID(1, id)).templateId(template.getId()).sourceIds(List.of())
            .message(message).build();
    }

    private Path media(Template template, String filename) throws IOException {
        Path directory = Files.createDirectories(templatesDirectory.resolve(template.getId().toString()));
        return Files.createFile(directory.resolve(filename));
    }

    private List<Source> submittedSourcePreview() throws IOException {
        Source submitted = new Source();
        submitted.setId(new UUID(2, 1));
        submitted.setStatus(Status.REVIEW); // Submitted sources are REVIEW, not APPROVED, in the current model.
        submitted.setWeight(10);
        Message creatingSource = new Message();
        creatingSource.setDate(100);
        List<Source> sources = new ArrayList<>(List.of(submitted));
        Path file = Files.createFile(templatesDirectory.resolve("submitted-source.png"));
        bag.put(WorkflowDataKey.SOURCES, sources);
        bag.put(WorkflowDataKey.SOURCE_FILES, new ArrayList<>(List.of(file)));
        bag.put(WorkflowDataKey.CREATING_SOURCE_MESSAGE, creatingSource);
        return sources;
    }

    private List<Meme> history() {
        return bag.getGeneric(WorkflowDataKey.MEMES_HISTORY, List.class, Meme.class);
    }
}