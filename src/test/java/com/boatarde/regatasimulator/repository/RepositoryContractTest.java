package com.boatarde.regatasimulator.repository;

import com.boatarde.regatasimulator.models.*;
import com.boatarde.regatasimulator.repository.jsondb.*;
import com.boatarde.regatasimulator.repository.sqlite.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsondb.JsonDBTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.telegram.telegrambots.meta.api.objects.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryContractTest {
    @TempDir Path temp;
    SqliteStore store;
    SourceRepository sources; TemplateRepository templates; AuthorRepository authors; MemeHistoryRepository history;
    void open(boolean sqlite) throws Exception {
        if (sqlite) {
            store = new SqliteStore(temp.resolve("contract.db"), 300);
            sources = new SqliteSourceRepository(store, new ObjectMapper()); templates = new SqliteTemplateRepository(store, new ObjectMapper());
            authors = new SqliteAuthorRepository(store); history = new SqliteMemeHistoryRepository(store, new ObjectMapper());
        } else {
            var db = new JsonDBTemplate(Files.createDirectory(temp.resolve("json")).toString(), "com.boatarde.regatasimulator.models");
            for (String name : List.of("sources", "templates", "users", "memes")) db.createCollection(name);
            sources = new JsonDbSourceRepository(db); templates = new JsonDbTemplateRepository(db);
            authors = new JsonDbAuthorRepository(db); history = new JsonDbMemeHistoryRepository(db);
        }
    }
    @AfterEach void close() { if (store != null) store.close(); }
    static Source source(String name) { Source s = new Source(); s.setId(UUID.randomUUID()); s.setDescription(name); s.setWeight(10); s.setStatus(Status.REVIEW); return s; }
    static Template template() {
        Template t = new Template(); t.setId(UUID.randomUUID()); t.setWeight(10); t.setStatus(Status.REVIEW);
        t.setAreas(List.of(TemplateArea.builder().index(1).source(1).topLeft(new AreaCorner(0,0)).topRight(new AreaCorner(20,0))
            .bottomRight(new AreaCorner(20,20)).bottomLeft(new AreaCorner(0,20)).build())); return t;
    }
    @ParameterizedTest @ValueSource(booleans={false,true}) void reviewAndWeightsPreserveOrigin(boolean sqlite) throws Exception {
        open(sqlite); Source s = source("O'Brien 100%_"); Template t = template();
        Message message = new Message(); message.setDate(25); message.setMessageId(777); message.setText("synthetic transport");
        Chat chat = new Chat(); chat.setId(-9_000_000_000L); message.setChat(chat);
        User user = new User(); user.setId(9_000_000_000L); user.setFirstName("Fixture"); message.setFrom(user); s.setMessage(message);
        sources.insertSubmission(s); templates.insertSubmission(t);
        assertEquals(1, sources.find(new SourceRepository.Criteria(null,9_000_000_000L,List.of("100%_"))).size());
        assertTrue(sources.find(new SourceRepository.Criteria(null,null,List.of("' OR 1=1"))).isEmpty());
        assertTrue(sources.bindReviewPreview(s.getId(),-3,20)); assertTrue(templates.bindReviewPreview(t.getId(),-3,20));
        sources.decreaseWeight(s.getId()); templates.decreaseWeight(t.getId());
        assertTrue(sources.decideReview(s.getId(),Status.APPROVED)); assertFalse(sources.decideReview(s.getId(),Status.REJECTED));
        assertTrue(templates.decideReview(t.getId(),Status.REJECTED)); assertFalse(templates.bindReviewPreview(t.getId(),-3,21));
        Source loaded = sources.findById(s.getId()).orElseThrow(); assertEquals(9,loaded.getWeight()); assertNull(loaded.getPreviewMessageId());
        assertEquals(new ObjectMapper().valueToTree(message),new ObjectMapper().valueToTree(loaded.getMessage()));
        sources.resetWeights(1); sources.decreaseWeight(s.getId()); assertEquals(1,sources.findById(s.getId()).orElseThrow().getWeight());
    }
    @ParameterizedTest @ValueSource(booleans={false,true}) void pagingHistoryAndAuthors(boolean sqlite) throws Exception {
        open(sqlite); Source a=source(null), b=source(null); Template t=template(); sources.insertSubmission(a); sources.insertSubmission(b); templates.insertSubmission(t);
        var page = sources.page(SourceRepository.Criteria.all(),1,1); assertEquals(2,page.getTotalItems());
        assertNotEquals(page.getItems().getFirst().getId(),sources.page(SourceRepository.Criteria.all(),2,1).getItems().getFirst().getId());
        assertTrue(sources.page(SourceRepository.Criteria.all(),3,1).getItems().isEmpty());
        var ids=List.of(a.getId(),b.getId(),a.getId()); Meme meme=Meme.builder().id(UUID.randomUUID()).templateId(t.getId()).sourceIds(ids).build(); history.recordDelivered(meme);
        sources.remove(a); templates.remove(t); assertEquals(ids,history.newestFirst().getFirst().getSourceIds()); assertEquals(t.getId(),history.newestFirst().getFirst().getTemplateId());
        assertTrue(sources.findById(a.getId()).isEmpty());
        authors.recordSubmitter(Author.builder().id(9_000_000_000L).firstName("before").build()); authors.recordSubmitter(Author.builder().id(9_000_000_000L).firstName("after").build());
        assertEquals(1,authors.findAll().size()); assertEquals("after",authors.findAll().getFirst().getFirstName());
    }
}