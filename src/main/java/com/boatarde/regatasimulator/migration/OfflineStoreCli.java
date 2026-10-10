package com.boatarde.regatasimulator.migration;

import com.boatarde.regatasimulator.repository.*;
import com.boatarde.regatasimulator.repository.sqlite.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Standalone main: no SpringApplication, Telegram, schedules or dotenv loading. */
public final class OfflineStoreCli {
    private static final String IMPORT="import";
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(OfflineStoreCli.class);
    private final ObjectMapper mapper=new ObjectMapper();
    public static void main(String[] args) {
        try {
            int status=new OfflineStoreCli().run(args);
            if(status!=0) System.exit(status);
        } catch(Exception e) {
            LOG.error("Offline operation refused or failed; preserve inputs and inspect the safe report.");
            System.exit(2);
        }
    }
    public int run(String... args) throws IOException {
        if(args.length==0 || !Set.of(IMPORT,"export").contains(args[0])) throw new IOException("Use import or export");
        Map<String,String> options=new HashMap<>();
        for(int i=1;i<args.length;i+=2) {
            if(i+1>=args.length || options.put(args[i],args[i+1])!=null) throw new IOException("Invalid options");
        }
        if(!"true".equals(options.remove("--ack-write-freeze-and-isolated-copy"))) throw new IOException("Write freeze and isolated copy acknowledgment required");
        Path input=path(options,"--source-path");
        Path sourceMedia=path(options,"--sources-path");
        Path templateMedia=path(options,"--templates-path");
        Path target=path(options,args[0].equals(IMPORT)?"--target-file":"--target-path");
        Path report=path(options,"--report-file");
        if(!options.isEmpty()) throw new IOException("Unknown options");
        List<Path> inputs=List.of(input,sourceMedia,templateMedia);
        OfflinePaths.newTarget(target,inputs); OfflinePaths.newTarget(report,inputs);
        if(report.startsWith(target) || target.startsWith(report)) throw new IOException("Report and target overlap");
        return args[0].equals(IMPORT) ? importSnapshot(input,sourceMedia,templateMedia,target,report) : exportSnapshot(input,sourceMedia,templateMedia,target,report);
    }
    private Path path(Map<String,String> options,String name) throws IOException {
        String value=options.remove(name);
        if(value==null) throw new IOException("Missing required option");
        return OfflinePaths.checked(Path.of(value));
    }
    private int importSnapshot(Path input,Path sourceMedia,Path templateMedia,Path target,Path report) throws IOException {
        OfflineSnapshot snapshot=new OfflineSnapshot(mapper); snapshot.read(input,sourceMedia,templateMedia);
        if(snapshot.blocked()) { report(report,snapshot,"AUDIT_FAILED"); return 2; }
        Files.createFile(target); // Atomic no-overwrite reservation; a failed candidate is retained, never reused.
        try(var store=new SqliteStore(target,2000)) {
            var sources=new SqliteSourceRepository(store,mapper);
            var templates=new SqliteTemplateRepository(store,mapper);
            var users=new SqliteAuthorRepository(store);
            var history=new SqliteMemeHistoryRepository(store,mapper);
            store.transactions().executeWithoutResult(tx -> {
                snapshot.users.forEach(users::recordSubmitter); snapshot.sources.forEach(sources::insertSubmission);
                snapshot.templates.forEach(templates::insertSubmission); snapshot.memes.forEach(history::importHistory);
                OfflineSnapshot loaded=load(store);
                if(!snapshot.canonical().equals(loaded.canonical())) throw new IllegalStateException("Field reconciliation failed");
                integrity(store);
                try {
                    OfflineSnapshot rechecked=new OfflineSnapshot(mapper); rechecked.read(input,sourceMedia,templateMedia);
                    if(!snapshot.manifest.equals(rechecked.manifest)) throw new IllegalStateException("Snapshot changed during import");
                } catch(IOException e) { throw new java.io.UncheckedIOException(e); }
            });
            report(report,snapshot,"IMPORTED_VERIFIED"); return 0;
        } catch(Exception e) {
            report(report,snapshot,"IMPORT_FAILED_CANDIDATE_RETAINED"); return 2;
        }
    }
    private int exportSnapshot(Path input,Path sourceMedia,Path templateMedia,Path target,Path report) throws IOException {
        Map<String,String> sourcesBefore=OfflinePaths.hashes(sourceMedia);
        Map<String,String> templatesBefore=OfflinePaths.hashes(templateMedia);
        OfflineSnapshot snapshot;
        try(var store=new SqliteStore(input,2000,true)) {
            snapshot=store.transactions().execute(tx -> { integrity(store); return load(store); });
        }
        snapshot.manifest.put(OfflineSnapshot.SOURCES,sourcesBefore); snapshot.manifest.put(OfflineSnapshot.TEMPLATES,templatesBefore);
        snapshot.audit(sourceMedia,templateMedia);
        if(snapshot.blocked()) { report(report,snapshot,"AUDIT_FAILED"); return 2; }
        Files.createDirectory(target);
        try {
            snapshot.writeJson(target.resolve("jsondb"));
            OfflinePaths.copyTree(sourceMedia,target.resolve(OfflineSnapshot.SOURCES)); OfflinePaths.copyTree(templateMedia,target.resolve(OfflineSnapshot.TEMPLATES));
            OfflineSnapshot restored=new OfflineSnapshot(mapper); restored.read(target.resolve("jsondb"),target.resolve(OfflineSnapshot.SOURCES),target.resolve(OfflineSnapshot.TEMPLATES));
            if(restored.blocked() || !snapshot.canonical().equals(restored.canonical()) || !sourcesBefore.equals(OfflinePaths.hashes(sourceMedia))
                || !templatesBefore.equals(OfflinePaths.hashes(templateMedia)) || !sourcesBefore.equals(OfflinePaths.hashes(target.resolve(OfflineSnapshot.SOURCES)))
                || !templatesBefore.equals(OfflinePaths.hashes(target.resolve(OfflineSnapshot.TEMPLATES)))) throw new IOException("Export reconciliation failed");
            snapshot.manifest.put("export",restored.manifest);
            report(report,snapshot,"EXPORTED_VERIFIED"); return 0;
        } catch(Exception e) { report(report,snapshot,"EXPORT_FAILED_CANDIDATE_RETAINED"); return 2; }
    }
    private OfflineSnapshot load(SqliteStore store) {
        OfflineSnapshot snapshot=new OfflineSnapshot(mapper);
        snapshot.sources.addAll(new SqliteSourceRepository(store,mapper).find(SourceRepository.Criteria.all()));
        snapshot.templates.addAll(new SqliteTemplateRepository(store,mapper).find(TemplateRepository.Criteria.all()));
        snapshot.users.addAll(new SqliteAuthorRepository(store).findAll()); snapshot.memes.addAll(new SqliteMemeHistoryRepository(store,mapper).newestFirst());
        return snapshot;
    }
    private void integrity(SqliteStore store) {
        if(!"ok".equals(store.jdbc().queryForObject("PRAGMA integrity_check",String.class)) || !store.jdbc().queryForList("PRAGMA foreign_key_check").isEmpty()) throw new IllegalStateException("Integrity check failed");
    }
    private void report(Path file,OfflineSnapshot snapshot,String outcome) throws IOException {
        String safe=mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("outcome",outcome,"counts",snapshot.counts(),"issues",snapshot.issues,"manifest",snapshot.manifest));
        Files.writeString(file,safe,StandardOpenOption.CREATE_NEW);
    }
}