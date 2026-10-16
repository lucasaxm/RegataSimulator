package com.boatarde.regatasimulator.application.creator;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

import static com.boatarde.regatasimulator.application.creator.TemplateDefinition.*;
import static com.boatarde.regatasimulator.application.creator.TemplateDefinitionValidator.*;

/** Closed manual Jackson 2 tree codec. Never uses default typing, reflection binding or a global mapper. */
public final class TemplateDefinitionCodec {
    public static final int MAX_BYTES = 256 * 1024;
    public static final int MAX_DEPTH = 16;
    public static final int MAX_NUMBER_LENGTH = 64;
    private final ObjectMapper mapper = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(MAX_DEPTH)
            .maxNumberLength(MAX_NUMBER_LENGTH).maxStringLength(MAX_PATTERN_LENGTH).maxNameLength(128).build())
        .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public TemplateDefinition decode(byte[] bytes) {
        require(bytes != null && bytes.length > 0 && bytes.length <= MAX_BYTES);
        try {
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return parse(json);
        } catch (CharacterCodingException exception) {
            throw new InvalidDefinition();
        }
    }

    public TemplateDefinition decode(String json) {
        require(json != null && !json.isEmpty() && json.length() <= MAX_BYTES);
        try {
            ByteBuffer utf8 = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(json));
            require(utf8.remaining() <= MAX_BYTES);
            return parse(json);
        } catch (CharacterCodingException exception) {
            throw new InvalidDefinition();
        }
    }

    private TemplateDefinition parse(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            fields(root, Set.of("definitionVersion", "coordinateSpace", "canvas", "areas"));
            JsonNode canvas = root.get("canvas");
            fields(canvas, Set.of("width", "height"));
            JsonNode array = root.get("areas");
            require(array.isArray() && !array.isEmpty() && array.size() <= MAX_AREAS);
            var areas = new ArrayList<Area>(array.size());
            for (JsonNode area : array) areas.add(area(area));
            var definition = new TemplateDefinition(integer(root, "definitionVersion"),
                enumeration(root, "coordinateSpace", CoordinateSpace.class),
                new Canvas(integer(canvas, "width"), integer(canvas, "height")), areas);
            validate(definition);
            return definition;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new InvalidDefinition();
        }
    }

    private Area area(JsonNode node) {
        require(node != null && node.isObject());
        String kind = string(node, "kind");
        switch (kind) {
            case "image" -> fields(node, Set.of("id", "kind", "sourceSlot", "plane", "corners"));
            case "text" -> fields(node, Set.of("id", "kind", "sourceSlot", "plane", "bounds", "textPattern", "style"));
            default -> throw new InvalidDefinition();
        }
        String id = string(node, "id");
        UUID uuid = UUID.fromString(id);
        require(uuid.toString().equalsIgnoreCase(id));
        Integer slot = node.get("sourceSlot").isNull() ? null : integer(node, "sourceSlot");
        Plane plane = enumeration(node, "plane", Plane.class);
        if (kind.equals("image")) {
            JsonNode corners = node.get("corners");
            fields(corners, Set.of("topLeft", "topRight", "bottomRight", "bottomLeft"));
            return new ImageArea(uuid, slot, plane, new Corners(point(corners.get("topLeft")),
                point(corners.get("topRight")), point(corners.get("bottomRight")), point(corners.get("bottomLeft"))));
        }
        JsonNode bounds = node.get("bounds");
        fields(bounds, Set.of("x", "y", "width", "height"));
        return new TextArea(uuid, slot, plane, new Bounds(integer(bounds, "x"), integer(bounds, "y"),
            integer(bounds, "width"), integer(bounds, "height")), string(node, "textPattern"), style(node.get("style")));
    }

    private Point point(JsonNode node) {
        fields(node, Set.of("x", "y"));
        return new Point(integer(node, "x"), integer(node, "y"));
    }

    private Style style(JsonNode node) {
        Set<String> required = Set.of("fontCatalogVersion", "fontId", "fontWeight", "fontSize", "color", "outline",
            "align", "verticalAlign", "lineHeight", "padding", "overflow");
        fields(node, required, Set.of("boxFill"));
        JsonNode outline = node.get("outline");
        fields(outline, Set.of("color", "width"));
        JsonNode lineHeight = node.get("lineHeight");
        require(lineHeight.isNumber() && Double.isFinite(lineHeight.doubleValue()));
        String fill = !node.has("boxFill") || node.get("boxFill").isNull() ? null : string(node, "boxFill");
        return new Style(string(node, "fontCatalogVersion"), string(node, "fontId"), integer(node, "fontWeight"),
            integer(node, "fontSize"), string(node, "color"), new Outline(string(outline, "color"),
            integer(outline, "width")), fill, enumeration(node, "align", Align.class),
            enumeration(node, "verticalAlign", VerticalAlign.class), lineHeight.doubleValue(), integer(node, "padding"),
            enumeration(node, "overflow", Overflow.class));
    }

    private static void fields(JsonNode node, Set<String> required) {
        fields(node, required, Set.of());
    }

    private static void fields(JsonNode node, Set<String> required, Set<String> optional) {
        require(node != null && node.isObject());
        node.fieldNames().forEachRemaining(name -> require(required.contains(name) || optional.contains(name)));
        for (String name : required) require(node.has(name));
    }

    private static int integer(JsonNode node, String name) {
        JsonNode value = node.get(name);
        require(value != null && value.isIntegralNumber() && value.canConvertToInt());
        return value.intValue();
    }

    private static String string(JsonNode node, String name) {
        JsonNode value = node.get(name);
        require(value != null && value.isTextual() && value.textValue().length() <= MAX_PATTERN_LENGTH);
        return value.textValue();
    }

    private static <E extends Enum<E>> E enumeration(JsonNode node, String name, Class<E> type) {
        return Enum.valueOf(type, string(node, name));
    }

    /** Explicit field order for round-trips; not a versioned idempotency fingerprint contract. */
    public byte[] encode(TemplateDefinition definition) {
        validate(definition);
        ObjectNode root = mapper.createObjectNode();
        root.put("definitionVersion", definition.definitionVersion());
        root.put("coordinateSpace", definition.coordinateSpace().name());
        root.putObject("canvas").put("width", definition.canvas().width()).put("height", definition.canvas().height());
        var areas = root.putArray("areas");
        for (Area area : definition.areas()) {
            ObjectNode node = areas.addObject();
            node.put("id", area.id().toString());
            node.put("kind", area instanceof ImageArea ? "image" : "text");
            if (area.sourceSlot() == null) node.putNull("sourceSlot");
            else node.put("sourceSlot", area.sourceSlot());
            node.put("plane", area.plane().name());
            switch (area) {
                case ImageArea image -> {
                    ObjectNode corners = node.putObject("corners");
                    putPoint(corners, "topLeft", image.corners().topLeft());
                    putPoint(corners, "topRight", image.corners().topRight());
                    putPoint(corners, "bottomRight", image.corners().bottomRight());
                    putPoint(corners, "bottomLeft", image.corners().bottomLeft());
                }
                case TextArea text -> {
                    Bounds bounds = text.bounds();
                    node.putObject("bounds").put("x", bounds.x()).put("y", bounds.y())
                        .put("width", bounds.width()).put("height", bounds.height());
                    node.put("textPattern", text.textPattern());
                    putStyle(node.putObject("style"), text.style());
                }
            }
        }
        try {
            byte[] bytes = mapper.writeValueAsBytes(root);
            require(bytes.length <= MAX_BYTES);
            return bytes;
        } catch (JsonProcessingException exception) {
            throw new InvalidDefinition();
        }
    }

    private static void putPoint(ObjectNode parent, String name, Point point) {
        parent.putObject(name).put("x", point.x()).put("y", point.y());
    }

    private static void putStyle(ObjectNode node, Style style) {
        node.put("fontCatalogVersion", style.fontCatalogVersion()).put("fontId", style.fontId())
            .put("fontWeight", style.fontWeight()).put("fontSize", style.fontSize()).put("color", style.color());
        node.putObject("outline").put("color", style.outline().color()).put("width", style.outline().width());
        if (style.boxFill() == null) node.putNull("boxFill");
        else node.put("boxFill", style.boxFill());
        node.put("align", style.align().name()).put("verticalAlign", style.verticalAlign().name())
            .put("lineHeight", style.lineHeight()).put("padding", style.padding()).put("overflow", style.overflow().name());
    }
}
