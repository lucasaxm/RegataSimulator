package com.boatarde.regatasimulator.migration;

import com.boatarde.regatasimulator.repository.*;
import com.boatarde.regatasimulator.repository.sqlite.*;
import com.boatarde.regatasimulator.util.FileUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.zip.ZipInputStream;

/** Versioned local artifact. Neither packaging nor restore starts Spring or calls Telegram. */
public final class RecoveryBundle {
    public static final long CHUNK_BYTES = 40L * 1024 * 1024;
    private static final long MAX_EXPANDED_BYTES = 2L * 1024 * 1024 * 1024;
    private static final int MAX_FILES = 100_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public record Part(String category, String file, String sha256) { }
    public record Manifest(int format, String engine, int schemaVersion, String databaseFile,
                           Map<String,Integer> counts, Map<String,String> hashes,
                           List<OfflineSnapshot.Issue> anomalies, List<Part> parts) { }

    private RecoveryBundle() { }

    static OfflineSnapshot load(SourceRepository sources, TemplateRepository templates,
                                AuthorRepository authors, MemeHistoryRepository history) {
        var snapshot = new OfflineSnapshot(MAPPER);
        snapshot.sourceRecords.addAll(sources.find(SourceRepository.Criteria.all()));
        snapshot.templateRecords.addAll(templates.find(TemplateRepository.Criteria.all()));
        snapshot.authorRecords.addAll(authors.findAll());
        snapshot.historyRecords.addAll(history.newestFirst());
        return snapshot;
    }

    static OfflineSnapshot load(SqliteStore store) {
        return load(new SqliteSourceRepository(store,MAPPER), new SqliteTemplateRepository(store,MAPPER),
            new SqliteAuthorRepository(store), new SqliteMemeHistoryRepository(store,MAPPER),new SqliteAuditRepository(store));
    }

    static OfflineSnapshot load(SourceRepository sources, TemplateRepository templates,AuthorRepository authors,
                                MemeHistoryRepository history,AuditRepository audits) {
        var snapshot=load(sources,templates,authors,history);
        snapshot.auditRecords.addAll(audits.findAll());
        return snapshot;
    }

