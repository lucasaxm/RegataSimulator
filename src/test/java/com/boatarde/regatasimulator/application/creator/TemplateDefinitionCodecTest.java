package com.boatarde.regatasimulator.application.creator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.boatarde.regatasimulator.application.creator.TemplateDefinition.*;
import static com.boatarde.regatasimulator.application.creator.TemplateDefinitionTest.*;
import static com.boatarde.regatasimulator.application.creator.TemplateDefinitionValidator.*;
import static org.junit.jupiter.api.Assertions.*;

class TemplateDefinitionCodecTest {
    private final TemplateDefinitionCodec codec = new TemplateDefinitionCodec();
    // Test-local tree mutations never touch the production mapper or application configuration.
    private final ObjectMapper trees = new ObjectMapper();

    @Test
    void roundTripsImageMixedSharedTextOnlyAndLiteralZeroExactly() {
        String nfd = "Cafe\u0301\nOlá % [x] @sentinel \\";
        for (TemplateDefinition value : List.of(definition(image(FIRST, 1)),
            definition(text(SECOND, 1, "Olá {{source.description}}"), image(FIRST, 2),
                text(THIRD, 2, "{{source.description}} / {{source.description}}")),
            definition(text(FIRST, 1, "{{source.description}}"), text(SECOND, 1, "{{source.description}}")),
            definition(text(FIRST, null, nfd)))) {
            byte[] encoded = codec.encode(value);
            assertEquals(value, codec.decode(encoded));
            assertEquals(value, codec.decode(new String(encoded, StandardCharsets.UTF_8)));
            assertArrayEquals(encoded, codec.encode(codec.decode(encoded)));
        }
        assertEquals(nfd, ((TextArea) codec.decode(codec.encode(definition(text(FIRST, null, nfd)))).areas().getFirst()).textPattern());
    }

    @Test
    void readsAuthoritativeWireWithoutReflectionOrImplicitClassDiscriminators() {
        String json = """
            {"definitionVersion":1,"coordinateSpace":"ORIGINAL_IMAGE_PIXELS","canvas":{"width":1000,"height":800},
             "areas":[{"id":"a1111111-1111-4111-8111-111111111111","kind":"image","sourceSlot":1,
             "plane":"FOREGROUND","corners":{"topLeft":{"x":50,"y":50},"topRight":{"x":400,"y":50},
             "bottomRight":{"x":400,"y":400},"bottomLeft":{"x":50,"y":400}}}]}
            """;
        assertEquals(definition(image(FIRST, 1)), codec.decode(json));
        invalid(json.replace("\"kind\":\"image\"", "\"kind\":\"com.example.ImageArea\""));
        invalid(json.replace("\"kind\":\"image\"", "\"kind\":\"text\""));
    }

