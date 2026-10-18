package com.boatarde.regatasimulator.application.creator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.boatarde.regatasimulator.application.creator.TemplateDefinition.*;
import static org.junit.jupiter.api.Assertions.*;

class CreatorContentFingerprintTest {
    // Owned synthetic 2x2 RGBA PNG; independent Node crypto/zlib vectors, not codec-derived expectations.
    private static final String PNG =
        "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAE0lEQVR4nGP4z8DwHwwZGP6DAQBJyAn3FGMynQAAAABJRU5ErkJggg==";
    private static final String IMAGE_HASH = "8e546007599fa6df13786602a501cc2933ae498bb8ed8ff0fa4664f551cad2be";
    private static final String IMAGE_AREA = """
        {"id":"a1111111-1111-4111-8111-111111111111","kind":"image","sourceSlot":1,"plane":"FOREGROUND","corners":{"topLeft":{"x":0,"y":0},"topRight":{"x":2,"y":0},"bottomRight":{"x":2,"y":2},"bottomLeft":{"x":0,"y":2}}}
        """.strip();
    private static final String TEXT_AREA = """
        {"id":"b2222222-2222-4222-8222-222222222222","kind":"text","sourceSlot":null,"plane":"BACKGROUND","bounds":{"x":0,"y":0,"width":2,"height":2},"textPattern":"Olá\\nCafé \\"x\\" \\\\","style":{"fontCatalogVersion":"catalog-v1","fontId":"liberation-sans","fontWeight":400,"fontSize":12,"color":"#aAbBcC","outline":{"color":"#000000","width":0},"boxFill":null,"align":"LEFT","verticalAlign":"TOP","lineHeight":1.2,"padding":0,"overflow":"SHRINK_TO_FIT"}}
        """.strip();
    private static final String IMAGE_JSON = json(IMAGE_AREA);
    private static final String LITERAL_JSON = json(TEXT_AREA);
    private static final String DYNAMIC_AREA = (TEXT_AREA.substring(0, TEXT_AREA.indexOf("\"textPattern\":"))
        + "\"textPattern\":\"Olá {{source.description}}\"," + TEXT_AREA.substring(TEXT_AREA.indexOf("\"style\":")))
        .replace("\"sourceSlot\":null", "\"sourceSlot\":1");
    private static final String MIXED_JSON = json(IMAGE_AREA + "," + DYNAMIC_AREA);
    private final TemplateDefinitionCodec codec = new TemplateDefinitionCodec();
    private final CreatorContentFingerprint fingerprints = new CreatorContentFingerprint();
    private final ObjectMapper trees = new ObjectMapper();

    @Test
    void freezesCanonicalBytesAndIndependentImageLiteralAndMixedGoldenVectors() {
        golden(IMAGE_JSON, "32b38897042f26328953fd1aa6ba3c2ff357935f2e1b912e4ce034172baf2067",
            "9f9b2d75eaeed0fc74275c8b0e4615be08e252ad148f171f9053ed67f9f9c8d6");
        golden(LITERAL_JSON, "e447fba7594ea328ba7126e73e2ae41d7ffd359857549d4463067f9c218d2312",
            "50741cfdef03439a2faf0a7f3a1537e482e499e84e0214d0e0217294a00b66b6");
        golden(MIXED_JSON, "2abe43bbe2589167df81f6ce7c68f33f2d9621f843245c976f6e4b3164f8965a",
            "ee6f8c0f36f63b299ec7095092265ba9c3c1b164dad1e0592f393091216c3785");
    }

