package com.boatarde.regatasimulator.adapter.media;

import com.boatarde.regatasimulator.application.MediaStorage;
import com.boatarde.regatasimulator.application.ApplicationFailure;
import com.boatarde.regatasimulator.util.FileUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Component
public class FileMediaStorage implements MediaStorage {
    private final Path sources;
    private final Path templates;
    public FileMediaStorage(@Value("${regata-simulator.sources.path}") String sources,
                            @Value("${regata-simulator.templates.path}") String templates) {
        this.sources = Path.of(sources); this.templates = Path.of(templates);
    }

    @Override
    public Path image(Kind kind, UUID id) {
        String base = kind == Kind.SOURCE ? "source" : "template";
        return FileUtils.getFirstExistingFile(directory(kind, id), base + ".jpg", base + ".jpeg", base + ".png")
            .orElseThrow(() -> new ApplicationFailure(ApplicationFailure.Kind.NOT_FOUND, "Media unavailable"));
    }

    @Override
    public Path prepare(Kind kind, UUID id) {
        try { return Files.createDirectories(directory(kind, id)); }
        catch (IOException e) { throw new ApplicationFailure(ApplicationFailure.Kind.EXECUTION, "Media preparation failed", e); }
    }

    @Override
    public void delete(Kind kind, UUID id) {
        deleteStrict(directory(kind,id));
    }

    private void deleteStrict(Path directory) {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        } catch (IOException e) {
            throw new ApplicationFailure(ApplicationFailure.Kind.EXECUTION, "Media deletion failed", e);
        }
    }

    @Override
    public void deleteAfterMetadata(Kind kind, UUID id, Runnable removeMetadata,
                                    java.util.function.BooleanSupplier metadataExists) {
        Path original=directory(kind,id);
        Path staged=original.resolveSibling(".delete-"+id+"-"+UUID.randomUUID());
        try { Files.move(original,staged,java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
        catch(IOException e) { throw new ApplicationFailure(ApplicationFailure.Kind.EXECUTION,"Media staging failed",e); }
        try { removeMetadata.run(); }
        catch(RuntimeException failure) {
            restoreOrRetain(original,staged,metadataExists,failure);
            throw failure;
        }
        // After metadata commit, a purge failure retains the staged copy for reconciliation.
        deleteStrict(staged);
    }

    private void restoreOrRetain(Path original,Path staged,java.util.function.BooleanSupplier metadataExists,
                                 RuntimeException failure) {
        try {
            if(metadataExists.getAsBoolean()) {
                if(Files.exists(original)) throw new IOException("Refusing to overwrite concurrent media");
                Files.move(staged,original,java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } else deleteStrict(staged);
        } catch(IOException | RuntimeException recovery) { failure.addSuppressed(recovery); }
    }

    private Path directory(Kind kind, UUID id) { return (kind == Kind.SOURCE ? sources : templates).resolve(id.toString()); }

    @Override
    public void discardUncommitted(Kind kind, UUID id) { FileUtils.deleteTree(directory(kind, id)); }
}