    /** Stage is private, immutable and already captured under the application barrier. */
    public static Path pack(Path stage, Path destination, String engine, long chunkBytes) throws IOException {
        OfflinePaths.newTarget(destination,List.of(stage));
        OfflineSnapshot snapshot = inspect(stage,engine);
        if (snapshot.blocked()) throw new IOException("Snapshot metadata/media invalid");
        Map<String,String> hashes = OfflinePaths.hashes(stage);
        long bytes=0;
        for (String name:hashes.keySet()) {
            safeRelative(name);
            bytes=Math.addExact(bytes,Files.size(stage.resolve(name)));
        }
        if (hashes.size()>MAX_FILES || bytes>MAX_EXPANDED_BYTES || chunkBytes<1 || chunkBytes>CHUNK_BYTES) throw new IOException("Bundle exceeds restoration limits");
        privateDirectory(destination);
        try {
            List<Part> parts = new ArrayList<>();
            for (String category : List.of(engine.equals("sqlite") ? "db" : "jsondb", "sources", "templates")) {
                List<Path> archives = FileUtils.zipInChunks(stage.resolve(category).toString(),chunkBytes,destination);
                for (Path archive : archives) parts.add(new Part(category,archive.getFileName().toString(),OfflinePaths.hash(archive)));
            }
            var manifest = new Manifest(1,engine,schemaVersion(stage,engine),
                engine.equals("sqlite") ? "db/store.db" : "jsondb",snapshot.counts(),hashes,List.copyOf(snapshot.issues),parts);
            writePrivate(destination.resolve("manifest.json"),MAPPER.writeValueAsBytes(manifest));
            // Marker is written last. Retention never selects incomplete artifacts.
            writePrivate(destination.resolve("COMPLETE"),OfflinePaths.hash(destination.resolve("manifest.json")).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return destination;
        } catch (IOException | RuntimeException e) {
            try { deleteOwned(destination); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    public static Path restore(Path bundle, Path target) throws IOException {
        return restore(bundle,target,MAX_EXPANDED_BYTES,MAX_FILES);
    }

    static Path restore(Path bundle,Path target,long maximumBytes,int maximumEntries) throws IOException {
        if(maximumBytes<1 || maximumBytes>MAX_EXPANDED_BYTES || maximumEntries<1 || maximumEntries>MAX_FILES) throw new IOException("Invalid restoration bounds");
        OfflinePaths.checked(bundle);
        OfflinePaths.newTarget(target,List.of(bundle));
        Manifest manifest = manifest(bundle);
        privateDirectory(target);
        try {
            long[] expanded = {0};
            Set<String> entries = new HashSet<>();
            for (String category : List.of(manifest.engine().equals("sqlite") ? "db" : "jsondb","sources","templates")) {
                privateDirectory(target.resolve(category));
            }
            for (Part part : manifest.parts()) {
                Path archive = bundle.resolve(part.file());
                if (Files.size(archive) > CHUNK_BYTES || !OfflinePaths.hash(archive).equals(part.sha256())) throw new IOException("Archive hash/size mismatch");
                extract(archive,target.resolve(part.category()),entries,expanded,maximumBytes,maximumEntries);
            }
            if (!manifest.hashes().equals(OfflinePaths.hashes(target))) throw new IOException("Restored file manifest mismatch");
            OfflineSnapshot restored = inspect(target,manifest.engine());
            var counts=new HashMap<>(manifest.counts()); counts.putIfAbsent("audits",0);
            if (restored.blocked() || !restored.counts().equals(counts) || schemaVersion(target,manifest.engine())!=manifest.schemaVersion()) throw new IOException("Restored metadata/media invalid");
            writePrivate(target.resolve("RESTORED_VERIFIED"),OfflinePaths.hash(bundle.resolve("manifest.json")).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return target;
        } catch (IOException | RuntimeException e) {
            // Keep the failed candidate, with no success marker, for operator inspection.
            throw new IOException("Restore failed; unverified candidate retained",e);
        }
    }

    static Manifest manifest(Path bundle) throws IOException {
        Map<String,String> files = OfflinePaths.hashes(bundle);
        Path file = bundle.resolve("manifest.json");
        if (!files.containsKey("COMPLETE") || Files.size(bundle.resolve("COMPLETE"))!=64 || Files.size(file) > 8L * 1024 * 1024
            || !Files.readString(bundle.resolve("COMPLETE")).equals(OfflinePaths.hash(file))) throw new IOException("Incomplete bundle");
        Manifest m = MAPPER.readValue(Files.readAllBytes(file),Manifest.class);
        if (m==null || m.format()!=1 || m.engine()==null || !Set.of("sqlite","jsondb").contains(m.engine())
            || !(m.engine().equals("sqlite") ? m.schemaVersion()>=2 && m.schemaVersion()<=SqliteStore.SCHEMA_VERSION : m.schemaVersion()==1)
            || !Objects.equals(m.databaseFile(),m.engine().equals("sqlite") ? "db/store.db" : "jsondb")
            || m.parts()==null || m.parts().size()>10_000 || m.hashes()==null || m.hashes().size()>MAX_FILES
            || m.counts()==null || !m.counts().keySet().containsAll(List.of("sources","templates","users","memes"))
            || m.counts().values().stream().anyMatch(n -> n==null || n<0) || m.anomalies()==null
            || m.anomalies().stream().anyMatch(issue -> issue==null || issue.collection()==null || issue.code()==null)) throw new IOException("Unsupported bundle");
        Set<String> allowed = new HashSet<>(List.of("manifest.json","COMPLETE"));
        for (Part part : m.parts()) {
            if (part==null || part.category()==null || part.file()==null || part.sha256()==null
                || !Set.of(m.engine().equals("sqlite") ? "db" : "jsondb","sources","templates").contains(part.category())
                || !part.file().matches("regata-backup-[a-zA-Z0-9-]+\\.zip") || !allowed.add(part.file())
                || !part.sha256().matches("[a-f0-9]{64}")) throw new IOException("Invalid archive manifest");
            if (!part.sha256().equals(files.get(part.file())) || Files.size(bundle.resolve(part.file()))>CHUNK_BYTES) throw new IOException("Archive checksum/size mismatch");
        }
        if (!allowed.equals(files.keySet())) throw new IOException("Missing or unexpected bundle parts");
        for (var entry : m.hashes().entrySet()) {
            safeRelative(entry.getKey());
            if(entry.getValue()==null || !entry.getValue().matches("[a-f0-9]{64}")) throw new IOException("Invalid file checksum");
        }
        return m;
    }

    private static void extract(Path archive, Path root, Set<String> seen, long[] expanded,long maximumBytes,int maximumEntries) throws IOException {
        rejectSpecialZipEntries(archive);
        try (var input = new ZipInputStream(Files.newInputStream(archive))) {
            java.util.zip.ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry=input.getNextEntry())!=null) {
                String name = entry.getName();
                Path relative = safeRelative(entry.isDirectory() ? name.substring(0,name.length()-1) : name);
                String key = root.getFileName()+"/"+relative;
                if (!seen.add(key) || seen.size()>maximumEntries) throw new IOException("Duplicate/excessive entries");
                Path output = root.resolve(relative);
                createParents(root,output.getParent());
                if (entry.isDirectory()) { if (!Files.exists(output)) privateDirectory(output); }
                else {
                    try (var stream = Files.newOutputStream(output,StandardOpenOption.CREATE_NEW)) {
                        Files.setPosixFilePermissions(output,PosixFilePermissions.fromString("rw-------"));
                        int n;
                        while ((n=input.read(buffer))!=-1) {
                            expanded[0] = Math.addExact(expanded[0],n);
                            if (expanded[0]>maximumBytes) throw new IOException("Expanded byte limit exceeded");
                            stream.write(buffer,0,n);
                        }
                    }
                }
                input.closeEntry();
            }
        }
    }

    /** Reject ZIP64/encrypted/special entries; delivery-sized bundles do not need ZIP64. */
    private static void rejectSpecialZipEntries(Path archive) throws IOException {
        if (Files.size(archive)>CHUNK_BYTES) throw new IOException("Archive too large");
        var data=java.nio.ByteBuffer.wrap(Files.readAllBytes(archive)).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        try {
            int end=-1;
            for (int i=data.limit()-22;i>=Math.max(0,data.limit()-65557);i--) {
                if (data.getInt(i)==0x06054b50 && i+22+Short.toUnsignedInt(data.getShort(i+20))==data.limit()) { end=i; break; }
            }
            if (end<0 || data.getShort(end+4)!=0 || data.getShort(end+6)!=0) throw new IOException("Invalid ZIP directory");
            int count=Short.toUnsignedInt(data.getShort(end+10)),position=data.getInt(end+16);
            if (count==65535 || position<0) throw new IOException("ZIP64 forbidden");
            for (int i=0;i<count;i++) {
                if (data.getInt(position)!=0x02014b50 || (data.getShort(position+8)&1)!=0) throw new IOException("Invalid/encrypted ZIP entry");
                int mode=(data.getInt(position+38)>>>16)&0170000;
                if (mode!=0 && mode!=0100000 && mode!=0040000) throw new IOException("Special ZIP entry forbidden");
                position=Math.addExact(position,46+Short.toUnsignedInt(data.getShort(position+28))+Short.toUnsignedInt(data.getShort(position+30))+Short.toUnsignedInt(data.getShort(position+32)));
            }
            if (position!=end) throw new IOException("Invalid ZIP directory size");
        } catch (IndexOutOfBoundsException | ArithmeticException e) { throw new IOException("Malformed ZIP directory",e); }
    }

    private static Path safeRelative(String name) throws IOException {
        if (name==null || name.isEmpty() || name.length()>2048 || name.contains("\\") || name.contains(":") || name.contains("%") || name.chars().anyMatch(Character::isISOControl)
            || name.startsWith("/") || Arrays.stream(name.split("/",-1)).anyMatch(p -> p.isEmpty() || p.equals(".") || p.equals(".."))) throw new IOException("Unsafe archive path");
        try { return Path.of(name); }
        catch(InvalidPathException e) { throw new IOException("Unsafe archive path",e); }
    }

    private static void createParents(Path root, Path parent) throws IOException {
        if (parent.equals(root)) return;
        createParents(root,parent.getParent());
        if (!Files.exists(parent)) privateDirectory(parent);
        OfflinePaths.checked(parent);
    }

    static OfflineSnapshot inspect(Path stage, String engine) throws IOException {
        OfflineSnapshot snapshot;
        if (engine.equals("sqlite")) {
            snapshot=readSqliteCopy(stage.resolve("db/store.db"),RecoveryBundle::load);
            snapshot.audit(stage.resolve("sources"),stage.resolve("templates"));
        } else if (engine.equals("jsondb")) {
            snapshot=new OfflineSnapshot(MAPPER);
            snapshot.read(stage.resolve("jsondb"),stage.resolve("sources"),stage.resolve("templates"));
        } else throw new IOException("Unsupported engine");
        return snapshot;
    }

    private static int schemaVersion(Path stage,String engine) throws IOException {
        if (!engine.equals("sqlite")) return 1;
        return readSqliteCopy(stage.resolve("db/store.db"),store -> store.jdbc().queryForObject("SELECT count(*) FROM DATABASECHANGELOG",Integer.class));
    }

    /** A read-only WAL connection can still write sidecars. Open only a private stopped-copy clone. */
    static <T> T readSqliteCopy(Path database,java.util.function.Function<SqliteStore,T> reader) throws IOException {
        OfflinePaths.checked(database);
        if(!Files.isRegularFile(database,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Database absent");
        Path scratch=Files.createTempDirectory("regata-inspection-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))).toRealPath();
        try {
            for(String suffix:List.of("","-wal","-shm")) {
                Path input=database.resolveSibling(database.getFileName()+suffix);
                OfflinePaths.checked(input);
                if(!Files.exists(input,LinkOption.NOFOLLOW_LINKS)) continue;
                if(!Files.isRegularFile(input,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid database sidecar");
                Path copy=scratch.resolve("store.db"+suffix);
                Files.copy(input,copy);
                Files.setPosixFilePermissions(copy,PosixFilePermissions.fromString("rw-------"));
            }
            try(var store=new SqliteStore(scratch.resolve("store.db"),2000,true)) { return reader.apply(store); }
        } catch(RuntimeException e) {
            throw new IOException("Database metadata unreadable",e);
        } finally {
            deleteOwned(scratch);
        }
    }

    static void privateDirectory(Path path) throws IOException {
        Files.createDirectory(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    static void writePrivate(Path path, byte[] bytes) throws IOException {
        Files.createFile(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(path,bytes);
    }

    static void deleteOwned(Path root) throws IOException {
        OfflinePaths.checked(root);
        try (var paths=Files.walk(root)) {
            for (Path path:paths.sorted(Comparator.reverseOrder()).toList()) { OfflinePaths.checked(path); Files.delete(path); }
        }
    }

    /** Only complete UUID-named direct children owned by this format are retention candidates. */
    public static void retain(Path root, int count) throws IOException {
        if (count<1 || count>100) throw new IOException("Retention count outside bounds");
        OfflinePaths.checked(root);
        List<Path> candidates;
        try (var paths=Files.list(root)) {
            candidates=paths.filter(p -> p.getFileName().toString().matches("backup-[a-f0-9-]{36}") && Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))
                .sorted(Comparator.comparing((Path p) -> p.getFileName().toString())).toList();
        }
        var verified=new ArrayList<Path>();
        for (Path path:candidates) { manifest(path); verified.add(path); }
        verified.sort(Comparator.comparingLong((Path p) -> { try { return Files.getLastModifiedTime(p.resolve("COMPLETE")).toMillis(); } catch(IOException e) { throw new java.io.UncheckedIOException(e); } }).reversed());
        for (Path path:verified.subList(Math.min(count,verified.size()),verified.size())) deleteOwned(path);
    }
}