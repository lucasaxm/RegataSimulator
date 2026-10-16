package com.boatarde.regatasimulator.application.creator;

import com.boatarde.regatasimulator.util.MediaValidation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.boatarde.regatasimulator.application.creator.TemplateDefinition.*;
import static com.boatarde.regatasimulator.application.creator.TemplateDefinitionTest.*;
import static com.boatarde.regatasimulator.application.creator.TemplateDefinitionValidator.*;
import static org.junit.jupiter.api.Assertions.*;

class TemplateDefinitionValidatorTest {
    @Test
    void mixedUnionCountsEachSlotOnceIndependentlyOfImageAreasAndOrder() {
        var value = definition(text(SECOND, 1, "Olá {{source.description}}"), image(FIRST, 2),
            text(THIRD, 2, "{{source.description}} / {{source.description}}"));
        assertEquals(2, validate(value));
        assertEquals(List.of(SECOND, FIRST, THIRD), value.areas().stream().map(Area::id).toList());
        assertEquals(1, validate(definition(text(FIRST, 1, "{{source.description}}"),
            text(SECOND, 1, "Nome: {{source.description}}"))));
        assertEquals(0, validate(definition(text(FIRST, null, "Olá\nCafe\u0301 {literal} % @ \\"))));
    }

    @Test
    void allAreaAndSlotBoundariesInclude128AndLiteralZeroButNotEmpty() {
        var areas = IntStream.rangeClosed(1, 128)
            .mapToObj(i -> (Area) image(new UUID(0, i), i)).toList();
        assertEquals(128, validate(new TemplateDefinition(1, CoordinateSpace.ORIGINAL_IMAGE_PIXELS,
            new Canvas(1000, 800), areas)));
        assertEquals(1, validate(definition(image(FIRST, 1), image(SECOND, 1))));
        invalid(definition());
        var tooMany = IntStream.rangeClosed(1, 129).mapToObj(i -> (Area) text(new UUID(0, i), null, "literal")).toList();
        invalid(new TemplateDefinition(1, CoordinateSpace.ORIGINAL_IMAGE_PIXELS, new Canvas(1000, 800), tooMany));
        for (Integer slot : new Integer[] {null, 0, -1, 2, 129, Integer.MAX_VALUE}) {
            invalid(definition(image(FIRST, slot)));
        }
        invalid(definition(image(FIRST, 1), text(SECOND, 3, "{{source.description}}")));
        invalid(definition(image(FIRST, 1), text(FIRST, null, "literal")));
        invalid(definition(text(FIRST, null, "{{source.description}}")));
        invalid(definition(text(FIRST, 1, "unused binding")));
    }

    @Test
    void validatesCanvasEvenWithoutDecodedImageAndChecksExactDecodedMatch() {
        var image = image(FIRST, 1);
        for (Canvas canvas : List.of(new Canvas(0, 1), new Canvas(1, 0), new Canvas(-1, 1),
            new Canvas(10001, 1), new Canvas(1, 10001), new Canvas(10000, 4001),
            new Canvas(Integer.MAX_VALUE, Integer.MAX_VALUE))) {
            invalid(new TemplateDefinition(1, CoordinateSpace.ORIGINAL_IMAGE_PIXELS, canvas, List.of(image)));
        }
        assertEquals(1, validate(new TemplateDefinition(1, CoordinateSpace.ORIGINAL_IMAGE_PIXELS,
            new Canvas(10000, 4000), List.of(image))));
        assertEquals(0, validate(new TemplateDefinition(1, CoordinateSpace.ORIGINAL_IMAGE_PIXELS,
            new Canvas(1, 1), List.of(new TextArea(FIRST, null, Plane.FOREGROUND,
                new Bounds(0, 0, 1, 1), "x", style())))));
        assertEquals(1, validate(definition(image), new MediaValidation.Dimensions(1000, 800)));
        assertThrows(InvalidDefinition.class, () -> validate(definition(image), new MediaValidation.Dimensions(800, 1000)));
        assertThrows(InvalidDefinition.class, () -> validate(definition(image), new MediaValidation.Dimensions(0, 0)));
        invalid(null);
        invalid(new TemplateDefinition(2, CoordinateSpace.ORIGINAL_IMAGE_PIXELS, new Canvas(1000, 800), List.of(image)));
        invalid(new TemplateDefinition(1, null, new Canvas(1000, 800), List.of(image)));
        invalid(new TemplateDefinition(1, CoordinateSpace.ORIGINAL_IMAGE_PIXELS, null, List.of(image)));
    }

    @ParameterizedTest
    @MethodSource("invalidQuads")
    void delegatesConvexityBoundsAndPositiveOverlapToExistingGeometry(Corners corners) {
        invalid(definition(new ImageArea(FIRST, 1, Plane.FOREGROUND, corners)));
    }

