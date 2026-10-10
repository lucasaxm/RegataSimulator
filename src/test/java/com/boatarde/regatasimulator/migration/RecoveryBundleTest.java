package com.boatarde.regatasimulator.migration;

import com.boatarde.regatasimulator.application.MediaMutationGuard;
import com.boatarde.regatasimulator.factory.ImageTestFactory;
import com.boatarde.regatasimulator.models.*;
import com.boatarde.regatasimulator.repository.jsondb.*;
import com.boatarde.regatasimulator.repository.sqlite.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecoveryBundleTest {
    @TempDir Path temp;
    final ObjectMapper mapper=new ObjectMapper();

    private Path stage() throws Exception {
        temp=temp.toRealPath();
        Path stage=Files.createDirectory(temp.resolve("stage"));
        for (String name:List.of("sources","templates","db")) Files.createDirectory(stage.resolve(name));
        return stage;
    }
    private Source source(Path stage) throws Exception {
        Source source=new Source(); source.setId(UUID.randomUUID()); source.setDescription("synthetic"); source.setWeight(9); source.setStatus(Status.APPROVED);
        ImageTestFactory.image(Files.createDirectory(stage.resolve("sources").resolve(source.getId().toString())).resolve("source.png"));
        Files.writeString(Files.createDirectory(stage.resolve("sources/.delete-uncertain")).resolve("retained.txt"),"uncertain bytes");
        return source;
    }

    @Test void sqliteNewWritesBackupRestorePreservesHashesMetadataOrphansAndPrivatePermissions() throws Exception {
        Path stage=stage(); Source source=source(stage);
        Path live=temp.resolve("live.db");
        try (var store=new SqliteStore(live,100)) {
            new SqliteSourceRepository(store,mapper).insertSubmission(source);
            new SqliteAuditRepository(store).append(ModerationAudit.builder().id(UUID.randomUUID()).itemType("SOURCE").itemId(source.getId())
                .actorName("synthetic-admin").actorTelegramId(9_000_000_000L).decidedAt(1234L).decision(Status.APPROVED).notification("FAILED").build());
            store.snapshot(stage.resolve("db/store.db"));
            new SqliteSourceRepository(store,mapper).decreaseWeight(source.getId());
        }
        Path bundle=RecoveryBundle.pack(stage,temp.resolve("backup-"+UUID.randomUUID()),"sqlite",RecoveryBundle.CHUNK_BYTES);
        Path restored=RecoveryBundle.restore(bundle,temp.resolve("restored"));
        assertTrue(Files.exists(restored.resolve("RESTORED_VERIFIED")));
        try (var copy=new SqliteStore(restored.resolve("db/store.db"),100,true)) {
            assertEquals(mapper.valueToTree(source),mapper.valueToTree(new SqliteSourceRepository(copy,mapper).findById(source.getId()).orElseThrow()));
            assertEquals(9_000_000_000L,new SqliteAuditRepository(copy).findAll().getFirst().getActorTelegramId());
        }
        assertEquals(OfflinePaths.hashes(stage.resolve("sources")),OfflinePaths.hashes(restored.resolve("sources")));
        var decoded=javax.imageio.ImageIO.read(restored.resolve("sources").resolve(source.getId().toString()).resolve("source.png").toFile());
        assertNotNull(decoded); assertTrue(decoded.getWidth()>0);
        try (var paths=Files.walk(restored)) {
            for (Path p:paths.toList()) assertEquals(PosixFilePermissions.fromString(Files.isDirectory(p)?"rwx------":"rw-------"),Files.getPosixFilePermissions(p));
        }
        assertThrows(java.io.IOException.class,()->RecoveryBundle.restore(bundle,restored));
        String safe=Files.readString(bundle.resolve("manifest.json"));
        assertTrue(safe.contains("db/store.db")); assertTrue(safe.contains("NULL_ORIGIN"));
    }

    @Test void jsonDbCaptureRoundtripAndRetentionNeverTouchesUnrelatedDirectories() throws Exception {
        temp=temp.toRealPath();
        Path json=Files.createDirectory(temp.resolve("json")),sources=Files.createDirectory(temp.resolve("sources")),templates=Files.createDirectory(temp.resolve("templates"));
        Path destination=temp.resolve("backups"); RecoveryBundle.privateDirectory(destination);
        var db=new io.jsondb.JsonDBTemplate(json.toString(),"com.boatarde.regatasimulator.models");
        for (String name:List.of("sources","templates","users","memes")) db.createCollection(name);
        var provider=mock(ObjectProvider.class);
        var service=new RecoverySnapshotService(new MediaMutationGuard(),new JsonDbSourceRepository(db),new JsonDbTemplateRepository(db),
            new JsonDbAuthorRepository(db),new JsonDbMemeHistoryRepository(db),provider,sources.toString(),templates.toString(),destination.toString(),1);
        Path first=service.capture();
        Path unrelated=Files.createDirectory(destination.resolve("operator-notes")); Files.writeString(unrelated.resolve("keep"),"keep");
        Path second=service.capture(); assertFalse(Files.exists(first)); assertTrue(Files.exists(second)); assertTrue(Files.exists(unrelated));
        Path restored=RecoveryBundle.restore(second,temp.resolve("restored"));
        var reopened=new io.jsondb.JsonDBTemplate(restored.resolve("jsondb").toString(),"com.boatarde.regatasimulator.models");
        assertTrue(reopened.findAll(Source.class).isEmpty());
    }

    @Test void missingPartTamperingSymlinkParentAndOverlappingTargetsFailClosed() throws Exception {
        Path stage=stage();
        try (var store=new SqliteStore(stage.resolve("db/store.db"),100)) { store.verifyIntegrity(); }
        Path bundle=RecoveryBundle.pack(stage,temp.resolve("backup-"+UUID.randomUUID()),"sqlite",RecoveryBundle.CHUNK_BYTES);
        var manifest=RecoveryBundle.manifest(bundle);
        Files.writeString(bundle.resolve(manifest.parts().getFirst().file()),"tamper",StandardOpenOption.APPEND);
        assertThrows(java.io.IOException.class,()->RecoveryBundle.restore(bundle,temp.resolve("bad")));
        assertFalse(Files.exists(temp.resolve("bad/RESTORED_VERIFIED")));
        Files.createSymbolicLink(temp.resolve("link"),temp);
        assertThrows(java.io.IOException.class,()->RecoveryBundle.restore(bundle,temp.resolve("link/target")));
        assertThrows(java.io.IOException.class,()->RecoveryBundle.restore(bundle,bundle.resolve("overlap")));
    }

    @Test void oversizeIndivisibleItemCleansPreparedBundle() throws Exception {
        Path stage=stage();
        try (var store=new SqliteStore(stage.resolve("db/store.db"),100)) { store.verifyIntegrity(); }
        Path target=temp.resolve("backup-"+UUID.randomUUID());
        assertThrows(java.io.IOException.class,()->RecoveryBundle.pack(stage,target,"sqlite",10));
        assertFalse(Files.exists(target));
    }

    @Test void multipartArchiveRestoresEveryWholeItemAndRefusesMissingPart() throws Exception {
        Path stage=stage();
        try (var store=new SqliteStore(stage.resolve("db/store.db"),100)) { store.verifyIntegrity(); }
        var random=new Random(7);
        for (int i=0;i<4;i++) {
            byte[] bytes=new byte[80_000]; random.nextBytes(bytes);
            Path item=Files.createDirectory(stage.resolve("sources").resolve(UUID.randomUUID().toString())); Files.write(item.resolve("retained.bin"),bytes);
        }
        Path bundle=RecoveryBundle.pack(stage,temp.resolve("backup-"+UUID.randomUUID()),"sqlite",130_000);
        var manifest=RecoveryBundle.manifest(bundle);
        assertTrue(manifest.parts().size()>=5);
        Path restored=RecoveryBundle.restore(bundle,temp.resolve("multipart"));
        assertEquals(OfflinePaths.hashes(stage.resolve("sources")),OfflinePaths.hashes(restored.resolve("sources")));
        Files.delete(bundle.resolve(manifest.parts().getLast().file()));
        assertThrows(java.io.IOException.class,()->RecoveryBundle.restore(bundle,temp.resolve("missing")));
        assertFalse(Files.exists(temp.resolve("missing")));
    }

    @Test void maliciousTraversalAndDuplicateZipEntriesCannotEscapeFreshTarget() throws Exception {
        for (String malicious:List.of("../escaped","/absolute","a/../../escaped","a\\b","a%2fb","a/./b")) {
            Path stage=Files.createDirectory(temp.toRealPath().resolve("stage-"+UUID.randomUUID()));
            for(String category:List.of("db","sources","templates")) Files.createDirectory(stage.resolve(category));
            try (var store=new SqliteStore(stage.resolve("db/store.db"),100)) { store.verifyIntegrity(); }
            Path bundle=RecoveryBundle.pack(stage,temp.toRealPath().resolve("backup-"+UUID.randomUUID()),"sqlite",RecoveryBundle.CHUNK_BYTES);
            var m=RecoveryBundle.manifest(bundle); PartReplacement replacement=maliciousPart(bundle,m,malicious);
            var updated=new RecoveryBundle.Manifest(m.format(),m.engine(),m.schemaVersion(),m.databaseFile(),m.counts(),m.hashes(),m.anomalies(),replacement.parts());
            Files.write(bundle.resolve("manifest.json"),mapper.writeValueAsBytes(updated)); Files.writeString(bundle.resolve("COMPLETE"),OfflinePaths.hash(bundle.resolve("manifest.json")));
            Path target=temp.toRealPath().resolve("restore-"+UUID.randomUUID());
            assertThrows(java.io.IOException.class,()->RecoveryBundle.restore(bundle,target));
            assertFalse(Files.exists(temp.resolve("escaped"))); assertFalse(Files.exists(target.resolve("RESTORED_VERIFIED")));
        }
    }
    private record PartReplacement(List<RecoveryBundle.Part> parts) { }
    @Test void rejectsUnixSymlinkAttributesWithoutCreatingAnyLink() throws Exception {
        Path stage=stage();
        try(var store=new SqliteStore(stage.resolve("db/store.db"),100)) { store.verifyIntegrity(); }
        Path bundle=RecoveryBundle.pack(stage,temp.resolve("backup-"+UUID.randomUUID()),"sqlite",RecoveryBundle.CHUNK_BYTES);
        var m=RecoveryBundle.manifest(bundle); var part=m.parts().getFirst(); Path archive=bundle.resolve(part.file());
        byte[] bytes=Files.readAllBytes(archive);
        var data=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for(int i=0;i<bytes.length-46;i++) if(data.getInt(i)==0x02014b50) { data.putInt(i+38,0120777<<16); break; }
        Files.write(archive,bytes);
        var updated=new RecoveryBundle.Manifest(m.format(),m.engine(),m.schemaVersion(),m.databaseFile(),m.counts(),m.hashes(),m.anomalies(),
            List.of(new RecoveryBundle.Part(part.category(),part.file(),OfflinePaths.hash(archive))));
        Files.write(bundle.resolve("manifest.json"),mapper.writeValueAsBytes(updated)); Files.writeString(bundle.resolve("COMPLETE"),OfflinePaths.hash(bundle.resolve("manifest.json")));
        Path target=temp.resolve("symlink-restore");
        assertThrows(java.io.IOException.class,()->RecoveryBundle.restore(bundle,target));
        assertFalse(Files.exists(target.resolve("RESTORED_VERIFIED")));
    }
    private PartReplacement maliciousPart(Path bundle,RecoveryBundle.Manifest m,String name) throws Exception {
        var part=m.parts().getFirst(); Path file=bundle.resolve(part.file());
        try(var zip=new ZipOutputStream(Files.newOutputStream(file))) { zip.putNextEntry(new ZipEntry(name)); zip.write(1); zip.closeEntry(); }
        return new PartReplacement(List.of(new RecoveryBundle.Part(part.category(),part.file(),OfflinePaths.hash(file))));
    }
}