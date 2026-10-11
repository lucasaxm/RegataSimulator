package com.boatarde.regatasimulator.repository.jsondb;

import com.boatarde.regatasimulator.models.ModerationAudit;
import com.boatarde.regatasimulator.repository.AuditRepository;
import io.jsondb.JsonDBTemplate;
import io.jsondb.query.Update;
import org.springframework.stereotype.Repository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.List;
import java.util.UUID;

@Repository
@ConditionalOnProperty(name="regata-simulator.database.engine",havingValue="jsondb",matchIfMissing=true)
public class JsonDbAuditRepository implements AuditRepository {
    private final JsonDBTemplate db;
    public JsonDbAuditRepository(JsonDBTemplate db) { this.db=db; }
    @Override public void append(ModerationAudit audit) { AuditRepository.validate(audit); db.insert(audit); }
    @Override public synchronized void notification(UUID id,String outcome) {
        AuditRepository.outcome(outcome);
        var stored=db.findById(id,ModerationAudit.class);
        if (stored==null || stored.getNotification()!=null
            || db.findAndModify("/.[id='%s']".formatted(id),Update.update("notification",outcome),ModerationAudit.class)==null) {
            throw new IllegalStateException("Audit outcome already recorded or absent");
        }
    }
    @Override public List<ModerationAudit> findAll() {
        return db.collectionExists("audits") ? db.findAll(ModerationAudit.class) : List.of();
    }
}