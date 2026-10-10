package com.boatarde.regatasimulator.flows.simulator;

import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.application.ApplicationFailure;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.Message;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetRandomSourceStepTest {
    private static final String APPROVED_QUERY = "/.[(status='APPROVED')]";
    private static final Clock ORDINARY_DAY = Clock.fixed(Instant.parse("2026-02-02T12:00:00Z"), ZoneOffset.UTC);

    @TempDir
    private Path sourcesDirectory;

    @Mock
    private JsonDBTemplate database;

    private WorkflowDataBag bag;
    private GetRandomSourceStep step;

    @BeforeEach
    void setUp() {
        bag = new WorkflowDataBag();
        bag.put(WorkflowDataKey.TEMPLATE, template(1));
        step = new GetRandomSourceStep(sourcesDirectory.toString(), database, ORDINARY_DAY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"source.jpg", "source.jpeg", "source.png"})
    void selectsApprovedSourceAndStoresItsMediaPath(String filename) throws IOException {
        Source source = source(1);
        Path file = media(source, filename);
        when(database.find(APPROVED_QUERY, Source.class)).thenReturn(new ArrayList<>(List.of(source)));
        when(database.findAll(Meme.class)).thenReturn(List.of());

        assertEquals(WorkflowAction.BUILD_MEME_STEP, step.run(bag));

        assertEquals(List.of(source), sources());
        assertEquals(List.of(file), sourceFiles());
        assertEquals(List.of(), history());
        verify(database).find(APPROVED_QUERY, Source.class);
        verify(database).findAll(Meme.class);
        verifyNoMoreInteractions(database);
    }

    @Test
    void prefersJpgWhenAllSupportedMediaFilesExist() throws IOException {
        Source source = source(1);
        Path jpg = media(source, "source.jpg");
        media(source, "source.jpeg");
        media(source, "source.png");
        when(database.find(APPROVED_QUERY, Source.class)).thenReturn(new ArrayList<>(List.of(source)));
        bag.put(WorkflowDataKey.MEMES_HISTORY, List.of());

        assertEquals(WorkflowAction.BUILD_MEME_STEP, step.run(bag));
        assertEquals(List.of(jpg), sourceFiles());
        verify(database, never()).findAll(Meme.class);
    }

    @Test
    void selectsOneDistinctSourcePerRequiredSlotAndAlignsFileOrder() throws IOException {
        Source first = source(1);
        Source second = source(2);
        Path firstFile = media(first, "source.png");
        Path secondFile = media(second, "source.jpeg");
        // Three areas reuse slot 1, so only two distinct sources are required.
        bag.put(WorkflowDataKey.TEMPLATE, template(1, 1, 2));
        bag.put(WorkflowDataKey.MEMES_HISTORY, List.of());
        when(database.find(APPROVED_QUERY, Source.class))
            .thenReturn(new ArrayList<>(List.of(first, second)));

        assertEquals(WorkflowAction.BUILD_MEME_STEP, step.run(bag));

        List<Source> selected = sources();
        assertEquals(2, selected.size());
        assertEquals(Set.of(first, second), Set.copyOf(selected));
        for (int i = 0; i < selected.size(); i++) {
            assertEquals(selected.get(i) == first ? firstFile : secondFile, sourceFiles().get(i));
        }
    }

    @Test
    void sortsHistoryNewestFirstOnceAndReusesTheSameBagHistory() throws IOException {
        Source oldestSource = source(1);
        Source middleSource = source(2);
        Source newestSource = source(3);
        Source unusedSource = source(4);
        Path file = media(unusedSource, "source.jpg");
        Meme imported = meme(1, null, oldestSource.getId());
        Meme older = meme(2, 100, middleSource.getId());
        Meme newer = meme(3, 200, newestSource.getId());
        when(database.find(APPROVED_QUERY, Source.class)).thenAnswer(invocation ->
            new ArrayList<>(List.of(oldestSource, middleSource, newestSource, unusedSource)));
        when(database.findAll(Meme.class)).thenReturn(List.of(older, imported, newer));

        assertEquals(WorkflowAction.BUILD_MEME_STEP, step.run(bag));
        List<Meme> cached = history();
        assertEquals(List.of(newer, older, imported), cached);
        assertEquals(List.of(unusedSource), sources());
        assertEquals(List.of(file), sourceFiles());

        assertEquals(WorkflowAction.BUILD_MEME_STEP, step.run(bag));
        assertSame(cached, history());
        assertEquals(List.of(unusedSource), sources());
        verify(database, times(2)).find(APPROVED_QUERY, Source.class);
        verify(database, times(1)).findAll(Meme.class);
    }

    @Test
    void historyExclusionDeduplicatesAndCapsDistinctIdsAtSeventyFivePercent() throws IOException {
        Source first = source(1);
        Source second = source(2);
        Source third = source(3);
        Source fourth = source(4);
        media(fourth, "source.jpg");
        // Supplied bag history is trusted as-is, not reloaded or sorted again.
        List<Meme> suppliedHistory = List.of(
            meme(1, 100, first.getId(), first.getId(), second.getId()),
            meme(2, 200, second.getId(), third.getId(), fourth.getId()));
        bag.put(WorkflowDataKey.MEMES_HISTORY, suppliedHistory);
        when(database.find(APPROVED_QUERY, Source.class))
            .thenReturn(new ArrayList<>(List.of(first, second, third, fourth)));

        assertEquals(WorkflowAction.BUILD_MEME_STEP, step.run(bag));
        assertSame(suppliedHistory, history());
        assertEquals(List.of(fourth), sources());
        verify(database, never()).findAll(Meme.class);
    }

    @Test
    void reportsUnavailableWithoutLoadingHistoryWhenNoApprovedSourcesExist() {
        when(database.find(APPROVED_QUERY, Source.class)).thenReturn(new ArrayList<>());

        assertEquals(ApplicationFailure.Kind.UNAVAILABLE,
            assertThrows(ApplicationFailure.class, () -> step.run(bag)).getKind());

        assertNull(history());
        assertNull(sources());
        assertNull(sourceFiles());
        verify(database).find(APPROVED_QUERY, Source.class);
        verifyNoMoreInteractions(database);
    }

    @Test
    void reportsUnavailableWithoutPublishingSelectionWhenSourceMediaIsMissing() {
        Source source = source(1);
        bag.put(WorkflowDataKey.MEMES_HISTORY, List.of());
        when(database.find(APPROVED_QUERY, Source.class)).thenReturn(new ArrayList<>(List.of(source)));

        assertThrows(ApplicationFailure.class, () -> step.run(bag));
        assertNull(sources());
        assertNull(sourceFiles());
    }

    @Test
    void oneItemPoolUsedInHistoryRemainsSelectable() throws IOException {
        Source source = source(1);
        media(source, "source.jpg");
        bag.put(WorkflowDataKey.MEMES_HISTORY, List.of(meme(1, 100, source.getId())));
        when(database.find(APPROVED_QUERY, Source.class)).thenReturn(new ArrayList<>(List.of(source)));

        assertEquals(WorkflowAction.BUILD_MEME_STEP, step.run(bag));
        assertEquals(List.of(source), sources());
    }

    @Test
    void insufficientDistinctSourcesForRequiredSlotsReportsUnavailable() {
        bag.put(WorkflowDataKey.TEMPLATE, template(1, 1, 2));
        bag.put(WorkflowDataKey.MEMES_HISTORY, List.of());
        when(database.find(APPROVED_QUERY, Source.class)).thenReturn(new ArrayList<>(List.of(source(1))));

        assertEquals(ApplicationFailure.Kind.UNAVAILABLE,
            assertThrows(ApplicationFailure.class, () -> step.run(bag)).getKind());
        assertNull(sources());
        assertNull(sourceFiles());
    }

    @ParameterizedTest
    @CsvSource({
        "2026-01-22T12:00:00Z, brenda",
        "2026-04-03T12:00:00Z, ander",
        "2026-04-14T12:00:00Z, gab",
        "2026-04-30T12:00:00Z, gui",
        "2026-05-27T12:00:00Z, xxk|gayzito",
        "2026-06-10T12:00:00Z, lucas|c4|celta",
        "2026-08-12T12:00:00Z, valb|punhet",
        "2026-10-25T12:00:00Z, dedey"
    })
    void birthdayQueryRequiresApprovalAndReportsUnavailableWhenFallbackIsAlsoEmpty(String instant, String aliases) {
        step = new GetRandomSourceStep(sourcesDirectory.toString(), database,
            Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
        String expectedQuery = birthdayQuery(aliases);
        when(database.find(expectedQuery, Source.class)).thenReturn(new ArrayList<>());

        assertThrows(ApplicationFailure.class, () -> step.run(bag));
        assertNull(sources());
        assertNull(sourceFiles());
        verify(database).find(expectedQuery, Source.class);
        verify(database).find(APPROVED_QUERY, Source.class);
        verifyNoMoreInteractions(database);
    }

    @ParameterizedTest
    @CsvSource({
        "2026-01-22T02:59:59Z, false",
        "2026-01-22T03:00:00Z, true",
        "2026-01-23T02:59:59Z, true",
        "2026-01-23T03:00:00Z, false"
    })
    void birthdayBoundaryUsesSaoPauloDateEvenWhenClockZoneIsUtc(String instant, boolean birthday) {
        step = new GetRandomSourceStep(sourcesDirectory.toString(), database,
            Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
        when(database.find(anyString(), eq(Source.class))).thenReturn(new ArrayList<>());

        assertThrows(ApplicationFailure.class, () -> step.run(bag));
        verify(database).find(birthday ? birthdayQuery("brenda") : APPROVED_QUERY, Source.class);
        if (birthday) verify(database).find(APPROVED_QUERY, Source.class);
        verifyNoMoreInteractions(database);
    }

    @Test
    void fullyUsedMinimalPoolStillFillsEveryRequiredSlotWithoutMutatingDatabaseList() throws IOException {
        Source first = source(1);
        Source second = source(2);
        media(first, "source.png");
        media(second, "source.png");
        List<Source> immutablePool = List.of(first, second);
        bag.put(WorkflowDataKey.TEMPLATE, template(1, 2));
        bag.put(WorkflowDataKey.MEMES_HISTORY, List.of(meme(1, 100, first.getId(), second.getId())));
        when(database.find(APPROVED_QUERY, Source.class)).thenReturn(immutablePool);

        assertEquals(WorkflowAction.BUILD_MEME_STEP, step.run(bag));
        assertEquals(Set.of(first, second), Set.copyOf(sources()));
        assertEquals(2, immutablePool.size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void birthdayFallsBackWhenPoolIsEmptyOrTooSmall(boolean hasOneBirthdaySource) throws IOException {
        step = new GetRandomSourceStep(sourcesDirectory.toString(), database,
            Clock.fixed(Instant.parse("2026-01-22T12:00:00Z"), ZoneOffset.UTC));
        Source first = source(1);
        Source second = source(2);
        media(first, "source.png");
        media(second, "source.png");
        bag.put(WorkflowDataKey.TEMPLATE, template(1, 2));
        bag.put(WorkflowDataKey.MEMES_HISTORY, List.of());
        when(database.find(birthdayQuery("brenda"), Source.class))
            .thenReturn(hasOneBirthdaySource ? List.of(first) : List.of());
        when(database.find(APPROVED_QUERY, Source.class)).thenReturn(List.of(first, second));

        assertEquals(WorkflowAction.BUILD_MEME_STEP, step.run(bag));
        assertEquals(Set.of(first, second), Set.copyOf(sources()));
    }

    private Source source(int id) {
        Source source = new Source();
        source.setId(new UUID(0, id));
        source.setStatus(Status.APPROVED);
        source.setDescription("ordinary source " + id);
        source.setWeight(10);
        // Imported sources legitimately have no originating Telegram Message.
        return source;
    }

    private Template template(int... slots) {
        List<TemplateArea> areas = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) {
            areas.add(TemplateArea.builder().index(i + 1).source(slots[i]).build());
        }
        return Template.builder().areas(areas).build();
    }

    private Meme meme(int id, Integer date, UUID... sourceIds) {
        Message message = null;
        if (date != null) {
            message = new Message();
            message.setDate(date);
        }
        return Meme.builder().id(new UUID(1, id)).sourceIds(List.of(sourceIds)).message(message).build();
    }

    private Path media(Source source, String filename) throws IOException {
        Path directory = Files.createDirectories(sourcesDirectory.resolve(source.getId().toString()));
        // Only existence is checked by this step; no image decoding or ImageMagick is involved.
        return Files.createFile(directory.resolve(filename));
    }

    private String birthdayQuery(String aliases) {
        String descriptions = Arrays.stream(aliases.split("\\|"))
            .map(alias -> "contains(translate(description, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), '"
                + alias + "')")
            .collect(Collectors.joining(" or "));
        return "/.[(status='APPROVED') and (" + descriptions + ")]";
    }

    private List<Source> sources() {
        return bag.getGeneric(WorkflowDataKey.SOURCES, List.class, Source.class);
    }

    private List<Path> sourceFiles() {
        return bag.getGeneric(WorkflowDataKey.SOURCE_FILES, List.class, Path.class);
    }

    private List<Meme> history() {
        return bag.getGeneric(WorkflowDataKey.MEMES_HISTORY, List.class, Meme.class);
    }
}