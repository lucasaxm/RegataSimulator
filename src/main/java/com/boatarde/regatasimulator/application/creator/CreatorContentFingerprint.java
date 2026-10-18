package com.boatarde.regatasimulator.application.creator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Pure content identity foundation, not durable idempotency or media validation.
 * V1 is SHA-256(ASCII("regata-creator-content-v1") || NUL || imageDigest32 || definitionDigest32).
 * Both component digests are SHA-256; the image digest covers the exact original bytes.
 *
 * <p>V1 freezes the validated {@link TemplateDefinitionCodec#encode} representation: compact UTF-8
 * JSON without BOM/trailing newline, insertion/property order as pinned by golden vectors, enum
 * names, canonical UUID strings, explicit null boxFill and double lineHeight encoded with
 * Double.toString semantics. Integers use decimal notation. Area order/IDs, color case and all
 * text (including Unicode composition, whitespace and newlines) are preserved, never normalized.
 * Decode wire JSON first: key order/formatting, omitted versus null boxFill and equivalent
 * lineHeight numbers then have the same typed meaning. Codec evolution must preserve these V1
 * bytes; a different mapping requires a new fingerprint version, not reinterpretation of V1.
 *
 * <p>No actor, session, key, filename, samples or viewport participates. Future SQLite bindings
 * must scope actor/key separately. Callers must keep input bytes unchanged during this call and
 * freeze/bind owned media separately; hashing neither decodes images nor guarantees filesystem
 * identity. No input array is retained or mutated.
 */
public final class CreatorContentFingerprint {
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;
    private static final byte[] DOMAIN = "regata-creator-content-v1\0".getBytes(StandardCharsets.US_ASCII);
    private final TemplateDefinitionCodec codec = new TemplateDefinitionCodec();

    public Result compute(byte[] originalImage, TemplateDefinition definition) {
        if (originalImage == null || originalImage.length == 0 || originalImage.length > MAX_IMAGE_BYTES) {
            throw new InvalidContent();
        }
        byte[] canonical;
        try {
            canonical = codec.encode(definition);
        } catch (TemplateDefinitionValidator.InvalidDefinition exception) {
            throw new InvalidContent();
        }
        MessageDigest digest = sha256();
        byte[] imageHash = digest.digest(originalImage);
        byte[] definitionHash = digest.digest(canonical);
        digest.update(DOMAIN);
        digest.update(imageHash);
        byte[] contentHash = digest.digest(definitionHash);
        HexFormat hex = HexFormat.of();
        return new Result(FORMAT_VERSION, hex.formatHex(imageHash), hex.formatHex(definitionHash),
            hex.formatHex(contentHash));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    /** Only immutable lowercase hexadecimal digests are exposed, never mutable digest arrays. */
    public record Result(int formatVersion, String imageSha256, String definitionSha256, String contentSha256) {
        public Result {
            if (formatVersion != FORMAT_VERSION || !isDigest(imageSha256) || !isDigest(definitionSha256)
                || !isDigest(contentSha256)) {
                throw new InvalidContent();
            }
        }

        private static boolean isDigest(String value) {
            return value != null && value.matches("[0-9a-f]{64}");
        }
    }

    /** Fixed generic failure: no submitted content or underlying validation cause is exposed. */
    public static final class InvalidContent extends IllegalArgumentException {
        public InvalidContent() {
            super("Invalid creator content");
        }
    }
}