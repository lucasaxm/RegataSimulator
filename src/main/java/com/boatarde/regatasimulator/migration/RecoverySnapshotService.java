package com.boatarde.regatasimulator.migration;

import com.boatarde.regatasimulator.application.MediaMutationGuard;
import com.boatarde.regatasimulator.application.ApplicationFailure;
import com.boatarde.regatasimulator.repository.*;
import com.boatarde.regatasimulator.repository.sqlite.SqliteStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.io.IOException;
import java.util.*;

@Service
public class RecoverySnapshotService {
    private final MediaMutationGuard guard;
    private final SourceRepository sources;
    private final TemplateRepository templates;
    private final AuthorRepository authors;
    private final MemeHistoryRepository history;
    private final AuditRepository audits;
    private final ObjectProvider<SqliteStore> sqlite;
    private final Path sourceMedia;
    private final Path templateMedia;
    private final String jsonDirectory;
    private final String sqliteFile;
    private final String localDirectory;
    private final int retention;

    public RecoverySnapshotService(MediaMutationGuard guard, SourceRepository sources, TemplateRepository templates,
        AuthorRepository authors, MemeHistoryRepository history, ObjectProvider<SqliteStore> sqlite,
        @Value("${regata-simulator.sources.path}") String sourceMedia,
        @Value("${regata-simulator.templates.path}") String templateMedia,
        @Value("${regata-simulator.backup.local-directory:}") String localDirectory,
        @Value("${regata-simulator.backup.retention-count:7}") int retention) {
        this(guard,sources,templates,authors,history,sqlite,sourceMedia,templateMedia,localDirectory,retention,null,"","");
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RecoverySnapshotService(MediaMutationGuard guard, SourceRepository sources, TemplateRepository templates,
        AuthorRepository authors, MemeHistoryRepository history, ObjectProvider<SqliteStore> sqlite,
        @Value("${regata-simulator.sources.path}") String sourceMedia,
        @Value("${regata-simulator.templates.path}") String templateMedia,
        @Value("${regata-simulator.backup.local-directory:}") String localDirectory,
        @Value("${regata-simulator.backup.retention-count:7}") int retention, AuditRepository audits,
        @Value("${regata-simulator.database.path:}") String jsonDirectory,
        @Value("${regata-simulator.database.sqlite-file:}") String sqliteFile) {
        this.guard=guard; this.sources=sources; this.templates=templates; this.authors=authors; this.history=history;
        this.sqlite=sqlite; this.sourceMedia=Path.of(sourceMedia); this.templateMedia=Path.of(templateMedia);
        this.localDirectory=localDirectory; this.retention=retention;
        this.audits=audits; this.jsonDirectory=jsonDirectory; this.sqliteFile=sqliteFile;
    }

    public synchronized Path capture() {
        try (var scratch=new Scratch()) {
            Path destination=destination();
            Path stage=scratch.path;
            String engine;
            try (var lease=guard.snapshot()) {
                SqliteStore store=sqlite.getIfAvailable();
                engine=store==null ? "jsondb" : "sqlite";
                if (store==null) {
                    var snapshot=audits==null ? RecoveryBundle.load(sources,templates,authors,history)
                        : RecoveryBundle.load(sources,templates,authors,history,audits);
                    snapshot.writeJson(stage.resolve("jsondb"));
                }
                else { RecoveryBundle.privateDirectory(stage.resolve("db")); store.snapshot(stage.resolve("db/store.db")); }
                OfflinePaths.copyTree(sourceMedia,stage.resolve("sources"));
                OfflinePaths.copyTree(templateMedia,stage.resolve("templates"));
                // Preserve orphan directories and uncertain deletion stages rather than silently purge them.
                if (RecoveryBundle.inspect(stage,engine).blocked()) throw new IOException("Inconsistent captured metadata/media");
                try (var paths=Files.walk(stage)) {
                    for (Path path:paths.toList()) Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(Files.isDirectory(path) ? "rwx------" : "rw-------"));
                }
            }
            Path bundle=RecoveryBundle.pack(stage,destination.resolve("backup-"+UUID.randomUUID()),engine,RecoveryBundle.CHUNK_BYTES);
            RecoveryBundle.retain(destination,retention);
            return bundle;
        } catch (IOException | RuntimeException e) {
            throw new ApplicationFailure(ApplicationFailure.Kind.EXECUTION,"Recovery snapshot failed",e);
        }
    }

    private Path destination() throws IOException {
        if (localDirectory.isBlank() || retention<1 || retention>100) throw new IOException("Configure a private independent backup directory and bounded retention");
        Path destination=OfflinePaths.checked(Path.of(localDirectory));
        var inputs=new ArrayList<>(List.of(sourceMedia,templateMedia));
        if (!jsonDirectory.isBlank()) inputs.add(Path.of(jsonDirectory));
        if (!sqliteFile.isBlank()) inputs.add(Path.of(sqliteFile));
        for (Path input:inputs) {
            OfflinePaths.checked(input);
            if (destination.startsWith(input) || input.startsWith(destination)) throw new IOException("Backup and data overlap");
        }
        if (!Files.isDirectory(destination) || !Files.getPosixFilePermissions(destination).equals(PosixFilePermissions.fromString("rwx------"))) throw new IOException("Existing owner-only destination required");
        return destination;
    }

    private static final class Scratch implements AutoCloseable {
        private final Path path;
        private Scratch() throws IOException {
            path=Files.createTempDirectory("regata-snapshot-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))).toRealPath();
        }
        @Override public void close() throws IOException { RecoveryBundle.deleteOwned(path); }
    }
}