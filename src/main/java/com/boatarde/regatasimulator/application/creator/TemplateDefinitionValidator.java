package com.boatarde.regatasimulator.application.creator;

import com.boatarde.regatasimulator.models.AreaCorner;
import com.boatarde.regatasimulator.models.TemplateArea;
import com.boatarde.regatasimulator.util.MediaValidation;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.boatarde.regatasimulator.application.creator.TemplateDefinition.*;

/** Pure admission checks; fitting, expanded-description limits and glyph/work safety remain future work. */
public final class TemplateDefinitionValidator {
    public static final int MAX_AREAS = 128;
    public static final int MAX_CANVAS_SIDE = 10_000;
    public static final long MAX_CANVAS_PIXELS = 40_000_000;
    public static final int MAX_COORDINATE = 10_000;
    // Provisional adoption of experiment input bounds, NOT native capacity or fitting proof.
    public static final int MAX_PATTERN_LENGTH = 4096;
    public static final int MAX_TEXT_SIDE = 2048;
    public static final long MAX_TEXT_PIXELS = 2_000_000;
    public static final int MAX_FONT_SIZE = 128;
    public static final int MAX_OUTLINE_WIDTH = 4;
    public static final int MAX_PADDING = 64;
    public static final String DESCRIPTION_TOKEN = "{{source.description}}";
    private static final Set<String> FONT_IDS = Set.of("liberation-sans", "liberation-serif", "liberation-mono");

    private TemplateDefinitionValidator() { }

    /** Returns K for the union 1..K; zero means an all-literal definition, not an absent source list. */
    public static int validate(TemplateDefinition definition) {
        return validate(definition, null);
    }

    /** A null decoded size supports contract-only validation; the declared canvas is always checked. */
    public static int validate(TemplateDefinition definition, MediaValidation.Dimensions decoded) {
        require(definition != null);
        require(definition.definitionVersion() == 1);
        require(definition.coordinateSpace() == CoordinateSpace.ORIGINAL_IMAGE_PIXELS);
        Canvas canvas = definition.canvas();
        require(canvas != null);
        require(canvas.width() >= 1 && canvas.width() <= MAX_CANVAS_SIDE
            && canvas.height() >= 1 && canvas.height() <= MAX_CANVAS_SIDE
            && (long) canvas.width() * canvas.height() <= MAX_CANVAS_PIXELS);
        require(decoded == null || (decoded.width() == canvas.width() && decoded.height() == canvas.height()));
        List<Area> areas = definition.areas();
        require(areas != null && !areas.isEmpty() && areas.size() <= MAX_AREAS);
        Set<UUID> ids = new HashSet<>();
        Set<Integer> slots = new HashSet<>();
        for (Area area : areas) {
            require(area != null && area.id() != null && ids.add(area.id()) && area.plane() != null);
            if (area.sourceSlot() != null) {
                require(area.sourceSlot() >= 1 && area.sourceSlot() <= MAX_AREAS);
                slots.add(area.sourceSlot());
            }
            switch (area) {
                case ImageArea image -> validateImage(image, canvas);
                case TextArea text -> validateText(text, canvas);
            }
        }
        for (int slot = 1; slot <= slots.size(); slot++) {
            require(slots.contains(slot));
        }
        return slots.size();
    }

    private static void validateImage(ImageArea image, Canvas canvas) {
        require(image.sourceSlot() != null && image.corners() != null);
        Corners corners = image.corners();
        // Copy values into a SINGLE temporary legacy area: its local slot/index are deliberately 1.
        // Never project the mixed definition to Template.areas or apply image-only global slot rules.
        TemplateArea temporary = new TemplateArea(1, 1, corner(corners.topLeft()), corner(corners.topRight()),
            corner(corners.bottomRight()), corner(corners.bottomLeft()), image.plane() == Plane.BACKGROUND);
        try {
            MediaValidation.geometry(List.of(temporary), new MediaValidation.Dimensions(canvas.width(), canvas.height()));
        } catch (IOException exception) {
            throw new InvalidDefinition();
        }
    }

    private static AreaCorner corner(Point point) {
        require(point != null);
        return new AreaCorner(point.x(), point.y());
    }

    private static void validateText(TextArea text, Canvas canvas) {
        Bounds bounds = text.bounds();
        require(bounds != null);
        long right = (long) bounds.x() + bounds.width();
        long bottom = (long) bounds.y() + bounds.height();
        require(bounds.width() >= 1 && bounds.width() <= MAX_TEXT_SIDE
            && bounds.height() >= 1 && bounds.height() <= MAX_TEXT_SIDE
            && (long) bounds.width() * bounds.height() <= MAX_TEXT_PIXELS);
        require(bounded(bounds.x()) && bounded(bounds.y()) && bounded(right) && bounded(bottom));
        require(Math.min(right, canvas.width()) > Math.max(bounds.x(), 0)
            && Math.min(bottom, canvas.height()) > Math.max(bounds.y(), 0));
        String pattern = text.textPattern();
        validatePatternCharacters(pattern);
        boolean dynamic = false;
        for (int i = 0; i < pattern.length();) {
            if (pattern.startsWith(DESCRIPTION_TOKEN, i)) {
                dynamic = true;
                i += DESCRIPTION_TOKEN.length();
            } else {
                // Single braces are literal. Any unmatched double brace is malformed template syntax.
                require(!pattern.startsWith("{{", i) && !pattern.startsWith("}}", i));
                i++;
            }
        }
        // Literal-only binding is deliberately forbidden: it must consume no unused source.
        require(dynamic == (text.sourceSlot() != null));
        validateStyle(text.style());
    }

    private static boolean bounded(long value) {
        return value >= -MAX_COORDINATE && value <= MAX_COORDINATE;
    }

    private static void validatePatternCharacters(String value) {
        require(value != null && !value.isBlank() && value.length() <= MAX_PATTERN_LENGTH);
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            require(character == '\n' || !Character.isISOControl(character));
            if (Character.isHighSurrogate(character)) {
                require(i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1)));
                i++;
            } else {
                require(!Character.isLowSurrogate(character));
            }
        }
    }

    private static void validateStyle(Style style) {
        require(style != null);
        require("catalog-v1".equals(style.fontCatalogVersion()));
        require(style.fontId() != null && FONT_IDS.contains(style.fontId()));
        require(style.fontWeight() == 400 || style.fontWeight() == 700);
        require(style.fontSize() >= 1 && style.fontSize() <= MAX_FONT_SIZE);
        color(style.color());
        require(style.outline() != null);
        color(style.outline().color());
        require(style.outline().width() >= 0 && style.outline().width() <= MAX_OUTLINE_WIDTH);
        if (style.boxFill() != null) color(style.boxFill());
        require(style.align() != null && style.verticalAlign() != null && style.overflow() == Overflow.SHRINK_TO_FIT);
        require(Double.isFinite(style.lineHeight()) && style.lineHeight() >= 1 && style.lineHeight() <= 2);
        require(style.padding() >= 0 && style.padding() <= MAX_PADDING);
    }

    private static void color(String color) {
        require(color != null && color.matches("#[0-9a-fA-F]{6}"));
    }

    static void require(boolean condition) {
        if (!condition) throw new InvalidDefinition();
    }

    /** No submitted value or underlying parser/geometry cause is exposed. */
    public static final class InvalidDefinition extends IllegalArgumentException {
        public InvalidDefinition() {
            super("Invalid template definition");
        }
    }
}
