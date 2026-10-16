package com.boatarde.regatasimulator.application.creatorauth;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/** Private, bounded Jackson 2 parser; never changes the application's mapper. */
public final class CreatorJson {
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8)
            .maxStringLength(32768).maxNumberLength(32).maxNameLength(128).build())
        .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private CreatorJson() { }

    public static JsonNode read(byte[] bytes) throws IOException { return MAPPER.readTree(bytes); }
    public static JsonNode read(String text) throws IOException { return MAPPER.readTree(text); }
}
