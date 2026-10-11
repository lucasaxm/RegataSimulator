package com.boatarde.regatasimulator.repository;

import com.boatarde.regatasimulator.models.ModerationAudit;
import java.util.List;
import java.util.UUID;

public interface AuditRepository {
    void append(ModerationAudit audit);
    void notification(UUID auditId, String outcome);
    List<ModerationAudit> findAll();

    static void validate(ModerationAudit audit) {
        if (audit==null || audit.getId()==null || audit.getItemId()==null
            || !java.util.Set.of("SOURCE","TEMPLATE").contains(audit.getItemType())
            || audit.getActorName()==null || audit.getActorName().isBlank() || audit.getActorName().length()>200
            || audit.getDecidedAt()<0 || audit.getDecision()==null
            || audit.getDecision()==com.boatarde.regatasimulator.models.Status.REVIEW) throw new IllegalArgumentException("Invalid audit record");
        if (audit.getNotification()!=null) outcome(audit.getNotification());
    }
    static void outcome(String outcome) {
        if (!java.util.Set.of("SENT","SKIPPED","FAILED").contains(outcome)) throw new IllegalArgumentException("Invalid notification outcome");
    }
}