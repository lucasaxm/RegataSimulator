package com.boatarde.regatasimulator.models;

import io.jsondb.annotation.Id;
import lombok.Getter;
import lombok.Setter;
import org.telegram.telegrambots.meta.api.objects.Message;

import java.util.UUID;

@Getter
@Setter
public abstract class CommonEntity {
    @Id
    private UUID id;
    private int weight;
    private Message message;
    private Status status;
}
