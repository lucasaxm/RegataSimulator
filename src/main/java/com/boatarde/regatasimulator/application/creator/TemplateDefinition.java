package com.boatarde.regatasimulator.application.creator;

import java.util.List;
import java.util.UUID;

/** Versioned contract only: no persistence, CSV projection or renderer integration. */
public record TemplateDefinition(int definitionVersion, CoordinateSpace coordinateSpace,
                                 Canvas canvas, List<Area> areas) {
    public TemplateDefinition {
        if (areas != null) {
            try {
                areas = List.copyOf(areas);
            } catch (NullPointerException exception) {
                throw new TemplateDefinitionValidator.InvalidDefinition();
            }
        }
    }

    public enum CoordinateSpace { ORIGINAL_IMAGE_PIXELS }
    public enum Plane { BACKGROUND, FOREGROUND }
    public enum Align { LEFT, CENTER, RIGHT }
    public enum VerticalAlign { TOP, MIDDLE, BOTTOM }
    public enum Overflow { SHRINK_TO_FIT }

    public record Canvas(int width, int height) { }
    public record Point(int x, int y) { }
    public record Corners(Point topLeft, Point topRight, Point bottomRight, Point bottomLeft) { }
    public record Bounds(int x, int y, int width, int height) { }

    public sealed interface Area permits ImageArea, TextArea {
        UUID id();
        Integer sourceSlot();
        Plane plane();
    }

    public record ImageArea(UUID id, Integer sourceSlot, Plane plane, Corners corners) implements Area { }
    public record TextArea(UUID id, Integer sourceSlot, Plane plane, Bounds bounds,
                           String textPattern, Style style) implements Area { }
    public record Outline(String color, int width) { }

    /**
     * catalog-v1 and its Liberation IDs are provisional contract allowlists, not shipped font
     * assets or published capabilities. These fields do not imply implemented native rendering.
     * All style fields are required except boxFill (absent on the wire means null).
     */
    public record Style(String fontCatalogVersion, String fontId, int fontWeight, int fontSize,
                        String color, Outline outline, String boxFill, Align align,
                        VerticalAlign verticalAlign, double lineHeight, int padding, Overflow overflow) { }
}
