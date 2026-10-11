package com.boatarde.regatasimulator.repository;

import com.boatarde.regatasimulator.models.*;
import com.boatarde.regatasimulator.repository.jsondb.*;
import com.boatarde.regatasimulator.repository.sqlite.*;
import com.boatarde.regatasimulator.service.*;
import com.boatarde.regatasimulator.application.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditRepositoryTest {
    @TempDir Path temp;
    ModerationAudit record() {
        return ModerationAudit.builder().id(UUID.randomUUID()).itemType("SOURCE").itemId(UUID.randomUUID())
            .actorName("fixture-admin").actorTelegramId(9_000_000_000L).decidedAt(123456789L).decision(Status.APPROVED).build();
    }
    @ParameterizedTest @ValueSource(booleans={false,true}) void preservesActorTimeAndImmutableIdWithSingleNotificationOutcome(boolean sqlite) throws Exception {
        try(var store=new SqliteStore(temp.resolve("audit.db"),100)) {
            var db=new io.jsondb.JsonDBTemplate(Files.createDirectory(temp.resolve("json")).toString(),"com.boatarde.regatasimulator.models"); db.createCollection("audits");
            AuditRepository audits=sqlite ? new SqliteAuditRepository(store) : new JsonDbAuditRepository(db);
            var record=record(); audits.append(record); audits.notification(record.getId(),"FAILED");
            var loaded=audits.findAll().getFirst(); assertEquals(record.getId(),loaded.getId()); assertEquals(record.getItemId(),loaded.getItemId());
            assertEquals(9_000_000_000L,loaded.getActorTelegramId()); assertEquals(123456789L,loaded.getDecidedAt()); assertEquals("FAILED",loaded.getNotification());
            assertThrows(RuntimeException.class,()->audits.append(record));
            assertThrows(IllegalStateException.class,()->audits.notification(record.getId(),"SENT"));
            if(sqlite) {
                assertThrows(org.springframework.dao.DataAccessException.class,()->store.jdbc().update("UPDATE moderation_audit SET actor_name='other'"));
                assertThrows(org.springframework.dao.DataAccessException.class,()->store.jdbc().update("DELETE FROM moderation_audit"));
            } else {
                var reopened=new io.jsondb.JsonDBTemplate(temp.resolve("json").toString(),"com.boatarde.regatasimulator.models");
                assertEquals("FAILED",new JsonDbAuditRepository(reopened).findAll().getFirst().getNotification());
            }
        }
    }
    @Test void auditInsertFailureRollsBackDecisionAndNeverNotifiesInSQLite() {
        try(var store=new SqliteStore(temp.resolve("atomic.db"),100)) {
            var sources=new SqliteSourceRepository(store,new ObjectMapper());
            Source source=new Source(); source.setId(UUID.randomUUID()); source.setDescription("synthetic"); source.setWeight(10); source.setStatus(Status.REVIEW); sources.insertSubmission(source);
            var telegram=mock(TelegramGateway.class);
            var service=new ModerationService(new SourceService(sources,mock(MediaStorage.class)),mock(TemplateService.class),telegram,
                new SqliteAuditRepository(store),writes -> store.transactions().executeWithoutResult(tx -> writes.run()),Clock.fixed(Instant.ofEpochMilli(1000),ZoneOffset.UTC));
            var decision=new ModerationService.Decision(ModerationService.ItemType.SOURCE,source.getId(),Status.APPROVED,"fixture",new ModerationService.Actor("admin",9_000_000_000L));
            store.jdbc().execute("CREATE TRIGGER audit_fail BEFORE INSERT ON moderation_audit BEGIN SELECT RAISE(ABORT,'synthetic'); END");
            assertThrows(ApplicationFailure.class,()->service.decide(decision)); assertEquals(Status.REVIEW,sources.findById(source.getId()).orElseThrow().getStatus());
            assertTrue(new SqliteAuditRepository(store).findAll().isEmpty()); verifyNoInteractions(telegram);
            store.jdbc().execute("DROP TRIGGER audit_fail");
            assertEquals(ModerationService.Notification.SKIPPED,service.decide(decision).notification());
            assertEquals(1,new SqliteAuditRepository(store).findAll().size());
            assertThrows(ApplicationFailure.class,()->service.decide(decision)); assertEquals(1,new SqliteAuditRepository(store).findAll().size());
        }
    }
}