package com.boatarde.regatasimulator.repository;

import com.boatarde.regatasimulator.application.ApplicationFailure;
import com.boatarde.regatasimulator.models.*;
import com.boatarde.regatasimulator.repository.sqlite.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SqliteRepositoryTest {
    @TempDir Path temp;
    SqliteStore store; SqliteSourceRepository sources; SqliteTemplateRepository templates; SqliteMemeHistoryRepository history;
    @BeforeEach void open() { store=new SqliteStore(temp.resolve("sqlite.db"),150); sources=new SqliteSourceRepository(store,new ObjectMapper()); templates=new SqliteTemplateRepository(store,new ObjectMapper()); history=new SqliteMemeHistoryRepository(store,new ObjectMapper()); }
    @AfterEach void close() { store.close(); }
    @Test void concurrentNormalizedNamesHaveExactlyOneWinner() throws Exception {
        var start=new CountDownLatch(1);
        try (var executor=Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> attempts=new ArrayList<>();
            for (String name:List.of(" ＡLPHA ","alpha")) attempts.add(executor.submit(() -> { start.await(); try { sources.insertSubmission(RepositoryContractTest.source(name)); return true; } catch(ApplicationFailure e) { assertEquals(ApplicationFailure.Kind.CONFLICT,e.getKind()); return false; } }));
            start.countDown(); assertNotEquals(attempts.get(0).get(5,TimeUnit.SECONDS),attempts.get(1).get(5,TimeUnit.SECONDS));
        }
        assertEquals(1,sources.find(SourceRepository.Criteria.all()).size());
    }
    @Test void concurrentReviewAcrossIndependentAdaptersHasOneWinner() throws Exception {
        var s=RepositoryContractTest.source("review"); sources.insertSubmission(s);
        var other=new SqliteSourceRepository(store,new ObjectMapper());
        try (var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(() -> sources.decideReview(s.getId(),Status.APPROVED)); var b=executor.submit(() -> other.decideReview(s.getId(),Status.REJECTED)); assertNotEquals(a.get(),b.get());
        }
    }
    @Test void heldWriterProducesBoundedUnavailableThenRecovers() throws Exception {
        var s=RepositoryContractTest.source("busy"); sources.insertSubmission(s);
        try (var connection=store.dataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var statement=connection.prepareStatement("UPDATE sources SET weight=9 WHERE id=?")) { statement.setString(1,s.getId().toString()); statement.executeUpdate(); }
            long start=System.nanoTime(); var failure=assertThrows(ApplicationFailure.class,() -> sources.decreaseWeight(s.getId()));
            assertEquals(ApplicationFailure.Kind.UNAVAILABLE,failure.getKind()); assertTrue(System.nanoTime()-start < TimeUnit.SECONDS.toNanos(3)); connection.rollback();
        }
        sources.decreaseWeight(s.getId()); assertEquals(9,sources.findById(s.getId()).orElseThrow().getWeight());
    }
    @Test void importedBatchRollsBackOnDuplicateAndInvalidWeights() {
        var a=RepositoryContractTest.source("a"); var b=RepositoryContractTest.source("A");
        assertThrows(ApplicationFailure.class,() -> sources.insertImported(List.of(a,b))); assertTrue(sources.find(SourceRepository.Criteria.all()).isEmpty());
        a.setWeight(0); assertThrows(ApplicationFailure.class,() -> sources.insertSubmission(a)); assertTrue(sources.find(SourceRepository.Criteria.all()).isEmpty());
    }
    @Test void geometryAndForeignKeysRejectInvalidRecordsAndRetainHistoricalIds() {
        var t=RepositoryContractTest.template(); t.getAreas().getFirst().setIndex(2); assertThrows(ApplicationFailure.class,() -> templates.insertSubmission(t));
        var failure=assertThrows(org.springframework.dao.DataAccessException.class,() -> store.jdbc().update("INSERT INTO meme_sources(meme_id,position,source_uuid,source_link) VALUES(?,?,?,?)",UUID.randomUUID().toString(),0,null,null));
        assertInstanceOf(org.sqlite.SQLiteException.class,failure.getMostSpecificCause());
        assertEquals(org.sqlite.SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY,((org.sqlite.SQLiteException)failure.getMostSpecificCause()).getResultCode());
        assertTrue(store.jdbc().queryForList("PRAGMA foreign_key_check").isEmpty());
    }
    @Test void historyInsertionAndRetentionRollbackTogether() {
        store.transactions().executeWithoutResult(tx -> { for(int i=0;i<1000;i++) history.importHistory(Meme.builder().id(new UUID(0,i+1)).sourceIds(List.of()).build()); });
        var delivered=Meme.builder().id(new UUID(0,2000)).sourceIds(List.of(UUID.randomUUID(),UUID.randomUUID())).build();
        store.jdbc().execute("CREATE TRIGGER fail_trim BEFORE DELETE ON memes BEGIN SELECT RAISE(ABORT,'synthetic trim failure'); END");
        assertThrows(ApplicationFailure.class,() -> history.recordDelivered(delivered)); assertEquals(1000,history.newestFirst().size()); assertTrue(history.newestFirst().stream().noneMatch(m -> m.getId().equals(delivered.getId())));
        store.jdbc().execute("DROP TRIGGER fail_trim"); history.recordDelivered(delivered); assertEquals(1000,history.newestFirst().size()); assertEquals(delivered.getSourceIds(),history.newestFirst().getFirst().getSourceIds());
    }
}