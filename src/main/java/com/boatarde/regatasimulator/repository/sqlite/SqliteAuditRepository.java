package com.boatarde.regatasimulator.repository.sqlite;

import com.boatarde.regatasimulator.models.*;
import com.boatarde.regatasimulator.repository.AuditRepository;
import java.util.List;
import java.util.UUID;

public final class SqliteAuditRepository implements AuditRepository {
    private final SqliteStore store;
    public SqliteAuditRepository(SqliteStore store) { this.store=store; }
    @Override public void append(ModerationAudit audit) {
        AuditRepository.validate(audit);
        SqliteOperations.call(() -> store.jdbc().update("INSERT INTO moderation_audit(id,item_type,item_uuid,actor_name,actor_telegram_id,decided_at,decision,notification) VALUES(?,?,?,?,?,?,?,?)",
            audit.getId().toString(),audit.getItemType(),audit.getItemId().toString(),audit.getActorName(),audit.getActorTelegramId(),audit.getDecidedAt(),audit.getDecision().name(),audit.getNotification()));
    }
    @Override public void notification(UUID id,String outcome) {
        AuditRepository.outcome(outcome);
        if (SqliteOperations.call(() -> store.jdbc().update("UPDATE moderation_audit SET notification=? WHERE id=? AND notification IS NULL",outcome,id.toString()))!=1) {
            throw new IllegalStateException("Audit outcome already recorded or absent");
        }
    }
    @Override public List<ModerationAudit> findAll() {
        if (store.jdbc().queryForObject("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='moderation_audit'",Integer.class)==0) return List.of();
        return SqliteOperations.call(() -> store.jdbc().query("SELECT * FROM moderation_audit ORDER BY decided_at,id",(row,n) ->
            ModerationAudit.builder().id(UUID.fromString(row.getString("id"))).itemType(row.getString("item_type"))
                .itemId(UUID.fromString(row.getString("item_uuid"))).actorName(row.getString("actor_name"))
                .actorTelegramId(SqliteCodec.nullableLong(row,"actor_telegram_id")).decidedAt(row.getLong("decided_at"))
                .decision(Status.valueOf(row.getString("decision"))).notification(row.getString("notification")).build()));
    }
}