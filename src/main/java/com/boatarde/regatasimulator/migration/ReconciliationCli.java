package com.boatarde.regatasimulator.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Read-only candidate inspection. Never restores, purges or starts application writers. */
public final class ReconciliationCli {
    private static final ObjectMapper MAPPER=new ObjectMapper();
    public record Stage(String kind,String directory,String itemId,boolean metadataPresent,boolean destinationPresent,
                        String recommendation,Map<String,String> hashes) { }
    public static void main(String[] args) {
        try { new ReconciliationCli().run(args); }
        catch(Exception e) { System.err.println("Inspection refused/failed; no automatic reconciliation is performed"); System.exit(2); }
    }
    public void run(String... args) throws IOException {
        if (args.length!=7 || !args[0].equals("--ack-stopped-isolated-copy") || !args[1].equals("true")
            || !Set.of("sqlite","jsondb").contains(args[2])) throw new IOException("Explicit stopped-copy inspection required");
        Path database=canonical(args[3]),sources=canonical(args[4]),templates=canonical(args[5]),report=canonical(args[6]);
        var inputs=List.of(database,sources,templates);
        for(int i=0;i<inputs.size();i++) for(int j=i+1;j<inputs.size();j++) {
            if(inputs.get(i).startsWith(inputs.get(j)) || inputs.get(j).startsWith(inputs.get(i))) throw new IOException("Input paths overlap");
        }
        OfflinePaths.newTarget(report,List.of(database,sources,templates));
        var stages=new ArrayList<Stage>(); inspect(sources,"SOURCE",Set.of(),stages); inspect(templates,"TEMPLATE",Set.of(),stages);
        OfflineSnapshot snapshot;
        if (args[2].equals("sqlite")) {
            if(!Files.isRegularFile(database,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Database absent");
            snapshot=RecoveryBundle.readSqliteCopy(database,RecoveryBundle::load);
            snapshot.audit(sources,templates);
        } else { snapshot=new OfflineSnapshot(MAPPER); snapshot.read(database,sources,templates); }
        for(var issue:snapshot.issues) {
            boolean stagedMissing=issue.code().equals("MISSING_MEDIA") && stages.stream().anyMatch(stage ->
                stage.itemId().equals(issue.id()) && issue.collection().equals(stage.kind().equals("SOURCE") ? "sources" : "templates"));
            if(issue.blocking() && !stagedMissing) throw new IOException("Snapshot metadata/media invalid: "+issue.code());
        }
        var sourceIds=new HashSet<UUID>(); snapshot.sourceRecords.forEach(s -> sourceIds.add(s.getId()));
        var templateIds=new HashSet<UUID>(); snapshot.templateRecords.forEach(t -> templateIds.add(t.getId()));
        stages.clear(); inspect(sources,"SOURCE",sourceIds,stages); inspect(templates,"TEMPLATE",templateIds,stages);
        RecoveryBundle.writePrivate(report,MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of("outcome","INSPECTED_ONLY","stages",stages,
            "policy","Freeze every writer; work on coherent copies. Recommendations require independent operator verification and explicit acknowledgment. Never overwrite a destination or infer deletion from unavailable metadata.")));
    }
    private static Path canonical(String value) throws IOException {
        try {
            Path path=Path.of(value),checked=OfflinePaths.checked(path);
            if(!path.equals(checked)) throw new IOException("Canonical paths required");
            for(Path existing=path;existing!=null;existing=existing.getParent()) {
                if(Files.exists(existing,LinkOption.NOFOLLOW_LINKS)) {
                    if(!existing.equals(existing.toRealPath())) throw new IOException("Canonical paths required");
                    break;
                }
            }
            return checked;
        } catch(InvalidPathException e) { throw new IOException("Invalid inspection path",e); }
    }
    private static UUID canonicalUuid(String value) throws IOException {
        try {
            UUID id=UUID.fromString(value);
            if(!id.toString().equals(value)) throw new IOException("Noncanonical stage UUID");
            return id;
        } catch(IllegalArgumentException e) { throw new IOException("Invalid stage UUID",e); }
    }
    private void inspect(Path root,String kind,Set<UUID> ids,List<Stage> results) throws IOException {
        OfflinePaths.hashes(root);
        try(var paths=Files.list(root)) {
            for(Path stage:paths.sorted().toList()) {
                String name=stage.getFileName().toString();
                if(!name.startsWith(".delete-")) continue;
                if(!name.matches("\\.delete-[a-f0-9-]{36}-[a-f0-9-]{36}") || !Files.isDirectory(stage,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unrecognized stage requires manual inspection");
                UUID id=canonicalUuid(name.substring(8,44));
                canonicalUuid(name.substring(45));
                boolean present=ids.contains(id),destination=Files.exists(root.resolve(id.toString()),LinkOption.NOFOLLOW_LINKS);
                String recommendation=destination ? "RETAIN_DESTINATION_CONFLICT" : present ? "RESTORE_CANDIDATE_OPERATOR_ACK_REQUIRED" : "RETAIN_COMMITTED_DELETION_OPERATOR_REVIEW";
                results.add(new Stage(kind,name,id.toString(),present,destination,recommendation,OfflinePaths.hashes(stage)));
            }
        }
    }
}