    @Test
    void onlyBoxFillMayBeAbsentAndCanonicalEncodingMakesItsNullExplicit() throws Exception {
        ObjectNode root = textTree();
        ((ObjectNode) root.at("/areas/0/style")).remove("boxFill");
        TemplateDefinition decoded = codec.decode(root.toString());
        assertNull(((TextArea) decoded.areas().getFirst()).style().boxFill());
        assertTrue(trees.readTree(codec.encode(decoded)).at("/areas/0/style/boxFill").isNull());
        for (String parent : List.of("", "/canvas", "/areas/0", "/areas/0/bounds", "/areas/0/style", "/areas/0/style/outline")) {
            ObjectNode original = (ObjectNode) textTree().at(parent);
            var names = new java.util.ArrayList<String>();
            original.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                if (name.equals("boxFill")) continue;
                ObjectNode missing = textTree();
                ((ObjectNode) missing.at(parent)).remove(name);
                invalid(missing.toString());
                ObjectNode explicitNull = textTree();
                ((ObjectNode) explicitNull.at(parent)).putNull(name);
                // Literal text explicitly requires a present null sourceSlot.
                if (!parent.equals("/areas/0") || !name.equals("sourceSlot")) invalid(explicitNull.toString());
            }
        }
    }

    @Test
    void rejectsUnknownPropertiesAndDuplicatesAtEveryObjectLevel() throws Exception {
        for (boolean imageOnly : new boolean[] {true, false}) {
            List<String> paths = imageOnly ? List.of("", "/canvas", "/areas/0", "/areas/0/corners",
                "/areas/0/corners/topLeft", "/areas/0/corners/topRight", "/areas/0/corners/bottomRight", "/areas/0/corners/bottomLeft")
                : List.of("", "/canvas", "/areas/0", "/areas/0/bounds", "/areas/0/style", "/areas/0/style/outline");
            for (String path : paths) {
                ObjectNode root = imageOnly ? imageTree() : textTree();
                ((ObjectNode) root.at(path)).put("@class", "untrusted.input");
                invalid(root.toString());
                root = imageOnly ? imageTree() : textTree();
                ObjectNode node = (ObjectNode) root.at(path);
                String first = node.fieldNames().next();
                String serialized = root.toString();
                String field = "\"" + first + "\":" + node.get(first);
                // Duplicates with identical values still fail, including nested style/point fields.
                invalid(serialized.replaceFirst(java.util.regex.Pattern.quote(field),
                    java.util.regex.Matcher.quoteReplacement(field + "," + field)));
            }
        }
        invalid(imageJson().replace("\"kind\":\"image\"", "\"kind\":\"image\",\"kind\":\"image\""));
        invalid(imageJson().replace("\"kind\":\"image\"", "\"kind\":\"text\",\"kind\":\"image\""));
    }

    @Test
    void imageFieldsAndEveryNamedPointAreRequiredAndNonNull() throws Exception {
        for (String path : List.of("/areas/0", "/areas/0/corners", "/areas/0/corners/topLeft",
            "/areas/0/corners/topRight", "/areas/0/corners/bottomRight", "/areas/0/corners/bottomLeft")) {
            var names = new java.util.ArrayList<String>();
            imageTree().at(path).fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                ObjectNode root = imageTree();
                ((ObjectNode) root.at(path)).remove(name);
                invalid(root.toString());
                root = imageTree();
                ((ObjectNode) root.at(path)).putNull(name);
                invalid(root.toString());
            }
        }
    }

    @Test
    void wireAreaLimitAccepts128AndRejects129WithoutEncodingInvalidModels() throws Exception {
        var areas = IntStream.rangeClosed(1, 128).mapToObj(i -> (Area) image(new UUID(0, i), i)).toList();
        var value = new TemplateDefinition(1, CoordinateSpace.ORIGINAL_IMAGE_PIXELS, new Canvas(1000, 800), areas);
        assertEquals(value, codec.decode(codec.encode(value)));
        ObjectNode root = (ObjectNode) trees.readTree(codec.encode(value));
        ((com.fasterxml.jackson.databind.node.ArrayNode) root.get("areas")).add(imageTree().at("/areas/0"));
        invalid(root.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "2", "1.0", "1e0", "1e2", "2147483648", "\"1\"", "true", "null", "[]", "{}"})
    void rejectsUnsupportedVersionsAndIntegerCoercions(String value) {
        invalid(imageJson().replace("\"definitionVersion\":1", "\"definitionVersion\":" + value));
    }

    @Test
    void integerTypesAreStrictEverywhereNotOnlyAtVersion() throws Exception {
        for (String path : List.of("/canvas/width", "/canvas/height", "/areas/0/sourceSlot",
            "/areas/0/corners/topLeft/x", "/areas/0/corners/topLeft/y")) {
            for (String bad : List.of("1.0", "1e2", "2147483648", "\"1\"", "true", "null")) {
                invalid(replace(imageTree(), path, trees.readTree(bad)).toString());
            }
        }
        for (String path : List.of("/areas/0/bounds/x", "/areas/0/bounds/y", "/areas/0/bounds/width", "/areas/0/bounds/height",
            "/areas/0/style/fontWeight", "/areas/0/style/fontSize", "/areas/0/style/outline/width", "/areas/0/style/padding")) {
            for (String bad : List.of("1.0", "1e2", "2147483648", "\"1\"", "false", "null")) {
                invalid(replace(textTree(), path, trees.readTree(bad)).toString());
            }
        }
    }

    @ParameterizedTest
    @MethodSource("badStyles")
    void closedStylePolicyRejectsInvalidValues(String relativePath, String json) throws Exception {
        invalid(replace(textTree(), "/areas/0/style/" + relativePath, trees.readTree(json)).toString());
    }

    static Stream<Arguments> badStyles() {
        return Stream.of(new String[] {"fontCatalogVersion", "\"catalog-v2\""},
            new String[] {"fontId", "\"Liberation Sans\""}, new String[] {"fontId", "\"https://fonts.invalid/x\""},
            new String[] {"fontWeight", "500"}, new String[] {"fontSize", "0"}, new String[] {"fontSize", "129"},
            new String[] {"color", "\"red\""}, new String[] {"color", "\"#fff\""}, new String[] {"color", "\"#12345678\""},
            new String[] {"outline/color", "\"#GG0000\""}, new String[] {"outline/width", "-1"},
            new String[] {"outline/width", "5"}, new String[] {"boxFill", "\"transparent\""}, new String[] {"boxFill", "3"},
            new String[] {"align", "\"center\""}, new String[] {"verticalAlign", "\"CENTER\""},
            new String[] {"lineHeight", "0.99"}, new String[] {"lineHeight", "2.01"}, new String[] {"lineHeight", "\"1.2\""},
            new String[] {"padding", "-1"}, new String[] {"padding", "65"}, new String[] {"overflow", "\"CLIP\""})
            .map(values -> Arguments.of((Object[]) values));
    }

    @ParameterizedTest
    @MethodSource("styleBoundaries")
    void acceptsInclusiveStyleBoundsAllCatalogFacesAlignmentsAndCasePreservingColors(
        String font, int weight, boolean upper, Align align, VerticalAlign vertical) throws Exception {
        ObjectNode root = textTree();
        ObjectNode style = (ObjectNode) root.at("/areas/0/style");
        style.put("fontId", font).put("fontWeight", weight).put("fontSize", upper ? 128 : 1)
            .put("padding", upper ? 64 : 0).put("lineHeight", upper ? 2 : 1)
            .put("align", align.name()).put("verticalAlign", vertical.name()).put("color", "#aAbBcC")
            .put("boxFill", "#123456");
        ((ObjectNode) style.get("outline")).put("width", upper ? 4 : 0);
        var value = codec.decode(root.toString());
        assertEquals(value, codec.decode(codec.encode(value)));
        assertEquals("#aAbBcC", ((TextArea) value.areas().getFirst()).style().color());
    }

    static Stream<Arguments> styleBoundaries() {
        return Stream.of("liberation-sans", "liberation-serif", "liberation-mono").flatMap(font ->
            Stream.of(400, 700).flatMap(weight -> Stream.of(false, true).flatMap(upper ->
                Stream.of(Align.values()).flatMap(align -> Stream.of(VerticalAlign.values())
                    .map(vertical -> Arguments.of(font, weight, upper, align, vertical))))));
    }

    @Test
    void zeroOutlineWidthDoesNotDisableColorValidation() throws Exception {
        ObjectNode invalidZeroOutline = textTree();
        ((ObjectNode) invalidZeroOutline.at("/areas/0/style/outline")).put("width", 0).put("color", "bad");
        invalid(invalidZeroOutline.toString());
    }

    @Test
    void rejectsBadDiscriminatorsUuidPlaneAndIncompatibleShapes() throws Exception {
        for (String kind : List.of("IMAGE", "unknown", "com.example.ImageArea")) {
            ObjectNode root = imageTree();
            ((ObjectNode) root.at("/areas/0")).put("kind", kind);
            invalid(root.toString());
        }
        for (String uuid : List.of("1-1-1-1-1", "not-a-uuid", FIRST + " ", "{" + FIRST + "}")) {
            ObjectNode root = imageTree();
            ((ObjectNode) root.at("/areas/0")).put("id", uuid);
            invalid(root.toString());
        }
        ObjectNode upperUuid = imageTree();
        ((ObjectNode) upperUuid.at("/areas/0")).put("id", FIRST.toString().toUpperCase(java.util.Locale.ROOT));
        assertEquals(FIRST, codec.decode(upperUuid.toString()).areas().getFirst().id());
        invalid(replace(imageTree(), "/coordinateSpace", trees.readTree("\"VIEW_PIXELS\"")).toString());
        invalid(replace(imageTree(), "/areas/0/plane", trees.readTree("\"foreground\"")).toString());
        ObjectNode incompatible = imageTree();
        ((ObjectNode) incompatible.at("/areas/0")).put("textPattern", "literal");
        invalid(incompatible.toString());
        ObjectNode oldShape = imageTree();
        oldShape.putArray("imageAreas");
        invalid(oldShape.toString());
        invalid(replace(imageTree(), "/areas/0/corners", trees.readTree("[0,0,1,0,1,1,0,1]")).toString());
    }

    @Test
    void boundsBytesDepthNumbersStringsTrailingJsonAndEncodingWithoutLeakingInput() throws Exception {
        String valid = imageJson();
        assertEquals(definition(image(FIRST, 1)), codec.decode(valid + " ".repeat(TemplateDefinitionCodec.MAX_BYTES - valid.length())));
        invalid(valid + " ".repeat(TemplateDefinitionCodec.MAX_BYTES - valid.length() + 1));
        invalid("é".repeat(TemplateDefinitionCodec.MAX_BYTES / 2 + 1));
        assertThrows(InvalidDefinition.class, () -> codec.decode(new byte[TemplateDefinitionCodec.MAX_BYTES + 1]));
        assertThrows(InvalidDefinition.class, () -> codec.decode(new byte[] {(byte) 0xc3, (byte) 0x28}));
        assertThrows(InvalidDefinition.class, () -> codec.decode((byte[]) null));
        invalid(null);
        for (String bad : List.of("", " ", "null", "[]", "true", "{", valid + valid, valid + " null", valid + " garbage",
            "[".repeat(17) + "0" + "]".repeat(17), valid.replace("\"definitionVersion\":1", "\"definitionVersion\":" + "1".repeat(65)),
            "{\"" + "a".repeat(129) + "\":0}", valid.replace("\"definitionVersion\":1", "\"definitionVersion\":NaN"))) {
            invalid(bad);
        }
        String text = textTree().toString();
        invalid(text.replace("\"lineHeight\":1.2", "\"lineHeight\":1e999"));
        invalid(text.replace("\"lineHeight\":1.2", "\"lineHeight\":NaN"));
        invalid(text.replace("\"lineHeight\":1.2", "\"lineHeight\":Infinity"));
        invalid(text.replace("\"literal\"", "\"" + "x".repeat(4097) + "\""));
        invalid(text.replace("\"literal\"", "\"\\uD800\""));
        invalid(text.replace("\"literal\"", "\"\\uDC00\""));
        invalid(text.replace("\"literal\"", "\"\\u0000\""));
        invalid(text.replace("literal", "x" + (char) 0xd800));
        ObjectNode deep = imageTree();
        ((ObjectNode) deep.at("/canvas")).set("width", trees.readTree("[".repeat(16) + "0" + "]".repeat(16)));
        invalid(deep.toString());
    }

    @Test
    void encodeAlsoValidatesAndBoundsOutputRatherThanProducingUndecodableOversizeMetadata() {
        assertThrows(InvalidDefinition.class, () -> codec.encode(definition(image(FIRST, 2))));
        var areas = IntStream.rangeClosed(1, 128)
            .mapToObj(i -> (Area) text(new UUID(0, i), null, "é".repeat(4096))).toList();
        var large = new TemplateDefinition(1, CoordinateSpace.ORIGINAL_IMAGE_PIXELS, new Canvas(1000, 800), areas);
        assertEquals(0, validate(large));
        assertThrows(InvalidDefinition.class, () -> codec.encode(large));
    }

    private ObjectNode imageTree() throws Exception {
        return (ObjectNode) trees.readTree(codec.encode(definition(image(FIRST, 1))));
    }

    private ObjectNode textTree() throws Exception {
        return (ObjectNode) trees.readTree(codec.encode(definition(text(FIRST, null, "literal"))));
    }

    private String imageJson() {
        return new String(codec.encode(definition(image(FIRST, 1))), StandardCharsets.UTF_8);
    }

    private static ObjectNode replace(ObjectNode root, String path, JsonNode value) {
        int slash = path.lastIndexOf('/');
        ((ObjectNode) root.at(path.substring(0, slash))).set(path.substring(slash + 1), value);
        return root;
    }

    private void invalid(String json) {
        InvalidDefinition exception = assertThrows(InvalidDefinition.class, () -> codec.decode(json));
        assertEquals("Invalid template definition", exception.getMessage());
        assertNull(exception.getCause());
    }
}
