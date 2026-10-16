package com.boatarde.regatasimulator.application.creator;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static com.boatarde.regatasimulator.application.creator.TemplateDefinition.*;
import static org.junit.jupiter.api.Assertions.*;

class TemplateDefinitionTest {
    static final UUID FIRST = UUID.fromString("a1111111-1111-4111-8111-111111111111");
    static final UUID SECOND = UUID.fromString("b2222222-2222-4222-8222-222222222222");
    static final UUID THIRD = UUID.fromString("c3333333-3333-4333-8333-333333333333");

    static TemplateDefinition definition(Area... areas) {
        return new TemplateDefinition(1, CoordinateSpace.ORIGINAL_IMAGE_PIXELS, new Canvas(1000, 800), List.of(areas));
    }

    static Corners rectangle(int x, int y, int width, int height) {
        return new Corners(new Point(x, y), new Point(x + width, y),
            new Point(x + width, y + height), new Point(x, y + height));
    }

    static ImageArea image(UUID id, Integer slot) {
        return new ImageArea(id, slot, Plane.FOREGROUND, rectangle(50, 50, 350, 350));
    }

    static Style style() {
        return new Style("catalog-v1", "liberation-sans", 700, 32, "#FFFFFF", new Outline("#000000", 2),
            null, Align.CENTER, VerticalAlign.TOP, 1.2, 4, Overflow.SHRINK_TO_FIT);
    }

    static TextArea text(UUID id, Integer slot, String pattern) {
        return new TextArea(id, slot, Plane.BACKGROUND, new Bounds(50, 420, 600, 100), pattern, style());
    }

    @Test
    void listIsDefensivelyCopiedAndEveryValueTypeIsImmutable() {
        var original = new ArrayList<Area>();
        original.add(image(FIRST, 1));
        var definition = new TemplateDefinition(1, CoordinateSpace.ORIGINAL_IMAGE_PIXELS, new Canvas(1000, 800), original);
        original.clear();
        assertEquals(1, definition.areas().size());
        assertThrows(UnsupportedOperationException.class, () -> definition.areas().clear());
        for (Class<?> type : List.of(TemplateDefinition.class, Canvas.class, Point.class, Corners.class, Bounds.class,
            ImageArea.class, TextArea.class, Style.class, Outline.class)) {
            assertTrue(type.isRecord(), type.getSimpleName());
        }
        assertTrue(Area.class.isSealed());
        assertEquals(2, Area.class.getPermittedSubclasses().length);
    }

    @Test
    void nullListAndRecordFieldsAreRejectedByValidatorWithoutNullPointerLeakage() {
        var invalid = List.of(
            new TemplateDefinition(1, CoordinateSpace.ORIGINAL_IMAGE_PIXELS, new Canvas(1000, 800), null),
            definition(new ImageArea(null, 1, Plane.FOREGROUND, rectangle(0, 0, 10, 10))),
            definition(new ImageArea(FIRST, 1, null, rectangle(0, 0, 10, 10))),
            definition(new ImageArea(FIRST, 1, Plane.FOREGROUND, null)),
            definition(new ImageArea(FIRST, 1, Plane.FOREGROUND, new Corners(null, null, null, null))),
            definition(new TextArea(FIRST, null, Plane.FOREGROUND, null, "literal", style())),
            definition(new TextArea(FIRST, null, Plane.FOREGROUND, new Bounds(0, 0, 10, 10), null, style())),
            definition(new TextArea(FIRST, null, Plane.FOREGROUND, new Bounds(0, 0, 10, 10), "literal", null)));
        for (TemplateDefinition value : invalid) {
            assertThrows(TemplateDefinitionValidator.InvalidDefinition.class, () -> TemplateDefinitionValidator.validate(value));
        }
        assertThrows(TemplateDefinitionValidator.InvalidDefinition.class, () ->
            new TemplateDefinition(1, CoordinateSpace.ORIGINAL_IMAGE_PIXELS, new Canvas(1, 1), Arrays.asList((Area) null)));
    }
}