    static Stream<Corners> invalidQuads() {
        return Stream.of(rectangle(-10001, 0, 10010, 10), rectangle(Integer.MIN_VALUE, 0, 10, 10),
            rectangle(0, 0, 0, 10), rectangle(0, 0, 10, 0), rectangle(1000, 0, 10, 10),
            rectangle(1000, 800, 10, 10), rectangle(-20, 0, 10, 10), rectangle(0, 800, 10, 10),
            new Corners(new Point(0, 0), new Point(20, 20), new Point(20, 0), new Point(0, 20)),
            new Corners(new Point(0, 0), new Point(20, 0), new Point(5, 5), new Point(0, 20)),
            new Corners(new Point(998, 804), new Point(1004, 798), new Point(1008, 802), new Point(1002, 808)));
    }

    @Test
    void acceptsPartialOffCanvasInBothWindingsWithoutChangingImmutablePoints() {
        Corners corners = rectangle(-10000, -10000, 10001, 10001);
        assertEquals(1, validate(definition(new ImageArea(FIRST, 1, Plane.BACKGROUND, corners))));
        Corners reversed = new Corners(corners.topLeft(), corners.bottomLeft(), corners.bottomRight(), corners.topRight());
        assertEquals(1, validate(definition(new ImageArea(FIRST, 1, Plane.FOREGROUND, reversed))));
        assertEquals(new Point(-10000, -10000), corners.topLeft());
        assertEquals(0, validate(withBounds(new Bounds(-10, -10, 20, 20))));
    }

    @ParameterizedTest
    @MethodSource("invalidBounds")
    void textBoundsUseLongArithmeticAndRequirePositiveAreaIntersection(Bounds bounds) {
        invalid(withBounds(bounds));
    }

    static Stream<Bounds> invalidBounds() {
        return Stream.of(new Bounds(0, 0, 0, 1), new Bounds(0, 0, 1, -1), new Bounds(0, 0, 2049, 1),
            new Bounds(0, 0, 1, 2049), new Bounds(0, 0, 2000, 1001), new Bounds(-10001, 0, 1, 1),
            new Bounds(9999, 0, 2, 1), new Bounds(0, 9999, 1, 2), new Bounds(1000, 0, 10, 10),
            new Bounds(1000, 800, 10, 10), new Bounds(-10, 0, 10, 10), new Bounds(0, 800, 10, 10),
            new Bounds(-20, 0, 10, 10), new Bounds(Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 1),
            new Bounds(Integer.MIN_VALUE, Integer.MIN_VALUE, 1, 1));
    }

    @Test
    void textSidePixelAndLengthLimitsAreInclusive() {
        assertEquals(0, validate(withBounds(new Bounds(0, 0, 2048, 1))));
        assertEquals(0, validate(withBounds(new Bounds(0, 0, 1, 2048))));
        assertEquals(0, validate(withBounds(new Bounds(0, 0, 2000, 1000))));
        assertEquals(0, validate(definition(text(FIRST, null, "é".repeat(4096)))));
        invalid(definition(text(FIRST, null, "x".repeat(4097))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \n", "{{}}", "{{source.name}}", "{{ source.description }}", "{{source.description",
        "source.description}}", "{{{{source.description}}}}", "{{source.description}} {{bad}}",
        "x\t", "x\r", "x\u0000", "x\u007f", "x\u0085"})
    void rejectsUnknownMalformedTokensBlankTextAndControls(String pattern) {
        Integer slot = pattern.contains(DESCRIPTION_TOKEN) ? 1 : null;
        invalid(definition(text(FIRST, slot, pattern)));
    }

    @Test
    void rejectsMalformedUtf16ButPreservesNewlinesNfdAndSupplementaryCharacters() {
        invalid(definition(text(FIRST, null, "x" + (char) 0xd800)));
        invalid(definition(text(FIRST, null, "x" + (char) 0xdc00)));
        invalid(definition(text(FIRST, null, "x" + (char) 0xd800 + "y")));
        String pattern = "Cafe\u0301\nOlá 😀 % [x] @sentinel \\";
        var text = text(FIRST, null, pattern);
        assertEquals(0, validate(definition(text)));
        assertEquals(pattern, text.textPattern());
    }

    @Test
    void rejectsNonfiniteLineHeightForDirectModelCallers() {
        for (double height : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.99, 2.01}) {
            Style original = style();
            Style bad = new Style(original.fontCatalogVersion(), original.fontId(), original.fontWeight(), original.fontSize(),
                original.color(), original.outline(), original.boxFill(), original.align(), original.verticalAlign(), height,
                original.padding(), original.overflow());
            invalid(definition(new TextArea(FIRST, null, Plane.FOREGROUND, new Bounds(0, 0, 10, 10), "literal", bad)));
        }
    }

    private static TemplateDefinition withBounds(Bounds bounds) {
        return definition(new TextArea(FIRST, null, Plane.FOREGROUND, bounds, "literal", style()));
    }

    private static void invalid(TemplateDefinition value) {
        InvalidDefinition exception = assertThrows(InvalidDefinition.class, () -> validate(value));
        assertEquals("Invalid template definition", exception.getMessage());
        assertNull(exception.getCause());
    }
}
