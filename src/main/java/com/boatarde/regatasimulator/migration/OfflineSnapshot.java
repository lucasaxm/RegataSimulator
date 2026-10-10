package com.boatarde.regatasimulator.migration;

import com.boatarde.regatasimulator.models.*;
import com.boatarde.regatasimulator.repository.DescriptionKey;
import com.boatarde.regatasimulator.util.MediaValidation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Direct read-only NDJSON reader, never constructs JsonDB or touches its lock directory. */
final class OfflineSnapshot {
    static final String SOURCES="sources";
    static final String TEMPLATES="templates";
    static final String USERS="users";
    static final String MEMES="memes";
    private static final String INVALID_RECORD="INVALID_RECORD";
    private static final String INVALID_ID="INVALID_OR_DUPLICATE_ID";
    record Issue(String collection,int row,String id,String code,boolean blocking) { }
    final List<Source> sourceRecords=new ArrayList<>();
    final List<Template> templateRecords=new ArrayList<>();
    final List<Author> authorRecords=new ArrayList<>();
    final List<Meme> historyRecords=new ArrayList<>();
    final List<ModerationAudit> auditRecords=new ArrayList<>();
    final List<Issue> issues=new ArrayList<>();
    final Map<String,Object> manifest=new TreeMap<>();
    private final Map<String,List<Integer>> originalRows=new HashMap<>();
    private final ObjectMapper mapper;
    OfflineSnapshot(ObjectMapper mapper) { this.mapper=mapper; }
    void read(Path json,Path sourceMedia,Path templateMedia) throws IOException {
        manifest.put("jsondb",OfflinePaths.hashes(json)); manifest.put(SOURCES,OfflinePaths.hashes(sourceMedia)); manifest.put(TEMPLATES,OfflinePaths.hashes(templateMedia));
        readCollection(json,SOURCES,Source.class,sourceRecords); readCollection(json,TEMPLATES,Template.class,templateRecords);
        readCollection(json,USERS,Author.class,authorRecords); readCollection(json,MEMES,Meme.class,historyRecords);
        if (Files.exists(json.resolve("audits.json"))) readCollection(json,"audits",ModerationAudit.class,auditRecords);
        audit(sourceMedia,templateMedia);
    }
    private <T> void readCollection(Path root,String name,Class<T> type,List<T> records) {
        Path file=root.resolve(name+".json");
        try(var reader=Files.newBufferedReader(file)) {
            var header=mapper.readTree(reader.readLine());
            if(header==null || !"1.0".equals(header.path("schemaVersion").asText())) issues.add(new Issue(name,1,null,"SCHEMA_VERSION",true));
            String line;
            int row=1;
            while((line=reader.readLine())!=null) {
                row++;
                readRecord(name,row,line,type,records);
            }
        } catch(Exception e) { issues.add(new Issue(name,1,null,"COLLECTION_UNREADABLE",true)); }
    }
    private <T> void readRecord(String name,int row,String line,Class<T> type,List<T> records) {
        try {
            var tree=mapper.readTree(line);
            if(tree==null || !tree.isObject()) throw new IOException("Object required");
            if(type!=Author.class && (!tree.path("id").isTextual() || !tree.path("id").asText().matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) {
                issues.add(new Issue(name,row,null,INVALID_ID,true)); return;
            }
            records.add(mapper.treeToValue(tree,type));
            originalRows.computeIfAbsent(name,key -> new ArrayList<>()).add(row);
        } catch(Exception e) { issues.add(new Issue(name,row,null,INVALID_RECORD,true)); }
    }
    void audit(Path sourceMedia,Path templateMedia) {
        Set<UUID> sourceIds=new HashSet<>();
        Set<UUID> templateIds=new HashSet<>();
        auditSources(sourceMedia,sourceIds);
        auditTemplates(templateMedia,templateIds);
        auditUsers();
        auditHistory(sourceIds,templateIds);
        Set<UUID> auditIds=new HashSet<>();
        for (int i=0;i<auditRecords.size();i++) {
            var record=auditRecords.get(i);
            try {
                com.boatarde.regatasimulator.repository.AuditRepository.validate(record);
                if (!auditIds.add(record.getId())) throw new IllegalArgumentException("Duplicate audit");
            } catch(RuntimeException e) { issue("audits",i,record.getId(),"INVALID_AUDIT",true); }
        }
    }
    private void auditSources(Path sourceMedia,Set<UUID> sourceIds) {
        Set<String> names=new HashSet<>();
        for(int i=0;i<sourceRecords.size();i++) {
            Source s=sourceRecords.get(i); common(SOURCES,i,s,sourceIds);
            String key=DescriptionKey.of(s.getDescription());
            if(key==null) issue(SOURCES,i,s.getId(),"NULL_DESCRIPTION",false);
            else if(key.isEmpty() || !names.add(key)) issue(SOURCES,i,s.getId(),key.isEmpty()?"EMPTY_DESCRIPTION":"DUPLICATE_DESCRIPTION",true);
            media(SOURCES,i,s.getId(),sourceMedia,"source",null);
        }
    }
    private void auditTemplates(Path templateMedia,Set<UUID> templateIds) {
        for(int i=0;i<templateRecords.size();i++) {
            Template t=templateRecords.get(i); common(TEMPLATES,i,t,templateIds);
            media(TEMPLATES,i,t.getId(),templateMedia,"template",t.getAreas());
            try { MediaValidation.geometry(t.getAreas(),null); }
            catch(Exception e) { issue(TEMPLATES,i,t.getId(),"INVALID_GEOMETRY",true); }
        }
    }
    private void auditUsers() {
        Set<Long> userIds=new HashSet<>();
        for(int i=0;i<authorRecords.size();i++) {
            Author a=authorRecords.get(i);
            if(a.getId()==null || !userIds.add(a.getId())) issue(USERS,i,null,INVALID_ID,true);
        }
    }
    private void auditHistory(Set<UUID> sourceIds,Set<UUID> templateIds) {
        Set<UUID> memeIds=new HashSet<>();
        for(int i=0;i<historyRecords.size();i++) {
            Meme m=historyRecords.get(i);
            if(m.getId()==null || !memeIds.add(m.getId())) issue(MEMES,i,m.getId(),INVALID_ID,true);
            if(m.getTemplateId()==null || !templateIds.contains(m.getTemplateId())) issue(MEMES,i,m.getId(),"ORPHAN_TEMPLATE",false);
            auditSourceOrder(m,i,sourceIds);
            if(m.getMessage()==null) issue(MEMES,i,m.getId(),"NULL_ORIGIN",false);
        }
    }
    private void auditSourceOrder(Meme meme,int row,Set<UUID> sourceIds) {
        if(meme.getSourceIds()==null) issue(MEMES,row,meme.getId(),"NULL_SOURCE_ORDER",false);
        else for(UUID id:meme.getSourceIds()) {
            if(id==null || !sourceIds.contains(id)) issue(MEMES,row,meme.getId(),"ORPHAN_SOURCE",false);
        }
    }
    private void common(String collection,int row,CommonEntity item,Set<UUID> ids) {
        if(item.getId()==null || !ids.add(item.getId())) issue(collection,row,item.getId(),INVALID_ID,true);
        if(item.getWeight()<1) issue(collection,row,item.getId(),"INVALID_WEIGHT",true);
        if(item.getStatus()==null) issue(collection,row,item.getId(),"INVALID_STATUS",true);
        if(item.getMessage()==null) issue(collection,row,item.getId(),"NULL_ORIGIN",false);
        else if(item.getMessage().getFrom()==null || item.getMessage().getChat()==null) issue(collection,row,item.getId(),"INCOMPLETE_ORIGIN",false);
        if((item.getPreviewChatId()==null)!=(item.getPreviewMessageId()==null) || item.getPreviewMessageId()!=null && item.getPreviewMessageId()<=0) issue(collection,row,item.getId(),"INVALID_PREVIEW_BINDING",true);
    }
    private void media(String collection,int row,UUID id,Path root,String prefix,List<TemplateArea> areas) {
        if(id==null) return;
        Path file=null;
        for(String suffix:List.of(".jpg",".jpeg",".png")) {
            Path candidate=root.resolve(id.toString()).resolve(prefix+suffix);
            if(Files.isRegularFile(candidate)) { file=candidate; break; }
        }
        if(file==null) { issue(collection,row,id,"MISSING_MEDIA",true); return; }
        try { var dimensions=MediaValidation.image(file); if(areas!=null) MediaValidation.geometry(areas,dimensions); }
        catch(Exception e) { issue(collection,row,id,"INVALID_MEDIA_OR_BOUNDS",true); }
    }
    private void issue(String collection,int row,UUID id,String code,boolean blocking) {
        List<Integer> rows=originalRows.get(collection);
        issues.add(new Issue(collection,rows==null?row+2:rows.get(row),id==null?null:id.toString(),code,blocking));
    }
    boolean blocked() { return issues.stream().anyMatch(Issue::blocking); }
    Map<String,Integer> counts() { return Map.of(SOURCES,sourceRecords.size(),TEMPLATES,templateRecords.size(),USERS,authorRecords.size(),MEMES,historyRecords.size(),"audits",auditRecords.size()); }
    void writeJson(Path root) throws IOException {
        Files.createDirectory(root);
        write(root,SOURCES,sourceRecords); write(root,TEMPLATES,templateRecords); write(root,USERS,authorRecords); write(root,MEMES,historyRecords);
        write(root,"audits",auditRecords);
    }
    private void write(Path root,String name,List<?> records) throws IOException {
        try(var writer=Files.newBufferedWriter(root.resolve(name+".json"),StandardOpenOption.CREATE_NEW)) {
            writer.write("{\"schemaVersion\":\"1.0\"}"); writer.newLine();
            for(Object entity:records) { writer.write(mapper.writeValueAsString(entity)); writer.newLine(); }
        }
    }
    Map<String,Object> canonical() {
        Map<String,Object> fields=new TreeMap<>();
        fields.put(SOURCES,sourceRecords.stream().sorted(Comparator.comparing(s -> s.getId().toString())).map(mapper::valueToTree).toList());
        fields.put(TEMPLATES,templateRecords.stream().sorted(Comparator.comparing(t -> t.getId().toString())).map(mapper::valueToTree).toList());
        fields.put(USERS,authorRecords.stream().sorted(Comparator.comparing(Author::getId)).map(mapper::valueToTree).toList());
        fields.put(MEMES,historyRecords.stream().sorted(Comparator.comparing(m -> m.getId().toString())).map(mapper::valueToTree).toList());
        fields.put("audits",auditRecords.stream().sorted(Comparator.comparing(a -> a.getId().toString())).map(mapper::valueToTree).toList());
        return fields;
    }
}