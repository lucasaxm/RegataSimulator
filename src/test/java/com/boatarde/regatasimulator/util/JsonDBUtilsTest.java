package com.boatarde.regatasimulator.util;

import com.boatarde.regatasimulator.models.AreaCorner;
import com.boatarde.regatasimulator.models.CommonEntity;
import com.boatarde.regatasimulator.models.Meme;
import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.models.Template;
import com.boatarde.regatasimulator.models.TemplateArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.telegram.telegrambots.meta.api.objects.Message;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class JsonDBUtilsTest {
    private static final String HEADER = "Area,Source,TLx,TLy,TRx,TRy,BRx,BRy,BLx,BLy,Background";
    private static final String VALID_ROW = "1,2,-10,20,30,40,50,60,70,-80,1";

    @Test
    void parsesExactElevenFieldHeaderIntegerCornersAndBackground() throws IOException {
        List<TemplateArea> areas = JsonDBUtils.parseTemplateCsv(HEADER + "\n" + VALID_ROW);

        assertEquals(List.of(TemplateArea.builder()
            .index(1)
            .source(2)
            .topLeft(new AreaCorner(-10, 20))
            .topRight(new AreaCorner(30, 40))
            .bottomRight(new AreaCorner(50, 60))
            .bottomLeft(new AreaCorner(70, -80))
            .background(true)
            .build()), areas);
    }

    @ParameterizedTest
    @CsvSource({"1, true", "0, false", "2, false", "-1, false"})
    void backgroundIsTrueOnlyForIntegerOne(int background, boolean expected) throws IOException {
        String row = "1,1,0,0,100,0,100,100,0,100," + background;

        List<TemplateArea> areas = JsonDBUtils.parseTemplateCsv(HEADER + "\r\n" + row + "\r\n");

        assertEquals(1, areas.size());
        assertEquals(expected, areas.getFirst().isBackground());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "area,Source,TLx,TLy,TRx,TRy,BRx,BRy,BLx,BLy,Background",
        "Source,Area,TLx,TLy,TRx,TRy,BRx,BRy,BLx,BLy,Background",
        " Area,Source,TLx,TLy,TRx,TRy,BRx,BRy,BLx,BLy,Background",
        "Area,Source,TLx,TLy,TRx,TRy,BRx,BRy,BLx,BLy,Background ",
        "Area,Source,TLx,TLy,TRx,TRy,BRx,BRy,BLx,BLy"
    })
    void rejectsNonExactHeaderWithIOException(String header) {
        IOException failure = assertThrows(IOException.class,
            () -> JsonDBUtils.parseTemplateCsv(header + "\n" + VALID_ROW));

        assertEquals("Invalid CSV header", failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "1,1,0,0,10,0,10,10,0,10",
        "1,1,0,0,10,0,10,10,0,10,1,extra",
        "1,1,0,0,10,0,10,10,0,10,",
        ""
    })
    void rejectsIncorrectFieldCountWithIOException(String row) {
        IOException failure = assertThrows(IOException.class,
            () -> JsonDBUtils.parseTemplateCsv(HEADER + "\n" + row + "\n"));

        assertEquals("Invalid CSV format", failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
    void malformedNumberInAnyFieldCurrentlyPropagatesNumberFormatException(int fieldIndex) {
        String[] fields = VALID_ROW.split(",");
        fields[fieldIndex] = "not-an-integer";
        String csv = HEADER + "\n" + String.join(",", fields);

        // Phase 0 characterization: numeric parse errors are not wrapped in IOException.
        assertThrows(NumberFormatException.class,
            () -> JsonDBUtils.parseTemplateCsv(csv));
    }

    @Test
    void sparseAndDuplicateAreaAndSourceIndicesAreCurrentlyAccepted() throws IOException {
        String csv = HEADER + "\n" + """
            3,7,0,0,10,0,10,10,0,10,0
            3,7,1,2,11,2,11,12,1,12,1
            99,42,0,0,10,0,10,10,0,10,0
            """;

        // Known validation gap, not desired behavior: Phase 1 must validate indices before rendering.
        List<TemplateArea> areas = JsonDBUtils.parseTemplateCsv(csv);

        assertEquals(List.of(3, 3, 99), areas.stream().map(TemplateArea::getIndex).toList());
        assertEquals(List.of(7, 7, 42), areas.stream().map(TemplateArea::getSource).toList());
        assertEquals(new AreaCorner(1, 2), areas.get(1).getTopLeft());
    }

    @Test
    void headerOnlyCsvCurrentlyProducesNoAreas() throws IOException {
        // Known validation gap: accepting empty geometry is not a recommendation for Phase 1.
        assertEquals(List.of(), JsonDBUtils.parseTemplateCsv(HEADER));
    }

    @ParameterizedTest
    @CsvSource({"0, 1", "1, 1", "2, 2", "4, 2", "5, 3", "9, 3"})
    void weightedSourceSelectionUsesProvidedRandomInterval(int randomValue, int expectedId) {
        List<Source> candidates = new ArrayList<>(List.of(source(1, 2), source(2, 3), source(3, 5)));
        Source expected = candidates.get(expectedId - 1);
        RandomGenerator random = mock(RandomGenerator.class);
        when(random.nextInt(10)).thenReturn(randomValue);

        List<Source> selected = JsonDBUtils.selectSourcesWithWeight(candidates, 1, random);

        assertEquals(List.of(expected), selected);
        assertEquals(2, candidates.size());
        assertTrue(candidates.stream().noneMatch(candidate -> candidate == expected));
        verify(random).nextInt(10);
        verifyNoMoreInteractions(random);
    }

    @ParameterizedTest
    @CsvSource({"0, 1", "1, 1", "2, 2", "4, 2", "5, 3", "9, 3"})
    void weightedTemplateSelectionUsesProvidedRandomInterval(int randomValue, int expectedId) {
        List<Template> candidates = new ArrayList<>(List.of(template(1, 2, 1), template(2, 3, 1),
            template(3, 5, 1)));
        Template expected = candidates.get(expectedId - 1);
        RandomGenerator random = mock(RandomGenerator.class);
        when(random.nextInt(10)).thenReturn(randomValue);

        List<Template> selected = JsonDBUtils.selectTemplatesWithWeight(candidates, 1, random);

        assertEquals(List.of(expected), selected);
        assertEquals(2, candidates.size());
        assertTrue(candidates.stream().noneMatch(candidate -> candidate == expected));
        verify(random).nextInt(10);
        verifyNoMoreInteractions(random);
    }

    @Test
    void weightedSourcesAreSelectedWithoutReplacementAndBoundsShrinkAfterEachRemoval() {
        Source first = source(1, 2);
        Source second = source(2, 3);
        Source third = source(3, 5);
        List<Source> candidates = new ArrayList<>(List.of(first, second, third));
        RandomGenerator random = removalSequenceRandom();

        assertEquals(List.of(second, third, first), JsonDBUtils.selectSourcesWithWeight(candidates, 3, random));
        assertTrue(candidates.isEmpty());
        verifyRemovalBounds(random);
    }

    @Test
    void weightedTemplatesAreSelectedWithoutReplacementAndBoundsShrinkAfterEachRemoval() {
        Template first = template(1, 2, 1);
        Template second = template(2, 3, 1);
        Template third = template(3, 5, 1);
        List<Template> candidates = new ArrayList<>(List.of(first, second, third));
        RandomGenerator random = removalSequenceRandom();

        assertEquals(List.of(second, third, first), JsonDBUtils.selectTemplatesWithWeight(candidates, 3, random));
        assertTrue(candidates.isEmpty());
        verifyRemovalBounds(random);
    }

    @Test
    void seededWeightedSourceSelectionIsReproducible() {
        List<Source> pool = List.of(source(1, 2), source(2, 3), source(3, 5), source(4, 8));
        List<Source> first = JsonDBUtils.selectSourcesWithWeight(new ArrayList<>(pool), 3, new Random(42));
        List<Source> second = JsonDBUtils.selectSourcesWithWeight(new ArrayList<>(pool), 3, new Random(42));

        assertEquals(first, second);
        assertEquals(3, first.size());
        assertEquals(3, first.stream().map(Source::getId).distinct().count());
    }

    @Test
    void seededWeightedTemplateSelectionIsReproducible() {
        List<Template> pool = List.of(template(1, 2, 1), template(2, 3, 2), template(3, 5, 1),
            template(4, 8, 3));
        List<Template> first = JsonDBUtils.selectTemplatesWithWeight(new ArrayList<>(pool), 3, new Random(42));
        List<Template> second = JsonDBUtils.selectTemplatesWithWeight(new ArrayList<>(pool), 3, new Random(42));

        assertEquals(first, second);
        assertEquals(3, first.size());
        assertEquals(3, first.stream().map(Template::getId).distinct().count());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void insufficientSourcesCurrentlyThrowBeforeUsingRandom(int available) {
        List<Source> candidates = new ArrayList<>();
        if (available == 1) {
            candidates.add(source(1, 10));
        }
        RandomGenerator random = mock(RandomGenerator.class);

        // Selection failure remains uncaught by the workflow; graceful recovery belongs to Phase 1.
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> JsonDBUtils.selectSourcesWithWeight(candidates, 2, random));

        assertEquals("Not enough entities to select from. Amount requested: 2, entities available: " + available,
            failure.getMessage());
        assertEquals(available, candidates.size());
        verifyNoInteractions(random);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void insufficientTemplatesCurrentlyThrowBeforeUsingRandom(int available) {
        List<Template> candidates = new ArrayList<>();
        if (available == 1) {
            candidates.add(template(1, 10, 1));
        }
        RandomGenerator random = mock(RandomGenerator.class);

        // Known Phase 0 failure contract, not a recommendation for graceful workflow handling.
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> JsonDBUtils.selectTemplatesWithWeight(candidates, 2, random));

        assertEquals("Not enough entities to select from. Amount requested: 2, entities available: " + available,
            failure.getMessage());
        assertEquals(available, candidates.size());
        verifyNoInteractions(random);
    }

    @Test
    void zeroTotalSourceWeightCurrentlyThrowsIllegalArgumentException() {
        List<Source> candidates = new ArrayList<>(List.of(source(1, 0), source(2, 0)));
        RandomGenerator random = new Random(42);

        // Known Phase 0 bug: total weight zero reaches Random.nextInt(0), rather than a safe fallback.
        assertThrows(IllegalArgumentException.class,
            () -> JsonDBUtils.selectSourcesWithWeight(candidates, 1, random));
        assertEquals(2, candidates.size());
    }

    @Test
    void zeroTotalTemplateWeightCurrentlyThrowsIllegalArgumentException() {
        List<Template> candidates = new ArrayList<>(List.of(template(1, 0, 1), template(2, 0, 1)));
        RandomGenerator random = new Random(42);

        // Known Phase 0 bug: total weight zero reaches Random.nextInt(0), rather than a safe fallback.
        assertThrows(IllegalArgumentException.class,
            () -> JsonDBUtils.selectTemplatesWithWeight(candidates, 1, random));
        assertEquals(2, candidates.size());
    }

    @Test
    void requestingZeroEntitiesDoesNotUseRandomEvenWithEmptyPools() {
        RandomGenerator random = mock(RandomGenerator.class);

        assertEquals(List.of(), JsonDBUtils.selectSourcesWithWeight(new ArrayList<>(), 0, random));
        assertEquals(List.of(), JsonDBUtils.selectTemplatesWithWeight(new ArrayList<>(), 0, random));
        verifyNoInteractions(random);
    }

    @Test
    void defaultWeightedOverloadsStillSelectAndRemoveTheOnlyCandidate() {
        Source source = source(1, 10);
        Template template = template(1, 10, 1);
        List<Source> sources = new ArrayList<>(List.of(source));
        List<Template> templates = new ArrayList<>(List.of(template));

        // A one-item pool makes the legacy overloads deterministic despite their internal Random.
        assertEquals(List.of(source), JsonDBUtils.selectSourcesWithWeight(sources, 1));
        assertEquals(List.of(template), JsonDBUtils.selectTemplatesWithWeight(templates, 1));
        assertTrue(sources.isEmpty());
        assertTrue(templates.isEmpty());
    }

    @Test
    void weightedSelectionResultsRemainMutable() {
        Source firstSource = source(1, 10);
        Source secondSource = source(2, 10);
        Template firstTemplate = template(1, 10, 1);
        Template secondTemplate = template(2, 10, 1);
        List<Source> sources = JsonDBUtils.selectSourcesWithWeight(new ArrayList<>(List.of(firstSource)), 1,
            new Random(42));
        List<Template> templates = JsonDBUtils.selectTemplatesWithWeight(new ArrayList<>(List.of(firstTemplate)), 1,
            new Random(42));

        sources.add(secondSource);
        templates.add(secondTemplate);

        assertEquals(List.of(firstSource, secondSource), sources);
        assertEquals(List.of(firstTemplate, secondTemplate), templates);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void singleAreaSelectionExcludesMultiAreaAndEmptyTemplatesBeforeUsingProvidedRandom(int index) {
        Template firstSingle = template(2, 0, 1);
        Template secondSingle = template(4, 100, 1);
        List<Template> candidates = new ArrayList<>(List.of(template(1, 10, 2), firstSingle,
            template(3, 10, 0), secondSingle, template(5, 10, 3)));
        List<Template> original = new ArrayList<>(candidates);
        RandomGenerator random = mock(RandomGenerator.class);
        when(random.nextInt(2)).thenReturn(index);

        assertSame(index == 0 ? firstSingle : secondSingle,
            JsonDBUtils.selectRandomSingleAreaTemplate(candidates, random));
        assertEquals(original, candidates); // Unlike weighted selection, this method does not remove candidates.
        verify(random).nextInt(2);
        verifyNoMoreInteractions(random);
    }

    @Test
    void seededSingleAreaSelectionIsReproducibleAndAlwaysEligible() {
        Template firstSingle = template(2, 10, 1);
        Template secondSingle = template(3, 10, 1);
        List<Template> candidates = new ArrayList<>(List.of(template(1, 10, 2), firstSingle, secondSingle));
        RandomGenerator firstRandom = new Random(42);
        RandomGenerator secondRandom = new Random(42);
        List<Template> firstSequence = new ArrayList<>();
        List<Template> secondSequence = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            firstSequence.add(JsonDBUtils.selectRandomSingleAreaTemplate(candidates, firstRandom));
            secondSequence.add(JsonDBUtils.selectRandomSingleAreaTemplate(candidates, secondRandom));
        }

        assertEquals(firstSequence, secondSequence);
        assertTrue(firstSequence.stream().allMatch(template -> template.getAreas().size() == 1));
        assertEquals(3, candidates.size());
    }

    @Test
    void noSingleAreaTemplateCurrentlyThrowsWithoutUsingRandom() {
        List<Template> candidates = new ArrayList<>(List.of(template(1, 10, 2), template(2, 10, 0)));
        RandomGenerator random = mock(RandomGenerator.class);

        // Known Phase 0 failure: preview workflows do not currently turn this exception into NONE.
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> JsonDBUtils.selectRandomSingleAreaTemplate(candidates, random));

        assertEquals("No single area templates found", failure.getMessage());
        assertEquals(2, candidates.size());
        verifyNoInteractions(random);
    }

    @Test
    void defaultSingleAreaOverloadStillSelectsTheOnlyEligibleCandidate() {
        Template single = template(2, 10, 1);
        List<Template> candidates = new ArrayList<>(List.of(template(1, 10, 2), single));

        assertSame(single, JsonDBUtils.selectRandomSingleAreaTemplate(candidates));
        assertEquals(2, candidates.size());
    }

    @Test
    void entityComparatorTreatsNullOriginAsTimestampZeroForSourcesAndTemplates() {
        Source importedSource = source(1, 10);
        Template importedTemplate = template(2, 10, 1);
        Source epochSource = source(3, 10);
        epochSource.setMessage(message(0));
        Template newerTemplate = template(4, 10, 1);
        newerTemplate.setMessage(message(200));
        Source olderSource = source(5, 10);
        olderSource.setMessage(message(100));
        Comparator<CommonEntity> comparator = JsonDBUtils.getComparator();

        assertEquals(0, comparator.compare(importedSource, epochSource));
        assertEquals(0, comparator.compare(importedTemplate, epochSource));
        assertEquals(0, comparator.compare(importedSource, importedTemplate));
        assertTrue(comparator.compare(importedSource, olderSource) < 0);
        assertEquals(List.of(importedTemplate, olderSource, newerTemplate),
            List.<CommonEntity>of(newerTemplate, importedTemplate, olderSource).stream().sorted(comparator).toList());
    }

    @Test
    void memeComparatorTreatsNullOriginAsTimestampZeroAndSupportsNewestFirstHistory() {
        Meme imported = Meme.builder().id(new UUID(1, 1)).message(null).build();
        Meme epoch = Meme.builder().id(new UUID(1, 2)).message(message(0)).build();
        Meme older = Meme.builder().id(new UUID(1, 3)).message(message(100)).build();
        Meme newer = Meme.builder().id(new UUID(1, 4)).message(message(200)).build();
        Comparator<Meme> comparator = JsonDBUtils.getMemeComparator();

        assertEquals(0, comparator.compare(imported, epoch));
        assertTrue(comparator.compare(imported, older) < 0);
        assertEquals(List.of(newer, older, imported),
            List.of(older, imported, newer).stream().sorted(comparator.reversed()).toList());
    }

    private Source source(int id, int weight) {
        Source source = new Source();
        source.setId(new UUID(0, id));
        source.setDescription("source " + id);
        source.setStatus(Status.APPROVED);
        source.setWeight(weight);
        return source;
    }

    private Template template(int id, int weight, int areaCount) {
        List<TemplateArea> areas = new ArrayList<>();
        for (int i = 1; i <= areaCount; i++) {
            areas.add(TemplateArea.builder().index(i).source(i).build());
        }
        Template template = Template.builder().areas(areas).build();
        template.setId(new UUID(0, id));
        template.setStatus(Status.APPROVED);
        template.setWeight(weight);
        return template;
    }

    private Message message(int date) {
        Message message = new Message();
        message.setDate(date);
        return message;
    }

    private RandomGenerator removalSequenceRandom() {
        RandomGenerator random = mock(RandomGenerator.class);
        when(random.nextInt(10)).thenReturn(2); // Select weight-3 candidate in [2, 5).
        when(random.nextInt(7)).thenReturn(2); // Then select weight-5 candidate in [2, 7).
        when(random.nextInt(2)).thenReturn(1); // Only weight-2 candidate remains.
        return random;
    }

    private void verifyRemovalBounds(RandomGenerator random) {
        verify(random).nextInt(10);
        verify(random).nextInt(7);
        verify(random).nextInt(2);
        verifyNoMoreInteractions(random);
    }
}