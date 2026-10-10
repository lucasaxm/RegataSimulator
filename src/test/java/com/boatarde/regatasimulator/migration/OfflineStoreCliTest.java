package com.boatarde.regatasimulator.migration;

import com.boatarde.regatasimulator.factory.ImageTestFactory;
import com.boatarde.regatasimulator.models.*;
import com.boatarde.regatasimulator.repository.sqlite.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsondb.JsonDBTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.telegram.telegrambots.meta.api.objects.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OfflineStoreCliTest {
    @TempDir Path temp;
    final ObjectMapper mapper=new ObjectMapper();
    Path json,sources,templates;
    Source source; Template template; Meme meme;
    void fixture() throws Exception {
        temp=temp.toRealPath();
        json=Files.createDirectory(temp.resolve("json")); sources=Files.createDirectory(temp.resolve("sources")); templates=Files.createDirectory(temp.resolve("templates"));
        var db=new JsonDBTemplate(json.toString(),"com.boatarde.regatasimulator.models");
        for(String collection:List.of("sources","templates","users","memes")) db.createCollection(collection);
        source=new Source(); source.setId(UUID.randomUUID()); source.setDescription(" Ｃafé "); source.setWeight(7); source.setStatus(Status.REVIEW);
        Message original=new Message(); original.setMessageId(5); original.setDate(10); original.setText("synthetic-private-text-not-for-report");
        Chat chat=new Chat(); chat.setId(-9_000_000_000L); original.setChat(chat); User user=new User(); user.setId(9_000_000_000L); user.setFirstName("Synthetic"); original.setFrom(user);
        source.setMessage(original); source.setPreviewChatId(chat.getId()); source.setPreviewMessageId(20); db.insert(source);
        Source imported=new Source(); imported.setId(UUID.randomUUID()); imported.setDescription(null); imported.setWeight(1); imported.setStatus(Status.APPROVED); db.insert(imported);
        template=new Template(); template.setId(UUID.randomUUID()); template.setWeight(19); template.setStatus(Status.APPROVED);
        template.setAreas(List.of(TemplateArea.builder().index(1).source(1).topLeft(new AreaCorner(0,0)).topRight(new AreaCorner(20,0)).bottomRight(new AreaCorner(20,20)).bottomLeft(new AreaCorner(0,20)).background(true).build())); db.insert(template);
        meme=Meme.builder().id(UUID.randomUUID()).templateId(UUID.randomUUID()).sourceIds(Arrays.asList(source.getId(),UUID.randomUUID(),source.getId(),null)).message(null).build(); db.insert(meme);
        db.insert(Author.builder().id(9_000_000_000L).firstName("Synthetic").build());
        for(Source s:List.of(source,imported)) ImageTestFactory.image(Files.createDirectory(sources.resolve(s.getId().toString())).resolve("source.png"));
        ImageTestFactory.image(Files.createDirectory(templates.resolve(template.getId().toString())).resolve("template.png"));
    }
    String[] args(String mode,Path input,Path target,Path report) {
        return new String[]{mode,"--ack-write-freeze-and-isolated-copy","true","--source-path",input.toString(),"--sources-path",sources.toString(),"--templates-path",templates.toString(),mode.equals("import")?"--target-file":"--target-path",target.toString(),"--report-file",report.toString()};
    }
    @Test void importNewWritesExportAndJsonDbReopenPreserveEveryFieldAndMedia() throws Exception {
        fixture(); Map<String,String> before=OfflinePaths.hashes(json),imagesBefore=OfflinePaths.hashes(sources);
        Path candidate=temp.resolve("candidate.db"),report=temp.resolve("import-report.json");
        var cli=new OfflineStoreCli(); assertEquals(0,cli.run(args("import",json,candidate,report)));
        assertEquals(before,OfflinePaths.hashes(json)); assertEquals(imagesBefore,OfflinePaths.hashes(sources));
        String safe=Files.readString(report); assertTrue(safe.contains("ORPHAN_SOURCE")); assertTrue(safe.contains("ORPHAN_TEMPLATE")); assertFalse(safe.contains("synthetic-private-text"));
        try(var store=new SqliteStore(candidate,100)) {
            var repository=new SqliteSourceRepository(store,mapper); Source stored=repository.findById(source.getId()).orElseThrow();
            assertEquals(mapper.valueToTree(source),mapper.valueToTree(stored));
            assertEquals(mapper.valueToTree(meme),mapper.valueToTree(new SqliteMemeHistoryRepository(store,mapper).newestFirst().getFirst()));
            assertTrue(repository.decideReview(source.getId(),Status.APPROVED)); repository.decreaseWeight(source.getId());
            Source newWrite=new Source(); newWrite.setId(UUID.randomUUID()); newWrite.setDescription("post migration"); newWrite.setWeight(10); newWrite.setStatus(Status.REVIEW); repository.insertSubmission(newWrite);
            ImageTestFactory.image(Files.createDirectory(sources.resolve(newWrite.getId().toString())).resolve("source.png"));
        }
        String dbBefore=OfflinePaths.hash(candidate); Path rollback=temp.resolve("rollback");
        assertEquals(0,cli.run(args("export",candidate,rollback,temp.resolve("export-report.json"))));
        assertEquals(dbBefore,OfflinePaths.hash(candidate));
        var reopened=new JsonDBTemplate(rollback.resolve("jsondb").toString(),"com.boatarde.regatasimulator.models");
        assertEquals(3,reopened.findAll(Source.class).size()); Source restored=reopened.findById(source.getId(),Source.class);
        assertEquals(Status.APPROVED,restored.getStatus()); assertEquals(6,restored.getWeight()); assertNull(restored.getPreviewChatId());
        assertEquals(mapper.valueToTree(source.getMessage()),mapper.valueToTree(restored.getMessage()));
        assertEquals(mapper.valueToTree(template),mapper.valueToTree(reopened.findById(template.getId(),Template.class)));
        assertEquals(mapper.valueToTree(meme),mapper.valueToTree(reopened.findById(meme.getId(),Meme.class)));
        assertEquals(OfflinePaths.hashes(sources),OfflinePaths.hashes(rollback.resolve("sources")));
        assertEquals(OfflinePaths.hashes(templates),OfflinePaths.hashes(rollback.resolve("templates")));
        assertThrows(java.io.IOException.class,() -> cli.run(args("import",json,candidate,temp.resolve("repeat-report.json"))));
    }
    @Test void auditsAllInvalidRowsWithoutCreatingTargetOrMutatingInputs() throws Exception {
        fixture(); var db=new JsonDBTemplate(json.toString(),"com.boatarde.regatasimulator.models");
        Source duplicate=new Source(); duplicate.setId(UUID.randomUUID()); duplicate.setDescription("café"); duplicate.setWeight(0); duplicate.setStatus(Status.REVIEW); db.insert(duplicate);
        template.getAreas().getFirst().setIndex(2); db.save(template,Template.class);
        Files.writeString(json.resolve("sources.json"),"\n{invalid-record}",StandardOpenOption.APPEND);
        var before=OfflinePaths.hashes(json); Path candidate=temp.resolve("blocked.db"),report=temp.resolve("audit.json");
        assertEquals(2,new OfflineStoreCli().run(args("import",json,candidate,report))); assertFalse(Files.exists(candidate)); assertEquals(before,OfflinePaths.hashes(json));
        String safe=Files.readString(report);
        for(String code:List.of("DUPLICATE_DESCRIPTION","INVALID_WEIGHT","INVALID_GEOMETRY","MISSING_MEDIA","INVALID_RECORD")) assertTrue(safe.contains(code),code);
        assertFalse(safe.contains("synthetic-private-text"));
    }
    @Test void rejectsMissingAcknowledgmentExistingAndOverlappingPathsAndSymlinks() throws Exception {
        fixture(); var cli=new OfflineStoreCli(); Path target=temp.resolve("guard.db"); String[] args=args("import",json,target,temp.resolve("guard.json"));
        args[2]="false"; assertThrows(java.io.IOException.class,() -> cli.run(args));
        assertThrows(java.io.IOException.class,() -> cli.run(args("import",json,json.resolve("overlap.db"),temp.resolve("guard2.json"))));
        Files.createSymbolicLink(temp.resolve("link"),json);
        assertThrows(java.io.IOException.class,() -> cli.run(args("import",temp.resolve("link"),target,temp.resolve("guard3.json"))));
        assertFalse(Files.exists(target));
    }
    @Test void readsLegacyOmittedPreviewBindingsWithoutBackfilling() throws Exception {
        fixture(); var lines=new ArrayList<>(Files.readAllLines(json.resolve("sources.json")));
        for(int i=1;i<lines.size();i++) { var node=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(lines.get(i)); node.remove(List.of("previewChatId","previewMessageId")); lines.set(i,node.toString()); }
        Files.write(json.resolve("sources.json"),lines); Path target=temp.resolve("legacy.db");
        assertEquals(0,new OfflineStoreCli().run(args("import",json,target,temp.resolve("legacy-report.json"))));
        try(var store=new SqliteStore(target,100,true)) { assertNull(new SqliteSourceRepository(store,mapper).findById(source.getId()).orElseThrow().getPreviewMessageId()); }
    }

    @Test void nullRecordsDuplicateIdsAndMissingCollectionsAreAllReported() throws Exception {
        fixture();
        Files.writeString(json.resolve("sources.json"),"null\n"+mapper.writeValueAsString(source)+"\n",StandardOpenOption.APPEND);
        Files.delete(json.resolve("users.json"));
        Path target=temp.resolve("invalid.db"),report=temp.resolve("invalid-report.json");
        assertEquals(2,new OfflineStoreCli().run(args("import",json,target,report)));
        var result=mapper.readTree(Files.readString(report));
        assertTrue(result.path("issues").toString().contains("INVALID_RECORD"));
        assertTrue(result.path("issues").toString().contains("INVALID_OR_DUPLICATE_ID"));
        assertTrue(result.path("issues").toString().contains("COLLECTION_UNREADABLE"));
        assertFalse(Files.exists(target));
    }
}