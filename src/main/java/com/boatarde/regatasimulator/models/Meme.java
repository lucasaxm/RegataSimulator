package com.boatarde.regatasimulator.models;

import io.jsondb.annotation.Document;
import io.jsondb.annotation.Id;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.telegram.telegrambots.meta.api.objects.Message;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
@Document(collection = "memes", schemaVersion = "1.0")
public class Meme {
    @Id
    private UUID id;
    private UUID templateId;
    private List<UUID> sourceIds;
    private Message message;
}
