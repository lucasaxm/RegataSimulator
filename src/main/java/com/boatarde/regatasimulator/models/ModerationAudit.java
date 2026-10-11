package com.boatarde.regatasimulator.models;

import io.jsondb.annotation.Document;
import io.jsondb.annotation.Id;
import lombok.*;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection="audits",schemaVersion="1.0")
public class ModerationAudit {
    @Id private UUID id;
    private String itemType;
    private UUID itemId;
    private String actorName;
    private Long actorTelegramId;
    private long decidedAt;
    private Status decision;
    private String notification;
}