    @Test
    void acceptsValidOwnedPngFixturesWithoutMutatingOrRetainingBytes() throws Exception {
        BufferedImage fixed = ImageIO.read(new ByteArrayInputStream(image()));
        assertNotNull(fixed);
        assertEquals(2, fixed.getWidth());
        assertEquals(2, fixed.getHeight());
        BufferedImage generated = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        generated.setRGB(0, 0, 0xff123456);
        var output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(generated, "png", output));
        byte[] bytes = output.toByteArray();
        byte[] before = bytes.clone();
        var result = fingerprints.compute(bytes, codec.decode(IMAGE_JSON));
        assertArrayEquals(before, bytes);
        bytes[0] ^= 1;
        assertEquals(result, fingerprints.compute(before, codec.decode(IMAGE_JSON)));
        assertNotEquals(result.imageSha256(), fingerprints.compute(bytes, codec.decode(IMAGE_JSON)).imageSha256());
    }

    @Test
    void stableAcrossRoundTripsForImageMixedSharedTextOnlyAndLiteralZero() {
        String shared = json(DYNAMIC_AREA + "," + DYNAMIC_AREA.replace("b2222222-2222-4222-8222-222222222222",
            "c3333333-3333-4333-8333-333333333333"));
        for (String wire : List.of(IMAGE_JSON, MIXED_JSON, shared, LITERAL_JSON)) {
            var definition = codec.decode(wire);
            var expected = fingerprints.compute(image(), definition);
            assertEquals(expected, fingerprints.compute(image(), codec.decode(codec.encode(definition))));
            assertEquals(expected, fingerprints.compute(image(), definition));
        }
        assertEquals(0, TemplateDefinitionValidator.validate(codec.decode(LITERAL_JSON)));
        assertEquals(1, TemplateDefinitionValidator.validate(codec.decode(shared)));
    }

    @Test
    void wirePropertyOrderFormattingAndUuidCaseDoNotChangeIdentity() throws Exception {
        for (String wire : List.of(IMAGE_JSON, MIXED_JSON, LITERAL_JSON)) {
            JsonNode reversed = reverseObjects(trees.readTree(wire));
            String formatted = trees.writerWithDefaultPrettyPrinter().writeValueAsString(reversed);
            assertEquals(hash(wire), hash(formatted));
        }
        assertEquals(hash(IMAGE_JSON), hash(IMAGE_JSON.replace("a1111111", "A1111111")));
    }

    @Test
    void omittedBoxFillAndEquivalentTypedLineHeightNumbersHaveSameIdentity() {
        assertEquals(hash(LITERAL_JSON), hash(LITERAL_JSON.replace("\"boxFill\":null,", "")));
        String one = LITERAL_JSON.replace("\"lineHeight\":1.2", "\"lineHeight\":1.0");
        assertEquals(hash(one), hash(one.replace("\"lineHeight\":1.0", "\"lineHeight\":1e0")));
        assertEquals(hash(one), hash(one.replace("\"lineHeight\":1.0", "\"lineHeight\":1")));
        assertNotEquals(hash(one), hash(LITERAL_JSON));
    }

    @Test
    void preservesUnicodeCompositionWhitespaceNewlinesAndColorCaseExactly() {
        for (String changed : List.of(LITERAL_JSON.replace("Café", "Café"),
            LITERAL_JSON.replace("Olá", "olá"), LITERAL_JSON.replace("Olá", " Olá"),
            LITERAL_JSON.replace("\\n", " "), LITERAL_JSON.replace("#aAbBcC", "#aabbcc"))) {
            assertNotEquals(hash(LITERAL_JSON), hash(changed));
        }
    }

    @Test
    void areaOrderIdentityGeometrySlotPlaneAndStyleAreContent() {
        assertNotEquals(hash(MIXED_JSON), hash(json(DYNAMIC_AREA + "," + IMAGE_AREA)));
        for (String changed : List.of(MIXED_JSON.replace("a1111111", "d1111111"),
            MIXED_JSON.replace("\"x\":0", "\"x\":-1"),
            MIXED_JSON.replace("\"sourceSlot\":1,\"plane\":\"BACKGROUND\"", "\"sourceSlot\":2,\"plane\":\"BACKGROUND\""),
            MIXED_JSON.replace("FOREGROUND", "BACKGROUND"), MIXED_JSON.replace("liberation-sans", "liberation-serif"),
            MIXED_JSON.replace("\"fontWeight\":400", "\"fontWeight\":700"),
            MIXED_JSON.replace("\"fontSize\":12", "\"fontSize\":13"),
            MIXED_JSON.replace("\"boxFill\":null", "\"boxFill\":\"#ffffff\""),
            MIXED_JSON.replace("\"width\":0", "\"width\":1"), MIXED_JSON.replace("LEFT", "CENTER"),
            MIXED_JSON.replace("TOP\"", "MIDDLE\""), MIXED_JSON.replace("\"padding\":0", "\"padding\":1"))) {
            assertNotEquals(hash(MIXED_JSON), hash(changed), changed);
        }
    }

    @Test
    void exactOriginalBytesNotDecodedPixelsDetermineImageDigest() {
        byte[] bytes = image();
        var before = fingerprints.compute(bytes, codec.decode(IMAGE_JSON));
        // Hash-only admission intentionally accepts a byte flip; future media validation is separate.
        bytes[0] ^= 1;
        var after = fingerprints.compute(bytes, codec.decode(IMAGE_JSON));
        assertNotEquals(before.imageSha256(), after.imageSha256());
        assertNotEquals(before.contentSha256(), after.contentSha256());
        assertEquals(before.definitionSha256(), after.definitionSha256());
        var changedDefinition = hash(MIXED_JSON);
        assertEquals(before.imageSha256(), changedDefinition.imageSha256());
        assertNotEquals(before.definitionSha256(), changedDefinition.definitionSha256());
    }

    @Test
    void imageByteBoundsAreInclusiveAndFailuresAreGeneric() {
        var definition = codec.decode(IMAGE_JSON);
        assertNotNull(fingerprints.compute(new byte[1], definition));
        assertNotNull(fingerprints.compute(new byte[CreatorContentFingerprint.MAX_IMAGE_BYTES], definition));
        invalid(() -> fingerprints.compute(null, definition));
        invalid(() -> fingerprints.compute(new byte[0], definition));
        invalid(() -> fingerprints.compute(new byte[CreatorContentFingerprint.MAX_IMAGE_BYTES + 1], definition));
    }

    @Test
    void validatesDefinitionRatherThanHashingInvalidModels() {
        invalid(() -> fingerprints.compute(image(), null));
        var valid = codec.decode(LITERAL_JSON);
        for (TemplateDefinition bad : List.of(new TemplateDefinition(2, valid.coordinateSpace(), valid.canvas(), valid.areas()),
            new TemplateDefinition(1, valid.coordinateSpace(), new Canvas(0, 2), valid.areas()),
            new TemplateDefinition(1, valid.coordinateSpace(), valid.canvas(), List.of()),
            new TemplateDefinition(1, valid.coordinateSpace(), valid.canvas(),
                List.of(new TextArea(UUID.randomUUID(), 1, Plane.BACKGROUND, new Bounds(0, 0, 2, 2),
                    "literal", ((TextArea) valid.areas().getFirst()).style()))))) {
            invalid(() -> fingerprints.compute(image(), bad));
        }
    }

    @Test
    void rejectsValidatedButOversizedCanonicalDefinitionWithoutLeakingCause() {
        var text = (TextArea) codec.decode(LITERAL_JSON).areas().getFirst();
        var areas = java.util.stream.IntStream.rangeClosed(1, 128).mapToObj(i -> (Area)
            new TextArea(new UUID(0, i), null, text.plane(), text.bounds(), "é".repeat(4096), text.style())).toList();
        var large = new TemplateDefinition(1, CoordinateSpace.ORIGINAL_IMAGE_PIXELS, new Canvas(2, 2), areas);
        assertEquals(0, TemplateDefinitionValidator.validate(large));
        invalid(() -> fingerprints.compute(image(), large));
    }

    @Test
    void immutableResultRequiresVersionOneAndThreeLowercaseSha256Strings() {
        var result = hash(IMAGE_JSON);
        assertEquals(1, result.formatVersion());
        for (String digest : List.of(result.imageSha256(), result.definitionSha256(), result.contentSha256())) {
            assertTrue(digest.matches("[0-9a-f]{64}"));
        }
        invalid(() -> new CreatorContentFingerprint.Result(2, IMAGE_HASH, IMAGE_HASH, IMAGE_HASH));
        for (String bad : new String[] {null, "", "0".repeat(63), "0".repeat(65), "g".repeat(64),
            IMAGE_HASH.toUpperCase(Locale.ROOT)}) {
            invalid(() -> new CreatorContentFingerprint.Result(1, bad, IMAGE_HASH, IMAGE_HASH));
            invalid(() -> new CreatorContentFingerprint.Result(1, IMAGE_HASH, bad, IMAGE_HASH));
            invalid(() -> new CreatorContentFingerprint.Result(1, IMAGE_HASH, IMAGE_HASH, bad));
        }
    }

    private void golden(String canonical, String definitionHash, String contentHash) {
        var definition = codec.decode(canonical);
        assertArrayEquals(canonical.getBytes(StandardCharsets.UTF_8), codec.encode(definition));
        assertEquals(new CreatorContentFingerprint.Result(1, IMAGE_HASH, definitionHash, contentHash),
            fingerprints.compute(image(), definition));
    }

    private CreatorContentFingerprint.Result hash(String wire) {
        return fingerprints.compute(image(), codec.decode(wire));
    }

    private static String json(String areas) {
        return "{\"definitionVersion\":1,\"coordinateSpace\":\"ORIGINAL_IMAGE_PIXELS\",\"canvas\":{\"width\":2,\"height\":2},\"areas\":["
            + areas + "]}";
    }

    private static byte[] image() {
        return Base64.getDecoder().decode(PNG);
    }

    private JsonNode reverseObjects(JsonNode value) {
        if (value.isObject()) {
            ObjectNode reversed = trees.createObjectNode();
            var names = new java.util.ArrayList<String>();
            value.fieldNames().forEachRemaining(names::add);
            for (String name : names.reversed()) reversed.set(name, reverseObjects(value.get(name)));
            return reversed;
        }
        if (value.isArray()) {
            var array = trees.createArrayNode();
            value.forEach(item -> array.add(reverseObjects(item)));
            return array;
        }
        return value;
    }

    private static void invalid(org.junit.jupiter.api.function.Executable action) {
        var exception = assertThrows(CreatorContentFingerprint.InvalidContent.class, action);
        assertEquals("Invalid creator content", exception.getMessage());
        assertNull(exception.getCause());
    }
}