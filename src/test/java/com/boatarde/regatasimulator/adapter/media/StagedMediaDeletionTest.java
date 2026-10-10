package com.boatarde.regatasimulator.adapter.media;

import com.boatarde.regatasimulator.application.MediaStorage;
import com.boatarde.regatasimulator.factory.ImageTestFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class StagedMediaDeletionTest {
    @TempDir Path root;
    @Test void uncertainMetadataFailureRetainsRecoverableStagedBytes() throws Exception {
        var storage=new FileMediaStorage(root.toString(),root.toString()); UUID id=UUID.randomUUID();
        Path original=ImageTestFactory.image(storage.prepare(MediaStorage.Kind.SOURCE,id).resolve("source.png")); byte[] bytes=Files.readAllBytes(original);
        assertThrows(IllegalStateException.class,() -> storage.deleteAfterMetadata(MediaStorage.Kind.SOURCE,id,
            () -> { throw new IllegalStateException("synthetic uncertain write"); }, () -> { throw new IllegalStateException("synthetic unavailable lookup"); }));
        assertFalse(Files.exists(original));
        try(var paths=Files.list(root)) {
            Path staged=paths.filter(p -> p.getFileName().toString().startsWith(".delete-"+id)).findFirst().orElseThrow();
            assertArrayEquals(bytes,Files.readAllBytes(staged.resolve("source.png")));
        }
    }
    @Test void confirmedRemovalPurgesStageButFailedWriteRestoresOriginal() throws Exception {
        var storage=new FileMediaStorage(root.toString(),root.toString()); UUID id=UUID.randomUUID();
        Path original=ImageTestFactory.image(storage.prepare(MediaStorage.Kind.SOURCE,id).resolve("source.png"));
        assertThrows(IllegalStateException.class,() -> storage.deleteAfterMetadata(MediaStorage.Kind.SOURCE,id,
            () -> { throw new IllegalStateException("synthetic rollback"); }, () -> true));
        assertTrue(Files.exists(original));
        storage.deleteAfterMetadata(MediaStorage.Kind.SOURCE,id,() -> { },() -> false);
        try(var paths=Files.list(root)) { assertEquals(0,paths.count()); }
    }
}