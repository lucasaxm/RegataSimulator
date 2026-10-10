package com.boatarde.regatasimulator.migration;

import com.boatarde.regatasimulator.models.*;
import com.boatarde.regatasimulator.repository.sqlite.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReconciliationCliTest {
    @TempDir Path temp;
    @ParameterizedTest @ValueSource(strings={"sqlite","jsondb"})
    void reportsRestoreDeletionAndDestinationConflictWithoutChangingCandidateBytes(String engine) throws Exception {
        Fixture fixture=fixture(engine); Path sources=fixture.sources();
        UUID restore=UUID.randomUUID(),deleted=UUID.randomUUID(),conflict=UUID.randomUUID();
        for(UUID id:List.of(restore,conflict)) addSource(fixture,id);
        for(UUID id:java.util.List.of(restore,deleted,conflict)) Files.writeString(Files.createDirectory(sources.resolve(".delete-"+id+"-"+UUID.randomUUID())).resolve("source.png"),"retained synthetic bytes");
        Files.createDirectory(sources.resolve(conflict.toString()));
        var before=OfflinePaths.hashes(fixture.root()); var names=entries(fixture.root()); Path report=temp.resolve("inspection.json");
        run(fixture,"true",report);
        String text=Files.readString(report);
        for(String code:java.util.List.of("INSPECTED_ONLY","RESTORE_CANDIDATE_OPERATOR_ACK_REQUIRED","RETAIN_COMMITTED_DELETION_OPERATOR_REVIEW","RETAIN_DESTINATION_CONFLICT")) assertTrue(text.contains(code));
        assertEquals(before,OfflinePaths.hashes(fixture.root()));
        assertEquals(names,entries(fixture.root()));
    }

    private record Fixture(String engine,Path root,Path database,Path sources,Path templates) { }
    private Fixture fixture(String engine) throws Exception {
        temp=temp.toRealPath(); Path root=Files.createDirectory(temp.resolve("copy-"+UUID.randomUUID()));
        Path sources=Files.createDirectory(root.resolve("sources")),templates=Files.createDirectory(root.resolve("templates"));
        Path database=root.resolve(engine.equals("sqlite") ? "copy.db" : "jsondb");
        if(engine.equals("sqlite")) { try(var store=new SqliteStore(database,100)) { store.verifyIntegrity(); } }
        else new OfflineSnapshot(new ObjectMapper()).writeJson(database);
        return new Fixture(engine,root,database,sources,templates);
    }
    private void addSource(Fixture fixture,UUID id) throws Exception {
        Source source=new Source(); source.setId(id); source.setDescription(id.toString()); source.setWeight(10); source.setStatus(Status.REVIEW);
        if(fixture.engine().equals("sqlite")) {
            try(var store=new SqliteStore(fixture.database(),100)) { new SqliteSourceRepository(store,new ObjectMapper()).insertSubmission(source); }
        } else Files.writeString(fixture.database().resolve("sources.json"),new ObjectMapper().writeValueAsString(source)+"\n",StandardOpenOption.APPEND);
    }
    private void run(Fixture fixture,String ack,Path report) throws IOException {
        new ReconciliationCli().run("--ack-stopped-isolated-copy",ack,fixture.engine(),fixture.database().toString(),fixture.sources().toString(),fixture.templates().toString(),report.toString());
    }
    private List<String> entries(Path root) throws IOException {
        try(var paths=Files.walk(root)) { return paths.map(root::relativize).map(Path::toString).sorted().toList(); }
    }
    private void refused(Fixture fixture,String ack,Path report) throws Exception {
        // Hashing symlinks is intentionally forbidden, so compare link targets separately.
        Map<String,String> before=new TreeMap<>();
        try(var paths=Files.walk(fixture.root())) {
            for(Path path:paths.toList()) if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))
                before.put(path.toString(),Files.isSymbolicLink(path) ? Files.readSymbolicLink(path).toString() : OfflinePaths.hash(path));
        }
        var names=entries(fixture.root()); boolean existed=Files.exists(report);
        assertThrows(IOException.class,()->run(fixture,ack,report));
        assertEquals(existed,Files.exists(report)); assertEquals(names,entries(fixture.root()));
        for(var entry:before.entrySet()) {
            Path path=Path.of(entry.getKey());
            assertEquals(entry.getValue(),Files.isSymbolicLink(path) ? Files.readSymbolicLink(path).toString() : OfflinePaths.hash(path));
        }
    }

    @ParameterizedTest @ValueSource(strings={"sqlite","jsondb"})
    void invalidStageNamesAndSymlinksRefuseReportWithoutInputChanges(String engine) throws Exception {
        UUID id=UUID.randomUUID();
        for(String name:List.of(".delete-"+id+"-"+"-".repeat(36),".delete-"+"-".repeat(36)+"-"+UUID.randomUUID(),
            ".delete-1-1-1-1-1-"+UUID.randomUUID(),".delete-"+id+"-1-1-1-1-1",
            ".delete-"+id+"-"+UUID.randomUUID().toString().toUpperCase(Locale.ROOT))) {
            Fixture fixture=fixture(engine); Files.createDirectory(fixture.sources().resolve(name));
            refused(fixture,"true",temp.resolve("rejected-"+UUID.randomUUID()+".json"));
        }
        Fixture fixture=fixture(engine);
        Files.createSymbolicLink(fixture.sources().resolve(".delete-"+id+"-"+UUID.randomUUID()),fixture.templates());
        refused(fixture,"true",temp.resolve("symlink-"+engine+".json"));
    }

    @ParameterizedTest @ValueSource(strings={"sqlite","jsondb"})
    void unavailableDatabaseAndInvalidMediaRootsFailClosed(String engine) throws Exception {
        Fixture missing=fixture(engine);
        if(engine.equals("sqlite")) Files.delete(missing.database()); else RecoveryBundle.deleteOwned(missing.database());
        refused(missing,"true",temp.resolve("missing-"+engine+".json"));
        Fixture malformed=fixture(engine);
        Files.writeString(engine.equals("sqlite") ? malformed.database() : malformed.database().resolve("sources.json"),"not metadata");
        refused(malformed,"true",temp.resolve("malformed-"+engine+".json"));
        Fixture media=fixture(engine); Files.delete(media.sources()); Files.writeString(media.sources(),"not a directory");
        refused(media,"true",temp.resolve("media-"+engine+".json"));
    }

    @ParameterizedTest @ValueSource(strings={"sqlite","jsondb"})
    void overlapsNoncanonicalPathsExistingReportAndMissingAcknowledgmentAreRefused(String engine) throws Exception {
        Fixture f=fixture(engine); Path report=f.root().resolve("report.json");
        refused(f,"false",report);
        Files.writeString(report,"operator report"); refused(f,"true",report);
        for(Fixture invalid:List.of(new Fixture(engine,f.root(),f.database(),f.sources(),f.sources()),
            new Fixture(engine,f.root(),f.database(),f.sources(),Files.createDirectory(f.sources().resolve("nested"))),
            new Fixture(engine,f.root(),f.database(),f.root(),f.templates()),
            new Fixture(engine,f.root(),f.database(),f.sources().resolve("../sources"),f.templates()),
            new Fixture(engine,f.root(),f.database(),Path.of("relative-sources"),f.templates()))) {
            refused(invalid,"true",temp.resolve("overlap-"+UUID.randomUUID()+".json"));
        }
        Path link=f.root().resolve("linked"); Files.createSymbolicLink(link,f.sources());
        refused(new Fixture(engine,f.root(),f.database(),link,f.templates()),"true",temp.resolve("linked-report-"+engine+".json"));
    }

    @Test void jsonDbBlockingIssuesCannotMasqueradeAsCommittedDeletion() throws Exception {
        for(String failure:List.of("missing-collection","duplicate","invalid-row","invalid-id","schema","unstaged-missing")) {
            Fixture f=fixture("jsondb"); UUID id=UUID.randomUUID(); addSource(f,id);
            if(!failure.equals("unstaged-missing")) Files.createDirectory(f.sources().resolve(".delete-"+id+"-"+UUID.randomUUID()));
            switch(failure) {
                case "missing-collection" -> Files.delete(f.database().resolve("users.json"));
                case "duplicate" -> addSource(f,id);
                case "invalid-row" -> Files.writeString(f.database().resolve("sources.json"),"{bad\n",StandardOpenOption.APPEND);
                case "invalid-id" -> Files.writeString(f.database().resolve("sources.json"),"{\"id\":\"1-1-1-1-1\"}\n",StandardOpenOption.APPEND);
                case "schema" -> Files.writeString(f.database().resolve("users.json"),"{\"schemaVersion\":\"2.0\"}\n");
                default -> { /* Unstaged missing media must remain blocking. */ }
            }
            refused(f,"true",temp.resolve("issue-"+failure+".json"));
        }
    }

    @ParameterizedTest @ValueSource(strings={"sqlite","jsondb"})
    void templateStagesAreAcceptedButCorruptOriginalMediaIsNotExempt(String engine) throws Exception {
        Fixture f=fixture(engine); UUID id=UUID.randomUUID();
        Template template=new Template(); template.setId(id); template.setWeight(10); template.setStatus(Status.REVIEW);
        template.setAreas(List.of(TemplateArea.builder().index(1).source(1).topLeft(new AreaCorner(0,0)).topRight(new AreaCorner(20,0))
            .bottomRight(new AreaCorner(20,20)).bottomLeft(new AreaCorner(0,20)).build()));
        if(engine.equals("sqlite")) {
            try(var store=new SqliteStore(f.database(),100)) { new SqliteTemplateRepository(store,new ObjectMapper()).insertSubmission(template); }
        } else Files.writeString(f.database().resolve("templates.json"),new ObjectMapper().writeValueAsString(template)+"\n",StandardOpenOption.APPEND);
        Files.writeString(Files.createDirectory(f.templates().resolve(".delete-"+id+"-"+UUID.randomUUID())).resolve("template.png"),"staged bytes");
        var before=OfflinePaths.hashes(f.root()); Path report=temp.resolve("template-"+engine+".json");
        run(f,"true",report);
        var stages=new ObjectMapper().readTree(Files.readString(report)).get("stages");
        assertEquals("TEMPLATE",stages.get(0).get("kind").asText()); assertTrue(stages.get(0).get("metadataPresent").asBoolean());
        assertEquals("RESTORE_CANDIDATE_OPERATOR_ACK_REQUIRED",stages.get(0).get("recommendation").asText());
        assertEquals(before,OfflinePaths.hashes(f.root()));
        Files.writeString(Files.createDirectory(f.templates().resolve(id.toString())).resolve("template.png"),"corrupt original");
        refused(f,"true",temp.resolve("corrupt-original-"+engine+".json"));
    }

    @Test void sqliteStoppedCopyIncludesCommittedWalWithoutChangingAnyDatabaseSidecar() throws Exception {
        Fixture f=fixture("sqlite"); UUID id=UUID.randomUUID(); Path database=f.root().resolve("wal-copy.db");
        try(var store=new SqliteStore(f.database(),100)) {
            Source source=new Source(); source.setId(id); source.setDescription("wal only"); source.setWeight(10); source.setStatus(Status.REVIEW);
            new SqliteSourceRepository(store,new ObjectMapper()).insertSubmission(source);
            assertTrue(Files.size(f.database().resolveSibling(f.database().getFileName()+"-wal"))>0);
            // No concurrent writer: copy the coherent main file and its committed WAL/SHM together.
            for(String suffix:List.of("","-wal","-shm")) Files.copy(f.database().resolveSibling(f.database().getFileName()+suffix),database.resolveSibling(database.getFileName()+suffix));
        }
        Files.createDirectory(f.sources().resolve(".delete-"+id+"-"+UUID.randomUUID()));
        Fixture copy=new Fixture("sqlite",f.root(),database,f.sources(),f.templates());
        var before=OfflinePaths.hashes(copy.root()); var names=entries(copy.root()); Path report=temp.resolve("wal-report.json");
        run(copy,"true",report);
        assertTrue(new ObjectMapper().readTree(Files.readString(report)).get("stages").get(0).get("metadataPresent").asBoolean());
        assertEquals(before,OfflinePaths.hashes(copy.root())); assertEquals(names,entries(copy.root()));
    }
}